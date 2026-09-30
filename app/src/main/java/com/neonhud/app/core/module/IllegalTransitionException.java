package com.neonhud.app.core.module;

/** Thrown when an action is not allowed in the current state (or another action is running). */
public final class IllegalTransitionException extends IllegalStateException {
    private final ModuleAction action;
    private final ModuleState state;

    public IllegalTransitionException(ModuleAction action, ModuleState state, String reason) {
        super(action + " not allowed in state " + state + ": " + reason);
        this.action = action;
        this.state = state;
    }

    public ModuleAction action() { return action; }
    public ModuleState state() { return state; }
}
