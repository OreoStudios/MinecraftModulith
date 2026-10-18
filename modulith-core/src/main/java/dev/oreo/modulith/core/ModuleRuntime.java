package dev.oreo.modulith.core;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Validates, starts, observes and stops a set of internal plugin modules. */
public final class ModuleRuntime implements AutoCloseable {
    private final List<ModuleDescriptor> startupOrder;
    private final Map<String, List<Class<?>>> componentTypes;
    private final Map<Class<?>, Object> platformServices;
    private final Logger logger;
    private final EventPublicationRegistry publicationRegistry;
    private final ModuleConfigurationProvider configurationProvider;
    private final ModulithMetrics metrics;
    private final EventBus eventBus;
    private final ConcurrentHashMap<Class<?>, ModuleServices.ServiceEntry> services = new ConcurrentHashMap<>();
    private final Map<String, RunningModule> running = new LinkedHashMap<>();
    private final Map<String, ModuleState> states = new LinkedHashMap<>();
    private final Map<String, Throwable> failures = new LinkedHashMap<>();
    private boolean started;

    private ModuleRuntime(
            List<ModuleDescriptor> startupOrder,
            Map<Class<?>, Object> platformServices,
            Logger logger,
            Executor eventExecutor,
            EventPublicationRegistry publicationRegistry,
            EventPayloadSerializer eventSerializer,
            ModuleConfigurationProvider configurationProvider,
            ModulithMetrics metrics,
            Map<String, List<Class<?>>> componentTypes
    ) {
        this.startupOrder = startupOrder;
        this.componentTypes = componentTypes;
        this.platformServices = Map.copyOf(platformServices);
        this.logger = logger;
        this.publicationRegistry = publicationRegistry;
        this.configurationProvider = configurationProvider;
        this.metrics = metrics;
        this.eventBus = new EventBus(eventExecutor, publicationRegistry, eventSerializer, metrics);
        startupOrder.forEach(descriptor -> states.put(descriptor.id(), ModuleState.DISCOVERED));
    }

    public static Builder builder() {
        return new Builder();
    }

    public synchronized ModuleRuntime start() {
        if (started) {
            return this;
        }

        logger.info(() -> "[MinecraftModulith] Starting " + startupOrder.size() + " modules: "
                + startupOrder.stream().map(ModuleDescriptor::id).toList());

        try {
            for (ModuleDescriptor descriptor : startupOrder) {
                String unavailable = unavailableDependency(descriptor);
                if (unavailable != null) {
                    skip(descriptor, unavailable);
                    continue;
                }
                try {
                    startOne(descriptor);
                } catch (ModulithException exception) {
                    if (!descriptor.optional()) {
                        throw exception;
                    }
                    failures.put(descriptor.id(), rootCause(exception));
                    logger.log(Level.SEVERE, "[MinecraftModulith] Optional module '" + descriptor.id()
                            + "' failed to start; continuing without it", exception);
                }
            }
            started = true;
            return this;
        } catch (RuntimeException exception) {
            stopStartedModules();
            throw exception;
        }
    }

    private String unavailableDependency(ModuleDescriptor descriptor) {
        for (String dependency : descriptor.dependencyModules()) {
            if (states.get(dependency) != ModuleState.RUNNING) {
                return dependency;
            }
        }
        return null;
    }

    private void skip(ModuleDescriptor descriptor, String dependency) {
        String reason = "dependency '" + dependency + "' is " + states.get(dependency);
        if (!descriptor.optional()) {
            states.put(descriptor.id(), ModuleState.FAILED);
            throw new ModulithException("Required module '" + descriptor.id() + "' cannot start: " + reason);
        }
        states.put(descriptor.id(), ModuleState.SKIPPED);
        failures.put(descriptor.id(), new ModulithException("Skipped: " + reason));
        logger.warning(() -> "[MinecraftModulith] Skipped optional module '" + descriptor.id() + "': " + reason);
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    private void startOne(ModuleDescriptor descriptor) {
        states.put(descriptor.id(), ModuleState.STARTING);
        LifecycleScope lifecycle = new LifecycleScope();
        long startedAt = System.nanoTime();
        try {
            MinecraftModule instance = instantiate(descriptor);
            ModuleServices moduleServices = new ModuleServices(
                    descriptor.id(),
                    descriptor.parsedDependencies(),
                    services
            );
            ModuleConfiguration configuration = descriptor.configurationEnabled()
                    ? Objects.requireNonNull(configurationProvider.load(descriptor), "configurationProvider returned null")
                    : ModuleConfiguration.empty();

            ModuleContext context = new ModuleContext(
                    descriptor.id(),
                    moduleServices,
                    eventBus,
                    lifecycle,
                    configuration,
                    platformServices,
                    Logger.getLogger(logger.getName() + "." + descriptor.id())
            );

            ModuleComponents components = new ModuleComponents(
                    context, componentTypes.getOrDefault(descriptor.id(), List.of()));
            context.setComponents(components);
            components.initialize();
            instance.enable(context);
            running.put(descriptor.id(), new RunningModule(descriptor, instance, lifecycle, configuration));
            states.put(descriptor.id(), ModuleState.RUNNING);
            metrics.moduleStarted(descriptor.id(), System.nanoTime() - startedAt);
            logger.info(() -> "[MinecraftModulith] Started module '" + descriptor.id() + "'");
        } catch (Exception | LinkageError exception) {
            // LinkageError covers optional plugin APIs missing from the server classpath.
            states.put(descriptor.id(), ModuleState.FAILED);
            try {
                lifecycle.close();
            } catch (RuntimeException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            // Dependents must not observe services from a module that never finished starting.
            services.values().removeIf(entry -> entry.owner().equals(descriptor.id()));
            throw new ModulithException("Failed to start module '" + descriptor.id() + "'", exception);
        }
    }

    private static MinecraftModule instantiate(ModuleDescriptor descriptor) {
        try {
            Constructor<? extends MinecraftModule> constructor = descriptor.implementation().getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (NoSuchMethodException exception) {
            throw new ModulithException(
                    "Module '" + descriptor.id() + "' must expose a no-argument constructor: "
                            + descriptor.implementation().getName(), exception
            );
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException exception) {
            throw new ModulithException("Cannot instantiate module '" + descriptor.id() + "'", exception);
        }
    }

    public synchronized void stop() {
        if (!started && running.isEmpty()) {
            return;
        }
        stopStartedModules();
        started = false;
    }

    private void stopStartedModules() {
        List<RunningModule> modules = new ArrayList<>(running.values());
        Collections.reverse(modules);
        RuntimeException first = null;

        for (RunningModule module : modules) {
            states.put(module.descriptor.id(), ModuleState.STOPPING);
            try {
                module.instance.disable();
            } catch (Exception exception) {
                logger.log(Level.SEVERE,
                        "[MinecraftModulith] Module disable failed: " + module.descriptor.id(), exception);
                first = append(first, new ModulithException(
                        "Failed to disable module '" + module.descriptor.id() + "'", exception
                ));
            }

            try {
                if (module.descriptor.configurationEnabled()) {
                    module.configuration.save();
                }
            } catch (RuntimeException exception) {
                logger.log(Level.SEVERE,
                        "[MinecraftModulith] Module configuration save failed: " + module.descriptor.id(), exception);
                first = append(first, exception);
            }

            try {
                module.lifecycle.close();
            } catch (RuntimeException exception) {
                logger.log(Level.SEVERE,
                        "[MinecraftModulith] Module cleanup failed: " + module.descriptor.id(), exception);
                first = append(first, exception);
            }

            metrics.moduleStopped(module.descriptor.id());
            states.put(module.descriptor.id(), ModuleState.STOPPED);
        }

        running.clear();
        services.clear();
        if (first != null) {
            throw first;
        }
    }

    private static RuntimeException append(RuntimeException first, RuntimeException next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    public List<ModuleDescriptor> modules() {
        return startupOrder;
    }

    public Map<String, ModuleState> states() {
        return Map.copyOf(states);
    }

    /** Root causes of optional modules that failed or were skipped during startup. */
    public synchronized Map<String, Throwable> failures() {
        return Map.copyOf(failures);
    }

    public EventBus events() {
        return eventBus;
    }

    public ModulithMetrics metrics() {
        return metrics;
    }

    public RuntimeDiagnostics diagnostics() {
        long incomplete;
        try {
            incomplete = publicationRegistry.incompleteCount();
        } catch (RuntimeException exception) {
            logger.log(Level.WARNING, "[MinecraftModulith] Could not read incomplete publications", exception);
            incomplete = -1;
        }

        return new RuntimeDiagnostics(
                states,
                startupOrder.stream().map(ModuleDescriptor::id).toList(),
                services.size(),
                incomplete,
                metrics.snapshot(),
                failureMessages()
        );
    }

    private Map<String, String> failureMessages() {
        Map<String, String> messages = new LinkedHashMap<>();
        failures.forEach((id, failure) -> messages.put(id, failure.getMessage() != null
                ? failure.getClass().getSimpleName() + ": " + failure.getMessage()
                : failure.getClass().getName()));
        return messages;
    }

    public String graphMermaid() {
        return ModuleGraphExporter.toMermaid(startupOrder);
    }

    public String graphGraphviz() {
        return ModuleGraphExporter.toGraphviz(startupOrder);
    }

    @Override
    public void close() {
        stop();
    }

    public static final class Builder {
        private final Set<Class<? extends MinecraftModule>> moduleTypes = new LinkedHashSet<>();
        private final Map<String, List<Class<?>>> components = new LinkedHashMap<>();
        private final Map<Class<?>, Object> platformServices = new LinkedHashMap<>();
        private Logger logger = Logger.getLogger("MinecraftModulith");
        private Executor eventExecutor = ForkJoinPool.commonPool();
        private EventPublicationRegistry publicationRegistry = EventPublicationRegistry.noop();
        private EventPayloadSerializer eventSerializer = EventPayloadSerializer.toStringSerializer();
        private ModuleConfigurationProvider configurationProvider = ModuleConfigurationProvider.none();
        private ModulithMetrics metrics = new ModulithMetrics();

        public Builder module(Class<? extends MinecraftModule> moduleType) {
            moduleTypes.add(Objects.requireNonNull(moduleType, "moduleType"));
            return this;
        }

        /** Registers a module-scoped injectable class (also supported in standalone tests). */
        public Builder component(String moduleId, Class<?> type) {
            components.computeIfAbsent(moduleId, ignored -> new ArrayList<>()).add(type);
            return this;
        }

        public Builder modules(Collection<Class<? extends MinecraftModule>> moduleTypes) {
            moduleTypes.forEach(this::module);
            return this;
        }

        public <T> Builder platformService(Class<T> type, T service) {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(service, "service");
            if (!type.isInstance(service)) {
                throw new IllegalArgumentException(service.getClass().getName() + " is not a " + type.getName());
            }
            platformServices.put(type, service);
            return this;
        }

        public Builder logger(Logger logger) {
            this.logger = Objects.requireNonNull(logger, "logger");
            return this;
        }

        public Builder eventExecutor(Executor eventExecutor) {
            this.eventExecutor = Objects.requireNonNull(eventExecutor, "eventExecutor");
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

        public Builder configurationProvider(ModuleConfigurationProvider configurationProvider) {
            this.configurationProvider = Objects.requireNonNull(configurationProvider, "configurationProvider");
            return this;
        }

        public Builder metrics(ModulithMetrics metrics) {
            this.metrics = Objects.requireNonNull(metrics, "metrics");
            return this;
        }

        public ModuleRuntime build() {
            List<ModuleDescriptor> descriptors = moduleTypes.stream().map(Builder::describe).toList();
            return new ModuleRuntime(
                    ModuleGraph.validateAndSort(descriptors),
                    platformServices,
                    logger,
                    eventExecutor,
                    publicationRegistry,
                    eventSerializer,
                    configurationProvider,
                    metrics,
                    Map.copyOf(components)
            );
        }

        public ModuleRuntime start() {
            return build().start();
        }

        public static ModuleDescriptor describe(Class<? extends MinecraftModule> type) {
            PluginModule annotation = type.getAnnotation(PluginModule.class);
            if (annotation == null) {
                throw new ModulithException("Module class is missing @PluginModule: " + type.getName());
            }
            return new ModuleDescriptor(
                    annotation.value(),
                    List.of(annotation.dependencies()),
                    type,
                    annotation.configuration(),
                    annotation.optional(),
                    List.of(annotation.after())
            );
        }
    }

    private record RunningModule(
            ModuleDescriptor descriptor,
            MinecraftModule instance,
            LifecycleScope lifecycle,
            ModuleConfiguration configuration
    ) {
    }
}
