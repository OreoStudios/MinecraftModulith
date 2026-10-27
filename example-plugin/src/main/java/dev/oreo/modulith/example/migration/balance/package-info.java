/** Balance module, migrated from a legacy command. May use the economy module's {@code payments} API. */
@ApplicationModule(id = "balance", allowedDependencies = "economy::payments")
package dev.oreo.modulith.example.migration.balance;

import dev.oreo.modulith.core.ApplicationModule;
