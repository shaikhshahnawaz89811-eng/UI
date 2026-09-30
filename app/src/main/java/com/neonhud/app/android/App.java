package com.neonhud.app.android;

import android.app.Application;
import android.content.Context;

import com.neonhud.app.core.chat.ChatController;
import com.neonhud.app.core.engine.ModelEngine;
import com.neonhud.app.core.memory.ConversationBrain;
import com.neonhud.app.core.memory.MemoryStore;
import com.neonhud.app.core.module.FileModelStorage;
import com.neonhud.app.core.module.ModelStorage;
import com.neonhud.app.core.module.ModuleManager;

import java.io.File;

/**
 * Process-wide singletons. The Activity is only a view: model, chat and memory live here, so leaving the app,
 * coming back or having the Activity destroyed never interrupts a load or a reply.
 */
public final class App extends Application {

    public static final String MODEL_FILE = "gemma-4-e2b.litertlm";
    /** Gemma 4 E2B is ~2.5 GB; anything far below this is certainly the wrong file. */
    private static final long MIN_MODEL_BYTES = 300L * 1024 * 1024;

    private static App instance;

    private ModelEngine engine;
    private ModelStorage storage;
    private ModuleManager modules;
    private MemoryStore memory;
    private ConversationBrain brain;
    private ChatController chat;

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        File modelDir = new File(getFilesDir(), "models");
        File engineCache = new File(getCacheDir(), "litert");

        // To swap the model later, replace this ONE line with another ModelEngine implementation.
        engine = new GemmaEngine(this, engineCache);

        storage = new FileModelStorage(modelDir, MODEL_FILE, ".litertlm", MIN_MODEL_BYTES, engineCache);
        modules = new ModuleManager(engine, storage, new PrefsStateStore(this));
        memory = new SqliteMemoryStore(this);
        brain = new ConversationBrain(memory, new ConversationBrain.Clock() {
            @Override public long now() { return System.currentTimeMillis(); }
        });
        chat = new ChatController(modules, brain, memory);
    }

    public static App get(Context c) { return (App) c.getApplicationContext(); }
    public static App instance() { return instance; }

    public ModuleManager modules() { return modules; }
    public ChatController chat() { return chat; }
    public ConversationBrain brain() { return brain; }
}
