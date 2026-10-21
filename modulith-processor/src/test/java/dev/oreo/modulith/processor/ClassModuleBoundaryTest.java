package dev.oreo.modulith.processor;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** Regression tests for the named API boundary used in the migration tutorial. */
class ClassModuleBoundaryTest {
    @TempDir
    Path temp;

    @Test
    void namedApiCanBeUsedByDeclaredConsumerModule() throws Exception {
        Fixture fixture = fixture(false);
        CompilationResult result = compile(fixture);
        assertTrue(result.success(), result.diagnostics());
    }

    @Test
    void directCrossModuleImplementationReferenceIsRejected() throws Exception {
        Fixture fixture = fixture(true);
        CompilationResult result = compile(fixture);
        assertFalse(result.success(), "Direct access to EconomyModule should be rejected");
        assertTrue(result.diagnostics().contains("not annotated with @ModuleApi"),
                result.diagnostics());
    }

    private Fixture fixture(boolean accessImplementation) throws Exception {
        Path source = temp.resolve("src");
        Path economyModule = write(source, "sample/economy/EconomyModule.java", """
                package sample.economy;
                @dev.oreo.modulith.core.PluginModule("economy")
                public final class EconomyModule {}
                """);
        Path economyApi = write(source, "sample/economy/EconomyService.java", """
                package sample.economy;
                @dev.oreo.modulith.core.ModuleApi("payments")
                public interface EconomyService { long balance(); }
                """);
        Path balanceModule = write(source, "sample/balance/BalanceModule.java", """
                package sample.balance;
                @dev.oreo.modulith.core.PluginModule(
                    value="balance", dependencies="economy::payments")
                public final class BalanceModule {}
                """);
        Path consumer = write(source, "sample/balance/BalanceCommand.java",
                accessImplementation ? """
                        package sample.balance;
                        import sample.economy.EconomyModule;
                        public final class BalanceCommand {
                            private final EconomyModule implementation;
                            public BalanceCommand(EconomyModule implementation) {
                                this.implementation = implementation;
                            }
                        }
                        """ : """
                        package sample.balance;
                        import sample.economy.EconomyService;
                        public final class BalanceCommand {
                            private final EconomyService economy;
                            public BalanceCommand(EconomyService economy) {
                                this.economy = economy;
                            }
                            public long check() { return economy.balance(); }
                        }
                        """);
        return new Fixture(List.of(economyModule, economyApi, balanceModule, consumer));
    }

    private Path write(Path source, String relative, String text) throws Exception {
        Path path = source.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text, StandardCharsets.UTF_8);
        return path;
    }

    private CompilationResult compile(Fixture fixture) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Requires a JDK");
        Path output = temp.resolve("classes");
        Files.createDirectories(output);
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(
                diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            var units = manager.getJavaFileObjectsFromPaths(fixture.files());
            var task = compiler.getTask(null, manager, diagnostics,
                    List.of("--release", "21", "-classpath", System.getProperty("java.class.path"),
                            "-d", output.toString()), null, units);
            task.setProcessors(List.of(new ModuleArchitectureProcessor()));
            boolean success = task.call();
            String messages = diagnostics.getDiagnostics().stream()
                    .map(d -> d.getKind() + ": " + d.getMessage(Locale.ROOT))
                    .reduce("", (left, right) -> left + right + System.lineSeparator());
            return new CompilationResult(success, messages);
        }
    }

    private record Fixture(List<Path> files) {}
    private record CompilationResult(boolean success, String diagnostics) {}
}
