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

    private companion object {
        // Small on-device models follow a reminder placed right next to the question far better than one in the system slot.
        const val REPLY_HINT = "\n\n[EXECUTION CONTRACT: Execute the CURRENT USER MESSAGE. If it says sikhao/teach, start teaching immediately. If it says code likho/full code/pura code/banao, actually provide the requested code or artifact, not a promise or explanation of capability. If it corrects the previous reply with nahi/no, follow the new action immediately. If it says haan/yes/karo/yahi/continue, resolve it from the immediately preceding user+assistant exchange and move forward. If there are multiple questions/tasks, answer every one in order. For a file CREATE request, follow the exact SKILLS NOTE marker format; place [[SKILL_FILE ...]] and [[END_SKILL_FILE]] on their own lines and do not replace them with a prose promise. Preserve exact names, acronyms, numbers, model names and programming languages. Never silently replace a user term. Do not restart with a generic introduction when the user asked to continue. If I write Hindi/Hinglish, reply in Hindi using ONLY English letters, never Devanagari. Never claim you created, saved or edited a file unless a SKILLS NOTE says it exists. No ** or ### symbols.]"
    }

    @Volatile private var engine: Engine? = null
    @Volatile private var visionReady = false      // false when this phone could not start the image input
    @Volatile private var cancelled = false
    @Volatile private var waiting: CountDownLatch? = null

    @Throws(Exception::class)
    override fun load(modelPath: String) {
        unload()
        cacheDir.mkdirs()
        // First try with image input (for attached pictures / PDF pages); if this phone cannot start it, load plain text-only.
        var failure: Throwable? = null
        for (vision in listOf<Backend?>(Backend.CPU(), null)) {
            val config = EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(),        // most compatible; Backend.GPU() is faster on phones that support OpenCL
                visionBackend = vision,
                cacheDir = cacheDir.path
            )
            val e = Engine(config)
            try {
                e.initialize()                  // blocking, several seconds: called from the module worker thread
                engine = e
                visionReady = vision != null
                return
            } catch (t: Throwable) {
                try { e.close() } catch (_: Throwable) { }
                failure = t
            }
        }
        throw failure ?: IllegalStateException("Gemma 4 E2B could not be loaded")
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
            samplerConfig = SamplerConfig(topK = 24, topP = 0.90, temperature = 0.25)
        )

        val conversation = e.createConversation(conversationConfig)
        // Attached pictures / PDF pages go before the textual question. Every image is explicitly labelled so the
        // model can distinguish page/frame/image N from another attachment when several are present.
        val parts = ArrayList<Content>()
        var imageNumber = 0
        if (visionReady) {
            for (a in prompt.attachments) {
                for (i in a.images.indices) {
                    val label = if (i < a.imageLabels.size) a.imageLabels[i] else "image ${i + 1}"
                    imageNumber += 1
                    parts.add(Content.Text("[IMAGE $imageNumber | file=${a.name} | $label]"))
                    parts.add(Content.ImageBytes(a.images[i]))
                }
            }
        }
        val text = StringBuilder()
        if (imageNumber > 0 && !visionReady) {
            text.append("[NOTE: one or more pictures/PDF pages were meant to be shown, but image input is not available on this phone. Tell the user you cannot see them.]\n\n")
        }
        for (a in prompt.attachments) if (a.text.isNotEmpty()) text.append(a.text).append("\n\n")
        // Internet results for THIS message only (cleaned and shortened by the web layer); placed next to the question.
        if (prompt.webContext.isNotEmpty()) text.append(prompt.webContext).append("\n\n")
        // Skill-router note for THIS message (e.g. "no file can be created yet"); empty for most messages.
        if (prompt.skillContext.isNotEmpty()) text.append(prompt.skillContext).append("\n\n")
        text.append(prompt.userMessage).append(REPLY_HINT)
        parts.add(Content.Text(text.toString()))

        val latch = CountDownLatch(1)
        waiting = latch
        var failure: Throwable? = null
        try {
            conversation.sendMessageAsync(Contents.of(*parts.toTypedArray()), object : MessageCallback {
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
