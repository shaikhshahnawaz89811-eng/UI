package com.neonhud.app.core.chat;

import com.neonhud.app.core.engine.GenerationCallback;
import com.neonhud.app.core.engine.ModelEngine;
import com.neonhud.app.core.memory.ConversationBrain;
import com.neonhud.app.core.memory.ConversationMessage;
import com.neonhud.app.core.memory.MemoryStore;
import com.neonhud.app.core.module.ModuleManager;
import com.neonhud.app.core.module.ModuleState;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Chat logic that lives for the whole process (not the Activity): so leaving the app, coming back, rotating
 * or being minimised never interrupts a reply that is being generated. The UI only observes it.
 */
public final class ChatController {

    public enum Kind { USER, AI, NOTICE }

    /** One line on the chat screen. Immutable; streaming replaces the item with a longer one. */
    public static final class Item {
        public final long key;
        public final Kind kind;
        public final String text;
        public final boolean pending;
        Item(long key, Kind kind, String text, boolean pending) {
            this.key = key; this.kind = kind; this.text = text; this.pending = pending;
        }
    }

    public enum SendResult { ACCEPTED, EMPTY, MODEL_NOT_READY, BUSY }

    public interface Listener {
        /** The list changed (may fire very often while streaming; may be called from any thread). */
        void onChatChanged();
    }

    private static final int SCREEN_HISTORY = 200;
    public static final int MAX_INPUT_CHARS = 4000;

    private final ModuleManager modules;
    private final ModelEngine engine;
    private final ConversationBrain brain;
    private final MemoryStore store;
    private final ExecutorService worker;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    private final List<Item> items = new ArrayList<Item>();
    private long nextKey = 1;
    private volatile boolean generating;

    public ChatController(ModuleManager modules, ConversationBrain brain, MemoryStore store) {
        this.modules = modules;
        this.engine = modules.engine();
        this.brain = brain;
        this.store = store;
        this.worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "gemma-chat");
                t.setDaemon(true);
                return t;
            }
        });
        for (ConversationMessage m : store.lastMessages(SCREEN_HISTORY)) {
            items.add(new Item(nextKey++, m.isUser() ? Kind.USER : Kind.AI, m.content, false));
        }
    }

    public void addListener(Listener l) { listeners.addIfAbsent(l); }
    public void removeListener(Listener l) { listeners.remove(l); }
    public boolean isGenerating() { return generating; }

    public synchronized List<Item> items() { return new ArrayList<Item>(items); }

    private void changed() {
        for (Listener l : listeners) {
            try { l.onChatChanged(); } catch (RuntimeException ignored) { }
        }
    }

    public SendResult send(final String rawText) {
        final String text = rawText == null ? "" : rawText.trim();
        if (text.isEmpty()) return SendResult.EMPTY;
        if (generating) return SendResult.BUSY;
        if (!modules.tryBeginReply()) {
            if (modules.state() != ModuleState.LOADED) {
                notice("Gemma 4 E2B is not loaded. Open Settings \u2699 and Import / Load the model.");
                return SendResult.MODEL_NOT_READY;
            }
            return SendResult.BUSY;
        }
        generating = true;
        final String clipped = text.length() > MAX_INPUT_CHARS ? text.substring(0, MAX_INPUT_CHARS) : text;
        final long aiKey;
        synchronized (this) {
            dropNotices();
            items.add(new Item(nextKey++, Kind.USER, clipped, false));
            aiKey = nextKey++;
            items.add(new Item(aiKey, Kind.AI, "", true));
        }
        changed();
        worker.execute(new Runnable() {
            @Override public void run() { runTurn(clipped, aiKey); }
        });
        return SendResult.ACCEPTED;
    }

    private void runTurn(String text, final long aiKey) {
        final StringBuilder reply = new StringBuilder();
        ConversationBrain.Turn turn = null;
        String error = null;
        try {
            turn = brain.beginTurn(text);
            engine.generate(turn.prompt, new GenerationCallback() {
                @Override public void onToken(String delta) {
                    if (delta == null || delta.isEmpty()) return;
                    synchronized (reply) { reply.append(delta); }
                    setAi(aiKey, reply.toString(), true);
                }
            });
        } catch (Throwable e) {
            String m = e.getMessage();
            error = "Reply failed: " + (m == null || m.isEmpty() ? e.getClass().getSimpleName() : m);
        }
        final String finalText = reply.toString();
        try {
            if (turn != null && !finalText.trim().isEmpty()) brain.finishTurn(turn, finalText);
        } catch (Throwable ignored) {
            // memory write failure must not lose the reply the user already sees
        }
        if (finalText.trim().isEmpty()) removeItem(aiKey); else setAi(aiKey, finalText, false);
        if (error != null) notice(error);
        else if (finalText.trim().isEmpty()) notice("The model returned no reply. Please try again.");
        generating = false;
        modules.endReply();
        changed();
    }

    /** Stops a reply in progress (used when the app is exited). */
    public void cancel() {
        try { engine.cancelGeneration(); } catch (Throwable ignored) { }
    }

    private synchronized void setAi(long key, String text, boolean pending) {
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).key == key) { items.set(i, new Item(key, Kind.AI, text, pending)); break; }
        }
        changed();
    }

    private synchronized void removeItem(long key) {
        for (int i = items.size() - 1; i >= 0; i--) if (items.get(i).key == key) { items.remove(i); break; }
    }

    private synchronized void dropNotices() {
        for (int i = items.size() - 1; i >= 0; i--) if (items.get(i).kind == Kind.NOTICE) items.remove(i);
    }

    private void notice(String text) {
        synchronized (this) {
            dropNotices();
            items.add(new Item(nextKey++, Kind.NOTICE, text, false));
        }
        changed();
    }
}
