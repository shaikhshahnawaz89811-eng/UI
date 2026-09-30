package com.neonhud.app.core.module;

import com.neonhud.app.core.engine.ModelEngine;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Orchestrates import / load / unload / delete of ONE offline model module (Gemma 4 E2B, Qwen Coder, ...).
 * Each module gets its own ModuleManager, so their states never affect each other.
 * Every public request is validated by {@link ModuleStateMachine} at call time, so a second Load,
 * a Delete while loaded, etc. are refused even when triggered programmatically.
 */
public final class ModuleManager {

    public interface Listener {
        /** May be called from any thread. */
        void onModuleChanged(ModuleSnapshot snapshot);
    }

    /** Result of a request: accepted (work started) or refused with a reason. */
    public static final class Result {
        public final boolean accepted;
        public final String reason;
        private Result(boolean accepted, String reason) { this.accepted = accepted; this.reason = reason; }
        static Result ok() { return new Result(true, ""); }
        static Result refused(String r) { return new Result(false, r); }
    }

    private final ModelEngine engine;
    private final ModelStorage storage;
    private final StateStore stateStore;
    private final ModuleStateMachine machine;
    private final ExecutorService worker;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<Listener>();

    private final String displayName;
    private volatile int importPercent = -1;
    private volatile String message = "";

    public ModuleManager(ModelEngine engine, ModelStorage storage, StateStore stateStore) {
        this(engine, storage, stateStore, "Gemma 4 E2B", "gemma-module");
    }

    public ModuleManager(ModelEngine engine, ModelStorage storage, StateStore stateStore,
                         String displayName, final String threadName) {
        this.displayName = displayName;
        this.engine = engine;
        this.storage = storage;
        this.stateStore = stateStore;
        storage.cleanupPartial();
        this.machine = new ModuleStateMachine(restore(stateStore.load(), storage.isModelPresent()));
        stateStore.save(machine.state());
        this.worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, threadName);
                t.setDaemon(true);
                return t;
            }
        });
    }

    /**
     * Cold-start reconciliation. After a process restart nothing is loaded in memory, so a persisted
     * LOADED becomes UNLOADED; a missing file means NOT_IMPORTED; an orphan file is recovered as IMPORTED.
     */
    public static ModuleState restore(ModuleState persisted, boolean filePresent) {
        if (!filePresent) return ModuleState.NOT_IMPORTED;
        if (persisted == null) return ModuleState.IMPORTED;
        switch (persisted) {
            case LOADED:
            case UNLOADED:
                return ModuleState.UNLOADED;
            case IMPORTED:
            case NOT_IMPORTED:
            default:
                return ModuleState.IMPORTED;
        }
    }

    // ---------------------------------------------------------------- listeners / snapshot

    public void addListener(Listener l) { listeners.addIfAbsent(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    public ModuleSnapshot snapshot() {
        ModuleStateMachine.View v = machine.view();      // one atomic read
        return new ModuleSnapshot(v.state, v.inFlight, v.replyActive, importPercent, message,
                v.canImport, v.canLoad, v.canUnload, v.canDelete);
    }

    private void publish() {
        ModuleSnapshot s = snapshot();
        for (Listener l : listeners) {
            try { l.onModuleChanged(s); } catch (RuntimeException ignored) { }
        }
    }

    public ModelEngine engine() { return engine; }
    public String displayName() { return displayName; }
    public ModuleState state() { return machine.state(); }

    // ---------------------------------------------------------------- actions

    public Result requestImport(final ImportSource source) {
        final ModuleStateMachine.Ticket t;
        try { t = machine.begin(ModuleAction.IMPORT); }
        catch (IllegalTransitionException e) { return Result.refused(e.getMessage()); }
        message = "";
        importPercent = 0;
        publish();
        worker.execute(new Runnable() {
            @Override public void run() {
                try {
                    storage.importModel(source, new ProgressSink() {
                        @Override public void onProgress(int p) { importPercent = p; publish(); }
                    });
                    finish(t, true, "Model imported successfully.");
                } catch (Throwable e) {
                    storage.cleanupPartial();
                    finish(t, false, "Import failed: " + describe(e));
                }
            }
        });
        return Result.ok();
    }

    public Result requestLoad() {
        final ModuleStateMachine.Ticket t;
        try { t = machine.begin(ModuleAction.LOAD); }
        catch (IllegalTransitionException e) { return Result.refused(e.getMessage()); }
        message = "";
        publish();
        worker.execute(new Runnable() {
            @Override public void run() {
                try {
                    engine.load(storage.modelPath());
                    finish(t, true, "");
                } catch (Throwable e) {
                    try { engine.unload(); } catch (Throwable ignored) { }
                    finish(t, false, "Load failed: " + describe(e));
                }
            }
        });
        return Result.ok();
    }

    public Result requestUnload() {
        final ModuleStateMachine.Ticket t;
        try { t = machine.begin(ModuleAction.UNLOAD); }
        catch (IllegalTransitionException e) { return Result.refused(e.getMessage()); }
        message = "";
        publish();
        worker.execute(new Runnable() {
            @Override public void run() {
                String note = "";
                try { engine.unload(); }
                catch (Throwable e) { note = "Unloaded (with warning: " + describe(e) + ")"; }
                // The engine reference is dropped either way, so the module is unloaded.
                finish(t, true, note);
            }
        });
        return Result.ok();
    }

    public Result requestDelete() {
        final ModuleStateMachine.Ticket t;
        try { t = machine.begin(ModuleAction.DELETE); }
        catch (IllegalTransitionException e) { return Result.refused(e.getMessage()); }
        message = "";
        publish();
        worker.execute(new Runnable() {
            @Override public void run() {
                try {
                    storage.deleteModel();
                    finish(t, true, displayName + " has been removed.");
                } catch (Throwable e) {
                    finish(t, false, "Delete failed: " + describe(e));
                }
            }
        });
        return Result.ok();
    }

    /** Claims the loaded model for one reply; false if not LOADED or something else is running. */
    public boolean tryBeginReply() {
        boolean ok = machine.beginReply();
        if (ok) publish();
        return ok;
    }

    public void endReply() {
        machine.endReply();
        publish();
    }

    /** Exit path: best-effort unload so nothing stays in memory. Only when LOADED and idle. */
    public void shutdownQuietly() {
        try {
            if (machine.can(ModuleAction.UNLOAD)) {
                ModuleStateMachine.Ticket t = machine.begin(ModuleAction.UNLOAD);
                try { engine.unload(); } catch (Throwable ignored) { }
                machine.commit(t);
                stateStore.save(machine.state());
            }
        } catch (RuntimeException ignored) { }
    }

    private void finish(ModuleStateMachine.Ticket t, boolean success, String msg) {
        if (success) machine.commit(t); else machine.rollback(t);
        stateStore.save(machine.state());
        importPercent = -1;
        message = msg;
        publish();
    }

    private static String describe(Throwable e) {
        String m = e.getMessage();
        return (m == null || m.isEmpty()) ? e.getClass().getSimpleName() : m;
    }
}
