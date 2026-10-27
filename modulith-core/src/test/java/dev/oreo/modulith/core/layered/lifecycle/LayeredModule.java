package dev.oreo.modulith.core.layered.lifecycle;

import dev.oreo.modulith.core.MinecraftModule;
import dev.oreo.modulith.core.ModuleContext;
import dev.oreo.modulith.core.PluginModule;

@PluginModule(value = "layered", optional = true)
public final class LayeredModule implements MinecraftModule {
    @Override
    public void enable(ModuleContext context) {
    }
}
