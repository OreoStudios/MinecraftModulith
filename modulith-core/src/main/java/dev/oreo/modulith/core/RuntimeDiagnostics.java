package dev.oreo.modulith.core;

import java.util.List;
import java.util.Map;

/** Immutable runtime diagnostic snapshot. */
public record RuntimeDiagnostics(
        Map<String, ModuleState> states,
        List<String> startupOrder,
        int publishedServices,
        long incompleteEventPublications,
        ModulithMetrics.Snapshot metrics,
        Map<String, String> failures
) {
    public RuntimeDiagnostics {
        states = Map.copyOf(states);
        startupOrder = List.copyOf(startupOrder);
        failures = Map.copyOf(failures);
    }

    public RuntimeDiagnostics(
            Map<String, ModuleState> states,
            List<String> startupOrder,
            int publishedServices,
            long incompleteEventPublications,
            ModulithMetrics.Snapshot metrics
    ) {
        this(states, startupOrder, publishedServices, incompleteEventPublications, metrics, Map.of());
    }
}
