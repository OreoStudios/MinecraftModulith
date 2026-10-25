package dev.oreo.modulith.example.economy.lifecycle;

import dev.oreo.modulith.core.MinecraftModule;
import dev.oreo.modulith.core.ModuleContext;
import dev.oreo.modulith.core.PluginModule;
import dev.oreo.modulith.example.economy.api.EconomyService;
import dev.oreo.modulith.example.economy.infra.InMemoryEconomyRepository;

import java.util.UUID;

@PluginModule(value = "economy", configuration = true)
public final class EconomyModule implements MinecraftModule, EconomyService {
    private InMemoryEconomyRepository balances;

    @Override
    public void enable(ModuleContext context) {
        balances = new InMemoryEconomyRepository();
        context.services().publish(EconomyService.class, this);

        if (context.config().get("starting-balance") == null) {
            context.config().set("starting-balance", 100L);
            context.config().save();
        }
    }

    @Override
    public long balance(UUID playerId) {
        return balances.balance(playerId);
    }

    @Override
    public void deposit(UUID playerId, long amount) {
        balances.deposit(playerId, amount);
    }
}
