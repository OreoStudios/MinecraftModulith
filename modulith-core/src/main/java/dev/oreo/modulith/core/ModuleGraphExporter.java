package dev.oreo.modulith.core;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/** Exports the validated module dependency graph for documentation and diagnostics. */
public final class ModuleGraphExporter {
    private ModuleGraphExporter() {
    }

    public static String toMermaid(Collection<ModuleDescriptor> modules) {
        List<ModuleDescriptor> sorted = modules.stream()
                .sorted(Comparator.comparing(ModuleDescriptor::id))
                .toList();

        StringBuilder out = new StringBuilder("graph TD\n");
        for (ModuleDescriptor module : sorted) {
            out.append("  ").append(node(module.id())).append("[\"").append(escape(module.id())).append("\"]\n");
        }
        for (ModuleDescriptor module : sorted) {
            for (String raw : module.dependencies()) {
                ModuleDependency dependency = ModuleDependency.parse(raw);
                out.append("  ").append(node(module.id())).append(" -->");
                if (dependency.apiName() != null) {
                    out.append("|").append(escape(dependency.apiName())).append("|");
                }
                out.append(" ").append(node(dependency.moduleId())).append("\n");
            }
            for (String after : module.after()) {
                out.append("  ").append(node(module.id())).append(" -.-> ").append(node(after)).append("\n");
            }
        }
        return out.toString();
    }

    public static String toGraphviz(Collection<ModuleDescriptor> modules) {
        List<ModuleDescriptor> sorted = modules.stream()
                .sorted(Comparator.comparing(ModuleDescriptor::id))
                .toList();

        StringBuilder out = new StringBuilder("digraph MinecraftModulith {\n");
        for (ModuleDescriptor module : sorted) {
            out.append("  \"").append(escape(module.id())).append("\";\n");
        }
        for (ModuleDescriptor module : sorted) {
            for (String raw : module.dependencies()) {
                ModuleDependency dependency = ModuleDependency.parse(raw);
                out.append("  \"").append(escape(module.id())).append("\" -> \"")
                        .append(escape(dependency.moduleId())).append("\"");
                if (dependency.apiName() != null) {
                    out.append(" [label=\"").append(escape(dependency.apiName())).append("\"]");
                }
                out.append(";\n");
            }
            for (String after : module.after()) {
                out.append("  \"").append(escape(module.id())).append("\" -> \"")
                        .append(escape(after)).append("\" [style=dashed];\n");
            }
        }
        out.append("}\n");
        return out.toString();
    }

    private static String node(String id) {
        return "m_" + id.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
