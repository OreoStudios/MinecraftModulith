package dev.oreo.modulith.example;

import dev.oreo.modulith.core.ModuleContext;
import dev.oreo.modulith.paper.ModuleScheduler;
import dev.oreo.modulith.paper.PaperPlatform;
import dev.oreo.modulith.paper.ScheduledTaskHandle;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Compiled examples for docs/FOLIA-SCHEDULING.md.
 * Call these methods from a suitable owning context; they are examples, not
 * automatically scheduled by the example plugin.
 */
public final class FoliaSchedulingExamples {
    private FoliaSchedulingExamples() {
    }

    /**
     * Capture the target location on its owning thread before scheduling.
     * Only the region callback interacts with the block.
     */
    public static ScheduledTaskHandle replaceBlock(ModuleContext context, Location location) {
        Location target = Objects.requireNonNull(location, "location").clone();
        ModuleScheduler scheduler = context.platform(PaperPlatform.class).contexts();
        return scheduler.region(context, target, () -> target.getBlock().setType(Material.GOLD_BLOCK));
    }

    /** Follow the player across region moves; never schedule by a stale player location. */
    public static ScheduledTaskHandle sendPlayerMessage(ModuleContext context, Player player) {
        ModuleScheduler scheduler = context.platform(PaperPlatform.class).contexts();
        return scheduler.entity(context, player, () -> player.sendMessage("Hello from your entity scheduler!"));
    }

    /** Global coordination only: logging is safe; player/block access is not implied. */
    public static ScheduledTaskHandle logGlobal(ModuleContext context, Logger logger) {
        ModuleScheduler scheduler = context.platform(PaperPlatform.class).contexts();
        return scheduler.global(context, () -> logger.info("Global coordination tick"));
    }

    /**
     * lookup must be thread-safe and must not access unsafe Bukkit APIs.
     * Call from the player's owning context to capture the UUID first.
     * If the player retires before the callback, the message is not delivered.
     */
    public static ScheduledTaskHandle fetchAndMessage(
            ModuleContext context, Player player, Function<UUID, String> lookup
    ) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(lookup, "lookup");
        UUID playerId = player.getUniqueId();
        ModuleScheduler scheduler = context.platform(PaperPlatform.class).contexts();
        return scheduler.async(context, () -> {
            String message = lookup.apply(playerId);
            scheduler.entity(context, player, () -> player.sendMessage(message));
        });
    }
}
