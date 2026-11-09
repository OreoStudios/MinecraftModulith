package dev.oreo.modulith.core;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ModuleInstancesTest {
    @ModuleApi("balance")
    interface Balance {
        long amount();
    }

    record WalletParams(long balance) {}

    @PluginModule("wallet")
    static final class Wallet implements MinecraftModule, Balance {
        private final WalletParams params;

        Wallet(WalletParams params) { this.params = params; }
        @Override public long amount() { return params.balance(); }
        @Override public void enable(ModuleContext context) {
            context.services().publish(Balance.class, this);
        }
    }

    @PluginModule(value = "reader", uses = "wallet::balance")
    static final class Reader implements MinecraftModule {
        static long east, west;
        @Override public void enable(ModuleContext context) {
            ModuleServices services = context.services();
            east = services.require("wallet.east", Balance.class).amount();
            west = services.require(new ServiceKey<>("wallet.west", Balance.class)).amount();
            assertThrows(ModulithException.class, () -> services.require(Balance.class));
        }
    }

    @Test
    void twoInstancesHaveIndependentParametersAndPublishedServices() {
        try (ModuleRuntime runtime = ModuleRuntime.builder()
                .bind("wallet.east", Wallet.class, new WalletParams(100),
                        (params, ctx) -> new Wallet(params))
                .bind("wallet.west", Wallet.class, new WalletParams(200),
                        (params, ctx) -> new Wallet(params))
                .bind("reader.default", Reader.class, "ignored",
                        (params, ctx) -> new Reader())
                .dependsOn("reader.default", "wallet.east::balance", "wallet.west::balance")
                .start()) {
            assertEquals(100, Reader.east);
            assertEquals(200, Reader.west);
            assertEquals(2, runtime.diagnostics().publishedServices());
            assertEquals(ModuleState.RUNNING, runtime.states().get("wallet.east"));
            assertEquals(ModuleState.RUNNING, runtime.states().get("wallet.west"));
            assertEquals("wallet", runtime.modules().stream()
                    .filter(m -> m.id().equals("wallet.east")).findFirst().orElseThrow().templateId());
        }
    }

    @Test
    void duplicateIdsAreRejectedAndUnknownBindingsCannotBeOverridden() {
        assertThrows(ModulithException.class, () -> ModuleRuntime.builder()
                .bind("wallet.east", Wallet.class, new WalletParams(1), (p, ctx) -> new Wallet(p))
                .bind("wallet.east", Wallet.class, new WalletParams(2), (p, ctx) -> new Wallet(p)));
        assertThrows(ModulithException.class,
                () -> ModuleRuntime.builder().dependsOn("missing", "wallet.east"));
    }

    @Test
    void cannotStopInstanceWhileRequiredDependentIsRunning() {
        try (ModuleRuntime runtime = ModuleRuntime.builder()
                .bind("wallet.east", Wallet.class, new WalletParams(100), (p, ctx) -> new Wallet(p))
                .bind("wallet.west", Wallet.class, new WalletParams(200), (p, ctx) -> new Wallet(p))
                .bind("reader.default", Reader.class, "ignored", (p, ctx) -> new Reader())
                .dependsOn("reader.default", "wallet.east::balance", "wallet.west::balance")
                .start()) {
            ModuleDependencyException failure = assertThrows(ModuleDependencyException.class,
                    () -> runtime.stop("wallet.east"));
            assertTrue(failure.getMessage().contains("reader.default"));
            assertEquals(ModuleState.RUNNING, runtime.states().get("wallet.east"));
            assertEquals(2, runtime.diagnostics().publishedServices());

            runtime.stop("reader.default");
            runtime.stop("wallet.east");
            runtime.stop("wallet.east"); // Idempotent for a known, stopped instance.
            assertThrows(ModulithException.class, () -> runtime.stop("wallet.unknown"));
            assertEquals(ModuleState.STOPPED, runtime.states().get("wallet.east"));
            assertEquals(ModuleState.RUNNING, runtime.states().get("wallet.west"));
            assertEquals(1, runtime.diagnostics().publishedServices());
        }
    }

    @Test
    void instancesWithSameTemplateHaveIndependentComponentContexts() {
        try (ModuleRuntime runtime = ModuleRuntime.builder()
                .bind("wallet.east", Wallet.class, new WalletParams(50), (p, ctx) -> {
                    assertEquals(new ModuleInstanceId("wallet", "wallet.east"), ctx.instanceIdentity());
                    return new Wallet(p);
                }).start()) {
            assertEquals(1, runtime.diagnostics().publishedServices());
        }
    }
}
