/** Homes module. May use the economy module's {@code payments} API. */
@ApplicationModule(id = "homes", allowedDependencies = "economy::payments")
package dev.oreo.modulith.example.homes;

import dev.oreo.modulith.core.ApplicationModule;
