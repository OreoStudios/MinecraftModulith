package dev.oreo.modulith.processor;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for layered modules: an {@code @ApplicationModule} package-info at the module
 * root with the {@code @PluginModule} lifecycle class in a {@code lifecycle} sub-package.
 */
class LayeredModuleBoundaryTest {
    @TempDir
    Path temp;

    @Test
    void layeredModulesCompileWithoutDuplicateRegistrations() throws Exception {
        Result result = compile(economy(), homes(consumer("long check(EconomyService economy) { return economy.balance(); }",
                "import sample.economy.api.EconomyService;")));
        assertTrue(result.success(), result.diagnostics());

        assertFalse(Files.exists(result.output().resolve("sample/economy/__MinecraftModulithModule.class")),
                "A package with a lifecycle class must not get a generated anchor");
        assertFalse(Files.exists(result.output().resolve("sample/homes/__MinecraftModulithModule.class")));
        String index = Files.readString(result.output().resolve("META-INF/minecraft-modulith/modules.idx"));
        assertTrue(index.contains("economy|sample.economy.lifecycle.EconomyModule||true"), index);
        assertTrue(index.contains("homes|sample.homes.lifecycle.HomesModule|economy::payments|false"), index);
        assertEquals(2, index.lines().count(), index);
    }

    /** Lambdas over captured wildcards must not produce false "cannot find symbol" errors. */
    @Test
    void lambdaOverWildcardCollectionCompilesInAProtectedModule() throws Exception {
        Result result = compile(economy(), homes(consumer(
                "int count(java.util.Collection<? extends CharSequence> names) {"
                        + " int[] total = {0}; names.forEach(n -> total[0] += n.length());"
                        + " names.stream().filter(n -> n.length() > 1).forEach(n -> total[0]++); return total[0]; }",
                "")));
        assertTrue(result.success(), result.diagnostics());
    }

    @Test
    void internalReferenceInsideALambdaIsStillRejected() throws Exception {
        Result result = compile(economy(), homes(consumer(
                "Runnable leak() { return () -> new LedgerCache(); }",
                "import sample.economy.infra.internal.LedgerCache;")));
        assertFalse(result.success());
        assertTrue(result.diagnostics().contains("cannot access internal type sample.economy.infra.internal.LedgerCache"),
                result.diagnostics());
    }

    @Test
    void infrastructureClassOfAnotherModuleIsRejected() throws Exception {
        Result result = compile(economy(), homes(consumer(
                "Object leak() { return new InMemoryEconomyRepository(); }",
                "import sample.economy.infra.InMemoryEconomyRepository;")));
        assertFalse(result.success());
        assertTrue(result.diagnostics().contains("Cross-module reference to "
                + "sample.economy.infra.InMemoryEconomyRepository is not allowed because it is not "
                + "annotated with @ModuleApi"), result.diagnostics());
    }

    @Test
    void domainClassOfAnotherModuleIsRejected() throws Exception {
        Result result = compile(economy(), homes(consumer(
                "Object leak() { return new Account(); }",
                "import sample.economy.domain.Account;")));
        assertFalse(result.success());
        assertTrue(result.diagnostics().contains("sample.economy.domain.Account is not allowed"),
                result.diagnostics());
    }

    @Test
    void lifecycleClassOfAnotherModuleIsRejected() throws Exception {
        Result result = compile(economy(), homes(consumer(
                "Object leak() { return new EconomyModule(); }",
                "import sample.economy.lifecycle.EconomyModule;")));
        assertFalse(result.success());
        assertTrue(result.diagnostics().contains("sample.economy.lifecycle.EconomyModule is not allowed"),
                result.diagnostics());
    }

    @Test
    void nestedInternalPackageOfAnotherModuleIsRejected() throws Exception {
        Result result = compile(economy(), homes(consumer(
                "Object leak() { return new LedgerCache(); }",
                "import sample.economy.infra.internal.LedgerCache;")));
        assertFalse(result.success());
        assertTrue(result.diagnostics().contains("Module 'homes' cannot access internal type "
                + "sample.economy.infra.internal.LedgerCache from module 'economy'"), result.diagnostics());
    }

    @Test
    void publicApiWithoutDeclaredDependencyIsRejected() throws Exception {
        Map<String, String> homes = homes(consumer(
                "long check(EconomyService economy) { return economy.balance(); }",
                "import sample.economy.api.EconomyService;"));
        homes.put("sample/homes/package-info.java", """
                @dev.oreo.modulith.core.ApplicationModule(id = "homes")
                package sample.homes;
                """);
        Result result = compile(economy(), homes);
        assertFalse(result.success());
        assertTrue(result.diagnostics().contains("Module 'homes' must declare dependency 'economy' or "
                + "'economy::payments' to access sample.economy.api.EconomyService"), result.diagnostics());
    }

    @Test
    void dependencyOnUnknownNamedApiIsRejected() throws Exception {
        Map<String, String> homes = homes(Map.of());
        homes.put("sample/homes/package-info.java", """
                @dev.oreo.modulith.core.ApplicationModule(id = "homes", allowedDependencies = "economy::refunds")
                package sample.homes;
                """);
        Result result = compile(economy(), homes);
        assertFalse(result.success());
        assertTrue(result.diagnostics().contains("depends on unknown named API 'economy::refunds'"),
                result.diagnostics());
    }

    @Test
    void ownModuleLayersMayReferenceEachOther() throws Exception {
        Map<String, String> economy = economy();
        economy.put("sample/economy/application/DepositUseCase.java", """
                package sample.economy.application;
                import sample.economy.domain.Account;
                import sample.economy.infra.InMemoryEconomyRepository;
                import sample.economy.infra.internal.LedgerCache;
                public final class DepositUseCase {
                    Object wire() { return new Object[] {
                        new Account(), new InMemoryEconomyRepository(), new LedgerCache() }; }
                }
                """);
        Result result = compile(economy, homes(Map.of()));
        assertTrue(result.success(), result.diagnostics());
    }

    @Test
    void dependenciesMayBeDeclaredOnTheLifecycleClass() throws Exception {
        Map<String, String> homes = homes(consumer(
                "long check(EconomyService economy) { return economy.balance(); }",
                "import sample.economy.api.EconomyService;"));
        homes.put("sample/homes/package-info.java", """
                @dev.oreo.modulith.core.ApplicationModule(id = "homes")
                package sample.homes;
                """);
        homes.put("sample/homes/lifecycle/HomesModule.java", """
                package sample.homes.lifecycle;
                @dev.oreo.modulith.core.PluginModule(value = "homes", dependencies = "economy::payments")
                public final class HomesModule implements dev.oreo.modulith.core.MinecraftModule {}
                """);
        Result result = compile(economy(), homes);
        assertTrue(result.success(), result.diagnostics());
    }

    @Test
    void conflictingDependencyDeclarationsAreRejected() throws Exception {
        Map<String, String> homes = homes(Map.of());
        homes.put("sample/homes/lifecycle/HomesModule.java", """
                package sample.homes.lifecycle;
                @dev.oreo.modulith.core.PluginModule(value = "homes", dependencies = "economy")
                public final class HomesModule implements dev.oreo.modulith.core.MinecraftModule {}
                """);
        Result result = compile(economy(), homes);
        assertFalse(result.success());
        assertTrue(result.diagnostics().contains("Module 'homes' declares different dependencies"),
                result.diagnostics());
    }

    @Test
    void lifecycleClassOutsideTheBoundaryIsADuplicateId() throws Exception {
        Map<String, String> sources = economy();
        sources.put("sample/other/EconomyModule.java", """
                package sample.other;
                @dev.oreo.modulith.core.PluginModule("economy")
                public final class EconomyModule implements dev.oreo.modulith.core.MinecraftModule {}
                """);
        sources.remove("sample/economy/lifecycle/EconomyModule.java");
        sources.put("sample/economy/lifecycle/Placeholder.java", """
                package sample.economy.lifecycle;
                final class Placeholder {}
                """);
        Result result = compile(sources);
        assertFalse(result.success());
        assertTrue(result.diagnostics().contains("Duplicate MinecraftModulith module id 'economy'"),
                result.diagnostics());
    }

    @Test
    void incrementalBuildOfPackageInfoDoesNotGenerateAnAnchor() throws Exception {
        Result first = compile(economy(), homes(Map.of()));
        assertTrue(first.success(), first.diagnostics());

        // Only the boundary declaration changed; the lifecycle class comes from the previous output.
        Result second = compileTo("incremental", first.output(), Map.of(
                "sample/economy/package-info.java", economy().get("sample/economy/package-info.java")));
        assertTrue(second.success(), second.diagnostics());
        assertFalse(Files.exists(second.output().resolve("sample/economy/__MinecraftModulithModule.class")),
                "An unchanged lifecycle class must not be replaced by a generated anchor");
        String index = Files.readString(second.output().resolve("META-INF/minecraft-modulith/modules.idx"));
        assertTrue(index.contains("economy|sample.economy.lifecycle.EconomyModule|"), index);
    }

    private Map<String, String> economy() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("sample/economy/package-info.java", """
                @dev.oreo.modulith.core.ApplicationModule(id = "economy")
                package sample.economy;
                """);
        sources.put("sample/economy/api/EconomyService.java", """
                package sample.economy.api;
                @dev.oreo.modulith.core.ModuleApi("payments")
                public interface EconomyService { long balance(); }
                """);
        sources.put("sample/economy/domain/Account.java", """
                package sample.economy.domain;
                public final class Account {}
                """);
        sources.put("sample/economy/infra/InMemoryEconomyRepository.java", """
                package sample.economy.infra;
                public final class InMemoryEconomyRepository {}
                """);
        sources.put("sample/economy/infra/internal/LedgerCache.java", """
                package sample.economy.infra.internal;
                public final class LedgerCache {}
                """);
        sources.put("sample/economy/lifecycle/EconomyModule.java", """
                package sample.economy.lifecycle;
                @dev.oreo.modulith.core.PluginModule(value = "economy", configuration = true)
                public final class EconomyModule implements dev.oreo.modulith.core.MinecraftModule {}
                """);
        return sources;
    }

    private Map<String, String> homes(Map<String, String> extra) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("sample/homes/package-info.java", """
                @dev.oreo.modulith.core.ApplicationModule(id = "homes", allowedDependencies = "economy::payments")
                package sample.homes;
                """);
        sources.put("sample/homes/lifecycle/HomesModule.java", """
                package sample.homes.lifecycle;
                @dev.oreo.modulith.core.PluginModule("homes")
                public final class HomesModule implements dev.oreo.modulith.core.MinecraftModule {}
                """);
        sources.putAll(extra);
        return sources;
    }

    private Map<String, String> consumer(String body, String imports) {
        return Map.of("sample/homes/application/HomeService.java", """
                package sample.homes.application;
                %s
                public final class HomeService {
                    %s
                }
                """.formatted(imports, body));
    }

    @SafeVarargs
    private Result compile(Map<String, String>... groups) throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        for (Map<String, String> group : groups) sources.putAll(group);
        return compileTo("classes", null, sources);
    }

    private Result compileTo(String name, Path previous, Map<String, String> sources) throws Exception {
        Path src = temp.resolve(name + "-src");
        List<Path> files = new ArrayList<>();
        for (var entry : sources.entrySet()) {
            Path file = src.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
            files.add(file);
        }
        Path output = temp.resolve(name);
        Path generated = temp.resolve(name + "-generated");
        Files.createDirectories(output);
        Files.createDirectories(generated);
        String classpath = System.getProperty("java.class.path")
                + (previous == null ? "" : File.pathSeparator + previous);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Requires a JDK");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(
                diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            var task = compiler.getTask(null, manager, diagnostics,
                    List.of("--release", "21", "-classpath", classpath, "-d", output.toString(),
                            "-s", generated.toString()),
                    null, manager.getJavaFileObjectsFromPaths(files));
            task.setProcessors(List.of(new ModuleArchitectureProcessor()));
            boolean success = task.call();
            String messages = diagnostics.getDiagnostics().stream()
                    .map(d -> d.getKind() + ": " + d.getMessage(Locale.ROOT))
                    .reduce("", (left, right) -> left + right + System.lineSeparator());
            return new Result(success, messages, output);
        }
    }

    private record Result(boolean success, String diagnostics, Path output) {}
}
