package dev.oreo.modulith.paper;

import dev.oreo.modulith.core.ModuleContext;
import dev.oreo.modulith.core.ModulithException;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Context-aware Paper/Folia scheduler. Global scheduling alone is NOT sufficient for
 * entity or region world access on Folia.
 */
public final class ModuleScheduler {
    private final JavaPlugin plugin;
    private final SchedulerAdapter global;

    ModuleScheduler(JavaPlugin plugin, SchedulerAdapter global) {
        this.plugin = plugin;
        this.global = global;
    }

    /**
     * Global coordination only. On Folia this callback does not own any entity
     * or block region, even though it runs synchronously in its own context.
     */
    public ScheduledTaskHandle global(ModuleContext scope, Runnable work) {
        return own(scope, global.run(work));
    }

    /**
     * Executes with ownership of the region containing the provided location.
     * Capture the location on the correct thread; do not use this for moving
     * entities, which can migrate to another region before execution.
     */
    public ScheduledTaskHandle region(ModuleContext scope, Location location, Runnable work) {
        Objects.requireNonNull(location);
        Objects.requireNonNull(work);
        if (!global.folia()) return own(scope, Bukkit.getScheduler().runTask(plugin, work)::cancel);
        try {
            Object scheduler = Bukkit.class.getMethod("getRegionScheduler").invoke(null);
            Method run = scheduler.getClass().getMethod("run", Plugin.class, Location.class, Consumer.class);
            Object task = run.invoke(scheduler, plugin, location, (Consumer<Object>) ignored -> work.run());
            return own(scope, reflective(task));
        } catch (ReflectiveOperationException ex) {
            throw new ModulithException("Could not schedule Folia region task", ex);
        }
    }

    /**
     * Executes on the entity's owning scheduler, following it across regions.
     * If the entity has retired, Folia may decline scheduling the task.
     */
    public ScheduledTaskHandle entity(ModuleContext scope, Entity entity, Runnable work) {
        Objects.requireNonNull(entity);
        Objects.requireNonNull(work);
        if (!global.folia()) return own(scope, Bukkit.getScheduler().runTask(plugin, work)::cancel);
        try {
            Object scheduler = entity.getClass().getMethod("getScheduler").invoke(entity);
            Method run = scheduler.getClass().getMethod("run", Plugin.class, Consumer.class, Runnable.class);
            Object task = run.invoke(scheduler, plugin, (Consumer<Object>) ignored -> work.run(),
                    (Runnable) () -> {});
            if (task == null) return () -> {}; // Entity already retired.
            return own(scope, reflective(task));
        } catch (ReflectiveOperationException ex) {
            throw new ModulithException("Could not schedule Folia entity task", ex);
        }
    }

    /**
     * Runs off-thread. Do not read or mutate region/entity-owned Bukkit state.
     * Schedule any world changes back through {@link #region} or {@link #entity}.
     */
    public ScheduledTaskHandle async(ModuleContext scope, Runnable work) {
        Objects.requireNonNull(work);
        if (!global.folia()) {
            BukkitTask task = Bukkit.getScheduler().runTaskAsynchronously(plugin, work);
            return own(scope, task::cancel);
        }
        try {
            Object scheduler = Bukkit.class.getMethod("getAsyncScheduler").invoke(null);
            Method run = scheduler.getClass().getMethod("runNow", Plugin.class, Consumer.class);
            Object task = run.invoke(scheduler, plugin, (Consumer<Object>) ignored -> work.run());
            return own(scope, reflective(task));
        } catch (ReflectiveOperationException ex) {
            throw new ModulithException("Could not schedule Folia async task", ex);
        }
    }

    private static ScheduledTaskHandle reflective(Object task) throws NoSuchMethodException {
        Method cancel = task.getClass().getMethod("cancel");
        return () -> {
            try { cancel.invoke(task); }
            catch (ReflectiveOperationException ex) {
                throw new ModulithException("Cannot cancel scheduled task", ex);
            }
        };
    }

    private static ScheduledTaskHandle own(ModuleContext context, ScheduledTaskHandle handle) {
        context.lifecycle().onClose(handle::cancel);
        return handle;
    }
}
