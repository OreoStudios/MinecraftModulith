package dev.oreo.modulith.example.audit.infra;

import dev.oreo.modulith.core.ModuleComponent;
import dev.oreo.modulith.example.audit.api.AuditService;
import dev.oreo.modulith.example.economy.api.EconomyService;
import java.util.UUID;

/** Demonstrates named API exposure and automatic constructor injection. */
@ModuleComponent
public final class AuditComponent implements AuditService {
    private final EconomyService economy;

    public AuditComponent(EconomyService economy) {
        this.economy = economy;
    }

    @Override
    public long currentBalance(UUID player) {
        return economy.balance(player);
    }
}
