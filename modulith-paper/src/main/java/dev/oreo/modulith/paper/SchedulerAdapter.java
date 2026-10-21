package dev.oreo.modulith.paper;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Scheduler abstraction for global-context work, not arbitrary Bukkit world access.
 * Use {@link ModuleScheduler#region} and {@link ModuleScheduler#entity} for
 * location- and entity-owned work (including on servers exposing Folia schedulers).
 */
public interface SchedulerAdapter {
    ScheduledTaskHandle run(Runnable runnable);

    ScheduledTaskHandle runLater(Runnable runnable, long delayTicks);

    ScheduledTaskHandle runTimer(Runnable runnable, long delayTicks, long periodTicks);

    boolean folia();

    static SchedulerAdapter detect(JavaPlugin plugin) {
        if (FoliaSchedulerAdapter.isSupported()) {
            return new FoliaSchedulerAdapter(plugin);
        }
        return new PaperSchedulerAdapter(plugin);
    }
}
