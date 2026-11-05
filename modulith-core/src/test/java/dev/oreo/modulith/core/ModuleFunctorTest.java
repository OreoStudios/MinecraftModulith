package dev.oreo.modulith.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ModuleFunctorTest {
    private static final List<String> EVENTS = new ArrayList<>();

    @ModuleApi("payments")
    interface EconomyApi {
        int balance();
    }

    @PluginModule("economy")
    static final class Economy implements MinecraftModule, EconomyApi {
        @Override
        public void enable(ModuleContext context) {
            EVENTS.add("economy:on");
            context.services().publish(EconomyApi.class, this);
        }

        @Override public int balance() { return 20; }
        @Override public void disable() { EVENTS.add("economy:off"); }
    }

    record ShopOptions(int multiplier, String currency) {}

    @PluginModule(value = "shop", dependencies = "economy::payments")
    static final class Shop implements MinecraftModule {
        private final EconomyApi api;
        private final ShopOptions options;

        Shop(EconomyApi api, ShopOptions options) {
            this.api = api;
            this.options = options;
        }

        @Override
        public void enable(ModuleContext context) {
            EVENTS.add("shop:" + (api.balance() * options.multiplier()) + ":" + options.currency());
        }

        @Override public void disable() { EVENTS.add("shop:off"); }
    }

    private static final ModuleFunctor<ShopOptions, Shop> MAKE_SHOP =
            (opts, context) -> new Shop(context.services().require(EconomyApi.class), opts);

    @Test
    void applicationWaitsForDependenciesAndUsesTypedParameters() {
        EVENTS.clear();
        try (ModuleRuntime runtime = ModuleRuntime.builder()
                .module(Economy.class)
                .functor(Shop.class, new ShopOptions(3, "coins"), MAKE_SHOP)
                .start()) {
            assertEquals(List.of("economy:on", "shop:60:coins"), EVENTS);
            assertEquals(ModuleState.RUNNING, runtime.states().get("shop"));
        }
        assertEquals(List.of("economy:on", "shop:60:coins", "shop:off", "economy:off"), EVENTS);
    }

    @Test
    void oneFunctorCanBeAppliedWithDifferentParametersInSeparateRuntimes() {
        EVENTS.clear();
        try (ModuleRuntime runtime = ModuleRuntime.builder()
                .module(Economy.class)
                .functor(Shop.class, new ShopOptions(2, "gold"), MAKE_SHOP)
                .start()) {
            assertTrue(EVENTS.contains("shop:40:gold"));
        }

        EVENTS.clear();
        try (ModuleRuntime runtime = ModuleRuntime.builder()
                .module(Economy.class)
                .functor(Shop.class, new ShopOptions(4, "silver"), MAKE_SHOP)
                .start()) {
            assertTrue(EVENTS.contains("shop:80:silver"));
        }
    }

    @Test
    void duplicateFunctorApplicationIsRejected() {
        assertThrows(ModulithException.class, () -> ModuleRuntime.builder()
                .functor(Shop.class, new ShopOptions(1, "a"), MAKE_SHOP)
                .functor(Shop.class, new ShopOptions(2, "b"), MAKE_SHOP));
    }

    @Test
    void unannotatedModulesAreRejectedEvenWithFunctor() {
        assertThrows(ModulithException.class, () -> ModuleRuntime.builder()
                .functor(Unannotated.class, "x", (p, ctx) -> new Unannotated())
                .build());
    }

    static final class Unannotated implements MinecraftModule {}

    @PluginModule(value = "optional-functor", optional = true)
    static final class OptionalFunctor implements MinecraftModule {}

    @Test
    void factoryFailureUsesExistingOptionalModuleRollback() {
        AtomicInteger cleanupCount = new AtomicInteger();
        try (ModuleRuntime runtime = ModuleRuntime.builder()
                .module(Economy.class)
                .functor(OptionalFunctor.class, "fail", (p, ctx) -> {
                    ctx.lifecycle().onClose(cleanupCount::incrementAndGet);
                    throw new IllegalStateException(p);
                }).start()) {
            assertEquals(ModuleState.FAILED, runtime.states().get("optional-functor"));
            assertEquals(ModuleState.RUNNING, runtime.states().get("economy"));
            assertEquals(1, cleanupCount.get());
        }
    }

    @Test
    void nullFactoryResultFailsOptionalModule() {
        try (ModuleRuntime runtime = ModuleRuntime.builder()
                .functor(OptionalFunctor.class, "null", (p, ctx) -> null)
                .start()) {
            assertEquals(ModuleState.FAILED, runtime.states().get("optional-functor"));
            assertInstanceOf(NullPointerException.class,
                    runtime.failures().get("optional-functor"));
        }
    }
}
