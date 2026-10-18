package dev.oreo.modulith.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ModuleGraph {
    private ModuleGraph() {
    }

    static List<ModuleDescriptor> validateAndSort(Collection<ModuleDescriptor> modules) {
        Map<String, ModuleDescriptor> byId = new LinkedHashMap<>();
        for (ModuleDescriptor module : modules) {
            if (module.id().isBlank()) {
                throw new ModuleDependencyException("Module id cannot be blank: " + module.implementation().getName());
            }
            ModuleDescriptor previous = byId.putIfAbsent(module.id(), module);
            if (previous != null) {
                throw new ModuleDependencyException("Duplicate module id '" + module.id() + "'");
            }
        }

        for (ModuleDescriptor module : byId.values()) {
            for (String rawDependency : module.dependencies()) {
                ModuleDependency dependency;
                try {
                    dependency = ModuleDependency.parse(rawDependency);
                } catch (IllegalArgumentException exception) {
                    throw new ModuleDependencyException(
                            "Module '" + module.id() + "' has invalid dependency selector '" + rawDependency + "'"
                    );
                }
                if (!byId.containsKey(dependency.moduleId())) {
                    throw new ModuleDependencyException(
                            "Module '" + module.id() + "' depends on missing module '" + dependency.moduleId() + "'"
                    );
                }
                if (dependency.moduleId().equals(module.id())) {
                    throw new ModuleDependencyException("Module '" + module.id() + "' cannot depend on itself");
                }
            }
            for (String after : module.after()) {
                if (after.equals(module.id())) {
                    throw new ModuleDependencyException("Module '" + module.id() + "' cannot start after itself");
                }
            }
        }

        Map<String, Visit> states = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        List<ModuleDescriptor> sorted = new ArrayList<>();
        for (ModuleDescriptor module : byId.values()) {
            visit(module, byId, states, stack, sorted);
        }
        return List.copyOf(sorted);
    }

    private static void visit(
            ModuleDescriptor module,
            Map<String, ModuleDescriptor> byId,
            Map<String, Visit> states,
            Deque<String> stack,
            List<ModuleDescriptor> sorted
    ) {
        Visit state = states.get(module.id());
        if (state == Visit.DONE) {
            return;
        }
        if (state == Visit.VISITING) {
            List<String> cycle = new ArrayList<>(stack);
            Collections.reverse(cycle);
            cycle.add(module.id());
            throw new ModuleDependencyException(
                    "Circular module dependency detected: " + String.join(" -> ", cycle)
            );
        }

        states.put(module.id(), Visit.VISITING);
        stack.push(module.id());
        for (String rawDependency : module.dependencies()) {
            ModuleDependency dependency = ModuleDependency.parse(rawDependency);
            visit(byId.get(dependency.moduleId()), byId, states, stack, sorted);
        }
        for (String after : module.after()) {
            // Ordering constraints on modules that are not installed are ignored.
            ModuleDescriptor predecessor = byId.get(after);
            if (predecessor != null) {
                visit(predecessor, byId, states, stack, sorted);
            }
        }
        stack.pop();
        states.put(module.id(), Visit.DONE);
        sorted.add(module);
    }

    private enum Visit {
        VISITING,
        DONE
    }
}
