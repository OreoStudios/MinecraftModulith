package dev.oreo.modulith.core;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Validated immutable catalog of module definitions and runtime instances. */
public final class ModuleCatalog {
    private final List<ModuleDescriptor> ordered;
    private final Map<String, ModuleDescriptor> byId;

    private ModuleCatalog(List<ModuleDescriptor> validated) {
        this.ordered = List.copyOf(validated);
        Map<String, ModuleDescriptor> index = new LinkedHashMap<>();
        validated.forEach(d -> index.put(d.id(), d));
        this.byId = Map.copyOf(index);
    }

    public static ModuleCatalog of(Collection<ModuleDescriptor> definitions) {
        Objects.requireNonNull(definitions, "definitions");
        return new ModuleCatalog(ModuleGraph.validateAndSort(definitions));
    }

    public List<ModuleDescriptor> modules() {
        return ordered;
    }

    public Optional<ModuleDescriptor> find(String instanceId) {
        Objects.requireNonNull(instanceId, "instanceId");
        return Optional.ofNullable(byId.get(instanceId));
    }

    public String markdown() {
        return ModuleArchitectureReport.markdown(ordered);
    }

    public String json() {
        return ModuleArchitectureReport.json(ordered);
    }
}
