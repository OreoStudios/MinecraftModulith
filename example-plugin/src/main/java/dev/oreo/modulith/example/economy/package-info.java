/**
 * Economy module. The whole package hierarchy ({@code api}, {@code infra}, {@code lifecycle})
 * is one module boundary; only {@code @ModuleApi} types are visible to other modules.
 */
@ApplicationModule(id = "economy")
package dev.oreo.modulith.example.economy;

import dev.oreo.modulith.core.ApplicationModule;
