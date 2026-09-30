package com.neonhud.app.android

import com.neonhud.app.core.coder.CoderPrompt
import com.neonhud.app.core.coder.CoderSpec
import com.neonhud.app.core.engine.GenerationCallback
import com.neonhud.app.core.engine.ModelEngine
import com.neonhud.app.core.engine.PromptPackage
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import kotlinx.coroutines.runBlocking

/**
 * The ONLY class that knows about llama.cpp / the Qwen2.5-Coder GGUF. Everything else talks to [ModelEngine].
 *
 * The free llama-android API has no streaming and no message list: it takes one system prompt and one user prompt and
 * returns the whole reply. So [CoderPrompt] folds the recent conversation into the user prompt, and the reply appears
 * in the chat when it is finished (the chat shows "..." while the model writes).
 *
 * The runtime's model handle is not thread-safe; ModuleStateMachine guarantees that load / reply / unload never overlap.
 */
class CoderEngine : ModelEngine {

    /** Closures keep the library's model type out of this file: only Llama.loadModel / complete / releaseModel are used. */
    private class Session(val complete: (String, String, Int) -> String, val release: () -> Unit)

    @Volatile private var session: Session? = null
    @Volatile private var cancelled = false

    @Throws(Exception::class)
    override fun load(modelPath: String) {
        unload()
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
        val config = LlamaConfig(
            contextSize = CoderSpec.CONTEXT_TOKENS,
            threads = threads,
            temperature = 0.2f,          // code: low randomness
            topP = 0.9f,
            topK = 40
        )
        val model = runBlocking { Llama.loadModel(modelPath = modelPath, config = config) }   // blocking, several seconds
        session = Session(
            complete = { userPrompt, systemPrompt, maxTokens ->
                runBlocking {
                    Llama.complete(model, prompt = userPrompt, systemPrompt = systemPrompt, maxTokens = maxTokens).text
                }
            },
            release = { runBlocking { Llama.releaseModel(model) } }
        )
    }

    override fun unload() {
        cancelled = true
        val s = session
        session = null
        if (s != null) {
            try { s.release() } catch (_: Throwable) { }   // releasing twice is safe in the library
        }
    }

    override fun isLoaded(): Boolean = session != null

    @Throws(Exception::class)
    override fun generate(prompt: PromptPackage, callback: GenerationCallback) {
        val s = session ?: throw IllegalStateException("${CoderSpec.DISPLAY_NAME} is not loaded")
        cancelled = false
        val raw = s.complete(CoderPrompt.user(prompt), CoderPrompt.system(prompt), CoderSpec.MAX_REPLY_TOKENS)
        if (cancelled) return           // the app is exiting: nobody is waiting for this text
        val text = CoderPrompt.cleanReply(raw)
        if (text.isNotEmpty()) callback.onToken(text)
    }

    /** llama.cpp (free tier) cannot stop mid-reply; the result is simply dropped when it arrives. */
    override fun cancelGeneration() {
        cancelled = true
    }
}
