package dev.oreo.modulith.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.GradleException;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Lightweight verification/documentation tasks based on annotation-processor metadata. */
public final class MinecraftModulithPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getPluginManager().apply("java");
        TaskProvider<?> verify = project.getTasks().register("verifyModulith", task -> {
            task.setGroup("verification");
            task.setDescription("Compiles the module graph and validates architectural boundaries via annotation processing.");
            task.dependsOn("compileJava");
            task.doLast(t -> {
                Path index = index(project);
                if (!Files.exists(index)) throw new GradleException(
                        "No module metadata found. Add modulith-processor to annotationProcessor.");
                try {
                    Set<String> ids = new LinkedHashSet<>();
                    for (String line : Files.readAllLines(index, StandardCharsets.UTF_8)) {
                        String[] parts = line.split("\\|", -1);
                        if (parts.length < 4) throw new GradleException("Malformed module metadata: " + line);
                        if (!ids.add(parts[0])) throw new GradleException("Duplicate module " + parts[0]);
                    }
                } catch (java.io.IOException e) {
                    throw new GradleException("Cannot inspect MinecraftModulith metadata", e);
                }
            });
        });

        TaskProvider<?> docs = project.getTasks().register("modulithDocs", task -> {
            task.setGroup("documentation");
            task.setDescription("Generates build/reports/modulith/modules.md and modules.mmd from discovered modules.");
            task.dependsOn(verify);
            task.doLast(t -> {
                try {
                    List<String> lines = Files.readAllLines(index(project), StandardCharsets.UTF_8);
                    StringBuilder mermaid = new StringBuilder("flowchart LR\n");
                    StringBuilder markdown = new StringBuilder("# MinecraftModulith modules\n\n");
                    for (String line : lines) {
                        String[] values = line.split("\\|", -1);
                        String id = values[0];
                        if (!id.matches("[A-Za-z0-9_-]+")) {
                            throw new GradleException("Unsafe module ID in metadata: " + id);
                        }
                        mermaid.append("  ").append(id).append("[\"").append(id).append("\"]\n");
                        markdown.append("## ").append(id).append("\n\n").append("Class: `")
                                .append(values[1]).append("`\n\n");
                        if (!values[2].isBlank()) {
                            for (String dependency : values[2].split(",")) {
                                String target = dependency.split("::", 2)[0];
                                mermaid.append("  ").append(id).append(" --> ").append(target).append("\n");
                                markdown.append("- Depends on `").append(dependency).append("`\n");
                            }
                        }
                    }
                    Path output = project.getLayout().getBuildDirectory()
                            .file("reports/modulith").get().getAsFile().toPath();
                    Files.createDirectories(output);
                    Files.writeString(output.resolve("modules.mmd"), mermaid, StandardCharsets.UTF_8);
                    Files.writeString(output.resolve("modules.md"), markdown, StandardCharsets.UTF_8);
                } catch (java.io.IOException e) {
                    throw new GradleException("Could not generate Modulith docs", e);
                }
            });
        });
        project.getTasks().register("modulithGraph", task -> {
            task.setGroup("documentation");
            task.dependsOn(docs);
        });
        project.getTasks().register("modulithTest", task -> {
            task.setGroup("verification");
            task.dependsOn("test");
        });
    }

    private static Path index(Project project) {
        return project.getLayout().getBuildDirectory()
                .file("classes/java/main/META-INF/minecraft-modulith/modules.idx")
                .get().getAsFile().toPath();
    }
}
