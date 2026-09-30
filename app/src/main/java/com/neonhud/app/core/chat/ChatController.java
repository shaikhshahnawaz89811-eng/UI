package com.neonhud.app.core.chat;

import com.neonhud.app.core.engine.Attachment;
import com.neonhud.app.core.engine.AttachmentLoader;
import com.neonhud.app.core.engine.GenerationCallback;
import com.neonhud.app.core.engine.ModelEngine;
import com.neonhud.app.core.engine.PromptPackage;
import com.neonhud.app.core.memory.ConversationBrain;
import com.neonhud.app.core.memory.ConversationMessage;
import com.neonhud.app.core.memory.MemoryStore;
import com.neonhud.app.core.module.ModuleManager;
import com.neonhud.app.core.module.ModuleState;
import com.neonhud.app.core.web.PlanContext;
import com.neonhud.app.core.web.SearchPlan;
import com.neonhud.app.core.web.WebLink;
import com.neonhud.app.core.web.WebMedia;
import com.neonhud.app.core.web.WebMode;
import com.neonhud.app.core.web.WebPic;
import com.neonhud.app.core.web.WebSearchService;
import com.neonhud.app.core.web.WebTurn;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
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
        public final List<Attachment> attachments;   // files shown on a USER bubble (empty otherwise)
        public final long time;                      // send time of a USER bubble, 0 = unknown (old history)
        /** A short line while the reply is being prepared ("Searching the web..."); "" = none. Only on a pending AI item. */
        public final String status;
        /** Pages / pictures the app shows under a finished AI reply (real search results only; empty otherwise). */
        public final List<WebLink> links;
        public final List<WebPic> pics;
        Item(long key, Kind kind, String text, boolean pending) {
            this(key, kind, text, pending, Collections.<Attachment>emptyList(), 0L);
        }
        Item(long key, Kind kind, String text, boolean pending, List<Attachment> attachments, long time) {
            this(key, kind, text, pending, attachments, time, "", null, null);
        }
        Item(long key, Kind kind, String text, boolean pending, List<Attachment> attachments, long time,
             String status, List<WebLink> links, List<WebPic> pics) {
            this.key = key; this.kind = kind; this.text = text; this.pending = pending;
            this.attachments = attachments; this.time = time;
            this.status = status == null ? "" : status;
            this.links = links == null ? Collections.<WebLink>emptyList() : links;
            this.pics = pics == null ? Collections.<WebPic>emptyList() : pics;
        }
    }

    public enum SendResult { ACCEPTED, EMPTY, MODEL_NOT_READY, BUSY }

    public interface Listener {
        /** The list changed (may fire very often while streaming; may be called from any thread). */
        void onChatChanged();
    }

    private static final int SCREEN_HISTORY = 200;
    public static final int MAX_INPUT_CHARS = 4000;
    public static final int MAX_ATTACHMENTS = 5;
    public static final String STATUS_SEARCHING = "Searching the web\u2026";
    public static final String STATUS_READING = "Reading the results\u2026";
    /** A link the user pasted is being opened / its text read. */
    public static final String STATUS_OPENING = "Opening the link\u2026";
    public static final String STATUS_READING_PAGE = "Reading the page\u2026";
    /** Used when the user sends files without typing anything. */
    static final String DEFAULT_FILE_ASK = "Please look at the attached file(s) and tell me what they contain.";

    private final ModuleManager modules;
    private final ModelEngine engine;
    private final ConversationBrain brain;
    private final MemoryStore store;
    private final ExecutorService worker;
    private final String displayName;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    private final List<Item> items = new ArrayList<Item>();
    private long nextKey = 1;
    private volatile boolean generating;
    private volatile boolean cancelled;
    private volatile AttachmentLoader loader;
    private volatile WebSearchService web;
    // only touched on the single worker thread
    private String lastQuery = "", lastUserText = "";
    private List<String> lastPageUrls = Collections.emptyList();

    public ChatController(ModuleManager modules, ConversationBrain brain, MemoryStore store) {
        this(modules, brain, store, modules.displayName(), "gemma-chat");
    }

    public ChatController(ModuleManager modules, ConversationBrain brain, MemoryStore store,
                          String displayName, final String threadName) {
        this.displayName = displayName;
        this.modules = modules;
        this.engine = modules.engine();
        this.brain = brain;
        this.store = store;
        this.worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, threadName);
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
    /** The Android side plugs in how attached files are read; without it files are listed by name only. */
    public void setAttachmentLoader(AttachmentLoader l) { loader = l; }
    /** Plugs in internet search. Without it (the coding chat) this chat never goes online. */
    public void setWebSearch(WebSearchService w) { web = w; }

    public synchronized List<Item> items() { return new ArrayList<Item>(items); }

    private void changed() {
        for (Listener l : listeners) {
            try { l.onChatChanged(); } catch (RuntimeException ignored) { }
        }
    }

    public SendResult send(final String rawText) {
        return send(rawText, null);
    }

    public SendResult send(final String rawText, List<Attachment> attached) {
        final String text = rawText == null ? "" : rawText.trim();
        final List<Attachment> files = attached == null
                ? Collections.<Attachment>emptyList()
                : new ArrayList<Attachment>(attached.subList(0, Math.min(attached.size(), MAX_ATTACHMENTS)));
        if (text.isEmpty() && files.isEmpty()) return SendResult.EMPTY;
        if (generating) return SendResult.BUSY;
        if (!modules.tryBeginReply()) {
            if (modules.state() != ModuleState.LOADED) {
                notice(displayName + " is not loaded. Open Settings \u2699 and Import / Load the model.");
                return SendResult.MODEL_NOT_READY;
            }
            return SendResult.BUSY;
        }
        generating = true;
        cancelled = false;
        final String clipped = text.length() > MAX_INPUT_CHARS ? text.substring(0, MAX_INPUT_CHARS) : text;
        final long aiKey;
        synchronized (this) {
            dropNotices();
            items.add(new Item(nextKey++, Kind.USER, clipped, false, files, System.currentTimeMillis()));
            aiKey = nextKey++;
            items.add(new Item(aiKey, Kind.AI, "", true));
        }
        changed();
        worker.execute(new Runnable() {
            @Override public void run() { runTurn(clipped, files, aiKey); }
        });
        return SendResult.ACCEPTED;
    }

    /** The text the memory layer stores: what was typed, plus the names of the files (their content is never stored). */
    private static String storedText(String typed, List<Attachment> files) {
        if (files.isEmpty()) return typed;
        StringBuilder sb = new StringBuilder(typed.isEmpty() ? DEFAULT_FILE_ASK : typed).append("\n[Attached: ");
        for (int i = 0; i < files.size(); i++) sb.append(i == 0 ? "" : ", ").append(files.get(i).name);
        return sb.append(']').toString();
    }

    /** Reads every attached file; one that cannot be read is still passed on, with a note, so the model can say so. */
    private List<Attachment> loadAll(List<Attachment> files) {
        List<Attachment> out = new ArrayList<Attachment>();
        AttachmentLoader l = loader;
        for (Attachment a : files) {
            if (l == null) { out.add(a); continue; }
            try {
                out.add(l.load(a));
            } catch (Throwable t) {
                String m = t.getMessage();
                out.add(a.loaded("ATTACHED FILE " + a.name + " COULD NOT BE READ"
                        + (m == null || m.isEmpty() ? "." : " (" + m + ")."), Collections.<byte[]>emptyList()));
            }
        }
        return out;
    }

    private void runTurn(String text, List<Attachment> files, final long aiKey) {
        final StringBuilder reply = new StringBuilder();
        ConversationBrain.Turn turn = null;
        String error = null;
        WebTurn webTurn = null;
        try {
            turn = brain.beginTurn(storedText(text, files));
            webTurn = searchIfWanted(text, files, aiKey);            // never throws; null = no web for this chat
            PromptPackage prompt = turn.prompt;
            if (webTurn != null && !webTurn.context.isEmpty()) prompt = prompt.withWebContext(webTurn.context);
            List<Attachment> toShow = new ArrayList<Attachment>();
            if (!files.isEmpty()) toShow.addAll(loadAll(files));
            if (webTurn != null) toShow.addAll(webPictures(webTurn));      // pictures / PDF pages from the web, for the vision model only
            if (!toShow.isEmpty()) prompt = prompt.withAttachments(toShow);
            if (!cancelled) {
                engine.generate(prompt, new GenerationCallback() {
                    @Override public void onToken(String delta) {
                        if (delta == null || delta.isEmpty()) return;
                        synchronized (reply) { reply.append(delta); }
                        setAi(aiKey, reply.toString(), true);
                    }
                });
            }
        } catch (Throwable e) {
            String m = e.getMessage();
            error = "Reply failed: " + (m == null || m.isEmpty() ? e.getClass().getSimpleName() : m);
        }
        final String finalText = reply.toString();
        final boolean fromWeb = webTurn != null && webTurn.ok();
        try {
            // an answer built from internet results is kept in the conversation but never turned into long-term memories
            if (turn != null && !finalText.trim().isEmpty()) brain.finishTurn(turn, finalText, !fromWeb);
        } catch (Throwable ignored) {
            // memory write failure must not lose the reply the user already sees
        }
        if (finalText.trim().isEmpty()) {
            removeItem(aiKey);
        } else if (fromWeb && !webTurn.plan.readOnly) {
            finishAi(aiKey, finalText, webTurn.links, webTurn.pics);
        } else {
            setAi(aiKey, finalText, false);
        }
        if (error != null) notice(error);
        else if (finalText.trim().isEmpty()) notice("The model returned no reply. Please try again.");
        else if (webTurn != null && !webTurn.notice.isEmpty()) notice(webTurn.notice);
        generating = false;
        modules.endReply();
        changed();
    }

    /** Web pictures and PDF pages as attachments of THIS reply only: they are not shown on the user's bubble and never stored. */
    private static List<Attachment> webPictures(WebTurn t) {
        List<Attachment> out = new ArrayList<Attachment>();
        for (WebMedia m : t.media) {
            out.add(new Attachment(m.pdf ? Attachment.Kind.PDF : Attachment.Kind.IMAGE, m.label, -1, m.url)
                    .loaded(m.note, m.jpegs));
        }
        return out;
    }

    /**
     * The web step of one message: plan, show "Searching..." only when a search really happens, search, and remember
     * what was searched so "uski photo dikhao" can find its subject. Any failure becomes a WebTurn with a reason.
     */
    private WebTurn searchIfWanted(String text, List<Attachment> files, long aiKey) {
        WebSearchService w = web;
        if (w == null) return null;
        try {
            PlanContext ctx = new PlanContext(WebMode.AUTO, !files.isEmpty(), lastQuery, lastUserText,
                    lastPageUrls, Calendar.getInstance().get(Calendar.YEAR));
            SearchPlan plan = w.plan(text, ctx);
            if (plan.search) {
                setStatus(aiKey, plan.readsLinks() ? STATUS_OPENING : STATUS_SEARCHING);
                if (!plan.query.isEmpty()) lastQuery = plan.query;
            }
            WebTurn t = w.prepare(plan);
            if (!text.isEmpty()) lastUserText = text;
            if (t.ok()) {
                if (plan.readsLinks() && !t.issues.isEmpty()) { /* keep prior page cache; issues are shown in the reply context */ }
                if (plan.readsLinks() && !plan.urls.isEmpty()) lastPageUrls = new ArrayList<String>(plan.urls);
                setStatus(aiKey, plan.readsLinks() ? STATUS_READING_PAGE : STATUS_READING);
            }
            return t;
        } catch (Throwable e) {
            return null;                                              // the web layer must never break a normal reply
        }
    }

    /** Stops a reply in progress (used when the app is exited). */
    public void cancel() {
        cancelled = true;
        try { engine.cancelGeneration(); } catch (Throwable ignored) { }
    }

    private synchronized void setAi(long key, String text, boolean pending) {
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).key == key) { items.set(i, new Item(key, Kind.AI, text, pending)); break; }
        }
        changed();
    }

    /** The waiting line under a reply that has no text yet. */
    private synchronized void setStatus(long key, String status) {
        for (int i = items.size() - 1; i >= 0; i--) {
            Item it = items.get(i);
            if (it.key == key) {
                if (it.text.isEmpty()) items.set(i, new Item(key, Kind.AI, "", true, it.attachments, 0L, status, null, null));
                break;
            }
        }
        changed();
    }

    /** The finished reply together with the pages and pictures that belong under it. */
    private synchronized void finishAi(long key, String text, List<WebLink> links, List<WebPic> pics) {
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).key == key) {
                items.set(i, new Item(key, Kind.AI, text, false, Collections.<Attachment>emptyList(), 0L, "", links, pics));
                break;
            }
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
