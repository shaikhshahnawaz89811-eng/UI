package com.neonhud.app.android;

import android.app.Application;
import android.content.Context;

import com.neonhud.app.core.chat.ChatController;
import com.neonhud.app.core.coder.CoderSpec;
import com.neonhud.app.core.engine.ModelEngine;
import com.neonhud.app.core.memory.ConversationBrain;
import com.neonhud.app.core.memory.MemoryStore;
import com.neonhud.app.core.module.FileModelStorage;
import com.neonhud.app.core.module.ModelStorage;
import com.neonhud.app.core.module.ModuleManager;
import com.neonhud.app.core.module.ModuleState;
import com.neonhud.app.core.module.ModuleSnapshot;
import com.neonhud.app.core.module.ModelRuntimeCoordinator;
import com.neonhud.app.core.search.TavilyKeyManager;
import com.neonhud.app.core.web.HttpFetcher;
import com.neonhud.app.core.web.HttpWebApi;
import com.neonhud.app.core.web.MediaReader;
import com.neonhud.app.core.web.WebSearchService;
import com.neonhud.app.core.skill.SkillRegistry;
import com.neonhud.app.core.skill.SkillExecution;
import com.neonhud.app.core.office.DocxSkill;
import com.neonhud.app.core.office.XlsxSkill;
import com.neonhud.app.core.office.PptxSkill;

import java.io.File;

/**
 * Process-wide singletons. The Activity is only a view: models, chats and memories live here, so leaving the app,
 * coming back or having the Activity destroyed never interrupts a load or a reply.
 *
 * Two independent modules, each with its own engine, file, state machine, chat and memory database:
 *   - Gemma 4 E2B (LiteRT-LM, .litertlm)                     -> general assistant
 *   - Qwen2.5-Coder 1.5B Instruct Q4_K_M (llama.cpp, .gguf)  -> coding assistant
 */
public final class App extends Application {

    public static final int MODE_GEMMA = 0;
    public static final int MODE_CODER = 1;

    public static final String MODEL_FILE = "gemma-4-e2b.litertlm";
    /** Gemma 4 E2B is ~2.5 GB; anything far below this is certainly the wrong file. */
    private static final long MIN_MODEL_BYTES = 300L * 1024 * 1024;

    private static App instance;

    private ModuleManager modules;
    private ConversationBrain brain;
    private ChatController chat;

    private ModuleManager coderModules;
    private ChatController coderChat;
    private ModelRuntimeCoordinator runtime;

    private TavilyKeyManager tavily;
    private PrefsWebSettings webSettings;
    private WebSearchService webSearch;
    private SkillRegistry skillRegistry;
    private PdfCreator pdfCreator;
    private DocxSkill docxSkill;
    private XlsxSkill xlsxSkill;
    private PptxSkill pptxSkill;

    private volatile int chatMode = MODE_GEMMA;

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        File modelDir = new File(getFilesDir(), "models");

        // ------------------------------------------------ Gemma 4 E2B (unchanged behaviour)
        File engineCache = new File(getCacheDir(), "litert");
        // To swap the model later, replace this ONE line with another ModelEngine implementation.
        ModelEngine engine = new GemmaEngine(this, engineCache);
        ModelStorage storage = new FileModelStorage(modelDir, MODEL_FILE, ".litertlm", MIN_MODEL_BYTES, engineCache);
        modules = new ModuleManager(engine, storage, new PrefsStateStore(this));
        MemoryStore memory = new SqliteMemoryStore(this);
        brain = new ConversationBrain(memory, new ConversationBrain.Clock() {
            @Override public long now() { return System.currentTimeMillis(); }
        });
        skillRegistry = new SkillRegistry();
        pdfCreator = new PdfCreator();
        docxSkill = new DocxSkill();
        xlsxSkill = new XlsxSkill();
        pptxSkill = new PptxSkill();
        File skillOutputDir = new File(getFilesDir(), "skill-outputs");
        SkillOutputBridge skillBridge = new SkillOutputBridge(this, skillOutputDir);
        SkillExecution skillExecution = new SkillExecution(docxSkill, xlsxSkill, pptxSkill, pdfCreator,
                skillBridge, skillBridge, skillOutputDir);

        // Both model files can be imported, but one shared runtime owns RAM and serializes model handoff.
        // The coordinator is created before either chat so every real Android reply uses the exclusive lifecycle.
        ModelEngine coderEngine = new CoderEngine();
        ModelStorage coderStorage = new FileModelStorage(modelDir, CoderSpec.MODEL_FILE, CoderSpec.REQUIRED_EXTENSION,
                CoderSpec.MIN_MODEL_BYTES, CoderSpec.DISPLAY_NAME, CoderSpec.GGUF_MAGIC);
        coderModules = new ModuleManager(coderEngine, coderStorage, new PrefsStateStore(this, "coder_module"),
                CoderSpec.DISPLAY_NAME, "coder-module");
        runtime = new ModelRuntimeCoordinator(modules, coderModules);
        modules.addListener(new ModuleManager.Listener() {
            @Override public void onModuleChanged(ModuleSnapshot snapshot) {
                // Import is the only user action that needs a default runtime kickoff. Coder remains imported-only.
                if (snapshot.state == ModuleState.IMPORTED && snapshot.inFlight == null) {
                    runtime.ensureGemmaLoadedAsync(null);
                }
            }
        });

        chat = new ChatController(modules, brain, memory, "Gemma 4 E2B", "gemma-chat", runtime,
                ModelRuntimeCoordinator.Target.GEMMA);
        chat.setSkillExecution(skillExecution);
        chat.setAttachmentLoader(new AttachmentReader(this));     // images / PDF / ZIP / video files the user attaches with "+"

        // ------------------------------------------------ Qwen2.5-Coder 1.5B Instruct Q4_K_M
        MemoryStore coderMemory = new SqliteMemoryStore(this, "coder_conversation_memory.db");
        ConversationBrain coderBrain = new ConversationBrain(coderMemory, new ConversationBrain.Clock() {
            @Override public long now() { return System.currentTimeMillis(); }
        }, CoderSpec.SYSTEM_BASE);
        coderChat = new ChatController(coderModules, coderBrain, coderMemory, CoderSpec.DISPLAY_NAME, "coder-chat", runtime,
                ModelRuntimeCoordinator.Target.CODER);
        coderChat.setSkillExecution(skillExecution);
        coderChat.setAttachmentLoader(new AttachmentReader(this));
        coderChat.setSkillsEnabled(true);

        // ------------------------------------------------ Tavily API keys (Settings card: Add tests the key, Delete removes it)
        tavily = new TavilyKeyManager(new HttpTavilyClient(), new PrefsTavilyKeyStore(this));

        // ------------------------------------------------ Web search for the general chat (Settings: Auto / Always / Off).
        // The coding chat stays fully offline: it is never given a search service.
        webSettings = new PrefsWebSettings(this);
        webSearch = new WebSearchService(new HttpWebApi(), tavily, webSettings, new WebSearchService.Clock() {
            @Override public long now() { return System.currentTimeMillis(); }
        });
        // Phase 2: pictures and PDFs behind links (and web pictures the user asked to "read") are downloaded directly and
        // handed to the vision model; pages are read through Tavily Extract. Both use only public http(s) addresses.
        webSearch.setMedia(new MediaReader(new HttpFetcher(), new WebMediaDecoder(this)));
        chat.setWebSearch(webSearch);

        // Cold start rule: imported models stay offline; only Gemma is automatically brought into RAM.
        if (modules.state() == ModuleState.IMPORTED || modules.state() == ModuleState.UNLOADED) {
            runtime.ensureGemmaLoadedAsync(null);
        }
    }

    public static App get(Context c) { return (App) c.getApplicationContext(); }
    public static App instance() { return instance; }

    public ModuleManager modules() { return modules; }
    public ChatController chat() { return chat; }
    public ConversationBrain brain() { return brain; }

    public ModuleManager coderModules() { return coderModules; }
    public ChatController coderChat() { return coderChat; }
    public ModelRuntimeCoordinator runtime() { return runtime; }

    public TavilyKeyManager tavily() { return tavily; }
    public PrefsWebSettings webSettings() { return webSettings; }
    public WebSearchService webSearch() { return webSearch; }
    public SkillRegistry skills() { return skillRegistry; }
    public PdfCreator pdfCreator() { return pdfCreator; }
    public DocxSkill docxSkill() { return docxSkill; }
    public XlsxSkill xlsxSkill() { return xlsxSkill; }
    public PptxSkill pptxSkill() { return pptxSkill; }

    /** Which chat the user is looking at (kept here so it survives the Activity being re-created). */
    public int chatMode() { return chatMode; }
    public void setChatMode(int mode) { chatMode = mode == MODE_CODER ? MODE_CODER : MODE_GEMMA; }
}
