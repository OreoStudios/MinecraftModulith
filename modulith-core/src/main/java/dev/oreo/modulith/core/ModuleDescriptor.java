package dev.oreo.modulith.core;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public record ModuleDescriptor(
        String id,
        List<String> dependencies,
        Class<? extends MinecraftModule> implementation,
        boolean configurationEnabled,
        boolean optional,
        List<String> after,
        List<String> uses
) {
    public ModuleDescriptor {
        dependencies = List.copyOf(dependencies);
        after = List.copyOf(after);
        uses = List.copyOf(uses);
    }

    /** Compatibility constructor for callers using the v0.5.x descriptor shape. */
    public ModuleDescriptor(String id, List<String> dependencies,
                            Class<? extends MinecraftModule> implementation,
                            boolean configurationEnabled, boolean optional, List<String> after) {
        this(id, dependencies, implementation, configurationEnabled, optional, after, List.of());
    }

    public ModuleDescriptor(
            String id,
            List<String> dependencies,
            Class<? extends MinecraftModule> implementation,
            boolean configurationEnabled
    ) {
        this(id, dependencies, implementation, configurationEnabled, false, List.of());
    }

    public List<ModuleDependency> parsedDependencies() {
        return dependencies.stream().map(ModuleDependency::parse).toList();
    }

    /** Named APIs that may be resolved without imposing startup or failure dependencies. */
    public List<ModuleDependency> parsedUses() {
        return uses.stream().map(ModuleDependency::parse).toList();
    }

    /** Complete API-access contract, distinct from startup dependencies. */
    public List<ModuleDependency> apiAccess() {
        return java.util.stream.Stream.concat(parsedDependencies().stream(), parsedUses().stream())
                .distinct().toList();
    }

    public Set<String> dependencyModules() {
        return parsedDependencies().stream().map(ModuleDependency::moduleId).collect(Collectors.toUnmodifiableSet());
    }
}
