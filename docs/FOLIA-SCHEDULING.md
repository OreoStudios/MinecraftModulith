# Paper and Folia: choosing the correct scheduler

MinecraftModulith owns and cancels scheduled tasks when their module closes. It does **not** make arbitrary Bukkit API calls thread-safe. Folia has separate ownership contexts for the global scheduler, individual regions, and entities; there is no universal "Bukkit main thread" for world access.

## Which method should I use?

| Operation | MinecraftModulith API | Folia execution context |
| --- | --- | --- |
| Global coordination without entity/block access | `paper.schedule(...)`, `scheduleLater(...)`, `scheduleTimer(...)` or `contexts.global(...)` | Global region scheduler |
| Change blocks at a known location | `contexts.region(context, location, action)` | Region owning that location |
| Interact with a player or moving entity | `contexts.entity(context, entity, action)` | Scheduler following that entity |
| Database/network/CPU work without unsafe Bukkit access | `contexts.async(context, action)` | Async scheduler |

On standard Paper, the global, region and entity helpers fall back to the synchronous Bukkit scheduler, and async work uses Bukkit's async scheduler. When Folia scheduler APIs are available, MinecraftModulith uses those schedulers instead. **Do not assume code that works on Paper's main thread is safe in a Folia global callback.**

The examples below are part of the compiled [`FoliaSchedulingExamples.java`](../example-plugin/src/main/java/dev/oreo/modulith/example/FoliaSchedulingExamples.java) source. These methods are not registered automatically: call them from a suitable module callback.

## 1. Modify a block in its region

Capture the location while executing on its owning context (for example, in a block event handler). Only access the block inside the region callback:

~~~java
Location target = location.clone(); // captured in the correct owning context
ModuleScheduler scheduler = context.platform(PaperPlatform.class).contexts();

scheduler.region(context, target, () ->
    target.getBlock().setType(Material.GOLD_BLOCK)
);
~~~

A region scheduler is **not** a substitute for an entity scheduler. An entity may move to a different region between scheduling and execution.

## 2. Work with a player on the entity scheduler

~~~java
ModuleScheduler scheduler = context.platform(PaperPlatform.class).contexts();

scheduler.entity(context, player, () ->
    player.sendMessage("Hello from your entity scheduler!")
);
~~~

Use the player's entity scheduler, not `region(context, player.getLocation(), ...)`. The entity scheduler follows a moving entity and may decline work if it has already retired. Do not assume the callback will always run.

## 3. Run a global coordination task

~~~java
ModuleScheduler scheduler = context.platform(PaperPlatform.class).contexts();

scheduler.global(context, () ->
    plugin.getLogger().info("Global coordination tick")
);
~~~

This is appropriate for logging or coordinating global work that does not require access to region/entity-owned Bukkit state. It is **not** permission to query or mutate a player's inventory, teleport an entity, or edit a block.

The convenience methods `paper.schedule(context, action)`, `scheduleLater` and `scheduleTimer` use this same **global** context when Folia scheduling is available. Do **not** treat them as Bukkit's old universal main-thread scheduler.

## 4. Do async work and return to entity ownership

Capture identifiers on the owning thread. Only perform thread-safe operations in the async callback. Schedule the player-facing result back through the entity scheduler:

~~~java
UUID playerId = player.getUniqueId(); // capture in the owning context
ModuleScheduler scheduler = context.platform(PaperPlatform.class).contexts();

scheduler.async(context, () -> {
    String message = threadSafeProfileLookup.apply(playerId); // no Bukkit world calls
    scheduler.entity(context, player, () -> player.sendMessage(message));
});
~~~

`threadSafeProfileLookup` is a `Function<UUID, String>` provided by your application, not a MinecraftModulith built-in. For a real database query, handle failures and timeouts appropriately. If the entity retires, the follow-up task may not be scheduled.

All tasks created with the context-aware helpers are registered with the module lifecycle, including the entity callback scheduled from async work. Closing the module cancels registered task handles; cancellation does **not** undo work already running. Never assume that cancellation makes a long-running async operation immediately stop.

For the underlying ownership rules, see [Paper's Folia support guide](https://docs.papermc.io/paper/dev/folia-support/). Follow the server APIs' threading contracts even when using MinecraftModulith's adapters.
