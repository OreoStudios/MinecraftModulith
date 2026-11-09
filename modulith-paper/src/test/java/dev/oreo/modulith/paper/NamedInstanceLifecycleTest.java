package dev.oreo.modulith.paper;

import dev.oreo.modulith.core.MinecraftModule;
import dev.oreo.modulith.core.ModuleApi;
import dev.oreo.modulith.core.ModuleContext;
import dev.oreo.modulith.core.ModuleListener;
import dev.oreo.modulith.core.ModuleRuntime;
import dev.oreo.modulith.core.ModuleServices;
import dev.oreo.modulith.core.ModuleState;
import dev.oreo.modulith.core.ModulithException;
import dev.oreo.modulith.core.PluginModule;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class NamedInstanceLifecycleTest {
    @ModuleApi("catalog")
    interface ShopService {
        String name();
    }

    record ShopOptions(String name) {}
    record StockChanged() {}

    @PluginModule("shop")
    static final class Shop implements MinecraftModule, ShopService {
        private final ShopOptions options;
        final AtomicInteger receivedEvents = new AtomicInteger();
        final AtomicInteger delayedRuns = new AtomicInteger();
        final AtomicInteger disables = new AtomicInteger();

        Shop(ShopOptions options) {
            this.options = options;
        }

        @Override
        public String name() {
            return options.name();
        }

        @Override
        public void enable(ModuleContext context) {
            context.services().publish(ShopService.class, this);
            context.listen(this); // Both instances register the identical listener class and ID.
            context.platform(PaperPlatform.class).scheduleLater(
                    context, delayedRuns::incrementAndGet, 20);
        }

        @ModuleListener(id = "stock-change")
        public void onStockChanged(StockChanged event) {
            receivedEvents.incrementAndGet();
        }

        @Override
        public void disable() {
            disables.incrementAndGet();
        }
    }

    @PluginModule(value = "observer", uses = "shop::catalog")
    static final class Observer implements MinecraftModule {
        private final AtomicReference<ModuleServices> registry;

        Observer(AtomicReference<ModuleServices> registry) {
            this.registry = registry;
        }

        @Override
        public void enable(ModuleContext context) {
            registry.set(context.services());
        }
    }

    @Test
    void stoppingOneNamedInstancePreservesSiblingServicesListenersAndPendingTasks() {
        ControlledScheduler scheduler = new ControlledScheduler();
        PaperPlatform platform = new PaperPlatform(null, scheduler);
        AtomicReference<Shop> alphaRef = new AtomicReference<>();
        AtomicReference<Shop> betaRef = new AtomicReference<>();
        AtomicReference<ModuleServices> observer = new AtomicReference<>();

        try (ModuleRuntime runtime = ModuleRuntime.builder()
                .platformService(PaperPlatform.class, platform)
                .bind("shop.alpha", Shop.class, new ShopOptions("Alpha"), (params, ctx) -> {
                    Shop shop = new Shop(params);
                    alphaRef.set(shop);
                    return shop;
                })
                .bind("shop.beta", Shop.class, new ShopOptions("Beta"), (params, ctx) -> {
                    Shop shop = new Shop(params);
                    betaRef.set(shop);
                    return shop;
                })
                .bind("observer.default", Observer.class, observer,
                        (registry, ctx) -> new Observer(registry))
                .start()) {

            Shop alpha = alphaRef.get();
            Shop beta = betaRef.get();
            ModuleServices services = observer.get();
            assertNotSame(alpha, beta);
            assertSame(alpha, services.require("shop.alpha", ShopService.class));
            assertSame(beta, services.require("shop.beta", ShopService.class));
            assertThrows(ModulithException.class, () -> services.require(ShopService.class));
            assertEquals(2, runtime.diagnostics().publishedServices());

            runtime.events().publish(new StockChanged());
            assertEquals(1, alpha.receivedEvents.get());
            assertEquals(1, beta.receivedEvents.get());

            // One shutdown must not unregister the sibling's listener or services.
            runtime.stop("shop.alpha");
            runtime.stop("shop.alpha"); // A repeated shutdown must not disable twice.
            assertEquals(ModuleState.STOPPED, runtime.states().get("shop.alpha"));
            assertEquals(ModuleState.RUNNING, runtime.states().get("shop.beta"));
            assertEquals(1, alpha.disables.get());
            assertEquals(0, beta.disables.get());
            assertEquals(1, runtime.diagnostics().publishedServices());
            assertThrows(ModulithException.class,
                    () -> services.require("shop.alpha", ShopService.class));
            assertSame(beta, services.require("shop.beta", ShopService.class));
            assertSame(beta, services.require(ShopService.class));

            runtime.events().publish(new StockChanged());
            assertEquals(1, alpha.receivedEvents.get());
            assertEquals(2, beta.receivedEvents.get());

            // Advance a fake scheduler deterministically; no sleep or timing race.
            assertEquals(2, scheduler.tasks.size());
            assertTrue(scheduler.tasks.get(0).cancelled);
            assertFalse(scheduler.tasks.get(1).cancelled);
            scheduler.runPending();
            assertEquals(0, alpha.delayedRuns.get());
            assertEquals(1, beta.delayedRuns.get());
        }
    }

    private static final class ControlledScheduler implements SchedulerAdapter {
        final List<PendingTask> tasks = new ArrayList<>();

        @Override
        public ScheduledTaskHandle run(Runnable runnable) {
            return runLater(runnable, 0);
        }

        @Override
        public ScheduledTaskHandle runLater(Runnable runnable, long delayTicks) {
            PendingTask task = new PendingTask(runnable);
            tasks.add(task);
            return task;
        }

        @Override
        public ScheduledTaskHandle runTimer(Runnable runnable, long delayTicks, long periodTicks) {
            throw new UnsupportedOperationException("Not needed by this test");
        }

        @Override
        public boolean folia() {
            return false;
        }

        void runPending() {
            List.copyOf(tasks).forEach(PendingTask::run);
        }
    }

    private static final class PendingTask implements ScheduledTaskHandle {
        private final Runnable runnable;
        boolean cancelled;
        boolean executed;

        PendingTask(Runnable runnable) {
            this.runnable = runnable;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        void run() {
            if (!cancelled && !executed) {
                executed = true;
                runnable.run();
            }
        }
    }
}
