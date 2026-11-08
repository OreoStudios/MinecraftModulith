package dev.oreo.modulith.core;

import java.util.Objects;

/** Public service contract qualified by the module instance that publishes it. */
public record ServiceKey<T>(String providerId, Class<T> contract) {
    public ServiceKey {
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(contract, "contract");
        if (providerId.isBlank()) throw new IllegalArgumentException("providerId cannot be blank");
    }
}
