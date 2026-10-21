package dev.oreo.modulith.paper;

import dev.oreo.modulith.core.ModuleContext;
import dev.oreo.modulith.core.ModulithException;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.Collection;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;

/** Paper resources automatically tied to a module lifecycle. */
public final class PaperPlatform {
    private final JavaPlugin plugin;
    private final SchedulerAdapter scheduler;
    private final ModuleScheduler contexts;

    PaperPlatform(JavaPlugin plugin) {
        this.plugin = plugin;
        this.scheduler = SchedulerAdapter.detect(plugin);
        this.contexts = new ModuleScheduler(plugin, scheduler);
    }

    public JavaPlugin plugin() {
        return plugin;
    }

    public SchedulerAdapter scheduler() {
        return scheduler;
    }

    /** Region/entity/global/async scheduling with module-owned cancellation. */
    public ModuleScheduler contexts() {
        return contexts;
    }

    public <T extends Listener> T registerListener(ModuleContext context, T listener) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(listener, "listener");
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        context.lifecycle().onClose(() -> HandlerList.unregisterAll(listener));
        return listener;
    }

    /**
     * Runs in the global scheduling context (Paper main scheduler fallback).
     *
     * <p>On Folia this is NOT an entity or region thread. Do not access players,
     * entities or region-owned blocks from the callback. For those operations use
     * {@link #contexts()} with {@link ModuleScheduler#entity} or
     * {@link ModuleScheduler#region} instead.
     */
    public ScheduledTaskHandle schedule(ModuleContext context, Runnable runnable) {
        ScheduledTaskHandle task = scheduler.run(runnable);
        context.lifecycle().onClose(task::cancel);
        return task;
    }

    /** Delayed global-context task; same Folia ownership restrictions as {@link #schedule}. */
    public ScheduledTaskHandle scheduleLater(ModuleContext context, Runnable runnable, long delayTicks) {
        ScheduledTaskHandle task = scheduler.runLater(runnable, delayTicks);
        context.lifecycle().onClose(task::cancel);
        return task;
    }

    /** Repeating global-context task; same Folia ownership restrictions as {@link #schedule}. */
    public ScheduledTaskHandle scheduleTimer(
            ModuleContext context,
            Runnable runnable,
            long delayTicks,
            long periodTicks
    ) {
        ScheduledTaskHandle task = scheduler.runTimer(runnable, delayTicks, periodTicks);
        context.lifecycle().onClose(task::cancel);
        return task;
    }

    /**
     * Standard Paper-only scheduler compatibility method. Prefer {@link #schedule(ModuleContext, Runnable)}
     * for global-only coordination, or {@link #contexts()} for entity and region work on Folia.
     */
    public BukkitTask runTask(ModuleContext context, Runnable runnable) {
        ensurePaperScheduler();
        BukkitTask task = Bukkit.getScheduler().runTask(plugin, runnable);
        context.lifecycle().onClose(task::cancel);
        return task;
    }

    public BukkitTask runTaskLater(ModuleContext context, Runnable runnable, long delayTicks) {
        ensurePaperScheduler();
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, runnable, delayTicks);
        context.lifecycle().onClose(task::cancel);
        return task;
    }

    public BukkitTask runTaskTimer(ModuleContext context, Runnable runnable, long delayTicks, long periodTicks) {
        ensurePaperScheduler();
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, runnable, delayTicks, periodTicks);
        context.lifecycle().onClose(task::cancel);
        return task;
    }

    /**
     * Registers a simple command using Paper's lifecycle-based Brigadier registrar.
     * The listener skips future re-registrations once its module scope has closed.
     * Paper controls the current command tree until the next lifecycle refresh.
     */
    public void registerBasicCommand(
            ModuleContext context, String label, String description,
            Collection<String> aliases, BasicCommand command) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(label);
        Objects.requireNonNull(command);
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            if (!context.lifecycle().isClosed()) {
                event.registrar().register(label, description, aliases, command);
            }
        });
    }

    public CommandRegistration registerCommand(
            ModuleContext context,
            String name,
            String description,
            CommandExecutor executor
    ) {
        return registerCommand(context, name, description, executor, null);
    }

    public CommandRegistration registerCommand(
            ModuleContext context,
            String name,
            String description,
            CommandExecutor executor,
            TabCompleter tabCompleter
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(executor, "executor");

        try {
            Constructor<PluginCommand> constructor = PluginCommand.class
                    .getDeclaredConstructor(String.class, Plugin.class);
            constructor.setAccessible(true);
            PluginCommand command = constructor.newInstance(name, plugin);
            command.setDescription(description == null ? "" : description);
            command.setExecutor(executor);
            if (tabCompleter != null) {
                command.setTabCompleter(tabCompleter);
            }

            Object commandMap = resolveCommandMap();
            Method register = findMethod(commandMap.getClass(), "register", 2);
            register.invoke(commandMap, plugin.getName().toLowerCase(), command);

            CommandRegistration registration = new CommandRegistration(
                    command,
                    () -> unregisterCommand(commandMap, command)
            );
            context.lifecycle().onClose(registration::close);
            return registration;
        } catch (ReflectiveOperationException exception) {
            throw new ModulithException("Could not dynamically register command '/" + name + "'", exception);
        }
    }

    private Object resolveCommandMap() throws ReflectiveOperationException {
        Method method = findMethod(plugin.getServer().getClass(), "getCommandMap", 0);
        return method.invoke(plugin.getServer());
    }

    @SuppressWarnings("unchecked")
    private void unregisterCommand(Object commandMap, PluginCommand command) {
        try {
            Method unregister = findMethod(command.getClass(), "unregister", 1);
            unregister.invoke(command, commandMap);

            Field knownCommands = findField(commandMap.getClass(), "knownCommands");
            knownCommands.setAccessible(true);
            Object value = knownCommands.get(commandMap);
            if (value instanceof Map<?, ?> map) {
                ((Map<String, Command>) map).entrySet().removeIf(entry -> entry.getValue() == command);
            }
        } catch (ReflectiveOperationException exception) {
            throw new ModulithException("Could not unregister command '/" + command.getName() + "'", exception);
        }
    }

    private static Method findMethod(Class<?> type, String name, int parameterCount) {
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                    method.setAccessible(true);
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        throw new ModulithException("Could not find method " + name + " on " + type.getName());
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private void ensurePaperScheduler() {
        if (scheduler.folia()) {
            throw new ModulithException(
                    "BukkitScheduler compatibility methods are unsafe on Folia; use contexts().entity/region for world work or schedule() for global-only work"
            );
        }
    }
}
