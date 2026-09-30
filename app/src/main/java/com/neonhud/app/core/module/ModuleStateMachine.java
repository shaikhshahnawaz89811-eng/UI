package com.neonhud.app.core.module;

/**
 * The single source of truth for what the Gemma 4 E2B module may do.
 *
 * <pre>
 *   NOT_IMPORTED --IMPORT--> IMPORTED --LOAD--> LOADED --UNLOAD--> UNLOADED --DELETE--> NOT_IMPORTED
 *                                                  ^                    |
 *                                                  +-------LOAD---------+
 * </pre>
 *
 * Every transition goes through {@link #begin} which is atomic (synchronized) and rejects
 * anything that is not on the graph above, or that is attempted while another action or an
 * AI reply is in progress. The UI only mirrors {@link #can}; it is NOT what protects the module.
 */
public final class ModuleStateMachine {

    /** Single-use permit returned by {@link #begin}. */
    public static final class Ticket {
        public final ModuleAction action;
        public final ModuleState from;
        public final ModuleState to;
        private boolean used;

        private Ticket(ModuleAction action, ModuleState from, ModuleState to) {
            this.action = action;
            this.from = from;
            this.to = to;
        }
    }

    private ModuleState state;
    private Ticket inFlight;
    private boolean replyActive;

    public ModuleStateMachine(ModuleState initial) {
        this.state = initial;
    }

    /** The static transition graph. Returns null when the action is illegal from that state. */
    public static ModuleState target(ModuleState from, ModuleAction action) {
        switch (action) {
            case IMPORT:
                return from == ModuleState.NOT_IMPORTED ? ModuleState.IMPORTED : null;
            case LOAD:
                return (from == ModuleState.IMPORTED || from == ModuleState.UNLOADED)
                        ? ModuleState.LOADED : null;
            case UNLOAD:
                return from == ModuleState.LOADED ? ModuleState.UNLOADED : null;
            case DELETE:
                return from == ModuleState.UNLOADED ? ModuleState.NOT_IMPORTED : null;
            default:
                return null;
        }
    }

    /** Everything the UI needs, read under ONE lock so buttons can never mix two different moments. */
    public static final class View {
        public final ModuleState state;
        public final ModuleAction inFlight;
        public final boolean replyActive;
        public final boolean canImport, canLoad, canUnload, canDelete;
        View(ModuleState state, ModuleAction inFlight, boolean replyActive,
             boolean i, boolean l, boolean u, boolean d) {
            this.state = state; this.inFlight = inFlight; this.replyActive = replyActive;
            this.canImport = i; this.canLoad = l; this.canUnload = u; this.canDelete = d;
        }
    }

    public synchronized View view() {
        return new View(state, inFlight == null ? null : inFlight.action, replyActive,
                can(ModuleAction.IMPORT), can(ModuleAction.LOAD), can(ModuleAction.UNLOAD), can(ModuleAction.DELETE));
    }

    public synchronized ModuleState state() { return state; }

    public synchronized ModuleAction inFlightAction() {
        return inFlight == null ? null : inFlight.action;
    }

    public synchronized boolean isReplyActive() { return replyActive; }

    /** True if {@link #begin} would succeed right now. */
    public synchronized boolean can(ModuleAction action) {
        return rejection(action) == null;
    }

    private String rejection(ModuleAction action) {
        if (inFlight != null) return "another action (" + inFlight.action + ") is still running";
        if (replyActive && action == ModuleAction.UNLOAD) return "an AI reply is in progress";
        if (target(state, action) == null) return "invalid transition";
        return null;
    }

    public synchronized Ticket begin(ModuleAction action) {
        String why = rejection(action);
        if (why != null) throw new IllegalTransitionException(action, state, why);
        inFlight = new Ticket(action, state, target(state, action));
        return inFlight;
    }

    public synchronized void commit(Ticket t) {
        check(t);
        t.used = true;
        state = t.to;
        inFlight = null;
    }

    public synchronized void rollback(Ticket t) {
        check(t);
        t.used = true;
        inFlight = null;   // state unchanged
    }

    private void check(Ticket t) {
        if (t == null || t.used || t != inFlight) {
            throw new IllegalStateException("stale or foreign ticket");
        }
    }

    /** Claims the model for one AI reply. Only possible while LOADED and idle. */
    public synchronized boolean beginReply() {
        if (state != ModuleState.LOADED || inFlight != null || replyActive) return false;
        replyActive = true;
        return true;
    }

    public synchronized void endReply() {
        replyActive = false;
    }
}
