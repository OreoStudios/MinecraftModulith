package dev.oreo.modulith.example.economy.infra;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Demo adapter for economy storage. Replace this with a database-backed adapter in production. */
public final class InMemoryEconomyRepository {
    private final Map<UUID, Long> balances = new ConcurrentHashMap<>();

    public long balance(UUID playerId) {
        return balances.computeIfAbsent(playerId, ignored -> 100L);
    }

    public void deposit(UUID playerId, long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must be >= 0");
        }
        balances.merge(playerId, amount, Long::sum);
    }
}
