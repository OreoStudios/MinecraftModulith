package dev.oreo.modulith.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ModuleLifecycleManagementTest {
    @ModuleApi("shop")
    interface ShopApi {
        String name();
        int deliveries();
    }

    record ShopSignal() {}

    @PluginModule(value = "shop", configuration = true)
    static final class Shop implements MinecraftModule, ShopApi {
        private final String name;
        private final AtomicInteger deliveries = new AtomicInteger();
        private final AtomicInteger reloadCallbacks = new AtomicInteger();
        private int revisionAtReload;
        private boolean disabled;
        private PendingTask pending;

        Shop(String name) {
            this.name = name;
        }

        @Override
        public void enable(ModuleContext context) {
            context.services().publish(ShopApi.class, this);
            context.listen(this);
            pending = new PendingTask();
            context.lifecycle().onClose(pending::cancel);
        }

        @ModuleListener(id = "same-listener-type-and-id")
        public void onShopSignal(ShopSignal signal) {
            deliveries.incrementAndGet();
        }

        @Override
        public void reload(ModuleContext context) {
            revisionAtReload = context.config().getInt("revision", -1);
            reloadCallbacks.incrementAndGet();
        }

        @Override
        public void disable() {
            disabled = true;
        }

        @Override public String name() { return name; }
        @Override public int deliveries() { return deliveries.get(); }
    }

    @PluginModule("dependent")
    static final class Dependent implements MinecraftModule {}

    private static final class PendingTask {
        private boolean cancelled;
        private int runs;

        void cancel() { cancelled = true; }

        void fire() {
            if (!cancelled) runs++;
        }
    }

    private static final class TestConfig implements ModuleConfiguration {
        private int revision;
        private int reloadCalls;
        private int saves;
        private boolean failReload;

        @Override public Object get(String key) { return "revision".equals(key) ? revision : null; }
        @Override public String getString(String key, String defaultValue) { return defaultValue; }
        @Override public int getInt(String key, int defaultValue) {
            return "revision".equals(key) ? revision : defaultValue;
        }
        @Override public long getLong(String key, long defaultValue) { return defaultValue; }
        @Override public double getDouble(String key, double defaultValue) { return defaultValue; }
        @Override public boolean getBoolean(String key, boolean defaultValue) { return defaultValue; }
        @Override public List<String> getStringList(String key) { return List.of(); }
        @Override public void set(String key, Object value) {
            if ("revision".equals(key)) revision = (Integer) value;
        }
        @Override public void reload() {
            reloadCalls++;
            if (failReload) throw new IllegalStateException("read failed");
            revision++;
        }
        @Override public void save() { saves++; }
    }

    @PluginModule("failure-probe")
    static final class FailureProbe implements MinecraftModule {
        private final AtomicBoolean failNext;
        private final AtomicInteger cleanups;

        FailureProbe(AtomicBoolean failNext, AtomicInteger cleanups) {
            this.failNext = failNext;
            this.cleanups = cleanups;
        }

        @Override
        public void enable(ModuleContext context) {
            context.lifecycle().onClose(cleanups::incrementAndGet);
            if (failNext.getAndSet(false)) throw new IllegalStateException("injected");
        }
    }

    @Test
    void startAndRestartRecreateOnlyTargetInstanceAndItsResources() {
        List<Shop> alphaInstances = new ArrayList<>();
        List<Shop> betaInstances = new ArrayList<>();
        try (ModuleRuntime runtime = ModuleRuntime.builder()
                .bind("shop.alpha", Shop.class, "alpha", (p, ctx) -> {
                    Shop shop = new Shop(p);
                    alphaInstances.add(shop);
                    return shop;
                })
                .bind("shop.beta", Shop.class, "beta", (p, ctx) -> {
                    Shop shop = new Shop(p);
                    betaInstances.add(shop);
                    return shop;
                })
                .start()) {
            Shop first = alphaInstances.getFirst();
            Shop beta = betaInstances.getFirst();
            runtime.events().publish(new ShopSignal());
            assertEquals(1, first.deliveries());
            assertEquals(1, beta.deliveries());

            runtime.stop("shop.alpha");
            assertTrue(first.disabled);
            assertTrue(first.pending.cancelled);
            assertEquals(1, runtime.diagnostics().publishedServices());

            runtime.start("shop.alpha");
            Shop second = alphaInstances.get(1);
            assertNotSame(first, second);
            assertEquals(ModuleState.RUNNING, runtime.states().get("shop.alpha"));
            assertEquals(2, runtime.diagnostics().publishedServices());
            runtime.events().publish(new ShopSignal());
            assertEquals(1, first.deliveries(), "Old listener must not receive events");
            assertEquals(1, second.deliveries());
            assertEquals(2, beta.deliveries());

            runtime.restart("shop.alpha");
            Shop third = alphaInstances.get(2);
            assertNotSame(second, third);
            assertTrue(second.disabled);
            assertTrue(second.pending.cancelled);
            assertFalse(third.pending.cancelled);
            assertSame(beta, betaInstances.getFirst(), "Sibling must not be replaced");
            assertFalse(beta.disabled);
            runtime.events().publish(new ShopSignal());
            assertEquals(1, second.deliveries());
            assertEquals(1, third.deliveries());
            assertEquals(3, beta.deliveries());
            first.pending.fire();
            second.pending.fire();
            third.pending.fire();
            assertEquals(0, first.pending.runs);
            assertEquals(0, second.pending.runs);
            assertEquals(1, third.pending.runs);
        }
        assertEquals(1, betaInstances.size());
        assertTrue(alphaInstances.stream().allMatch(shop -> shop.disabled));
    }

    @Test
    void reloadRefreshesOnlySelectedConfigurationWithoutReplacingInstance() {
        List<TestConfig> configs = new ArrayList<>();
        List<Shop> instances = new ArrayList<>();
        try (ModuleRuntime runtime = ModuleRuntime.builder()
                .bind("shop.alpha", Shop.class, "alpha", (p, ctx) -> {
                    Shop shop = new Shop(p);
                    instances.add(shop);
                    return shop;
                })
                .bind("shop.beta", Shop.class, "beta", (p, ctx) -> {
                    Shop shop = new Shop(p);
                    instances.add(shop);
                    return shop;
                })
                .configurationProvider(desc -> {
                    TestConfig config = new TestConfig();
                    configs.add(config);
                    return config;
                })
                .start()) {
            Shop alpha = instances.stream().filter(shop -> shop.name().equals("alpha")).findFirst().orElseThrow();
            Shop beta = instances.stream().filter(shop -> shop.name().equals("beta")).findFirst().orElseThrow();
            assertEquals(2, configs.size());

            runtime.reload("shop.alpha");
            assertEquals(1, configs.stream().mapToInt(config -> config.reloadCalls).sum());
            assertEquals(1, alpha.reloadCallbacks.get());
            assertEquals(1, alpha.revisionAtReload);
            assertEquals(0, beta.reloadCallbacks.get());
            assertEquals(2, runtime.diagnostics().publishedServices());
            assertEquals(2, instances.size(), "Reload must not recreate modules");
            assertFalse(alpha.pending.cancelled);
            assertFalse(beta.pending.cancelled);
            runtime.events().publish(new ShopSignal());
            assertEquals(1, alpha.deliveries());
            assertEquals(1, beta.deliveries());
        }
        assertEquals(2, configs.stream().mapToInt(config -> config.saves).sum());
    }

    @Test
    void reloadFailureIsReportedButDoesNotKillSiblingOrModule() {
        TestConfig config = new TestConfig();
        try (ModuleRuntime runtime = ModuleRuntime.builder()
                .bind("shop.alpha", Shop.class, "alpha", (p, ctx) -> new Shop(p))
                .configurationProvider(desc -> config)
                .start()) {
            config.failReload = true;
            ModulithException error = assertThrows(ModulithException.class,
                    () -> runtime.reload("shop.alpha"));
            assertInstanceOf(IllegalStateException.class, error.getCause());
            assertEquals(ModuleState.RUNNING, runtime.states().get("shop.alpha"));
            config.failReload = false;
            assertDoesNotThrow(() -> runtime.reload("shop.alpha"));
        }
    }

    @Test
    void requiredDependenciesPreventStopAndRestartAndMustRunBeforeStart() {
        try (ModuleRuntime runtime = ModuleRuntime.builder()
                .bind("shop.alpha", Shop.class, "alpha", (p, ctx) -> new Shop(p))
                .bind("dependent.one", Dependent.class, "ignored", (p, ctx) -> new Dependent())
                .dependsOn("dependent.one", "shop.alpha::shop")
                .start()) {
            assertThrows(ModulithException.class, () -> runtime.stop("shop.alpha"));
            assertThrows(ModulithException.class, () -> runtime.restart("shop.alpha"));
            assertEquals(ModuleState.RUNNING, runtime.states().get("shop.alpha"));

            runtime.stop("dependent.one");
            runtime.stop("shop.alpha");
            assertThrows(ModulithException.class, () -> runtime.start("dependent.one"));
            assertEquals(ModuleState.STOPPED, runtime.states().get("dependent.one"));
            runtime.start("shop.alpha");
            runtime.start("dependent.one");
            assertEquals(ModuleState.RUNNING, runtime.states().get("dependent.one"));
        }
    }

    @Test
    void failedInstanceStartCleansItsScopeAndCanBeRetried() {
        AtomicBoolean failNext = new AtomicBoolean(false);
        AtomicInteger cleanups = new AtomicInteger();
        try (ModuleRuntime runtime = ModuleRuntime.builder()
                .bind("failure.one", FailureProbe.class, "unused",
                        (p, ctx) -> new FailureProbe(failNext, cleanups))
                .start()) {
            runtime.stop("failure.one");
            assertEquals(1, cleanups.get());
            failNext.set(true);
            ModulithException exception = assertThrows(ModulithException.class,
                    () -> runtime.start("failure.one"));
            assertInstanceOf(IllegalStateException.class, exception.getCause());
            assertEquals(ModuleState.FAILED, runtime.states().get("failure.one"));
            assertEquals(2, cleanups.get());
            runtime.start("failure.one");
            assertEquals(ModuleState.RUNNING, runtime.states().get("failure.one"));
            assertFalse(runtime.failures().containsKey("failure.one"));
        }
        assertEquals(3, cleanups.get());
    }

    @Test
    void lifecycleOperationsRejectUnknownInstancesAndReloadOfStoppedModules() {
        try (ModuleRuntime runtime = ModuleRuntime.builder()
                .bind("shop.alpha", Shop.class, "alpha", (p, ctx) -> new Shop(p))
                .start()) {
            assertThrows(ModulithException.class, () -> runtime.start("missing"));
            assertThrows(ModulithException.class, () -> runtime.restart("missing"));
            assertThrows(ModulithException.class, () -> runtime.reload("missing"));
            runtime.stop("shop.alpha");
            assertThrows(ModulithException.class, () -> runtime.restart("shop.alpha"));
            assertThrows(ModulithException.class, () -> runtime.reload("shop.alpha"));
        }
    }
}
