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
import com.neonhud.app.core.search.TavilyKeyManager;

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

    private TavilyKeyManager tavily;

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
        chat = new ChatController(modules, brain, memory);

        // ------------------------------------------------ Qwen2.5-Coder 1.5B Instruct Q4_K_M (same flow, own everything)
        ModelEngine coderEngine = new CoderEngine();
        ModelStorage coderStorage = new FileModelStorage(modelDir, CoderSpec.MODEL_FILE, CoderSpec.REQUIRED_EXTENSION,
                CoderSpec.MIN_MODEL_BYTES, CoderSpec.DISPLAY_NAME, CoderSpec.GGUF_MAGIC);
        coderModules = new ModuleManager(coderEngine, coderStorage, new PrefsStateStore(this, "coder_module"),
                CoderSpec.DISPLAY_NAME, "coder-module");
        MemoryStore coderMemory = new SqliteMemoryStore(this, "coder_conversation_memory.db");
        ConversationBrain coderBrain = new ConversationBrain(coderMemory, new ConversationBrain.Clock() {
            @Override public long now() { return System.currentTimeMillis(); }
        }, CoderSpec.SYSTEM_BASE);
        coderChat = new ChatController(coderModules, coderBrain, coderMemory, CoderSpec.DISPLAY_NAME, "coder-chat");

        // ------------------------------------------------ Tavily API keys (Settings card: Add tests the key, Delete removes it)
        tavily = new TavilyKeyManager(new HttpTavilyClient(), new PrefsTavilyKeyStore(this));
    }

    public static App get(Context c) { return (App) c.getApplicationContext(); }
    public static App instance() { return instance; }

    public ModuleManager modules() { return modules; }
    public ChatController chat() { return chat; }
    public ConversationBrain brain() { return brain; }

    public ModuleManager coderModules() { return coderModules; }
    public ChatController coderChat() { return coderChat; }

    public TavilyKeyManager tavily() { return tavily; }

    /** Which chat the user is looking at (kept here so it survives the Activity being re-created). */
    public int chatMode() { return chatMode; }
    public void setChatMode(int mode) { chatMode = mode == MODE_CODER ? MODE_CODER : MODE_GEMMA; }
}
