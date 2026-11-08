package dev.oreo.modulith.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises real javac annotation-processing rounds and an incremental dependency build. */
class PackageModuleProcessorTest {
    @TempDir Path temp;

    @Test
    void generatedAnchorsCompileAcrossRoundsAndIncrementalBuilds() throws Exception {
        Path input = temp.resolve("sources");
        source(input, "example/economy/package-info.java", """
                @dev.oreo.modulith.core.ApplicationModule(id="economy")
                package example.economy;
                """);
        source(input, "example/economy/api/package-info.java", """
                @dev.oreo.modulith.core.NamedInterface("payments")
                package example.economy.api;
                """);
        source(input, "example/economy/api/Payments.java", """
                package example.economy.api;
                public interface Payments { long amount(); }
                """);
        Path economy = input.resolve("example/economy/package-info.java");
        Path audit = source(input, "example/economy/audit/package-info.java", """
                @dev.oreo.modulith.core.NamedInterface("audit")
                package example.economy.audit;
                """);
        Path homes = source(input, "example/homes/package-info.java", """
                @dev.oreo.modulith.core.ApplicationModule(
                    id="homes", allowedDependencies={"economy::payments"},
                    uses={"economy::audit"})
                package example.homes;
                """);
        Path homeType = source(input, "example/homes/Home.java", """
                package example.homes;
                import example.economy.api.Payments;
                public class Home { public long check(Payments api) { return api.amount(); } }
                """);
        Path first = temp.resolve("classes1");
        List<Path> all = List.of(
                input.resolve("example/economy/package-info.java"),
                input.resolve("example/economy/api/package-info.java"),
                input.resolve("example/economy/api/Payments.java"), audit, homes, homeType);
        compile(first, null, all);
        assertTrue(Files.exists(first.resolve("example/economy/__MinecraftModulithModule.class")));
        assertTrue(Files.exists(first.resolve("example/homes/__MinecraftModulithModule.class")));
        String firstIndex = Files.readString(first.resolve("META-INF/minecraft-modulith/modules.idx"));
        assertTrue(firstIndex.contains("economy|"));
        assertTrue(firstIndex.contains("homes|example.homes.__MinecraftModulithModule"
                + "|economy::payments|false|economy::audit"), firstIndex);

        // The provider's unchanged package-info is not presented to javac in this pass.
        // The prior module index must establish that economy::payments is not missing.
        Path second = temp.resolve("classes2");
        compile(second, first, List.of(homes, homeType));
        assertTrue(Files.exists(second.resolve("example/homes/__MinecraftModulithModule.class")));
        String index = Files.readString(second.resolve("META-INF/minecraft-modulith/modules.idx"));
        assertTrue(index.contains("economy|"), index);
        assertTrue(index.contains("homes|"), index);
        assertTrue(index.contains("economy::audit"), index);

        // A more subtle partial rebuild DOES process economy, but only its 'audit'
        // named interface. Its unchanged 'payments' interface is deliberately absent.
        // An incomplete exportedApis map must not reject homes -> economy::payments.
        Path third = temp.resolve("classes3");
        compile(third, first, List.of(economy, audit, homes, homeType));
        assertTrue(Files.exists(third.resolve("example/homes/__MinecraftModulithModule.class")));
    }

    private Path source(Path base, String name, String body) throws Exception {
        Path file = base.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, body, StandardCharsets.UTF_8);
        return file;
    }

    private void compile(Path output, Path previous, List<Path> sources) throws Exception {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertNotNull(javac, "Tests must run using a JDK");
        Files.createDirectories(output);
        String classpath = System.getProperty("java.class.path") +
                (previous == null ? "" : File.pathSeparator + previous);
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = javac.getStandardFileManager(diagnostics, null,
                StandardCharsets.UTF_8)) {
            var units = manager.getJavaFileObjectsFromPaths(sources);
            Path generated = output.resolve("generated");
            Files.createDirectories(generated);
            var task = javac.getTask(null, manager, diagnostics,
                    List.of("-classpath", classpath, "-d", output.toString(),
                            "-s", generated.toString(), "--release", "21"),
                    null, units);
            task.setProcessors(List.of(new ModuleArchitectureProcessor()));
            boolean ok = task.call();
            assertTrue(ok, () -> diagnostics.getDiagnostics().toString());
            assertTrue(diagnostics.getDiagnostics().stream()
                    .noneMatch(d -> d.getMessage(Locale.ROOT).contains("Attempt to recreate a file")),
                    () -> diagnostics.getDiagnostics().toString());
        }
    }
}
