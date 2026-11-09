package dev.oreo.modulith.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class NamedInstanceLifecycleIsolationTest {
    @ModuleApi("shop")
    interface ShopService {
        String name();
        int deliveries();
    }

    record ShopParameters(String name) {}
    record ShopEvent(String message) {}

    /**
     * Both instances deliberately use identical module, service and listener types.
     * The registry and lifecycle must use the instance ID, never the class identity.
     */
    @PluginModule("shop")
    static final class Shop implements MinecraftModule, ShopService {
        private final ShopParameters parameters;
        private final AtomicInteger deliveries = new AtomicInteger();
        private final AtomicInteger delayedRuns = new AtomicInteger();
        private PendingTask delayed;
        private boolean disabled;

        Shop(ShopParameters parameters) {
            this.parameters = parameters;
        }

        @Override
        public void enable(ModuleContext context) {
            context.services().publish(ShopService.class, this);
            context.listen(this);
            delayed = new PendingTask(delayedRuns::incrementAndGet);
            // A deterministic stand-in for PaperPlatform.scheduleLater's
            // context.lifecycle().onClose(task::cancel) ownership contract.
            context.lifecycle().onClose(delayed::cancel);
        }

        @ModuleListener(id = "on-shop-event")
        public void onShopEvent(ShopEvent event) {
            deliveries.incrementAndGet();
        }

        @Override public String name() { return parameters.name(); }
        @Override public int deliveries() { return deliveries.get(); }
        @Override public void disable() { disabled = true; }
    }

    @PluginModule(value = "observer", uses = "shop::shop")
    static final class Observer implements MinecraftModule {
        private ModuleContext context;

        @Override public void enable(ModuleContext context) {
            this.context = context;
        }

        ShopService service(String instanceId) {
            return context.services().require(instanceId, ShopService.class);
        }
    }

    static final class PendingTask {
        private final Runnable action;
        private boolean cancelled;
        private boolean executed;

        PendingTask(Runnable action) { this.action = action; }

        void cancel() { cancelled = true; }

        void fire() {
            if (!cancelled && !executed) {
                executed = true;
                action.run();
            }
        }
    }

    @Test
    void stoppingOneNamedInstanceDoesNotAffectSiblingInstance() {
        List<Shop> shops = new ArrayList<>();
        Observer observer = new Observer();
        try (ModuleRuntime runtime = ModuleRuntime.builder()
                .bind("shop.alpha", Shop.class, new ShopParameters("alpha"), (p, ctx) -> {
                    Shop shop = new Shop(p);
                    shops.add(shop);
                    return shop;
                })
                .bind("shop.beta", Shop.class, new ShopParameters("beta"), (p, ctx) -> {
                    Shop shop = new Shop(p);
                    shops.add(shop);
                    return shop;
                })
                .functor(Observer.class, "observer", (p, ctx) -> observer)
                .start()) {

            Shop alpha = shops.stream().filter(s -> s.name().equals("alpha")).findFirst().orElseThrow();
            Shop beta = shops.stream().filter(s -> s.name().equals("beta")).findFirst().orElseThrow();

            assertSame(alpha, observer.service("shop.alpha"));
            assertSame(beta, observer.service("shop.beta"));
            assertEquals(2, runtime.diagnostics().publishedServices());

            runtime.events().publish(new ShopEvent("before-stop"));
            assertEquals(1, alpha.deliveries());
            assertEquals(1, beta.deliveries());

            runtime.stop("shop.alpha");

            assertEquals(ModuleState.STOPPED, runtime.states().get("shop.alpha"));
            assertEquals(ModuleState.RUNNING, runtime.states().get("shop.beta"));
            assertTrue(alpha.disabled);
            assertFalse(beta.disabled);
            assertEquals(1, runtime.diagnostics().publishedServices());
            assertThrows(ModulithException.class, () -> observer.service("shop.alpha"));
            assertSame(beta, observer.service("shop.beta"));

            runtime.events().publish(new ShopEvent("after-stop"));
            assertEquals(1, alpha.deliveries(), "Stopped instance listener must be unsubscribed");
            assertEquals(2, beta.deliveries(), "Sibling instance listener must remain active");

            alpha.delayed.fire();
            beta.delayed.fire();
            assertTrue(alpha.delayed.cancelled, "Stopped instance's delayed work must be cancelled");
            assertEquals(0, alpha.delayedRuns.get());
            assertFalse(beta.delayed.cancelled, "Sibling instance's task must remain scheduled");
            assertEquals(1, beta.delayedRuns.get());

            runtime.stop("shop.alpha"); // idempotent, must not touch beta
            assertEquals(ModuleState.RUNNING, runtime.states().get("shop.beta"));
        }
        assertTrue(shops.stream().allMatch(s -> s.disabled));
    }

    @PluginModule("dependent")
    static final class Dependent implements MinecraftModule {}

    @Test
    void cannotStopInstanceWhileRequiredByRunningDependent() {
        try (ModuleRuntime runtime = ModuleRuntime.builder()
                .bind("shop.alpha", Shop.class, new ShopParameters("alpha"), (p, ctx) -> new Shop(p))
                .bind("dependent.one", Dependent.class, "unused", (p, ctx) -> new Dependent())
                .dependsOn("dependent.one", "shop.alpha::shop")
                .start()) {
            ModulithException failure = assertThrows(ModulithException.class,
                    () -> runtime.stop("shop.alpha"));
            assertTrue(failure.getMessage().contains("dependent.one"));
            assertEquals(Map.of("shop.alpha", ModuleState.RUNNING,
                    "dependent.one", ModuleState.RUNNING), runtime.states());
            runtime.stop("dependent.one");
            runtime.stop("shop.alpha");
        }
    }

    @Test
    void unknownInstanceIsRejected() {
        try (ModuleRuntime runtime = ModuleRuntime.builder()
                .bind("shop.alpha", Shop.class, new ShopParameters("alpha"), (p, ctx) -> new Shop(p))
                .start()) {
            assertThrows(ModulithException.class, () -> runtime.stop("shop.unknown"));
        }
    }
}
