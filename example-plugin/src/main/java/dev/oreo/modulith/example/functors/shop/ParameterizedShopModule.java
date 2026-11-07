package dev.oreo.modulith.example.functors.shop;

import dev.oreo.modulith.core.MinecraftModule;
import dev.oreo.modulith.core.ModuleContext;
import dev.oreo.modulith.core.PluginModule;
import dev.oreo.modulith.example.functors.economy.api.EconomyBalanceService;

/** Independently parameterized shop consuming a named API from economy. */
@PluginModule(value = "demo-shop", dependencies = "demo-economy::balance")
public final class ParameterizedShopModule implements MinecraftModule {
    private final ShopParameters parameters;
    private final EconomyBalanceService economy;

    public ParameterizedShopModule(ShopParameters parameters, EconomyBalanceService economy) {
        this.parameters = parameters;
        this.economy = economy;
    }

    @Override
    public void enable(ModuleContext context) {
        context.logger().info("Demo shop: " + (parameters.taxRate() * 100)
                + "% tax, " + parameters.currency()
                + ", economy starting balance=" + economy.startingBalance());
    }
}
