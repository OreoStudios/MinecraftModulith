package dev.oreo.modulith.example.rewards.lifecycle;

import dev.oreo.modulith.core.EventDelivery;
import dev.oreo.modulith.core.MinecraftModule;
import dev.oreo.modulith.core.ModuleContext;
import dev.oreo.modulith.core.ModuleListener;
import dev.oreo.modulith.core.PluginModule;
import dev.oreo.modulith.example.homes.api.HomeCreatedEvent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@PluginModule(value = "rewards", dependencies = "homes::events")
public final class RewardsModule implements MinecraftModule {
    @Override
    public void enable(ModuleContext context) {
        context.listen(this);
    }

    @ModuleListener(id = "rewards.home-created", delivery = EventDelivery.ASYNC)
    public CompletionStage<Void> onHomeCreated(HomeCreatedEvent event) {
        return CompletableFuture.runAsync(() -> {
            // Demo async reaction. Real plugins could persist an achievement/reward here.
            event.playerId().toString();
        });
    }
}
