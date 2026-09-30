package com.neonhud.app.core.engine;

/**
 * The only thing the rest of the app knows about the AI runtime.
 * UI, memory, topic detection and the module manager never touch a concrete model API,
 * so Gemma 4 E2B can later be swapped for another model by writing one new adapter.
 *
 * All methods block the calling thread; call them from a worker thread.
 */
public interface ModelEngine {

    /** Loads the model file into memory. Must throw on failure and leave the engine unloaded. */
    void load(String modelPath) throws Exception;

    /** Releases the model. Must be idempotent and never throw for "already unloaded". */
    void unload();

    boolean isLoaded();

    /**
     * Generates one reply, streaming pieces through {@code callback}, and returns when finished
     * (or after {@link #cancelGeneration()}). Throws on engine failure.
     */
    void generate(PromptPackage prompt, GenerationCallback callback) throws Exception;

    /** Asks a running {@link #generate} to stop early. Safe to call from another thread. */
    void cancelGeneration();
}
