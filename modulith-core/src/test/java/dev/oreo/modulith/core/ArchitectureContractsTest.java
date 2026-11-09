package dev.oreo.modulith.core;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ArchitectureContractsTest {
    @ModuleApi("payments")
    interface Payments {
        int balance();
    }

    @PluginModule("provider")
    static final class Provider implements MinecraftModule, Payments {
        @Override public int balance() { return 42; }
        @Override public void enable(ModuleContext ctx) {
            ctx.services().publish(Payments.class, this);
        }
    }

    @PluginModule(value = "consumer", uses = "provider::payments")
    static final class Consumer implements MinecraftModule {
        static boolean resolved;
        @Override public void enable(ModuleContext ctx) {
            resolved = ctx.services().require(Payments.class).balance() == 42;
        }
    }

    @Test
    void usesGrantsRuntimeAccessWithoutAddingAnOrderingEdge() {
        Consumer.resolved = false;
        ModuleDescriptor descriptor = ModuleRuntime.Builder.describe(Consumer.class);
        assertTrue(descriptor.dependencies().isEmpty());
        assertEquals(List.of("provider::payments"), descriptor.uses());
        try (ModuleRuntime runtime = ModuleRuntime.builder()
                .module(Provider.class).module(Consumer.class).start()) {
            assertTrue(Consumer.resolved);
            assertTrue(runtime.graphMermaid().contains("uses::payments"));
        }
    }

    @Test
    void usesDoesNotRequireProviderToBeInstalledOrAvailableAtStartup() {
        try (ModuleRuntime runtime = ModuleRuntime.builder().module(UsesMissing.class).start()) {
            assertEquals(ModuleState.RUNNING, runtime.states().get("optional-user"));
        }
    }

    @PluginModule(value = "optional-user", uses = "missing-provider::api")
    static final class UsesMissing implements MinecraftModule {}

    @PluginModule("stoppable")
    static final class BadStop implements MinecraftModule {
        static int starts;
        @Override public void enable(ModuleContext ctx) { starts++; }
        @Override public void disable() { throw new IllegalStateException("disable failed"); }
    }

    @Test
    void stopFailureNeverLeavesRuntimeMarkedStarted() {
        BadStop.starts = 0;
        ModuleRuntime runtime = ModuleRuntime.builder().module(BadStop.class).start();
        assertThrows(ModulithException.class, () -> runtime.stop());
        assertEquals(ModuleState.STOPPED, runtime.states().get("stoppable"));
        assertDoesNotThrow(() -> runtime.stop());
        assertDoesNotThrow(() -> { runtime.start(); });
        assertEquals(2, BadStop.starts);
        assertThrows(ModulithException.class, () -> runtime.stop());
    }

    @PluginModule("required")
    static final class Required implements MinecraftModule {
        @Override public void enable(ModuleContext ctx) {
            ctx.lifecycle().onClose(() -> { throw new IllegalStateException("cleanup"); });
            throw new IllegalArgumentException("startup");
        }
    }

    @Test
    void originalStartupErrorSurvivesRollbackFailure() {
        ModulithException failure = assertThrows(ModulithException.class,
                () -> ModuleRuntime.builder().module(Required.class).start());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        // The failed module cleans its own lifecycle scope; any cleanup exception
        // must not replace the original startup exception.
        assertEquals(1, failure.getCause().getSuppressed().length);
    }
}
