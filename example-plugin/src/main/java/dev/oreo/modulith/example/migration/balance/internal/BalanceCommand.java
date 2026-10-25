package dev.oreo.modulith.example.migration.balance.internal;

import dev.oreo.modulith.example.economy.api.EconomyService;
import java.util.Objects;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * A migrated command: depends only on the economy module's named public API,
 * not on EconomyModule or any internal manager.
 */
public final class BalanceCommand implements CommandExecutor {
    private final EconomyService economy;

    public BalanceCommand(EconomyService economy) {
        this.economy = Objects.requireNonNull(economy, "economy");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        sender.sendMessage("Balance: " + economy.balance(player.getUniqueId()));
        return true;
    }
}
