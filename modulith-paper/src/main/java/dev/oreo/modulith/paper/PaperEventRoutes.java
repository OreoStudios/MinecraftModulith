package dev.oreo.modulith.paper;

import dev.oreo.modulith.core.EventBus;
import dev.oreo.modulith.core.EventDelivery;
import dev.oreo.modulith.core.ModuleContext;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Event handlers with explicit Paper/Folia thread affinity.
 *
 * <p>Persistent event deliveries only complete when the scheduled handler
 * actually finishes. Use EventBus.publishAsync() with these handlers: blocking
 * publish() on the same region/entity scheduler may deadlock.</p>
 */
public final class PaperEventRoutes {
    private final ModuleContext context;
    private final ModuleScheduler scheduler;
    private final Set<Pending> pending = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    PaperEventRoutes(ModuleContext context, ModuleScheduler scheduler) {
        this.context = context;
        this.scheduler = scheduler;
        // A single cleanup action owns all in-flight deliveries; there is no
        // per-event lifecycle callback accumulation on long-running servers.
        context.lifecycle().onClose(() -> {
            closed.set(true);
            for (Pending delivery : pending) {
                delivery.cancel(new CancellationException("Module stopped: " + context.moduleId()));
            }
        });
    }

    /** Execute only global-coordination work, never world/region access. */
    public <E> EventBus.Subscription global(
            Class<E> eventType, String listenerId, Consumer<? super E> listener) {
        return register(eventType, listenerId, listener,
                (event, work) -> scheduler.globalUnmanaged(work));
    }

    /** Execute with ownership of the target location's region. */
    public <E> EventBus.Subscription region(
            Class<E> eventType, String listenerId,
            Function<? super E, Location> target, Consumer<? super E> listener) {
        Objects.requireNonNull(target, "target");
        return register(eventType, listenerId, listener,
                (event, work) -> scheduler.regionUnmanaged(
                        Objects.requireNonNull(target.apply(event), "region target"), work));
    }

    /** Execute using the entity scheduler, following entity migration on Folia. */
    public <E> EventBus.Subscription entity(
            Class<E> eventType, String listenerId,
            Function<? super E, Entity> target, Consumer<? super E> listener) {
        Objects.requireNonNull(target, "target");
        return register(eventType, listenerId, listener,
                (event, work) -> scheduler.entityUnmanaged(
                        Objects.requireNonNull(target.apply(event), "entity target"), work));
    }

    /** Execute off-thread; never access Bukkit entity or region-owned state. */
    public <E> EventBus.Subscription async(
            Class<E> eventType, String listenerId, Consumer<? super E> listener) {
        return register(eventType, listenerId, listener,
                (event, work) -> scheduler.asyncUnmanaged(work));
    }

    private <E> EventBus.Subscription register(Class<E> eventType, String listenerId,
            Consumer<? super E> listener, Route<E> route) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(listenerId, "listenerId");
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(route, "route");
        // Scope even default-module route listener IDs to avoid collisions.
        String publicationListenerId = context.moduleId() + "::" + listenerId;
        EventBus.Subscription subscription = context.events().subscribeStage(
                eventType, publicationListenerId, EventDelivery.SYNC,
                event -> schedule(event, listener, route));
        context.lifecycle().onClose(subscription::close);
        return subscription;
    }

    private <E> CompletionStage<Void> schedule(
            E event, Consumer<? super E> listener, Route<E> route) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        if (closed.get()) {
            result.completeExceptionally(new CancellationException("Module is stopped"));
            return result;
        }
        Pending delivery = new Pending(result);
        pending.add(delivery);
        result.whenComplete((value, error) -> pending.remove(delivery));
        try {
            ScheduledTaskHandle handle = route.schedule(event, () -> {
                if (result.isDone()) return;
                try {
                    listener.accept(event);
                    result.complete(null);
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
            delivery.handle.set(handle);
            if (closed.get()) delivery.cancel(new CancellationException("Module is stopped"));
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    @FunctionalInterface
    private interface Route<E> {
        ScheduledTaskHandle schedule(E event, Runnable callback);
    }

    private static final class Pending {
        private final CompletableFuture<Void> future;
        private final AtomicReference<ScheduledTaskHandle> handle = new AtomicReference<>();

        private Pending(CompletableFuture<Void> future) {
            this.future = future;
        }

        private void cancel(Throwable reason) {
            future.completeExceptionally(reason);
            ScheduledTaskHandle task = handle.get();
            if (task != null) task.cancel();
        }
    }
}
