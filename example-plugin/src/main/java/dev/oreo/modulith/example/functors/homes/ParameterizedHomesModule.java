package dev.oreo.modulith.example.functors.homes;

import dev.oreo.modulith.core.MinecraftModule;
import dev.oreo.modulith.core.ModuleContext;
import dev.oreo.modulith.core.PluginModule;
import dev.oreo.modulith.example.functors.economy.api.EconomyBalanceService;

/** Independently parameterized module consuming a named API from economy. */
@PluginModule(value = "demo-homes", dependencies = "demo-economy::balance")
public final class ParameterizedHomesModule implements MinecraftModule {
    private final HomesParameters parameters;
    private final EconomyBalanceService economy;

    public ParameterizedHomesModule(HomesParameters parameters, EconomyBalanceService economy) {
        this.parameters = parameters;
        this.economy = economy;
    }

    @Override
    public void enable(ModuleContext context) {
        context.logger().info("Demo homes: max=" + parameters.maxHomes()
                + ", cooldown=" + parameters.teleportCooldownSeconds()
                + "s, economy currency=" + economy.currency());
    }
}
