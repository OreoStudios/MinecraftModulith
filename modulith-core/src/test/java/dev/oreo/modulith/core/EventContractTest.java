package dev.oreo.modulith.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class EventContractTest {
    record Alpha(String value) {}
    record Beta(String value) {}

    @Test
    void replayMatchesBothPublishedEventTypeAndListenerId() {
        Memory registry = new Memory();
        EventPayloadCodec codec = new EventPayloadCodec() {
            @Override public String serialize(Object event) {
                return event instanceof Alpha a ? a.value() : ((Beta) event).value();
            }
            @Override public Object deserialize(String eventType, String payload) {
                return eventType.equals(Alpha.class.getName())
                        ? new Alpha(payload) : new Beta(payload);
            }
        };
        EventBus bus = new EventBus(Runnable::run, registry, codec, new ModulithMetrics());
        AtomicInteger alpha = new AtomicInteger();
        AtomicInteger beta = new AtomicInteger();
        // Different event types may legitimately use the same listener ID.
        bus.subscribe(Alpha.class, "shared", EventDelivery.SYNC, value -> alpha.incrementAndGet());
        bus.subscribe(Beta.class, "shared", EventDelivery.SYNC, value -> beta.incrementAndGet());
        registry.begin(Beta.class.getName(), "shared", "hello");
        assertEquals(1, bus.replayIncomplete(1).recovered());
        assertEquals(0, alpha.get());
        assertEquals(1, beta.get());
        assertThrows(ModulithException.class,
                () -> bus.subscribe(Beta.class, "shared", EventDelivery.SYNC, value -> {}));
    }

    @Test
    void failureToPersistOneListenerStillAttemptsRemainingListeners() {
        Memory registry = new Memory() {
            private int attempts;
            @Override public EventPublication begin(String type, String id, String payload) {
                if (++attempts == 1) throw new IllegalStateException("db unavailable");
                return super.begin(type,id,payload);
            }
        };
        EventBus bus = new EventBus(Runnable::run, registry,
                EventPayloadSerializer.toStringSerializer(), new ModulithMetrics());
        AtomicInteger invocations = new AtomicInteger();
        bus.subscribe(Alpha.class, "first", EventDelivery.SYNC, a -> invocations.incrementAndGet());
        bus.subscribe(Alpha.class, "second", EventDelivery.SYNC, a -> invocations.incrementAndGet());
        assertThrows(EventDispatchException.class, () -> bus.publish(new Alpha("event")));
        assertEquals(1, invocations.get());
    }

    @Test
    void stagedDeliveryCompletesOnlyAfterTheActualHandler() {
        Memory registry = new Memory();
        EventBus bus = new EventBus(Runnable::run, registry,
                EventPayloadSerializer.toStringSerializer(), new ModulithMetrics());
        CompletableFuture<Void> pending = new CompletableFuture<>();
        bus.subscribeStage(Alpha.class, "staged", EventDelivery.SYNC, event -> pending);
        CompletableFuture<EventDispatchResult> result =
                bus.publishAsync(new Alpha("message")).toCompletableFuture();
        assertFalse(result.isDone());
        assertEquals(EventPublicationStatus.PENDING,
                registry.all.values().iterator().next().status());
        pending.complete(null);
        assertTrue(result.join().successful());
        assertEquals(EventPublicationStatus.COMPLETED,
                registry.all.values().iterator().next().status());
    }

    @Test
    void stagedDeliveryFailureIsRecordedForRetries() {
        Memory registry = new Memory();
        EventBus bus = new EventBus(Runnable::run, registry,
                EventPayloadSerializer.toStringSerializer(), new ModulithMetrics());
        CompletableFuture<Void> pending = new CompletableFuture<>();
        bus.subscribeStage(Alpha.class, "staged-failure", EventDelivery.SYNC, event -> pending);
        CompletableFuture<EventDispatchResult> result =
                bus.publishAsync(new Alpha("message")).toCompletableFuture();
        pending.completeExceptionally(new IllegalStateException("failed"));
        assertThrows(EventDispatchException.class, result::join);
        assertEquals(EventPublicationStatus.FAILED,
                registry.all.values().iterator().next().status());
    }

    static class Memory implements EventPublicationRegistry {
        final Map<UUID,EventPublication> all = new LinkedHashMap<>();
        @Override public EventPublication begin(String type, String listener, String payload) {
            var e = new EventPublication(UUID.randomUUID(),type,listener,payload,
                    EventPublicationStatus.PENDING,Instant.now(),null,null);
            all.put(e.id(),e);
            return e;
        }
        @Override public void complete(UUID id) {
            var e=all.get(id);
            all.put(id,new EventPublication(id,e.eventType(),e.listenerId(),e.payload(),
                    EventPublicationStatus.COMPLETED,e.publishedAt(),Instant.now(),null));
        }
        @Override public void fail(UUID id,String message) {
            var e=all.get(id);
            all.put(id,new EventPublication(id,e.eventType(),e.listenerId(),e.payload(),
                    EventPublicationStatus.FAILED,e.publishedAt(),Instant.now(),message));
        }
        @Override public List<EventPublication> incomplete() {
            return all.values().stream().filter(e->e.status()==EventPublicationStatus.PENDING).toList();
        }
    }
}
