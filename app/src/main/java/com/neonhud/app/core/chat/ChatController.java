package com.neonhud.app.core.chat;

import com.neonhud.app.core.engine.Attachment;
import com.neonhud.app.core.engine.AttachmentLoader;
import com.neonhud.app.core.engine.GenerationCallback;
import com.neonhud.app.core.engine.ModelEngine;
import com.neonhud.app.core.engine.PromptPackage;
import com.neonhud.app.core.engine.ReadBudget;
import com.neonhud.app.core.engine.ReadSelection;
import com.neonhud.app.core.engine.SelectableAttachmentLoader;
import com.neonhud.app.core.engine.AttachmentReadCache;
import com.neonhud.app.core.memory.ConversationBrain;
import com.neonhud.app.core.memory.ConversationMessage;
import com.neonhud.app.core.memory.MemoryStore;
import com.neonhud.app.core.module.ModuleManager;
import com.neonhud.app.core.module.ModuleState;
import com.neonhud.app.core.skill.RouterContext;
import com.neonhud.app.core.skill.SkillPlan;
import com.neonhud.app.core.skill.SkillExecution;
import com.neonhud.app.core.skill.SkillTask;
import com.neonhud.app.core.skill.SkillPrompt;
import com.neonhud.app.core.skill.SkillRouter;
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
        public final List<Attachment> attachments;   // files shown on a USER bubble
        public final List<Attachment> outputFiles;   // real files created/edited by a skill (AI bubble only)
        public final long time;                      // send time of a USER bubble, 0 = unknown (old history)
        /** A short line while the reply is being prepared ("Searching the web..."); "" = none. Only on a pending AI item. */
        public final String status;
        /** Pages / pictures the app shows under a finished AI reply (real search results only; empty otherwise). */
        public final List<WebLink> links;
        public final List<WebPic> pics;
        Item(long key, Kind kind, String text, boolean pending) {
            this(key, kind, text, pending, Collections.<Attachment>emptyList(), Collections.<Attachment>emptyList(), 0L);
        }
        Item(long key, Kind kind, String text, boolean pending, List<Attachment> attachments, long time) {
            this(key, kind, text, pending, attachments, Collections.<Attachment>emptyList(), time);
        }
        Item(long key, Kind kind, String text, boolean pending, List<Attachment> attachments, List<Attachment> outputFiles, long time) {
            this(key, kind, text, pending, attachments, outputFiles, time, "", null, null);
        }
        Item(long key, Kind kind, String text, boolean pending, List<Attachment> attachments, long time,
             String status, List<WebLink> links, List<WebPic> pics) {
            this(key, kind, text, pending, attachments, Collections.<Attachment>emptyList(), time, status, links, pics);
        }
        Item(long key, Kind kind, String text, boolean pending, List<Attachment> attachments, List<Attachment> outputFiles, long time,
             String status, List<WebLink> links, List<WebPic> pics) {
            this.key = key; this.kind = kind; this.text = text; this.pending = pending;
            this.attachments = attachments;
            this.outputFiles = outputFiles == null ? Collections.<Attachment>emptyList() : outputFiles;
            this.time = time;
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
    private volatile SkillExecution skillExecution;
    private final AttachmentReadCache readCache = new AttachmentReadCache();
    // only touched on the single worker thread
    private String lastQuery = "", lastUserText = "";
    private Attachment lastOutput;

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
    /** Connects the deterministic file executor. Null keeps the read-only core behaviour. */
    public void setSkillExecution(SkillExecution execution) { skillExecution = execution; }

    /** The skill router (files / documents). The offline coding chat switches it off. */
    public void setSkillsEnabled(boolean on) { skillsEnabled = on; }

    private volatile boolean skillsEnabled = true;

    private boolean hasLastReply() {
        synchronized (this) {
            for (int i = items.size() - 1; i >= 0; i--) {
                Item it = items.get(i);
                if (it.kind == Kind.AI && !it.pending && !it.text.trim().isEmpty()) return true;
            }
        }
        return false;
    }

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

    /** Loads only the READ tasks, honoring explicit page/slide/sheet selection and the in-process read cache. */
    private List<Attachment> loadReadTasks(SkillPlan plan, List<Attachment> sources) {
        List<Attachment> out = new ArrayList<Attachment>();
        if (plan == null || sources == null || sources.isEmpty()) return out;
        for (SkillTask task : plan.tasks) {
            if (task == null || task.action != SkillTask.Action.READ) continue;
            int idx = task.fileIndex;
            if (idx < 0 || idx >= sources.size()) continue;
            Attachment source = sources.get(idx);
            Attachment loaded = readCache.get(source, task.selection);
            if (loaded == null && task.selection.isEmpty()) loaded = readCache.getLatest(source);
            if (loaded == null) loaded = loadOne(source, task.selection);
            if (loaded == null) continue;
            readCache.put(source, task.selection, loaded);
            out.add(loaded);
        }
        return out;
    }

    private Attachment loadOne(Attachment source, ReadSelection selection) {
        AttachmentLoader l = loader;
        if (l == null || source == null) return source;
        try {
            Attachment loaded;
            if (l instanceof SelectableAttachmentLoader) {
                loaded = ((SelectableAttachmentLoader) l).load(source, selection == null ? ReadSelection.none() : selection);
            } else {
                loaded = l.load(source);
            }
            if (loaded != null) return loaded;
            return source;
        } catch (Throwable t) {
            String m = t.getMessage();
            return source.loaded("ATTACHED FILE " + source.name + " COULD NOT BE READ"
                    + (m == null || m.isEmpty() ? "." : " (" + m + ")."), Collections.<byte[]>emptyList());
        }
    }

    private void runTurn(String text, List<Attachment> files, final long aiKey) {
        final StringBuilder reply = new StringBuilder();
        ConversationBrain.Turn turn = null;
        String error = null;
        WebTurn webTurn = null;
        SkillPlan plan = null;
        List<SkillTask> writeTasks = Collections.emptyList();
        try {
            turn = brain.beginTurn(storedText(text, files));
            plan = skillsEnabled
                    ? SkillRouter.route(text, files, new RouterContext(hasLastReply(), lastOutput, readCache.sources()))
                    : null;
            if (plan != null && plan.needsClarify()) {
                reply.append(plan.clarify);
                setAi(aiKey, plan.clarify, false);
                brain.finishTurn(turn, plan.clarify, false);
                generating = false;
                modules.endReply();
                changed();
                return;
            }
            writeTasks = plan == null ? Collections.<SkillTask>emptyList() : writeTasks(plan);
            boolean hasWrite = !writeTasks.isEmpty();
            boolean hasCreate = false;
            for (SkillTask t : writeTasks) if (t.action == SkillTask.Action.CREATE) hasCreate = true;

            // File work never becomes an accidental web search about the file name.
            if (!hasWrite) webTurn = searchIfWanted(text, files, aiKey);

            PromptPackage prompt = turn.prompt;
            if (plan != null) {
                String note = SkillPrompt.build(plan);
                if (!note.isEmpty()) prompt = prompt.withSkillContext(note);
            }
            List<Attachment> toShow = new ArrayList<Attachment>();
            if (!files.isEmpty()) {
                toShow.addAll(loadReadTasks(plan, files));
            } else if (plan != null && !plan.tasks.isEmpty()) {
                toShow.addAll(loadReadTasks(plan, readCache.sources()));
            }
            if (files.isEmpty() && lastOutput != null && usesLastOutput(plan)) {
                Attachment loadedLast = loadOne(lastOutput, ReadSelection.none());
                if (loadedLast != null) toShow.add(loadedLast);
            }
            if (webTurn != null && !webTurn.context.isEmpty()) prompt = prompt.withWebContext(webTurn.context);
            if (webTurn != null) toShow.addAll(webPictures(webTurn));
            toShow = new ArrayList<Attachment>(ReadBudget.apply(toShow));
            if (!toShow.isEmpty()) prompt = prompt.withAttachments(toShow);

            // EDIT-only is deterministic and does not need a model call. Normal chat and CREATE tasks do.
            if (!hasWrite || hasCreate) {
                final boolean createFlow = hasCreate;
                if (createFlow) setStatus(aiKey, "Preparing the requested file…");
                if (!cancelled) {
                    engine.generate(prompt, new GenerationCallback() {
                        @Override public void onToken(String delta) {
                            if (delta == null || delta.isEmpty()) return;
                            synchronized (reply) { reply.append(delta); }
                            // Marker syntax is an execution protocol, not chat text; keep it out of the visible bubble.
                            if (createFlow) setStatus(aiKey, "Generating file content…");
                            else setAi(aiKey, reply.toString(), true);
                        }
                    });
                }
            }
        } catch (Throwable e) {
            String m = e.getMessage();
            error = "Reply failed: " + (m == null || m.isEmpty() ? e.getClass().getSimpleName() : m);
        }

        final List<Attachment> outputFiles = new ArrayList<Attachment>();
        boolean skillWriteHandled = false;
        if (error == null && plan != null && !writeTasks.isEmpty() && skillExecution != null) {
            skillWriteHandled = true;
            StringBuilder skillReply = new StringBuilder();
            String modelText = reply.toString();
            for (int i = 0; i < writeTasks.size(); i++) {
                SkillTask task = writeTasks.get(i);
                int step = i + 1;
                int createOrdinal = 0;
                if (task.action == SkillTask.Action.CREATE) {
                    for (int j = 0; j < i; j++) {
                        SkillTask previous = writeTasks.get(j);
                        if (previous.action == SkillTask.Action.CREATE && previous.skill == task.skill) createOrdinal++;
                    }
                }
                setStatus(aiKey, "Step " + step + "/" + writeTasks.size() + ": " + stepLabel(task) + "…");
                SkillExecution.Result result = skillExecution.execute(task, modelText, files, lastOutput, createOrdinal);
                if (skillReply.length() > 0) skillReply.append('\n');
                skillReply.append("Step ").append(step).append('/').append(writeTasks.size()).append(": ")
                        .append(result.message);
                if (result.success && result.output != null) {
                    outputFiles.add(result.output);
                    lastOutput = result.output;
                }
            }
            for (String u : plan.unsupported) skillReply.append('\n').append("Not possible: ").append(u);
            for (String n : plan.notDone) skillReply.append('\n').append("Not done: ").append(n);
            reply.setLength(0);
            reply.append(skillReply.toString().trim());
            setAiWithOutputs(aiKey, reply.toString(), false, outputFiles);
        } else if (error == null && plan != null && plan.hasWriteTask() && skillExecution == null) {
            // Keep Stage 1's honesty guarantee if the Android wiring is accidentally missing.
            reply.setLength(0);
            reply.append("No file was created: file execution is not connected.");
            setAi(aiKey, reply.toString(), false);
        }

        final String finalText = reply.toString();
        final boolean fromWeb = webTurn != null && webTurn.ok();
        try {
            if (turn != null && !finalText.trim().isEmpty()) brain.finishTurn(turn, finalText, !fromWeb);
        } catch (Throwable ignored) { }
        if (finalText.trim().isEmpty()) {
            removeItem(aiKey);
        } else if (!skillWriteHandled && fromWeb && !webTurn.plan.readOnly) {
            finishAi(aiKey, finalText, webTurn.links, webTurn.pics);
        } else if (!skillWriteHandled) {
            setAi(aiKey, finalText, false);
        }
        if (error != null) notice(error);
        else if (finalText.trim().isEmpty()) notice("The model returned no reply. Please try again.");
        else if (!skillWriteHandled && webTurn != null && !webTurn.notice.isEmpty()) notice(webTurn.notice);
        generating = false;
        modules.endReply();
        changed();
    }

    private static List<SkillTask> writeTasks(SkillPlan plan) {
        List<SkillTask> out = new ArrayList<SkillTask>();
        for (SkillTask t : plan.tasks) if (t != null && t.writes()) out.add(t);
        return out;
    }

    private static boolean usesLastOutput(SkillPlan plan) {
        if (plan == null) return false;
        for (SkillTask t : plan.tasks) {
            if (t.source == SkillTask.Source.PREVIOUS_STEP) return true;
        }
        return false;
    }

    private static String stepLabel(SkillTask t) {
        String verb = t.action == SkillTask.Action.EDIT ? "Editing " : "Creating ";
        switch (t.skill) {
            case DOCX: return verb + "Word file";
            case XLSX: return verb + "Excel file";
            case PPTX: return verb + "PowerPoint file";
            case PDF_CREATOR: return verb + "PDF";
            default: return verb + t.skill.name();
        }
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
                    Calendar.getInstance().get(Calendar.YEAR));
            SearchPlan plan = w.plan(text, ctx);
            if (plan.search) {
                setStatus(aiKey, plan.readsLinks() ? STATUS_OPENING : STATUS_SEARCHING);
                if (!plan.query.isEmpty()) lastQuery = plan.query;
            }
            WebTurn t = w.prepare(plan);
            if (!text.isEmpty()) lastUserText = text;
            if (t.ok()) setStatus(aiKey, plan.readsLinks() ? STATUS_READING_PAGE : STATUS_READING);
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
            if (items.get(i).key == key) {
                Item it = items.get(i);
                items.set(i, new Item(key, Kind.AI, text, pending, it.attachments, it.outputFiles, 0L, "", it.links, it.pics));
                break;
            }
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
                items.set(i, new Item(key, Kind.AI, text, false, Collections.<Attachment>emptyList(), Collections.<Attachment>emptyList(), 0L, "", links, pics));
                break;
            }
        }
        changed();
    }

    private synchronized void setAiWithOutputs(long key, String text, boolean pending, List<Attachment> outputs) {
        List<Attachment> safe = outputs == null ? Collections.<Attachment>emptyList() : new ArrayList<Attachment>(outputs);
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).key == key) {
                items.set(i, new Item(key, Kind.AI, text, pending, Collections.<Attachment>emptyList(), safe, 0L, "", null, null));
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
