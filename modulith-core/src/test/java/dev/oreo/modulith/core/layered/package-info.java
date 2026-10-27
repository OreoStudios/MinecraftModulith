@ApplicationModule(id = "layered", allowedDependencies = "economy::payments", after = "audit", configuration = true)
package dev.oreo.modulith.core.layered;

import dev.oreo.modulith.core.ApplicationModule;
