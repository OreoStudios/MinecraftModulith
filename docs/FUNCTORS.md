# Typed OCaml-inspired module functors

An OCaml functor takes a module and produces another module. In Java,
MinecraftModulith offers an analogous **typed factory**: a
\`ModuleFunctor<P, M>\` receives parameter \`P\` and produces module \`M\`.

The parameter can be an interface implementation or a Java record with
**any number of strongly typed arguments**.

## Example: swap an Economy implementation

\`\`\`java
@ModuleApi("payments")
public interface EconomyService {
    long balance(UUID playerId);
}

record ShopOptions(int taxPercent, String currency) {}

@PluginModule(value = "shop", dependencies = "economy::payments")
public final class ShopModule implements MinecraftModule {
    private final EconomyService economy;
    private final ShopOptions options;

    public ShopModule(EconomyService economy, ShopOptions options) {
        this.economy = economy;
        this.options = options;
    }

    @Override
    public void enable(ModuleContext context) {
        context.logger().info("Shop currency: " + options.currency());
    }
}
\`\`\`

Apply your functor using an explicit factory, which runs **after economy
starts**, not when the builder is configured:

\`\`\`java
ModuleFunctor<ShopOptions, ShopModule> makeShop =
        (options, context) -> new ShopModule(
                context.services().require(EconomyService.class), options);

ModuleRuntime runtime = ModuleRuntime.builder()
        .module(EconomyModule.class)
        .functor(ShopModule.class, new ShopOptions(5, "coins"), makeShop)
        .start();
\`\`\`

The same API is available for Paper / Folia:
\`\`\`java
PaperModulith modulith = PaperModulith.builder(this)
        .basePackage("com.example.plugin")
        .functor(ShopModule.class, new ShopOptions(5, "coins"), makeShop)
        .start();
\`\`\`

Your module retains its annotated ID, graph dependencies, API boundaries,
lifecycle, scoped components, and cleanup behavior. The factory can resolve
running module APIs through \`ModuleContext.services()\`. It can also receive
a prebuilt API implementation directly as its parameter.

This capability allows constructor arguments without a zero-argument module
constructor. You can apply the same functor to different **runtimes** with
different parameters or implementations.

**Scope:** exactly one functor binding per annotated module class in one
runtime. This does **not** yet support OCaml generative functors that create
multiple independently named instances of a template in the same runtime.
Supporting that safely would require per-instance service namespaces and
generated identities. Java also does not provide OCaml's module signature
type system.

Threading: a factory runs during module startup. To access Minecraft
regions/entities from later async tasks, use the existing Paper/Folia
context-aware schedulers.
