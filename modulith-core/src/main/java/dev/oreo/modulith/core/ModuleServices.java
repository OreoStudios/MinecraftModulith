package dev.oreo.modulith.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Module-scoped facade over an instance-qualified service registry.
 *
 * <p>Publishing is isolated by (provider instance, API contract). A plain
 * require(Class) resolves uniquely or fails with an actionable ambiguity;
 * require(String, Class) selects one named provider.</p>
 */
public final class ModuleServices {
    private final String moduleId;
    private final String templateId;
    private final List<ModuleDependency> allowedDependencies;
    private final ConcurrentHashMap<ServiceKey<?>, ServiceEntry> shared;

    ModuleServices(String moduleId, String templateId,
                   List<ModuleDependency> allowedDependencies,
                   ConcurrentHashMap<ServiceKey<?>, ServiceEntry> shared) {
        this.moduleId = Objects.requireNonNull(moduleId, "moduleId");
        this.templateId = Objects.requireNonNull(templateId, "templateId");
        this.allowedDependencies = List.copyOf(allowedDependencies);
        this.shared = shared;
    }

    /** A service published under this module instance's identity. */
    public <T> void publish(Class<T> type, T implementation) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(implementation, "implementation");
        if (!type.isInstance(implementation)) {
            throw new IllegalArgumentException(
                    implementation.getClass().getName() + " does not implement " + type.getName());
        }

        ModuleApi api = type.getAnnotation(ModuleApi.class);
        NamedInterface named = type.getPackage().getAnnotation(NamedInterface.class);
        if (api == null && named == null) {
            throw new ModulithException("Published module service " + type.getName()
                    + " must be annotated with @ModuleApi");
        }
        String apiName = api != null ? api.value().trim() : named.value().trim();
        if (apiName.isEmpty()) {
            throw new ModulithException("@ModuleApi name cannot be blank on " + type.getName());
        }

        ServiceKey<T> key = new ServiceKey<>(moduleId, type);
        ServiceEntry existing = shared.putIfAbsent(
                key, new ServiceEntry(moduleId, templateId, apiName, implementation));
        if (existing != null) {
            throw new ModulithException("Service " + type.getName()
                    + " is already published by module instance '" + existing.owner() + "'");
        }
    }

    /** Resolve a contract only if exactly one visible instance provides it. */
    public <T> T require(Class<T> type) {
        Objects.requireNonNull(type, "type");
        List<ServiceEntry> accessible = new ArrayList<>();
        List<ServiceEntry> all = new ArrayList<>();
        shared.forEach((key, entry) -> {
            if (key.contract().equals(type)) {
                all.add(entry);
                if (allowed(entry)) accessible.add(entry);
            }
        });
        if (accessible.size() == 1) return type.cast(accessible.getFirst().instance());
        if (accessible.size() > 1) {
            throw new ModulithException("Ambiguous module service " + type.getName()
                    + ": multiple providers visible to '" + moduleId
                    + "'. Use require(providerInstanceId, " + type.getSimpleName() + ".class)");
        }
        if (!all.isEmpty()) {
            throw new ModuleDependencyException("Module '" + moduleId
                    + "' cannot access " + type.getName()
                    + " without declaring an API dependency or uses selector");
        }
        throw new ModulithException("No module service published for " + type.getName());
    }

    /** Resolve exactly one instance-qualified service while checking module boundaries. */
    public <T> T require(String providerInstanceId, Class<T> type) {
        Objects.requireNonNull(providerInstanceId, "providerInstanceId");
        Objects.requireNonNull(type, "type");
        ServiceEntry entry = shared.get(new ServiceKey<>(providerInstanceId, type));
        if (entry == null) {
            throw new ModulithException("No module service " + type.getName()
                    + " published by '" + providerInstanceId + "'");
        }
        if (!allowed(entry)) {
            throw new ModuleDependencyException("Module '" + moduleId + "' cannot access API '"
                    + entry.apiName() + "' from '" + entry.owner()
                    + "' without declaring a matching dependency or uses selector");
        }
        return type.cast(entry.instance());
    }

    public <T> T require(ServiceKey<T> key) {
        Objects.requireNonNull(key, "key");
        return require(key.providerId(), key.contract());
    }

    /**
     * Resolve an optionally available service. A missing contract returns empty,
     * but unauthorized access and ambiguous providers still fail explicitly.
     */
    public <T> Optional<T> find(Class<T> type) {
        Objects.requireNonNull(type, "type");
        boolean exists = shared.keySet().stream().anyMatch(key -> key.contract().equals(type));
        return exists ? Optional.of(require(type)) : Optional.empty();
    }

    private boolean allowed(ServiceEntry entry) {
        return entry.owner().equals(moduleId)
                || allowedDependencies.stream().anyMatch(dep ->
                        dep.allows(entry.owner(), entry.apiName())
                                || dep.allows(entry.templateId(), entry.apiName()));
    }

    Map<ServiceKey<?>, ServiceEntry> snapshot() {
        return Map.copyOf(shared);
    }

    record ServiceEntry(String owner, String templateId, String apiName, Object instance) {}
}
