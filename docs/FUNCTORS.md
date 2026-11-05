# Typed OCaml-inspired module functors

MinecraftModulith supports **per-module typed parameters**. Its
`ModuleFunctor<P, M>` is a Java analogue of applying an OCaml functor:
it accepts a parameter of type `P` (often a Java record with several fields)
and constructs a `MinecraftModule` of type `M`.

The application happens during module startup, after declared dependencies have
started. Existing `@PluginModule` IDs, boundary checks, named API dependencies,
lifecycle and cleanup behavior still apply.

## Quick example: separate parameters for Economy, Homes and Shop

Each module declares its own independent parameter contract:

```java
record EconomyParameters(String currency, long startingBalance) {}
record HomesParameters(int maxHomes, int teleportCooldownSeconds) {}
record ShopParameters(double taxRate, String currency) {}
```

The modules can have constructors that **require** those records; a no-argument
constructor is not needed when you use a functor:

```java
@PluginModule("demo-economy")
final class ParameterizedEconomyModule implements MinecraftModule {
    ParameterizedEconomyModule(EconomyParameters options) { /* ... */ }
}

@PluginModule(value = "demo-homes", dependencies = "demo-economy::balance")
final class ParameterizedHomesModule implements MinecraftModule {
    ParameterizedHomesModule(HomesParameters options, EconomyBalanceService economy) { /* ... */ }
}

@PluginModule(value = "demo-shop", dependencies = "demo-economy::balance")
final class ParameterizedShopModule implements MinecraftModule {
    ParameterizedShopModule(ShopParameters options, EconomyBalanceService economy) { /* ... */ }
}
```

Create all three modules independently in the Paper/Folia builder:

```java
PaperModulith modulith = PaperModulith.builder(this)
    .basePackage("dev.oreo.modulith.example")
    .functor(ParameterizedEconomyModule.class,
        new EconomyParameters("coins", 1000),
        (params, ctx) -> new ParameterizedEconomyModule(params))
    .functor(ParameterizedHomesModule.class,
        new HomesParameters(5, 30),
        (params, ctx) -> new ParameterizedHomesModule(
            params, ctx.services().require(EconomyBalanceService.class)))
    .functor(ParameterizedShopModule.class,
        new ShopParameters(0.05, "coins"),
        (params, ctx) -> new ParameterizedShopModule(
            params, ctx.services().require(EconomyBalanceService.class)))
    .start();
```

The economy starts first because Homes and Shop declare its named API as a
dependency. Each factory receives its **own** parameter record and the
`ModuleContext` used to resolve the published `EconomyBalanceService`.

This code is wired into
[`example-plugin/ExamplePlugin.java`](../example-plugin/src/main/java/dev/oreo/modulith/example/ExamplePlugin.java).
Follow the definitions in
[`example-plugin/.../functors/`](../example-plugin/src/main/java/dev/oreo/modulith/example/functors)
for complete compiling Java types.

**Note:** these example modules only log their settings and demonstrate service
wiring; they do not implement a production economy, homes or shop system.

## Core-only example (without Paper)

The same API works in plain Java tests and other runtime integrations:

```java
ModuleFunctor<ShopParameters, ParameterizedShopModule> makeShop =
    (params, context) -> new ParameterizedShopModule(
        params, context.services().require(EconomyBalanceService.class));

try (ModuleRuntime runtime = ModuleRuntime.builder()
    .functor(ParameterizedEconomyModule.class,
        new EconomyParameters("pearls", 500),
        (params, ctx) -> new ParameterizedEconomyModule(params))
    .functor(ParameterizedShopModule.class,
        new ShopParameters(0.02, "pearls"), makeShop)
    .start()) {
    // Both modules are running with independent typed parameters.
}
```

The core module's
[`ModuleFunctorTest.java`](../modulith-core/src/test/java/dev/oreo/modulith/core/ModuleFunctorTest.java)
also tests **three different parameterized modules in one runtime**, independent
parameters across runtimes, and failure/cleanup handling.

## What this is (and what it is not)

- One annotated module class has **one** functor binding in a runtime.
- Each **different** annotated module class can use its own parameter type.
- A record is useful for multiple strongly typed values, but interfaces and
  strategy objects are valid parameters too.
- You can reuse a factory with different parameters in **separate** runtimes.
- This does **not** yet implement OCaml generative functors that create multiple
  independent named instances of the *same* module class in one runtime.
  That requires distinct module identities and service namespaces.
- Java records and interfaces model contracts, but Java does not have OCaml's
  full static module signature type system.

A functor is called during module startup. Follow Folia threading restrictions
when scheduling any later region/entity work through `PaperPlatform.contexts()`.

## Generative-style named module instances

Different from `.functor()`, which constructs the default annotated module
once, `.bind(instanceId, template, parameters, factory)` can create multiple
independent instances of the **same template** in one runtime:

```java
ModuleRuntime runtime = ModuleRuntime.builder()
    .bind("wallet.east", Wallet.class, new WalletParams(100),
        (params, ctx) -> new Wallet(params))
    .bind("wallet.west", Wallet.class, new WalletParams(200),
        (params, ctx) -> new Wallet(params))
    .start();
```

The services registry keys publications by **instance ID and Java API
contract**, so both wallets can publish the same `Balance.class`. A consumer
chooses a specific provider using
`ctx.services().require("wallet.west", Balance.class)` or
`ctx.services().require(new ServiceKey<>("wallet.west", Balance.class))`.
A bare `require(Balance.class)` succeeds only if one accessible provider
publishes that contract; otherwise the registry reports ambiguity.

Consumers should still declare `uses = "wallet::balance"` on their annotated
template for compile-time access permission. At the composition root, configure
runtime startup ordering for a named instance:

```java
.bind("reader.default", Reader.class, new ReaderParams(),
    (params, ctx) -> new Reader(params))
.dependsOn("reader.default", "wallet.east::balance", "wallet.west::balance")
```

`uses` grants API access without ordering; `dependsOn` adds the explicit
startup dependencies. A consumer can also use an instance-qualified selector
in the builder. The instance's `ModuleContext.instanceIdentity()` reports both
the annotated template ID and its independent runtime ID.

The same `.bind(...)` and `.dependsOn(...)` methods are available on
`PaperModulith.Builder`. When a class is only named-bound, package discovery
does not start an additional default copy of the class. Each instance has
its own components, lifecycle resources, logging identity and configuration
descriptor, while the event bus shares the runtime and namespaces listeners.

This is an operational Java analogue of *generative* application. It is not
OCaml's static generative type system: Java class identity is unchanged,
and module instance identity is enforced by the runtime registry.
