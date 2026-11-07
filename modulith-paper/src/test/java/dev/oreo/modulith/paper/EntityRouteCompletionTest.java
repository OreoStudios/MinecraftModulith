package dev.oreo.modulith.paper;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;

class EntityRouteCompletionTest {
    @Test
    void retiredEntityFailsPendingDelivery() {
        CompletableFuture<Void> completion = new CompletableFuture<>();

        EntityRouteCompletion.retiredCallback(completion).run();

        assertThrows(CancellationException.class,
                () -> completion.get(2, TimeUnit.SECONDS));
    }
}