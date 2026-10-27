package dev.oreo.modulith.core.layered.infra;

import dev.oreo.modulith.core.MinecraftModule;
import dev.oreo.modulith.core.ModuleContext;
import dev.oreo.modulith.core.PluginModule;

/** Nested module with its own id: the enclosing package-info does not describe it. */
@PluginModule(value = "nested", dependencies = "economy")
public final class MismatchedModule implements MinecraftModule {
    @Override
    public void enable(ModuleContext context) {
    }
}
