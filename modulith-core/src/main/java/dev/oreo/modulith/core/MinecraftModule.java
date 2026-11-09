package dev.oreo.modulith.core;

/** Lifecycle contract for an internal module. */
public interface MinecraftModule {
    default void enable(ModuleContext context) throws Exception {
    }

    default void disable() throws Exception {
    }

    /**
     * Invoked after an opt-in module's configuration has been reloaded.
     * Existing resources remain registered; use restart() for a fresh lifecycle.
     */
    default void reload(ModuleContext context) throws Exception {
    }
}
