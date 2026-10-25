package dev.oreo.modulith.example.economy.api;

import dev.oreo.modulith.core.ModuleApi;

import java.util.UUID;

@ModuleApi("payments")
public interface EconomyService {
    long balance(UUID playerId);

    void deposit(UUID playerId, long amount);
}
