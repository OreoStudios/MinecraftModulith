package dev.oreo.modulith.example.functors.economy;

import dev.oreo.modulith.core.MinecraftModule;
import dev.oreo.modulith.core.ModuleContext;
import dev.oreo.modulith.core.PluginModule;
import dev.oreo.modulith.example.functors.economy.api.EconomyBalanceService;

/** Demonstrates a functor whose parameters configure the published service. */
@PluginModule("demo-economy")
public final class ParameterizedEconomyModule implements MinecraftModule, EconomyBalanceService {
    private final EconomyParameters parameters;

    public ParameterizedEconomyModule(EconomyParameters parameters) {
        this.parameters = parameters;
    }

    @Override
    public void enable(ModuleContext context) {
        context.services().publish(EconomyBalanceService.class, this);
        context.logger().info("Demo economy: " + parameters.startingBalance()
                + " starting " + parameters.currency());
    }

    @Override
    public long startingBalance() {
        return parameters.startingBalance();
    }

    @Override
    public String currency() {
        return parameters.currency();
    }
}
