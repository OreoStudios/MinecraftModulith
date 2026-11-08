package dev.oreo.modulith.example.functors.economy.api;

import dev.oreo.modulith.core.ModuleApi;

/** Named public API for another module to consume without accessing economy internals. */
@ModuleApi("balance")
public interface EconomyBalanceService {
    long startingBalance();
    String currency();
}
