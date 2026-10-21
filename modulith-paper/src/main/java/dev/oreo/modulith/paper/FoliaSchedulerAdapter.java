package dev.oreo.modulith.paper;

import dev.oreo.modulith.core.ModulithException;
import io.papermc.paper.ServerBuildInfo;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Reflection-based Folia global scheduler adapter. Reflection keeps the Paper artifact loadable
 * on both Paper and Folia without a hard Folia-only linkage.
 */
final class FoliaSchedulerAdapter implements SchedulerAdapter {
    private final JavaPlugin plugin;
    private final Object scheduler;

    FoliaSchedulerAdapter(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        try {
            scheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
        } catch (ReflectiveOperationException exception) {
            throw new ModulithException("Folia global scheduler is not available", exception);
        }
    }

    /**
     * Paper exposes the Folia scheduler methods too, with a synchronous fallback.
     * Their existence does not mean regionized threading is enabled. Detect the
     * actual server brand so the BukkitScheduler compatibility API stays usable
     * on standard Paper and is rejected only on Folia.
     */
    static boolean isSupported() {
        return ServerBuildInfo.buildInfo().isBrandCompatible(Key.key("papermc", "folia"));
    }

    @Override
    public ScheduledTaskHandle run(Runnable runnable) {
        return invoke("run", runnable);
    }

    @Override
    public ScheduledTaskHandle runLater(Runnable runnable, long delayTicks) {
        return invoke("runDelayed", runnable, delayTicks);
    }

    @Override
    public ScheduledTaskHandle runTimer(Runnable runnable, long delayTicks, long periodTicks) {
        return invoke("runAtFixedRate", runnable, delayTicks, periodTicks);
    }

    @Override
    public boolean folia() {
        return true;
    }

    private ScheduledTaskHandle invoke(String name, Runnable runnable, long... ticks) {
        Objects.requireNonNull(runnable, "runnable");
        int parameterCount = 2 + ticks.length;
        Method method = findMethod(scheduler.getClass(), name, parameterCount);

        Consumer<Object> callback = ignored -> runnable.run();
        Object[] arguments = new Object[parameterCount];
        arguments[0] = plugin;
        arguments[1] = callback;
        for (int i = 0; i < ticks.length; i++) {
            arguments[i + 2] = ticks[i];
        }

        try {
            Object task = method.invoke(scheduler, arguments);
            Method cancel = task.getClass().getMethod("cancel");
            return () -> {
                try {
                    cancel.invoke(task);
                } catch (IllegalAccessException | InvocationTargetException exception) {
                    throw new ModulithException("Could not cancel Folia scheduled task", exception);
                }
            };
        } catch (IllegalAccessException | InvocationTargetException exception) {
            throw new ModulithException("Could not schedule Folia task using " + name, exception);
        } catch (NoSuchMethodException exception) {
            throw new ModulithException("Folia scheduled task does not expose cancel()", exception);
        }
    }

    private static Method findMethod(Class<?> type, String name, int parameterCount) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                return method;
            }
        }
        throw new ModulithException(
                "Could not find Folia scheduler method " + name + " with " + parameterCount + " parameters"
        );
    }
}
