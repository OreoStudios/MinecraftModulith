package dev.oreo.modulith.example.migration.balance.lifecycle;

import dev.oreo.modulith.core.MinecraftModule;
import dev.oreo.modulith.core.ModuleContext;
import dev.oreo.modulith.core.PluginModule;
import dev.oreo.modulith.example.economy.api.EconomyService;
import dev.oreo.modulith.example.migration.balance.internal.BalanceCommand;
import dev.oreo.modulith.paper.PaperPlatform;

/**
 * Small, independently migrated feature; the remaining plugin does not need
 * to change. Try /modbalance on the example server.
 */
@PluginModule(value = "balance", dependencies = "economy::payments")
public final class BalanceModule implements MinecraftModule {
    @Override
    public void enable(ModuleContext context) {
        EconomyService economy = context.services().require(EconomyService.class);
        context.platform(PaperPlatform.class).registerCommand(
                context, "modbalance", "Display your demo balance", new BalanceCommand(economy)
        );
    }
}
