package dev.oreo.modulith.example.smoke;

import dev.oreo.modulith.core.MinecraftModule;
import dev.oreo.modulith.core.ModuleContext;
import dev.oreo.modulith.core.PluginModule;
import dev.oreo.modulith.paper.PaperEventRoutes;
import dev.oreo.modulith.paper.PaperPlatform;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.concurrent.CompletionException;
import java.util.logging.Level;

/**
 * Manually runnable Paper/Folia integration probe.
 * Join the example server and check all four route logs.
 * This module intentionally does not change gameplay state.
 */
@PluginModule("paper-smoke")
public final class PaperSmokeModule implements MinecraftModule {
    public record SmokeSignal(Player player, Location location, String playerName) {}

    @Override
    public void enable(ModuleContext context) {
        PaperPlatform platform = context.platform(PaperPlatform.class);
        PaperEventRoutes routes = platform.eventRoutes(context);

        routes.global(SmokeSignal.class, "global", signal ->
                context.logger().info("SMOKE global: " + signal.playerName()));
        routes.async(SmokeSignal.class, "async", signal ->
                // No Bukkit world/entity calls may occur in the async route.
                context.logger().info("SMOKE async: " + signal.playerName()));
        routes.region(SmokeSignal.class, "region", SmokeSignal::location, signal ->
                context.logger().info("SMOKE region: " + signal.playerName()));
        routes.entity(SmokeSignal.class, "entity", SmokeSignal::player, signal ->
                context.logger().info("SMOKE entity: " + signal.playerName()
                        + " online=" + signal.player().isOnline()));

        platform.registerListener(context, new Listener() {
            @EventHandler
            public void onPlayerJoin(PlayerJoinEvent event) {
                Player player = event.getPlayer();
                // Capture entity/region coordinates on PlayerJoinEvent's owning thread.
                SmokeSignal signal = new SmokeSignal(
                        player, player.getLocation(), player.getName());
                // Never block the join/entity thread with EventBus.publish() here.
                context.events().publishAsync(signal).whenComplete((result, error) -> {
                    if (error != null) {
                        Throwable cause = error instanceof CompletionException completion
                                ? completion.getCause() : error;
                        context.logger().log(Level.WARNING,
                                "SMOKE Paper/Folia event routes failed", cause);
                    } else {
                        context.logger().info("SMOKE completed "
                                + result.dispatchedListeners() + " delivery routes");
                    }
                });
            }
        });
    }
}
