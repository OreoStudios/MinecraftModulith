package dev.oreo.modulith.core;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

/** Runtime facilities available to a module. */
public final class ModuleContext {
    private ModuleComponents components;
    private final String moduleId;
    private final ModuleInstanceId instanceIdentity;
    private final ModuleServices services;
    private final EventBus events;
    private final LifecycleScope lifecycle;
    private final ModuleConfiguration configuration;
    private final Map<Class<?>, Object> platformServices;
    private final Logger logger;

    ModuleContext(
            String moduleId,
            String templateId,
            ModuleServices services,
            EventBus events,
            LifecycleScope lifecycle,
            ModuleConfiguration configuration,
            Map<Class<?>, Object> platformServices,
            Logger logger
    ) {
        this.moduleId = moduleId;
        this.instanceIdentity = new ModuleInstanceId(templateId, moduleId);
        this.services = services;
        this.events = events;
        this.lifecycle = lifecycle;
        this.configuration = configuration;
        this.platformServices = platformServices;
        this.logger = logger;
    }

    void setComponents(ModuleComponents components) { this.components = components; }

    /** Access constructor-injected components owned by this module. */
    public ModuleComponents components() { return Objects.requireNonNull(components, "components not initialized"); }

    public String moduleId() {
        return moduleId;
    }

    /** Distinguishes the runtime instance from its annotated template identity. */
    public ModuleInstanceId instanceIdentity() {
        return instanceIdentity;
    }

    public ModuleServices services() {
        return services;
    }

    public EventBus events() {
        return events;
    }

    public LifecycleScope lifecycle() {
        return lifecycle;
    }

    public ModuleConfiguration config() {
        return configuration;
    }

    /** Reads a validated immutable record snapshot of this module's configuration. */
    public <T extends Record> T config(Class<T> recordType) {
        return TypedModuleConfiguration.read(configuration, recordType);
    }

    public Logger logger() {
        return logger;
    }

    public <T> T platform(Class<T> type) {
        Objects.requireNonNull(type, "type");
        Object value = platformServices.get(type);
        if (value == null) {
            throw new ModulithException("Platform service not available: " + type.getName());
        }
        return type.cast(value);
    }

    /** Registers all @ModuleListener methods and automatically unsubscribes them on module stop. */
    public void listen(Object listener) {
        List<EventBus.Subscription> subscriptions = moduleId.equals(instanceIdentity.templateId())
                ? events.register(listener)
                : events.register(listener, moduleId);
        for (EventBus.Subscription subscription : subscriptions) {
            lifecycle.onClose(subscription::close);
        }
    }
}
