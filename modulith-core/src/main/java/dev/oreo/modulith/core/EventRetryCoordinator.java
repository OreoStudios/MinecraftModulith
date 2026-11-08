package dev.oreo.modulith.core;

import java.time.Instant;
import java.util.Objects;

/**
 * Single-worker retry coordinator with persistent per-publication attempt counts.
 * Call tick periodically on the correct platform execution context. Does NOT provide
 * cross-process claims/leases or exactly-once semantics.
 */
public final class EventRetryCoordinator {
    private final EventBus events;
    private final EventPublicationRegistry registry;
    private final EventRetryPolicy policy;

    public EventRetryCoordinator(EventBus events, EventPublicationRegistry registry, EventRetryPolicy policy) {
        this.events = Objects.requireNonNull(events);
        this.registry = Objects.requireNonNull(registry);
        this.policy = Objects.requireNonNull(policy);
    }

    /**
     * In-process serialized retry tick. A separate process still requires an
     * external claim/lease mechanism; this is not an exactly-once guarantee.
     */
    public synchronized EventRetryTickReport tick(Instant now, int batchSize) {
        Objects.requireNonNull(now);
        if (batchSize < 0) throw new IllegalArgumentException("batchSize must be >= 0");
        int retried = 0, dead = 0, deferred = 0, unavailable = 0;
        for (EventPublication publication : registry.failed(batchSize)) {
            int count = registry.retryCount(publication.id());
            if (count >= policy.maxRetries()) {
                registry.deadLetter(publication.id(), "Maximum retry count exceeded: " + count);
                dead++;
                continue;
            }
            Instant lastAttempt = publication.completedAt() == null
                    ? publication.publishedAt() : publication.completedAt();
            if (now.isBefore(lastAttempt.plus(policy.backoff(count)))) {
                deferred++;
                continue;
            }
            // Storage adapters increment conditionally only while status is FAILED.
            int attempt = registry.incrementRetryCount(publication.id());
            if (attempt <= 0) continue;
            EventRecoveryReport result = events.replayFailedPublication(publication.id());
            retried += result.recovered() + result.failed();
            unavailable += result.unavailableListeners();
        }
        return new EventRetryTickReport(retried, dead, deferred, unavailable);
    }
}
