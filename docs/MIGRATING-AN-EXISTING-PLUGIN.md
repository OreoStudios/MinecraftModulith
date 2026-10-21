# Gradually migrating an existing plugin

You can introduce MinecraftModulith **one feature at a time**. The goal is to move a command's dependency behind a named module API while leaving the rest of the plugin operational.

The repository includes a buildable version of this example in [`example-plugin`](../example-plugin/). After compiling it, run `/modbalance` to try the migrated command.

## Before: command reaches directly into a manager

A conventional Bukkit plugin often wires a concrete manager into a command:

~~~java
public final class LegacyBalanceCommand implements CommandExecutor {
    private final EconomyManager manager;

    public LegacyBalanceCommand(EconomyManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        sender.sendMessage("Balance: " + manager.balance(player.getUniqueId()));
        return true;
    }
}
~~~

A legacy `JavaPlugin.onEnable()` might register it using `getCommand("balance").setExecutor(new LegacyBalanceCommand(manager))` after declaring the command in `plugin.yml`. There is nothing inherently wrong with this approach for a small plugin, but the command depends directly on the manager's implementation.

## Step 1: add MinecraftModulith alongside the existing code

Add `modulith-paper` and the `modulith-processor` annotation processor to your Gradle build (see [installation](../README.md#installation)). Bootstrap once in the existing `JavaPlugin`:

~~~java
private PaperModulith modulith;

@Override
public void onEnable() {
    // Keep existing listeners, managers and unrelated commands in place.
    modulith = PaperModulith.builder(this)
        .basePackage("com.example.myplugin.modules")
        .start();
}

@Override
public void onDisable() {
    if (modulith != null) modulith.close();
}
~~~

This is a **sketch for an existing plugin**, not a replacement for your current bootstrap. Keep any existing shutdown logic and avoid accidentally registering the same command twice. The [actual example plugin bootstrap](../example-plugin/src/main/java/dev/oreo/modulith/example/ExamplePlugin.java) also configures an event publication registry.

## Step 2: expose one small named API

Define a public contract in the economy module instead of sharing a concrete manager:

~~~java
@ModuleApi("payments")
public interface EconomyService {
    long balance(UUID playerId);
    void deposit(UUID playerId, long amount);
}
~~~

This is the repository's real [`EconomyService`](../example-plugin/src/main/java/dev/oreo/modulith/example/economy/EconomyService.java). The existing [`EconomyModule`](../example-plugin/src/main/java/dev/oreo/modulith/example/economy/EconomyModule.java) implements it and publishes that contract when enabled:

~~~java
@PluginModule(value = "economy", configuration = true)
public final class EconomyModule implements MinecraftModule, EconomyService {
    @Override
    public void enable(ModuleContext context) {
        context.services().publish(EconomyService.class, this);
    }
    // Implement EconomyService here or delegate to your existing manager.
}
~~~

In a real migration, **do not silently create a second copy of your live economy state**. If unrelated legacy commands still use the old manager, have the module delegate to the **same manager instance** until those consumers have been migrated, or plan an explicit ownership handoff.

## Step 3: move just the balance command behind the contract

Declare the feature's dependency on the named API rather than the entire economy module:

~~~java
@PluginModule(value = "balance", dependencies = "economy::payments")
public final class BalanceModule implements MinecraftModule {
    @Override
    public void enable(ModuleContext context) {
        EconomyService economy = context.services().require(EconomyService.class);
        context.platform(PaperPlatform.class).registerCommand(
            context, "modbalance", "Display your demo balance", new BalanceCommand(economy)
        );
    }
}
~~~

The full [`BalanceModule`](../example-plugin/src/main/java/dev/oreo/modulith/example/migration/balance/BalanceModule.java) and [`BalanceCommand`](../example-plugin/src/main/java/dev/oreo/modulith/example/migration/balance/BalanceCommand.java) compile as part of `:example-plugin:compileJava`. `BalanceCommand` has a constructor parameter of type `EconomyService`; it never imports `EconomyModule`. Existing unrelated plugin commands and listeners can stay as they are.

The example uses `/modbalance` instead of `/balance` to avoid conflict with an existing server command. In your own plugin, migrate the existing command registration instead of registering a duplicate.

## Step 4: get useful compile-time boundary checks

The dependency declaration `economy::payments` allows access to the annotated `EconomyService` API, **not** to arbitrary economy implementation classes.

For example, add an import and direct call to `dev.oreo.modulith.example.economy.EconomyModule` from `BalanceCommand`. On a **clean** build with `modulith-processor` configured, the processor should reject this cross-module implementation dependency; the interface call is allowed.

~~~bash
./gradlew clean :example-plugin:compileJava
~~~

Compiler checks apply to source that the annotation processor actually sees. They are not runtime security isolation, and incremental builds may lack enough information for the same complete diagnostics as a clean build. Keep the processor configured and use a clean build in CI.

## Step 5: repeat when ready

Migrate the next command or listener, one consumer at a time. The original manager need not disappear immediately. The useful first win is that one consumer uses a stable public contract while the rest of the plugin can continue to work.

**Verify on your own server:** The example compiles the migrated module; it does not automatically prove all business logic, persistence, command registration, and threading behavior in your existing plugin will work unchanged. Test the moved feature before migrating more.
