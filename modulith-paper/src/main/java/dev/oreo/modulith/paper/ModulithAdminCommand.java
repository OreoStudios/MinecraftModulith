package dev.oreo.modulith.paper;

import dev.oreo.modulith.core.ModuleRuntime;
import dev.oreo.modulith.core.RuntimeDiagnostics;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** Opt-in administrator inspection surface; permission: minecraftmodulith.admin. */
public final class ModulithAdminCommand implements BasicCommand {
    private final ModuleRuntime runtime;

    public ModulithAdminCommand(ModuleRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime);
    }

    @Override
    public String permission() {
        return "minecraftmodulith.admin";
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        return args.length <= 1 ? List.of("modules", "events", "graph") : List.of();
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        var sender = source.getSender();
        if (args.length == 0 || args[0].equalsIgnoreCase("modules")) {
            RuntimeDiagnostics diagnostics = runtime.diagnostics();
            sender.sendMessage("[MinecraftModulith] " + diagnostics.states().size() + " modules:");
            diagnostics.states().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        String reason = diagnostics.failures().get(entry.getKey());
                        sender.sendMessage(" - " + entry.getKey() + ": " + entry.getValue()
                                + (reason != null ? " (" + reason + ")" : ""));
                    });
            return;
        }
        if (args[0].equalsIgnoreCase("events")) {
            RuntimeDiagnostics diagnostics = runtime.diagnostics();
            var metrics = diagnostics.metrics();
            sender.sendMessage("[MinecraftModulith] Published=" + metrics.eventsPublished()
                    + ", listener completed=" + metrics.listenerCompletions()
                    + ", failures=" + metrics.listenerFailures()
                    + ", incomplete=" + diagnostics.incompleteEventPublications());
            return;
        }
        if (args[0].equalsIgnoreCase("graph")) {
            sender.sendMessage("[MinecraftModulith] Mermaid module dependency graph:");
            runtime.graphMermaid().lines().forEach(sender::sendMessage);
            return;
        }
        sender.sendMessage("Usage: /modulith [modules|events|graph]");
    }
}
