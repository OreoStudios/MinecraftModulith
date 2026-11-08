package dev.oreo.modulith.example.functors.homes;

/** Independent parameters for the homes demonstration module. */
public record HomesParameters(int maxHomes, int teleportCooldownSeconds) {
    public HomesParameters {
        if (maxHomes < 1 || teleportCooldownSeconds < 0) {
            throw new IllegalArgumentException("Invalid homes limits");
        }
    }
}
