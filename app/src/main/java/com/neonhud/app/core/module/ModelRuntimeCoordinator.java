package com.neonhud.app.core.module;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Owns the process-wide RAM policy for offline models: exactly ONE model may be LOADED at a time.
 * Imports are independent; loading is exclusive and is driven by the active conversation/task.
 */
public final class ModelRuntimeCoordinator {
    public enum Target { GEMMA, CODER }

    public interface StatusSink { void onStatus(String status); }

    public interface Listener { void onRuntimeChanged(RuntimeSnapshot snapshot); }

    public static final class RuntimeSnapshot {
        public final Target active;
        public final boolean transitioning;
        public final String status;
        public RuntimeSnapshot(Target active, boolean transitioning, String status) {
            this.active = active;
            this.transitioning = transitioning;
            this.status = status == null ? "" : status;
        }
        public boolean gemmaActive() { return active == Target.GEMMA; }
        public boolean coderActive() { return active == Target.CODER; }
    }

    private final ModuleManager gemma;
    private final ModuleManager coder;
    private final Object lock = new Object();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<Listener>();

    private volatile Target active;
    private volatile boolean transitioning;
    private volatile String status = "";

    public ModelRuntimeCoordinator(ModuleManager gemma, ModuleManager coder) {
        this.gemma = gemma;
        this.coder = coder;
        reconcileLoadedState();
    }

    public RuntimeSnapshot snapshot() {
        return new RuntimeSnapshot(active, transitioning, status);
    }

    public void addListener(Listener l) { listeners.addIfAbsent(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    /** Called after process restore and after Gemma import: only Gemma may be auto-loaded while idle. */
    public void ensureGemmaLoadedAsync(final StatusSink sink) {
        new Thread(new Runnable() {
            @Override public void run() {
                try { ensureLoaded(Target.GEMMA, sink); }
                catch (Throwable ignored) { }
            }
        }, "gemma-autoload").start();
    }

    /** Ensures exactly one requested model is resident. This call blocks the caller's worker, never the UI thread. */
    public boolean ensureLoaded(Target target, StatusSink sink) {
        synchronized (lock) {
            if (!waitForModuleActionsIdle(sink)) return false;
            transitioning = true;
            setStatus(sink, target == Target.CODER ? "Preparing Qwen Coder…" : "Preparing Gemma…");
            try {
                ModuleManager wanted = manager(target);
                ModuleManager other = manager(other(target));

                if (wanted.state() == ModuleState.NOT_IMPORTED) {
                    setStatus(sink, targetName(target) + " is not imported.");
                    return false;
                }

                ModuleSnapshot otherSnap = other.snapshot();
                if (otherSnap.replyActive) {
                    setStatus(sink, targetName(other(target)) + " is still replying.");
                    return false;
                }
                if (otherSnap.state == ModuleState.LOADED) {
                    setStatus(sink, "Unloading " + targetName(other(target)) + "…");
                    ModuleManager.Result r = other.requestUnload();
                    if (!r.accepted || !waitForManagerIdle(other, sink)) return false;
                }

                ModuleSnapshot wantedSnap = wanted.snapshot();
                if (wantedSnap.state != ModuleState.LOADED) {
                    setStatus(sink, "Loading " + targetName(target) + "…");
                    ModuleManager.Result r = wanted.requestLoad();
                    if (!r.accepted || !waitForManagerIdle(wanted, sink)) return false;
                }

                if (wanted.state() != ModuleState.LOADED || manager(other(target)).state() == ModuleState.LOADED) {
                    setStatus(sink, "Model runtime could not reach a single active model state.");
                    return false;
                }
                active = target;
                setStatus(sink, targetName(target) + " is ready.");
                publish();
                return true;
            } finally {
                transitioning = false;
                publish();
            }
        }
    }

    /** Claims the requested model for one reply after making it the only loaded model. */
    public boolean beginReply(Target target, StatusSink sink) {
        synchronized (lock) {
            if (transitioning) return false;
            transitioning = true;
            publish();
            try {
                if (!ensureLoadedLocked(target, sink)) return false;
                ModuleManager wanted = manager(target);
                if (!wanted.tryBeginReply()) return false;
                active = target;
                setStatus(sink, targetName(target) + " is generating…");
                return true;
            } finally {
                transitioning = false;
                publish();
            }
        }
    }

    /** Ends a reply. Coding always returns RAM ownership to Gemma before the coder is considered offline. */
    public void endReply(Target target, StatusSink sink) {
        synchronized (lock) {
            ModuleManager finished = manager(target);
            try { finished.endReply(); } catch (RuntimeException ignored) { }
            if (target == Target.CODER) {
                transitioning = true;
                publish();
                try {
                    ModuleSnapshot g = gemma.snapshot();
                    if (g.state == ModuleState.IMPORTED || g.state == ModuleState.UNLOADED) {
                        setStatus(sink, "Switching back to Gemma…");
                        ModuleManager.Result u = coder.requestUnload();
                        if (u.accepted && waitForManagerIdle(coder, sink)) {
                            ModuleManager.Result l = gemma.requestLoad();
                            if (l.accepted && waitForManagerIdle(gemma, sink) && gemma.state() == ModuleState.LOADED) {
                                active = Target.GEMMA;
                                setStatus(sink, "Gemma is ready.");
                            } else {
                                active = null;
                                setStatus(sink, "Coder finished; Gemma could not be loaded.");
                            }
                        } else {
                            setStatus(sink, "Coder finished; unloading it did not complete.");
                        }
                    } else {
                        // There is no Gemma file to return to. Still make sure coder is not left resident.
                        ModuleManager.Result u = coder.requestUnload();
                        if (u.accepted) waitForManagerIdle(coder, sink);
                        active = null;
                        setStatus(sink, "Coder finished; Gemma is not imported.");
                    }
                } finally {
                    transitioning = false;
                    publish();
                }
            } else {
                active = Target.GEMMA;
                setStatus(sink, "Gemma is ready.");
                publish();
            }
        }
    }

    /** Best-effort process exit path; guarantees at most one engine remains resident. */
    public void shutdownQuietly() {
        synchronized (lock) {
            try { if (gemma.snapshot().canUnload) gemma.requestUnload(); } catch (RuntimeException ignored) { }
            try { if (coder.snapshot().canUnload) coder.requestUnload(); } catch (RuntimeException ignored) { }
        }
    }

    private boolean ensureLoadedLocked(Target target, StatusSink sink) {
        if (!waitForModuleActionsIdle(sink)) return false;
        ModuleManager wanted = manager(target);
        ModuleManager other = manager(other(target));
        if (wanted.state() == ModuleState.NOT_IMPORTED) {
            setStatus(sink, targetName(target) + " is not imported.");
            return false;
        }
        ModuleSnapshot otherSnap = other.snapshot();
        if (otherSnap.replyActive) {
            setStatus(sink, targetName(other(target)) + " is still replying.");
            return false;
        }
        boolean otherWasLoaded = otherSnap.state == ModuleState.LOADED;
        if (otherWasLoaded) {
            setStatus(sink, "Unloading " + targetName(other(target)) + "…");
            ModuleManager.Result r = other.requestUnload();
            if (!r.accepted || !waitForManagerIdle(other, sink)) return false;
        }
        if (wanted.state() != ModuleState.LOADED) {
            setStatus(sink, "Loading " + targetName(target) + "…");
            ModuleManager.Result r = wanted.requestLoad();
            if (!r.accepted || !waitForManagerIdle(wanted, sink)) {
                restorePreviouslyActive(other, otherWasLoaded, sink);
                return false;
            }
        }
        boolean exclusive = wanted.state() == ModuleState.LOADED && manager(other(target)).state() != ModuleState.LOADED;
        if (!exclusive) {
            restorePreviouslyActive(other, otherWasLoaded, sink);
        }
        return exclusive;
    }

    private void restorePreviouslyActive(ModuleManager other, boolean wasLoaded, StatusSink sink) {
        if (!wasLoaded) return;
        if (other.state() == ModuleState.LOADED) return;
        if (other.state() == ModuleState.IMPORTED || other.state() == ModuleState.UNLOADED) {
            setStatus(sink, "Restoring " + other.displayName() + "…");
            ModuleManager.Result r = other.requestLoad();
            if (r.accepted) waitForManagerIdle(other, sink);
        }
    }

    private boolean waitForModuleActionsIdle(StatusSink sink) {
        return waitForManagerIdle(gemma, sink) && waitForManagerIdle(coder, sink);
    }

    private boolean waitForManagerIdle(ModuleManager m, StatusSink sink) {
        long end = System.currentTimeMillis() + 180000L;
        while (System.currentTimeMillis() < end) {
            ModuleSnapshot s = m.snapshot();
            if (s.inFlight == null) return true;
            try { Thread.sleep(8L); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                setStatus(sink, "Model transition interrupted.");
                return false;
            }
        }
        setStatus(sink, "Model transition timed out.");
        return false;
    }

    private void reconcileLoadedState() {
        ModuleState gs = gemma.state();
        ModuleState cs = coder.state();
        if (gs == ModuleState.LOADED && cs == ModuleState.LOADED) {
            // A process restart should never have two resident engines; defensively unload coder.
            try { coder.requestUnload(); } catch (RuntimeException ignored) { }
            active = Target.GEMMA;
        } else if (gs == ModuleState.LOADED) active = Target.GEMMA;
        else if (cs == ModuleState.LOADED) active = Target.CODER;
        else active = null;
    }

    private ModuleManager manager(Target t) { return t == Target.CODER ? coder : gemma; }
    private static Target other(Target t) { return t == Target.CODER ? Target.GEMMA : Target.CODER; }
    private static String targetName(Target t) { return t == Target.CODER ? "Qwen Coder" : "Gemma"; }

    private void setStatus(StatusSink sink, String s) {
        status = s == null ? "" : s;
        if (sink != null) try { sink.onStatus(status); } catch (RuntimeException ignored) { }
        publish();
    }

    private void publish() {
        RuntimeSnapshot s = snapshot();
        for (Listener l : listeners) {
            try { l.onRuntimeChanged(s); } catch (RuntimeException ignored) { }
        }
    }
}
