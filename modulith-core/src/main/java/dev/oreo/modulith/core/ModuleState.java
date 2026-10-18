package dev.oreo.modulith.core;

public enum ModuleState {
    DISCOVERED,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    FAILED,
    /** Not started because a module it depends on is unavailable. */
    SKIPPED
}
