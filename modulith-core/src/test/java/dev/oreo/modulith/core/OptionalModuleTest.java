package dev.oreo.modulith.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionalModuleTest {
    private static final List<String> ORDER = new ArrayList<>();

    @AfterEach
    void reset() {
        ORDER.clear();
    }

    @Test
    void optionalModuleFailingWithLinkageErrorDoesNotAbortStartup() {
        ModuleRuntime runtime = ModuleRuntime.builder()
                .module(Core.class)
                .module(MissingPluginApi.class)
                .module(LaterModule.class)
                .start();

        assertEquals(ModuleState.RUNNING, runtime.states().get("core"));
        assertEquals(ModuleState.FAILED, runtime.states().get("tab"));
        assertEquals(ModuleState.RUNNING, runtime.states().get("later"));
        assertInstanceOf(NoClassDefFoundError.class, runtime.failures().get("tab"));
        assertTrue(runtime.diagnostics().failures().get("tab").contains("com/example/Missing"));
        assertTrue(ORDER.contains("tab:cleanup"), "Failed module resources must be released");

        runtime.stop();
        assertEquals(List.of("core:on", "tab:cleanup", "later:on", "later:off", "core:off"), ORDER);
    }

    @Test
    void failedOptionalModuleWithdrawsServicesAndSkipsOptionalDependents() {
        ModuleRuntime runtime = ModuleRuntime.builder()
                .module(PublishesThenFails.class)
                .module(OptionalDependent.class)
                .start();

        assertEquals(ModuleState.FAILED, runtime.states().get("broken"));
        assertEquals(ModuleState.SKIPPED, runtime.states().get("dependent"));
        assertEquals(0, runtime.diagnostics().publishedServices());
        assertTrue(runtime.diagnostics().failures().get("dependent").contains("broken"));
        runtime.stop();
    }

    @Test
    void requiredDependentOfFailedOptionalModuleAbortsAndRollsBack() {
        ModulithException failure = assertThrows(ModulithException.class, () -> ModuleRuntime.builder()
                .module(Core.class)
                .module(PublishesThenFails.class)
                .module(RequiredDependent.class)
                .start());

        assertTrue(failure.getMessage().contains("required-dependent"));
        assertTrue(ORDER.contains("core:off"), "Started modules must be rolled back");
    }

    @Test
    void requiredModuleFailureStillAbortsStartup() {
        assertThrows(ModulithException.class, () -> ModuleRuntime.builder()
                .module(Core.class)
                .module(RequiredFailure.class)
                .start());
        assertEquals(List.of("core:on", "core:off"), ORDER);
    }

    @Test
    void afterOrdersStartupWithoutCascadingFailures() {
        ModuleRuntime runtime = ModuleRuntime.builder()
                .module(OrderedAfterTab.class)
                .module(MissingPluginApi.class)
                .module(Core.class)
                .start();

        assertEquals(ModuleState.FAILED, runtime.states().get("tab"));
        assertEquals(ModuleState.RUNNING, runtime.states().get("holograms"));
        assertEquals(List.of("core:on", "tab:cleanup", "holograms:on"), ORDER);
        assertEquals(List.of("core", "tab", "holograms"), runtime.diagnostics().startupOrder());
        runtime.stop();
    }

    @Test
    void afterIgnoresAbsentModulesButDetectsCycles() {
        ModuleRuntime runtime = ModuleRuntime.builder().module(OrderedAfterTab.class).start();
        assertEquals(ModuleState.RUNNING, runtime.states().get("holograms"));
        runtime.stop();

        assertThrows(ModuleDependencyException.class, () -> ModuleRuntime.builder()
                .module(AfterCycleA.class)
                .module(AfterCycleB.class)
                .build());
    }

    @PluginModule("core")
    static final class Core implements MinecraftModule {
        @Override
        public void enable(ModuleContext context) {
            ORDER.add("core:on");
        }

        @Override
        public void disable() {
            ORDER.add("core:off");
        }
    }

    @PluginModule(value = "tab", dependencies = "core", optional = true)
    static final class MissingPluginApi implements MinecraftModule {
        @Override
        public void enable(ModuleContext context) {
            context.lifecycle().onClose(() -> ORDER.add("tab:cleanup"));
            throw new NoClassDefFoundError("com/example/Missing");
        }
    }

    @PluginModule(value = "later", dependencies = "core")
    static final class LaterModule implements MinecraftModule {
        @Override
        public void enable(ModuleContext context) {
            ORDER.add("later:on");
        }

        @Override
        public void disable() {
            ORDER.add("later:off");
        }
    }

    @ModuleApi("broken")
    interface BrokenApi {
    }

    @PluginModule(value = "broken", optional = true)
    static final class PublishesThenFails implements MinecraftModule, BrokenApi {
        @Override
        public void enable(ModuleContext context) {
            context.services().publish(BrokenApi.class, this);
            throw new IllegalStateException("boom");
        }
    }

    @PluginModule(value = "dependent", dependencies = "broken", optional = true)
    static final class OptionalDependent implements MinecraftModule {
        @Override
        public void enable(ModuleContext context) {
            ORDER.add("dependent:on");
        }
    }

    @PluginModule(value = "required-dependent", dependencies = "broken")
    static final class RequiredDependent implements MinecraftModule {
    }

    @PluginModule(value = "required-failure", dependencies = "core")
    static final class RequiredFailure implements MinecraftModule {
        @Override
        public void enable(ModuleContext context) {
            throw new IllegalStateException("boom");
        }
    }

    @PluginModule(value = "holograms", after = "tab")
    static final class OrderedAfterTab implements MinecraftModule {
        @Override
        public void enable(ModuleContext context) {
            ORDER.add("holograms:on");
        }
    }

    @PluginModule(value = "after-a", after = "after-b")
    static final class AfterCycleA implements MinecraftModule {
    }

    @PluginModule(value = "after-b", dependencies = "after-a")
    static final class AfterCycleB implements MinecraftModule {
    }
}
