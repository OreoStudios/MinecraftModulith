package dev.oreo.modulith.paper;

import dev.oreo.modulith.core.EventPayloadSerializer;
import dev.oreo.modulith.core.EventPublicationRegistry;
import dev.oreo.modulith.core.MinecraftModule;
import dev.oreo.modulith.core.ModuleConfigurationProvider;
import dev.oreo.modulith.core.ModuleRuntime;
import dev.oreo.modulith.core.ModulithException;
import dev.oreo.modulith.core.PluginModule;
import dev.oreo.modulith.core.ModuleComponent;
import dev.oreo.modulith.core.ModuleBoundary;
import dev.oreo.modulith.core.ModuleContributor;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import org.bukkit.plugin.java.JavaPlugin;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.ServiceLoader;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/** Entry point for bootstrapping MinecraftModulith inside a Paper/Folia plugin. */
public final class PaperModulith implements AutoCloseable {
    private final ModuleRuntime runtime;

    private PaperModulith(ModuleRuntime runtime) {
        this.runtime = runtime;
    }

    public static Builder builder(JavaPlugin plugin) {
        return new Builder(plugin);
    }

    public ModuleRuntime runtime() {
        return runtime;
    }

    @Override
    public void close() {
        runtime.stop();
    }

    public static final class Builder {
        private final JavaPlugin plugin;
        private final Set<Class<? extends MinecraftModule>> explicitModules = new LinkedHashSet<>();
        private final Map<String, List<Class<?>>> explicitComponents = new LinkedHashMap<>();
        private String basePackage;
        private boolean discoverContributors;
        private boolean diagnosticsCommand;
        private EventPublicationRegistry publicationRegistry = EventPublicationRegistry.noop();
        private EventPayloadSerializer eventSerializer = EventPayloadSerializer.toStringSerializer();
        private Executor eventExecutor = ForkJoinPool.commonPool();
        private ModuleConfigurationProvider configurationProvider;

        private Builder(JavaPlugin plugin) {
            this.plugin = Objects.requireNonNull(plugin, "plugin");
        }

        /** Enables /modulith [modules|events|graph] for permission minecraftmodulith.admin. */
        public Builder diagnosticsCommand(boolean enabled) {
            this.diagnosticsCommand = enabled;
            return this;
        }

        /** Discover classpath-visible module contributors through Java ServiceLoader. */
        public Builder discoverContributors(boolean enabled) {
            this.discoverContributors = enabled;
            return this;
        }

        public Builder basePackage(String basePackage) {
            this.basePackage = Objects.requireNonNull(basePackage, "basePackage");
            return this;
        }

        public Builder component(String moduleId, Class<?> componentType) {
            explicitComponents.computeIfAbsent(moduleId, ignored -> new ArrayList<>()).add(componentType);
            return this;
        }

        public Builder module(Class<? extends MinecraftModule> moduleType) {
            explicitModules.add(Objects.requireNonNull(moduleType, "moduleType"));
            return this;
        }

        public Builder publicationRegistry(EventPublicationRegistry publicationRegistry) {
            this.publicationRegistry = Objects.requireNonNull(publicationRegistry, "publicationRegistry");
            return this;
        }

        public Builder eventSerializer(EventPayloadSerializer eventSerializer) {
            this.eventSerializer = Objects.requireNonNull(eventSerializer, "eventSerializer");
            return this;
        }

        public Builder eventExecutor(Executor eventExecutor) {
            this.eventExecutor = Objects.requireNonNull(eventExecutor, "eventExecutor");
            return this;
        }

        public Builder configurationProvider(ModuleConfigurationProvider configurationProvider) {
            this.configurationProvider = Objects.requireNonNull(configurationProvider, "configurationProvider");
            return this;
        }

        public PaperModulith start() {
            Set<Class<? extends MinecraftModule>> modules = new LinkedHashSet<>(explicitModules);
            if (basePackage != null && !basePackage.isBlank()) {
                modules.addAll(discover(basePackage));
            }
            if (discoverContributors) {
                ServiceLoader.load(ModuleContributor.class, plugin.getClass().getClassLoader()).forEach(provider -> {
                    modules.addAll(provider.modules());
                    provider.components().forEach((moduleId, types) ->
                            types.forEach(type -> component(moduleId, type)));
                });
            }
            if (modules.isEmpty()) {
                throw new ModulithException("No MinecraftModulith modules were discovered");
            }

            PaperPlatform paperPlatform = new PaperPlatform(plugin);
            ModuleConfigurationProvider configs = configurationProvider != null
                    ? configurationProvider
                    : new YamlModuleConfigurationProvider(plugin);

            ModuleRuntime.Builder runtimeBuilder = ModuleRuntime.builder()
                    .modules(modules)
                    .platformService(JavaPlugin.class, plugin)
                    .platformService(PaperPlatform.class, paperPlatform)
                    .platformService(SchedulerAdapter.class, paperPlatform.scheduler())
                    .publicationRegistry(publicationRegistry)
                    .eventSerializer(eventSerializer)
                    .eventExecutor(eventExecutor)
                    .configurationProvider(configs)
                    .logger(plugin.getLogger());
            explicitComponents.forEach((id, types) -> types.forEach(type -> runtimeBuilder.component(id, type)));
            if (basePackage != null && !basePackage.isBlank()) {
                discoverComponents(basePackage, modules).forEach((id, types) ->
                        types.forEach(type -> runtimeBuilder.component(id, type)));
            }
            ModuleRuntime runtime = runtimeBuilder.start();
            if (diagnosticsCommand) {
                plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                        event.registrar().register("modulith", "MinecraftModulith runtime diagnostics",
                                List.of(), new ModulithAdminCommand(runtime)));
            }
            return new PaperModulith(runtime);
        }

        private Map<String, List<Class<?>>> discoverComponents(
                String packageName, Set<Class<? extends MinecraftModule>> modules) {
            Map<String, List<Class<?>>> result = new LinkedHashMap<>();
            try (ScanResult scan = new ClassGraph()
                    .overrideClassLoaders(plugin.getClass().getClassLoader())
                    .acceptPackages(packageName).enableClassInfo().enableAnnotationInfo().scan()) {
                for (Class<?> type : scan.getClassesWithAnnotation(ModuleComponent.class).loadClasses()) {
                    Class<? extends MinecraftModule> owner = modules.stream()
                            .filter(module -> {
                                String root = ModuleBoundary.rootPackage(module);
                                return type.getPackageName().equals(root) ||
                                        type.getPackageName().startsWith(root + ".");
                            })
                            .max(java.util.Comparator.comparingInt(m -> ModuleBoundary.rootPackage(m).length()))
                            .orElseThrow(() -> new ModulithException(
                                    "No module owns component " + type.getName()));
                    result.computeIfAbsent(owner.getAnnotation(PluginModule.class).value(),
                            ignored -> new ArrayList<>()).add(type);
                }
            }
            return result;
        }

        private Set<Class<? extends MinecraftModule>> discover(String packageName) {
            Set<Class<? extends MinecraftModule>> result = new LinkedHashSet<>();
            try (ScanResult scan = new ClassGraph()
                    .overrideClassLoaders(plugin.getClass().getClassLoader())
                    .acceptPackages(packageName)
                    .enableClassInfo()
                    .enableAnnotationInfo()
                    .scan()) {
                for (Class<?> candidate : scan.getClassesWithAnnotation(PluginModule.class).loadClasses()) {
                    if (!MinecraftModule.class.isAssignableFrom(candidate)) {
                        throw new ModulithException(
                                "@PluginModule class must implement MinecraftModule: " + candidate.getName()
                        );
                    }
                    @SuppressWarnings("unchecked")
                    Class<? extends MinecraftModule> moduleType = (Class<? extends MinecraftModule>) candidate;
                    result.add(moduleType);
                }
            }
            return result;
        }
    }
}
