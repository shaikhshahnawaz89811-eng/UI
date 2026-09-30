package com.neonhud.app.android

import android.content.Context
import com.google.ai.edge.litertlm.*
import com.neonhud.app.core.engine.GenerationCallback
import com.neonhud.app.core.engine.ModelEngine
import com.neonhud.app.core.engine.PromptPackage
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * The ONLY class that knows about LiteRT-LM / Gemma. Everything else talks to [ModelEngine], so replacing Gemma
 * with another model means writing one more adapter like this and changing one line in [App].
 *
 * Each reply gets a fresh Conversation built from the controlled prompt (system context + relevant memory +
 * recent turns), so the model never sees the whole database and the engine itself stays stateless.
 */
class GemmaEngine(private val context: Context, private val cacheDir: File) : ModelEngine {

    @Volatile private var engine: Engine? = null
    @Volatile private var cancelled = false
    @Volatile private var waiting: CountDownLatch? = null

    @Throws(Exception::class)
    override fun load(modelPath: String) {
        unload()
        cacheDir.mkdirs()
        val config = EngineConfig(
            modelPath = modelPath,
            backend = Backend.CPU(),            // most compatible; Backend.GPU() is faster on phones that support OpenCL
            cacheDir = cacheDir.path
        )
        val e = Engine(config)
        try {
            e.initialize()                      // blocking, several seconds: called from the module worker thread
        } catch (t: Throwable) {
            try { e.close() } catch (_: Throwable) { }
            throw t
        }
        engine = e
    }

    override fun unload() {
        cancelled = true
        waiting?.countDown()
        val e = engine
        engine = null
        if (e != null) {
            try { e.close() } catch (_: Throwable) { }
        }
    }

    override fun isLoaded(): Boolean = engine != null

    @Throws(Exception::class)
    override fun generate(prompt: PromptPackage, callback: GenerationCallback) {
        val e = engine ?: throw IllegalStateException("Gemma 4 E2B is not loaded")
        cancelled = false

        val history = ArrayList<Message>()
        for (turn in prompt.recentConversation) {
            history.add(if (turn.fromUser) Message.user(turn.text) else Message.model(turn.text))
        }
        val conversationConfig = ConversationConfig(
            systemInstruction = Contents.of(prompt.systemInstruction()),
            initialMessages = history,
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7)
        )

        val conversation = e.createConversation(conversationConfig)
        val latch = CountDownLatch(1)
        waiting = latch
        var failure: Throwable? = null
        try {
            conversation.sendMessageAsync(prompt.userMessage, object : MessageCallback {
                override fun onMessage(message: Message) {
                    if (cancelled) return
                    val piece = message.toString()
                    if (piece.isNotEmpty()) callback.onToken(piece)
                }

                override fun onDone() { latch.countDown() }

                override fun onError(throwable: Throwable) {
                    failure = throwable
                    latch.countDown()
                }
            })
            latch.await()
        } finally {
            waiting = null
            // If generation was cancelled the app is exiting; closing a conversation mid-decode is not safe, so leave it.
            if (!cancelled) {
                try { conversation.close() } catch (_: Throwable) { }
            }
        }
        failure?.let { if (!cancelled) throw it }
    }

    override fun cancelGeneration() {
        cancelled = true
        waiting?.countDown()
    }
}
