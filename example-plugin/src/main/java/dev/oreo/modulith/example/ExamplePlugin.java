package dev.oreo.modulith.example;

import dev.oreo.modulith.core.EventPublicationRegistry;
import dev.oreo.modulith.example.functors.economy.EconomyParameters;
import dev.oreo.modulith.example.functors.economy.ParameterizedEconomyModule;
import dev.oreo.modulith.example.functors.economy.api.EconomyBalanceService;
import dev.oreo.modulith.example.functors.homes.HomesParameters;
import dev.oreo.modulith.example.functors.homes.ParameterizedHomesModule;
import dev.oreo.modulith.example.functors.shop.ShopParameters;
import dev.oreo.modulith.example.functors.shop.ParameterizedShopModule;
import dev.oreo.modulith.jdbc.JdbcEventPublicationRegistry;
import dev.oreo.modulith.mongodb.MongoEventPublicationRegistry;
import dev.oreo.modulith.paper.PaperModulith;
import dev.oreo.modulith.sqlite.SqliteEventPublicationRegistry;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Example plugin demonstrating MinecraftModulith with three interchangeable persistence backends.
 *
 * <p>Switch between backends by uncommenting the relevant block below and adding the
 * corresponding JDBC driver or MongoDB driver to your plugin's dependencies.
 *
 * <p>SQLite — zero configuration, bundled driver, good for single-server setups.
 * <p>JDBC   — any JDBC-compatible database (PostgreSQL, MySQL, MariaDB, H2, …).
 *             Bring your own driver and DataSource (e.g. HikariCP).
 * <p>MongoDB — MongoDB Java driver 5.x is bundled with modulith-events-mongodb.
 */
public final class ExamplePlugin extends JavaPlugin {
    private PaperModulith modulith;
    private AutoCloseable publicationRegistry;

    @Override
    public void onEnable() {
        publicationRegistry = buildRegistry();

        modulith = PaperModulith.builder(this)
                .basePackage("dev.oreo.modulith.example")
                // Three independent functor applications: Economy, Homes and Shop
                // each receive their own strongly typed record parameters.
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
                .publicationRegistry((EventPublicationRegistry) publicationRegistry)
                .diagnosticsCommand(true)   // enables /modulith for minecraftmodulith.admin
                .start();

        getLogger().info("Module graph:\n" + modulith.runtime().graphMermaid());
        getLogger().info("Diagnostics: " + modulith.runtime().diagnostics());
    }

    private AutoCloseable buildRegistry() {
        // ── SQLite (default) ─────────────────────────────────────────────────────
        // Zero-config, single file. No extra driver needed.
        return new SqliteEventPublicationRegistry(
                getDataFolder().toPath().resolve("modulith-events.db")
        );

        // ── JDBC — PostgreSQL / MySQL / MariaDB / H2 / … ────────────────────────
        // Uncomment and replace the DataSource with your own (e.g. HikariCP).
        // Add your JDBC driver to the plugin's dependencies (not bundled here).
        //
        // HikariConfig config = new HikariConfig();
        // config.setJdbcUrl("jdbc:postgresql://localhost:5432/myplugin");
        // config.setUsername("user");
        // config.setPassword("secret");
        // HikariDataSource dataSource = new HikariDataSource(config);
        // return new JdbcEventPublicationRegistry(dataSource);

        // ── MongoDB ──────────────────────────────────────────────────────────────
        // Uncomment and point at your MongoDB instance.
        // The MongoDB Java driver is bundled with modulith-events-mongodb.
        //
        // MongoClient client = MongoClients.create("mongodb://localhost:27017");
        // MongoCollection<Document> collection = client
        //         .getDatabase("myplugin")
        //         .getCollection("modulith_event_publication");
        // return new MongoEventPublicationRegistry(collection);
    }

    @Override
    public void onDisable() {
        if (modulith != null) {
            modulith.close();
        }
        if (publicationRegistry != null) {
            try {
                publicationRegistry.close();
            } catch (Exception exception) {
                getLogger().warning("Failed to close publication registry: " + exception.getMessage());
            }
        }
    }
}
