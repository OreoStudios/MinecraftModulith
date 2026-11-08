package dev.oreo.modulith.core;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** Markdown and JSON architecture reports generated from validated module descriptors. */
public final class ModuleArchitectureReport {
    private ModuleArchitectureReport() {}

    public static String markdown(Collection<ModuleDescriptor> modules) {
        Objects.requireNonNull(modules, "modules");
        StringBuilder out = new StringBuilder("# MinecraftModulith architecture\n\n");
        out.append("| Instance | Template | Implementation | Dependencies | Uses | After | Optional |\n");
        out.append("| --- | --- | --- | --- | --- | --- | --- |\n");
        sorted(modules).forEach(d -> out.append("| ")
                .append(cell(d.id())).append(" | ")
                .append(cell(d.templateId())).append(" | ")
                .append(cell(d.implementation().getName())).append(" | ")
                .append(cell(String.join(", ", d.dependencies()))).append(" | ")
                .append(cell(String.join(", ", d.uses()))).append(" | ")
                .append(cell(String.join(", ", d.after()))).append(" | ")
                .append(d.optional()).append(" |\n"));
        return out.toString();
    }

    public static String json(Collection<ModuleDescriptor> modules) {
        Objects.requireNonNull(modules, "modules");
        return "{\"schemaVersion\":1,\"modules\":[" + sorted(modules).stream().map(d ->
                "{\"id\":" + quote(d.id())
                + ",\"templateId\":" + quote(d.templateId())
                + ",\"implementation\":" + quote(d.implementation().getName())
                + ",\"dependencies\":" + array(d.dependencies())
                + ",\"uses\":" + array(d.uses())
                + ",\"after\":" + array(d.after())
                + ",\"optional\":" + d.optional()
                + ",\"configurationEnabled\":" + d.configurationEnabled()
                + "}").collect(Collectors.joining(",")) + "]}";
    }

    private static List<ModuleDescriptor> sorted(Collection<ModuleDescriptor> modules) {
        return modules.stream().sorted(Comparator.comparing(ModuleDescriptor::id)).toList();
    }

    private static String cell(String raw) {
        return raw.replace("|", "\\|").replace("\n", " ");
    }

    private static String array(List<String> values) {
        return "[" + values.stream().map(ModuleArchitectureReport::quote)
                .collect(Collectors.joining(",")) + "]";
    }

    private static String quote(String raw) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) out.append(String.format("\\u%04x", (int) ch));
                    else out.append(ch);
                }
            }
        }
        return out.append('"').toString();
    }
}
