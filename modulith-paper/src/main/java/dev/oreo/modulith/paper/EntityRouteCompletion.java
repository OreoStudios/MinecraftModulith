package dev.oreo.modulith.paper;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/** Completes an entity-routed delivery away from Folia's critical retirement callback. */
final class EntityRouteCompletion {
    private EntityRouteCompletion() {}

    static Runnable retiredCallback(CompletableFuture<Void> completion) {
        Objects.requireNonNull(completion, "completion");
        return () -> CompletableFuture.runAsync(() -> completion.completeExceptionally(
                new CancellationException("Entity retired before event handler ran")));
    }
}