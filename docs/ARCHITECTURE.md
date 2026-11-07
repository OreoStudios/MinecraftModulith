# MinecraftModulith kernel architecture (0.6.x proposal)

> **Status:** implemented on the unmerged development PR. The current published
> version is still 0.5.3. This document describes the PR's architecture; it
> is not a promise of a released, fully backward-compatible API.

## Purpose

The framework has distinct layers:

1. **Module declarations** — \`@PluginModule\` and \`@ApplicationModule\`
   define source boundaries, required dependencies, optional runtime API uses,
   ordering, and configuration.
2. **Module catalog** — validated \`ModuleCatalog\` and \`ModuleDescriptor\`
   capture immutable module definition and instance identities before startup.
3. **Composition root** — \`ModuleRuntime.Builder\` or \`PaperModulith.Builder\`
   binds typed module functors. \`functor()\` configures the default annotated
   module; \`bind(instanceId,...)\` instantiates a named copy of the same
   template. \`dependsOn(instanceId,...)\` overrides its startup selectors.
4. **Module services** — \`ServiceKey<T>(providerInstanceId, contract)\`
   qualifies public APIs by provider identity. \`ModuleServices.require(Class)\`
   works only when one accessible provider exists, while
   \`require(instanceId, Class)\` explicitly selects a provider.
5. **Module runtime** — starts validated dependencies first, applies functors
   during startup, scopes resources to module instances, and shuts down in
   reverse topological order with cleanup/rollback.
6. **Event bus** — tracks delivery per publication and listener ID, with a
   completion stage that reflects actual handler completion. Retries are
   at-least-once and require idempotent listeners; distributed claims/leases
   are not yet implemented.
7. **Paper / Folia adapter** — \`PaperEventRoutes\` schedules event work on the
   appropriate global/region/entity/async scheduler, not on arbitrary threads.

## Module dependency selectors

| Selector | Startup ordering | Failure propagation | API access |
| --- | --- | --- | --- |
| \`dependencies = "economy::payments"\` | Yes | Yes | Yes |
| \`uses = "economy::payments"\` | No | No | Yes |
| \`after = "economy"\` | Yes if present | No | No |

A \`uses\` selector allows an API to be looked up later, but does **not**
guarantee the provider has started when a consumer starts. Check service
availability and use \`dependencies\` or an explicit instance \`dependsOn\`
binding when early access is required.

For named instances, source-level access to a module is validated using its
**annotated template ID**; runtime startup and provider selection use the
**instance ID**. Example:
\`\`\`java
@PluginModule(value = "wallet-consumer", uses = "wallet::balance")
final class WalletConsumer implements MinecraftModule {}

ModuleRuntime runtime = ModuleRuntime.builder()
    .bind("wallet.a", Wallet.class, new Params(100), Wallet::new)
    .bind("wallet.b", Wallet.class, new Params(200), Wallet::new)
    .bind("consumer.default", WalletConsumer.class, new Params(0),
        (params, ctx) -> new WalletConsumer())
    .dependsOn("consumer.default", "wallet.a::balance", "wallet.b::balance")
    .start();
\`\`\`

## Runtime reports

\`runtime.catalog()\` returns immutable module definitions in validated
startup order. \`runtime.architectureMarkdown()\` and
\`runtime.architectureJson()\` include runtime and template IDs plus named
\`dependencies\`, \`uses\`, and \`after\` selectors. The JSON format starts
with \`schemaVersion: 1\`.

The Gradle plugin's \`modulithDocs\` task generates metadata-based Markdown
and Mermaid diagrams for **compile-time module declarations**. Named runtime
instances are composition-root state and therefore appear in the runtime
reports, not the annotation-processor's static \`modules.idx\`.

## Event reliability and threading

- Publication records identify an actual event type and registered listener;
  replay filters on **both**, not just listener ID.
- Duplicate listener IDs within one registered event type are rejected.
- \`subscribeStage()\` records completion only once the returned
  \`CompletionStage\` completes, not once a scheduled task was queued.
- A serialization/registry error for one listener is surfaced as failure
  without suppressing attempts to deliver to the remaining listeners.
- The retry coordinator serializes ticks **within one coordinator**.
  Multiple JVMs, proxies and server nodes must use an external storage
  claim/lease protocol; exactly-once delivery is **not** guaranteed.
- \`EventDelivery.ASYNC\` runs on a generic executor by default and must
  **never** directly access Paper/Folia region/entity world state.
  Use \`PaperPlatform.eventRoutes(context)\` for \`global\`, \`region\`,
  \`entity\` and \`async\` handlers with explicit affinity.
- Use \`EventBus.publishAsync(...)\` for those routes. Blocking \`publish()\`
  from the target scheduler can deadlock waiting for its own queued work.
- A module's pending routed deliveries and listener registrations are
  cancelled on lifecycle close. Retired-entity scheduling is surfaced as a
  delivery failure rather than silently marking it complete.

## Validation

Automated CI compiles all modules and runs core, processor and persistence
tests using Java 21:

\`\`\`bash
./gradlew clean build publishToMavenLocal
\`\`\`

### Paper/Folia server smoke test (manual)

The \`example-plugin\` contains \`PaperSmokeModule\`, which is discovered by
\`PaperModulith.builder(plugin).basePackage(...)\` and registers the four
event routes. This is a **live-server test fixture**, not an automated CI
integration test.

1. Build \`./gradlew :example-plugin:jar\`, and copy the plugin jar plus
   supported dependencies into a disposable **Paper 1.21.x** test server
   (Java 21). Repeat on a separate compatible **Folia** test server.
2. Start the server and verify modules have started; use \`/modulith modules\`
   if the diagnostics permission is enabled.
3. Join with a test player. The console should contain four entries:
   \`SMOKE global\`, \`SMOKE async\`, \`SMOKE region\`, \`SMOKE entity\`,
   followed by \`SMOKE completed 4 delivery routes\`.
4. On Folia, verify the entity callback may safely access
   \`player.isOnline()\` and that neither global nor async callbacks touch
   region/entity Bukkit state.
5. Stop the server and check that listeners/tasks are cancelled and no
   uncaught scheduling exceptions appear. Repeat while a player disconnects
   rapidly; a retired entity may produce a **failed** delivery record.

The smoke module only logs its work. It does not exercise moving entities,
large server networks, cross-process event leases, or production persistence
under stress.

## Engineering next steps

- Confirm binary and source compatibility before the 0.6.x release; public
  record shapes such as \`ModuleDescriptor\` may require a migration plan.
- Add an automated Paper and Folia integration runner (including a real
  server bootstrap) as separate CI jobs, not just compiler coverage.
- Add database-specific claim/lease implementations if multiple server nodes
  will run persistent event recovery against the same registry.
- Add performance tests for high-cardinality service registries and
  high-volume event publication.
