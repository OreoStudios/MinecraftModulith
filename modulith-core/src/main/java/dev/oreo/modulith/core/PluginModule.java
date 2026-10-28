package dev.oreo.modulith.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a class as a module managed by MinecraftModulith.
 *
 * <p>Dependencies may target a whole module ({@code economy}) or a named public API
 * ({@code economy::payments}).</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PluginModule {
    String value();

    String[] dependencies() default {};

    /**
     * Ordering-only constraints: this module starts after the listed module ids when they are present,
     * but gains no API access and still starts if one of them failed, was skipped or is absent.
     */
    String[] after() default {};
    /**
     * APIs this module calls at runtime without needing their module started first, such as a
     * menu that opens another feature's screen. Selectors use the same syntax as
     * {@link #dependencies()} and grant the same compile-time access, but impose no start order
     * and no failure propagation: look the service up when needed and handle it being absent.
     * Use this to break dependency cycles between modules that only collaborate after startup.
     */
    String[] uses() default {};

    /** Enables module-specific configuration when a provider is installed. */
    boolean configuration() default false;

    /**
     * Optional modules may fail to start without aborting the runtime. A failed optional module is
     * marked {@link ModuleState#FAILED}, its services are withdrawn, and optional modules depending on
     * it are {@link ModuleState#SKIPPED}. Required modules depending on it abort startup.
     */
    boolean optional() default false;
}
