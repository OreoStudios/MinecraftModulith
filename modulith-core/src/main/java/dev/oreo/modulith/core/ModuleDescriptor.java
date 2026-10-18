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
        List<String> after
) {
    public ModuleDescriptor {
        dependencies = List.copyOf(dependencies);
        after = List.copyOf(after);
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

    public Set<String> dependencyModules() {
        return parsedDependencies().stream().map(ModuleDependency::moduleId).collect(Collectors.toUnmodifiableSet());
    }
}
