package dev.oreo.modulith.core;

import java.lang.annotation.*;

/** Declares a package as a module. The annotation processor generates its lifecycle anchor. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PACKAGE)
public @interface ApplicationModule {
    String id();
    String[] allowedDependencies() default {};
    /** See {@link PluginModule#after()}. */
    String[] after() default {};
    boolean configuration() default false;
    /** See {@link PluginModule#optional()}. */
    boolean optional() default false;
}
