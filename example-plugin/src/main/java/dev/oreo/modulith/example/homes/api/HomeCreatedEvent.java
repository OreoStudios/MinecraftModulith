package dev.oreo.modulith.example.homes.api;

import dev.oreo.modulith.core.ModuleApi;

import java.util.UUID;

@ModuleApi("events")
public record HomeCreatedEvent(UUID playerId, String name) {
}
