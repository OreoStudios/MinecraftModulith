package dev.oreo.modulith.core;

import dev.oreo.modulith.core.layered.infra.MismatchedModule;
import dev.oreo.modulith.core.layered.lifecycle.LayeredModule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleBoundaryTest {
    @Test
    void lifecycleClassInSubPackageOwnsTheDeclaredRootPackage() {
        assertEquals("dev.oreo.modulith.core.layered", ModuleBoundary.rootPackage(LayeredModule.class));
    }

    @Test
    void packageAndClassDeclarationsAreMerged() {
        ModuleDescriptor descriptor = ModuleRuntime.Builder.describe(LayeredModule.class);

        assertEquals("layered", descriptor.id());
        assertEquals(List.of("economy::payments"), descriptor.dependencies());
        assertEquals(List.of("audit"), descriptor.after());
        assertTrue(descriptor.configurationEnabled());
        assertTrue(descriptor.optional());
    }

    @Test
    void declarationWithAnotherIdDoesNotApply() {
        assertTrue(ModuleBoundary.declaration(MismatchedModule.class).isEmpty());
        assertEquals("dev.oreo.modulith.core.layered.infra", ModuleBoundary.rootPackage(MismatchedModule.class));

        ModuleDescriptor descriptor = ModuleRuntime.Builder.describe(MismatchedModule.class);
        assertEquals(List.of("economy"), descriptor.dependencies());
        assertFalse(descriptor.configurationEnabled());
    }

    @Test
    void classWithoutPackageDeclarationOwnsItsOwnPackage() {
        assertEquals("dev.oreo.modulith.core", ModuleBoundary.rootPackage(Plain.class));
        assertEquals(List.of("other"), ModuleRuntime.Builder.describe(Plain.class).dependencies());
    }

    @PluginModule(value = "plain", dependencies = "other")
    static final class Plain implements MinecraftModule {
        @Override
        public void enable(ModuleContext context) {
        }
    }
}
