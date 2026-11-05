package dev.oreo.modulith.core;

import java.util.Objects;

/**
 * Runtime instance identity and its annotated module-template ID.
 * The default module uses the same value for both fields.
 */
public record ModuleInstanceId(String templateId, String value) {
    public ModuleInstanceId {
        Objects.requireNonNull(templateId, "templateId");
        Objects.requireNonNull(value, "value");
        if (templateId.isBlank() || value.isBlank()) {
            throw new IllegalArgumentException("Module identity cannot be blank");
        }
    }

    public boolean isDefault() {
        return templateId.equals(value);
    }
}
