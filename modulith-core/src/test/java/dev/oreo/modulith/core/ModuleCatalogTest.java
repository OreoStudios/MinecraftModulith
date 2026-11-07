package dev.oreo.modulith.core;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ModuleCatalogTest {
    @PluginModule(value = "catalog-provider", uses = "optional-api::access")
    static final class Provider implements MinecraftModule {}

    @PluginModule(value = "catalog-consumer", dependencies = "catalog-provider")
    static final class Consumer implements MinecraftModule {}

    @Test
    void immutableCatalogCapturesModuleInstancesAndApiContracts() {
        ModuleDescriptor provider = ModuleRuntime.Builder.describe(Provider.class);
        ModuleDescriptor consumer = ModuleRuntime.Builder.describe(Consumer.class);
        ModuleDescriptor copy = new ModuleDescriptor("catalog-provider.vip",
                provider.dependencies(), provider.implementation(), false, false,
                provider.after(), provider.uses(), provider.id());
        ModuleCatalog catalog = ModuleCatalog.of(List.of(consumer, provider, copy));
        assertEquals(3, catalog.modules().size());
        assertEquals("catalog-provider", catalog.find("catalog-provider.vip")
                .orElseThrow().instanceIdentity().templateId());
        assertTrue(catalog.markdown().contains("catalog-provider.vip"));
        assertTrue(catalog.json().contains("\"templateId\":\"catalog-provider\""));
        assertTrue(catalog.json().contains("\"uses\":[\"optional-api::access\"]"));
        assertTrue(catalog.json().contains("\"schemaVersion\":1"));
        assertTrue(catalog.find("missing").isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> catalog.modules().clear());
    }

    @Test
    void duplicateRuntimeInstanceIdentitiesAreRejectedBeforeStartup() {
        ModuleDescriptor provider = ModuleRuntime.Builder.describe(Provider.class);
        assertThrows(ModuleDependencyException.class,
                () -> ModuleCatalog.of(List.of(provider, provider)));
    }
}
