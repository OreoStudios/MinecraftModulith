package dev.oreo.modulith.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * Resolves the package boundary that owns a module class.
 *
 * <p>A {@link PluginModule} class normally owns its own package and every sub-package. When a
 * package containing the class (or one of its parents) declares {@link ApplicationModule} with the
 * same id, that package is the module root instead. This lets a layered module keep its lifecycle
 * class in a sub-package such as {@code economy.lifecycle} while {@code economy.api},
 * {@code economy.infra} and the other layers stay inside the same boundary.</p>
 */
public final class ModuleBoundary {
    private ModuleBoundary() {
    }

    /** Returns the root package of the module implemented by {@code type}. */
    public static String rootPackage(Class<?> type) {
        return declaration(type).map(Declaration::packageName).orElse(type.getPackageName());
    }

    /**
     * Returns the {@link ApplicationModule} that declares the boundary for {@code type}, if any.
     * Only a declaration with the same id as the class's {@link PluginModule} is considered.
     */
    public static Optional<Declaration> declaration(Class<?> type) {
        PluginModule module = type.getAnnotation(PluginModule.class);
        if (module == null) {
            return Optional.empty();
        }
        String packageName = type.getPackageName();
        while (!packageName.isEmpty()) {
            ApplicationModule declared = packageAnnotation(type.getClassLoader(), packageName);
            if (declared != null) {
                return declared.id().trim().equals(module.value().trim())
                        ? Optional.of(new Declaration(packageName, declared))
                        : Optional.empty();
            }
            int dot = packageName.lastIndexOf('.');
            packageName = dot < 0 ? "" : packageName.substring(0, dot);
        }
        return Optional.empty();
    }

    /**
     * Combines a class-level declaration with the package-level boundary declaration.
     * Each attribute may be declared in either place; the annotation processor rejects
     * conflicting values at compile time.
     */
    static ModuleDescriptor describe(Class<? extends MinecraftModule> type, PluginModule annotation) {
        Optional<Declaration> boundary = declaration(type);
        if (boundary.isEmpty()) {
            return new ModuleDescriptor(
                    annotation.value(),
                    List.of(annotation.dependencies()),
                    type,
                    annotation.configuration(),
                    annotation.optional(),
                    List.of(annotation.after())
            );
        }
        ApplicationModule declared = boundary.get().annotation();
        return new ModuleDescriptor(
                annotation.value(),
                merge(annotation.dependencies(), declared.allowedDependencies()),
                type,
                annotation.configuration() || declared.configuration(),
                annotation.optional() || declared.optional(),
                merge(annotation.after(), declared.after())
        );
    }

    private static List<String> merge(String[] first, String[] second) {
        LinkedHashSet<String> values = new LinkedHashSet<>(List.of(first));
        values.addAll(List.of(second));
        return new ArrayList<>(values);
    }

    private static ApplicationModule packageAnnotation(ClassLoader loader, String packageName) {
        try {
            return Class.forName(packageName + ".package-info", false, loader)
                    .getAnnotation(ApplicationModule.class);
        } catch (ClassNotFoundException | LinkageError ignored) {
            return null;
        }
    }

    /** A package-level module declaration. */
    public record Declaration(String packageName, ApplicationModule annotation) {
    }
}
