package dev.oreo.modulith.example.homes.lifecycle;

import dev.oreo.modulith.core.EventCompletionPolicy;
import dev.oreo.modulith.core.MinecraftModule;
import dev.oreo.modulith.core.ModuleContext;
import dev.oreo.modulith.core.PluginModule;
import dev.oreo.modulith.example.economy.api.EconomyService;
import dev.oreo.modulith.example.homes.api.HomeCreatedEvent;
import dev.oreo.modulith.paper.PaperPlatform;
import org.bukkit.entity.Player;

@PluginModule(value = "homes", configuration = true)
public final class HomesModule implements MinecraftModule {
    private EconomyService economy;
    private ModuleContext context;

    @Override
    public void enable(ModuleContext context) {
        this.context = context;
        this.economy = context.services().require(EconomyService.class);

        if (context.config().get("command-name") == null) {
            context.config().set("command-name", "modhome");
            context.config().save();
        }

        PaperPlatform paper = context.platform(PaperPlatform.class);
        String commandName = context.config().getString("command-name", "modhome");

        paper.registerCommand(
                context,
                commandName,
                "Creates a demo home using MinecraftModulith",
                (sender, command, label, args) -> {
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage("Players only.");
                        return true;
                    }

                    String homeName = args.length == 0 ? "base" : args[0];
                    long balance = economy.balance(player.getUniqueId());
                    player.sendMessage("Created home '" + homeName + "'. Demo balance: " + balance);

                    context.events().publish(
                            new HomeCreatedEvent(player.getUniqueId(), homeName),
                            EventCompletionPolicy.FIRE_AND_FORGET
                    );
                    return true;
                }
        );
    }
}
