package tests;

import com.neonhud.app.core.chat.ChatController;
import com.neonhud.app.core.coder.CoderPrompt;
import com.neonhud.app.core.coder.CoderSpec;
import com.neonhud.app.core.engine.Attachment;
import com.neonhud.app.core.engine.PromptPackage;
import com.neonhud.app.core.memory.ConversationBrain;
import com.neonhud.app.core.memory.InMemoryStore;
import com.neonhud.app.core.module.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The Qwen2.5-Coder module: same Import/Load/Unload/Delete rules as Gemma, fully independent of it. */
final class CoderModuleTests {

    private static final ConversationBrain.Clock CLOCK = new ConversationBrain.Clock() {
        public long now() { return System.currentTimeMillis(); }
    };

    static void run() throws Exception {
        specConstants();
        independentModules();
        coderFlowRules();
        ggufStorage();
        coderChatIsSeparate();
        promptBuilder();
    }

    // ---------------------------------------------------------------- the model we were asked for
    static void specConstants() {
        T.section("coder: spec constants");
        T.eq("Qwen2.5-Coder 1.5B Instruct Q4_K_M", CoderSpec.DISPLAY_NAME, "model name exactly as requested");
        T.check(CoderSpec.MODEL_FILE.endsWith(".gguf"), "stored as .gguf");
        T.check(CoderSpec.MIN_MODEL_BYTES > 400L * 1024 * 1024, "0.5B model (~400 MB) would be rejected");
        T.check(CoderSpec.MIN_MODEL_BYTES < 1000L * 1024 * 1024, "real 1.5B Q4_K_M file (~1 GB) is accepted");
        T.eq("GGUF", new String(CoderSpec.GGUF_MAGIC), "magic bytes");
        T.check(CoderSpec.CONTEXT_TOKENS > CoderSpec.MAX_REPLY_TOKENS, "reply limit fits in the context window");
    }

    private static Object[] newCoderModule(Fakes.FakeEngine e, Fakes.FakeStorage s, Fakes.MemStateStore st) {
        ModuleManager mm = new ModuleManager(e, s, st, CoderSpec.DISPLAY_NAME, "coder-module");
        return new Object[]{mm};
    }

    // ---------------------------------------------------------------- two modules never touch each other
    static void independentModules() throws Exception {
        T.section("coder: independent from Gemma");
        Fakes.FakeEngine ge = new Fakes.FakeEngine(), ce = new Fakes.FakeEngine();
        Fakes.FakeStorage gs = new Fakes.FakeStorage(), cs = new Fakes.FakeStorage();
        ModuleManager gemma = new ModuleManager(ge, gs, new Fakes.MemStateStore(null));
        ModuleManager coder = new ModuleManager(ce, cs, new Fakes.MemStateStore(null), CoderSpec.DISPLAY_NAME, "coder-module");

        T.eq(ModuleState.NOT_IMPORTED, coder.state(), "coder starts Not Imported");
        gemma.requestImport(Fakes.src("g.litertlm", 5)); Fakes.awaitIdle(gemma);
        gemma.requestLoad(); Fakes.awaitIdle(gemma);
        T.eq(ModuleState.LOADED, gemma.state(), "gemma loaded");
        T.eq(ModuleState.NOT_IMPORTED, coder.state(), "coder untouched by gemma actions");
        T.check(!coder.snapshot().canLoad && !coder.snapshot().canUnload && !coder.snapshot().canDelete && coder.snapshot().canImport,
                "coder buttons: only Import");

        coder.requestImport(Fakes.src("q.gguf", 5)); Fakes.awaitIdle(coder);
        coder.requestLoad(); Fakes.awaitIdle(coder);
        T.eq(ModuleState.LOADED, coder.state(), "coder loaded");
        T.eq(1, ge.loadCalls.get(), "gemma engine loaded exactly once");
        T.eq(1, ce.loadCalls.get(), "coder engine loaded exactly once");

        coder.requestUnload(); Fakes.awaitIdle(coder);
        T.eq(ModuleState.UNLOADED, coder.state(), "coder unloaded");
        T.eq(ModuleState.LOADED, gemma.state(), "gemma still loaded after coder unload");
        T.eq(0, ge.unloadCalls.get(), "gemma engine never unloaded");
        coder.requestDelete(); Fakes.awaitIdle(coder);
        T.eq(ModuleState.NOT_IMPORTED, coder.state(), "coder deleted");
        T.check(!cs.present && gs.present, "only the coder file was removed");
        T.eq(ModuleState.LOADED, gemma.state(), "gemma still loaded after coder delete");
        T.eq(0, ge.violations.get() + ce.violations.get(), "no engine misuse");
    }

    // ---------------------------------------------------------------- the same rules: Import -> Load -> Unload -> Delete
    static void coderFlowRules() throws Exception {
        T.section("coder: same Load / Unload / Delete rules");
        Fakes.FakeEngine e = new Fakes.FakeEngine();
        Fakes.FakeStorage s = new Fakes.FakeStorage();
        ModuleManager m = new ModuleManager(e, s, new Fakes.MemStateStore(null), CoderSpec.DISPLAY_NAME, "coder-module");
        T.check(!m.requestLoad().accepted, "load before import refused");
        T.check(!m.requestDelete().accepted, "delete before import refused");
        T.check(m.requestImport(Fakes.src("q.gguf", 5)).accepted, "import accepted");
        Fakes.awaitIdle(m);
        T.check(!m.requestImport(Fakes.src("q.gguf", 5)).accepted, "second import refused");
        T.check(!m.requestDelete().accepted, "delete while only imported refused");
        T.check(m.requestLoad().accepted, "load accepted");
        Fakes.awaitIdle(m);
        T.check(!m.requestLoad().accepted, "second load refused");
        T.check(!m.requestDelete().accepted, "delete while loaded refused (logic, not just UI)");
        T.check(m.requestUnload().accepted, "unload accepted");
        Fakes.awaitIdle(m);
        T.check(!m.requestUnload().accepted, "second unload refused");
        T.check(m.requestLoad().accepted, "load again after unload accepted");
        Fakes.awaitIdle(m);
        m.requestUnload(); Fakes.awaitIdle(m);
        T.check(m.requestDelete().accepted, "delete after unload accepted");
        Fakes.awaitIdle(m);
        T.eq(ModuleState.NOT_IMPORTED, m.state(), "back to Not Imported after delete");
        T.eq(CoderSpec.DISPLAY_NAME + " has been removed.", m.snapshot().message, "delete message names the coder model");
        T.eq(0, e.violations.get(), "no engine misuse");

        // cold start: persisted LOADED must come back UNLOADED (nothing is in memory after a restart)
        Fakes.FakeStorage s2 = new Fakes.FakeStorage(); s2.present = true;
        ModuleManager cold = new ModuleManager(new Fakes.FakeEngine(), s2, new Fakes.MemStateStore(ModuleState.LOADED),
                CoderSpec.DISPLAY_NAME, "coder-module");
        T.eq(ModuleState.UNLOADED, cold.state(), "restart: coder Loaded -> Unloaded");
    }

    // ---------------------------------------------------------------- real file storage with the GGUF header check
    static void ggufStorage() throws Exception {
        T.section("coder: GGUF file storage");
        File dir = Files.createTempDirectory("coder-test").toFile();
        final long min = 1024;
        FileModelStorage st = new FileModelStorage(dir, CoderSpec.MODEL_FILE, CoderSpec.REQUIRED_EXTENSION, min,
                CoderSpec.DISPLAY_NAME, CoderSpec.GGUF_MAGIC);

        T.check(!st.isModelPresent(), "nothing present at start");
        // wrong extension -> names the coder model, not Gemma
        try { st.importModel(bytes("model.litertlm", "GGUF", 4096), null); T.check(false, "wrong extension must be refused"); }
        catch (IOException ex) {
            T.check(ex.getMessage().contains(CoderSpec.DISPLAY_NAME) && !ex.getMessage().contains("Gemma"), "wrong-extension message names the coder: " + ex.getMessage());
        }
        // too small
        try { st.importModel(bytes("q.gguf", "GGUF", 100), null); T.check(false, "too small must be refused"); }
        catch (IOException ex) { T.check(ex.getMessage().contains(CoderSpec.DISPLAY_NAME), "too-small message names the coder"); }
        // right size, wrong header (some other file renamed to .gguf)
        try { st.importModel(bytes("q.gguf", "JUNK", 4096), null); T.check(false, "non-GGUF content must be refused"); }
        catch (IOException ex) { T.check(ex.getMessage().contains("not a valid"), "bad header refused: " + ex.getMessage()); }
        T.check(!st.isModelPresent(), "nothing kept after refused imports");
        T.check(!new File(dir, CoderSpec.MODEL_FILE + ".part").exists(), "no .part leftover after refusal");

        // good file
        final int[] last = {-1};
        st.importModel(bytes("qwen2.5-coder-1.5b-instruct-q4_k_m.gguf", "GGUF", 4096), new ProgressSink() {
            public void onProgress(int p) { last[0] = p; }
        });
        T.check(st.isModelPresent(), "valid GGUF imported");
        T.eq(100, last[0], "progress reaches 100");
        T.eq(4096L, new File(st.modelPath()).length(), "copied completely");
        // nameless picker result (msf:1234) is still accepted when the content is right
        st.deleteModel();
        T.check(!st.isModelPresent(), "delete removes the file");
        st.importModel(bytes("msf:1234", "GGUF", 4096), null);
        T.check(st.isModelPresent(), "nameless picker name accepted with valid header");
        st.deleteModel();
        T.check(!new File(st.modelPath()).exists(), "deleted again");

        // the ORIGINAL Gemma storage form is unchanged: no header check, Gemma wording
        FileModelStorage gemma = new FileModelStorage(dir, "g.litertlm", ".litertlm", min);
        gemma.importModel(bytes("g.litertlm", "JUNK", 4096), null);
        T.check(gemma.isModelPresent(), "gemma storage still has no header check");
        try { gemma.importModel(bytes("x.gguf", "JUNK", 4096), null); T.check(false, "gemma must refuse .gguf"); }
        catch (IOException ex) { T.check(ex.getMessage().contains("Gemma 4 E2B"), "gemma message unchanged"); }
        gemma.deleteModel();
        for (File f : dir.listFiles()) f.delete();
        dir.delete();
    }

    private static ImportSource bytes(final String name, final String head, final int size) {
        return new ImportSource() {
            public String displayName() { return name; }
            public long sizeBytes() { return size; }
            public InputStream open() {
                byte[] b = new byte[size];
                byte[] h = head.getBytes();
                System.arraycopy(h, 0, b, 0, h.length);
                return new ByteArrayInputStream(b);
            }
        };
    }

    // ---------------------------------------------------------------- chat: own history, own notices, own engine
    static void coderChatIsSeparate() throws Exception {
        T.section("coder: separate chat");
        Fakes.FakeEngine ge = new Fakes.FakeEngine(), ce = new Fakes.FakeEngine();
        ge.cannedReply = "GEMMA reply here.";
        ce.cannedReply = "```python\nprint('hi')\n```";
        ModuleManager gemma = new ModuleManager(ge, new Fakes.FakeStorage(), new Fakes.MemStateStore(null));
        ModuleManager coder = new ModuleManager(ce, new Fakes.FakeStorage(), new Fakes.MemStateStore(null), CoderSpec.DISPLAY_NAME, "coder-module");
        InMemoryStore gStore = new InMemoryStore(), cStore = new InMemoryStore();
        ChatController gChat = new ChatController(gemma, new ConversationBrain(gStore, CLOCK), gStore);
        ChatController cChat = new ChatController(coder, new ConversationBrain(cStore, CLOCK, CoderSpec.SYSTEM_BASE), cStore,
                CoderSpec.DISPLAY_NAME, "coder-chat");

        T.eq(ChatController.SendResult.MODEL_NOT_READY, cChat.send("python me sort likho"), "coder not loaded -> refused");
        List<ChatController.Item> items = cChat.items();
        T.check(items.size() == 1 && items.get(0).text.startsWith(CoderSpec.DISPLAY_NAME + " is not loaded"),
                "notice names the coder model: " + (items.isEmpty() ? "-" : items.get(0).text));
        T.eq(ChatController.SendResult.MODEL_NOT_READY, gChat.send("hello"), "gemma not loaded -> refused");
        T.check(gChat.items().get(0).text.startsWith("Gemma 4 E2B is not loaded"), "gemma notice unchanged");

        gemma.requestImport(Fakes.src("g.litertlm", 5)); Fakes.awaitIdle(gemma);
        gemma.requestLoad(); Fakes.awaitIdle(gemma);
        coder.requestImport(Fakes.src("q.gguf", 5)); Fakes.awaitIdle(coder);
        coder.requestLoad(); Fakes.awaitIdle(coder);

        T.eq(ChatController.SendResult.ACCEPTED, cChat.send("Python mein bubble sort ka code likho"), "coder message accepted");
        ChatTests.waitIdle(cChat);
        T.eq(ChatController.SendResult.ACCEPTED, gChat.send("Mumbai se Pune train kitne baje?"), "gemma message accepted");
        ChatTests.waitIdle(gChat);

        T.check(ce.lastPrompt != null && ce.lastPrompt.userMessage.contains("bubble sort"), "coder engine got the coding message");
        T.check(ce.lastPrompt.systemContext.startsWith("You are Qwen2.5-Coder"), "coder engine got the coder system prompt");
        T.check(ge.lastPrompt != null && ge.lastPrompt.userMessage.contains("Mumbai"), "gemma engine got its own message");
        T.check(ge.lastPrompt.systemContext.startsWith("You are Gemma 4 E2B"), "gemma keeps its own system prompt");
        T.check(!ge.lastPrompt.systemContext.contains("Qwen2.5-Coder"), "no coder text leaks into gemma");

        boolean coderHasReply = false, coderHasGemma = false, gemmaHasCode = false;
        for (ChatController.Item it : cChat.items()) { if (it.text.contains("print('hi')")) coderHasReply = true; if (it.text.contains("GEMMA")) coderHasGemma = true; }
        for (ChatController.Item it : gChat.items()) { if (it.text.contains("print('hi')")) gemmaHasCode = true; }
        T.check(coderHasReply, "coder chat shows the code reply");
        T.check(!coderHasGemma && !gemmaHasCode, "the two chats never mix");

        // both controllers can reply while the other is busy (independent workers + state machines)
        ge.tokenDelayMs = 20; ge.cannedReply = "slow gemma reply with many words in it";
        T.eq(ChatController.SendResult.ACCEPTED, gChat.send("batao kuch"), "gemma starts a slow reply");
        T.eq(ChatController.SendResult.ACCEPTED, cChat.send("Java mein hello world likho"), "coder replies while gemma is busy");
        ChatTests.waitIdle(cChat); ChatTests.waitIdle(gChat);
        T.check(!coder.snapshot().replyActive && !gemma.snapshot().replyActive, "both idle again");
        T.eq(0, ge.violations.get() + ce.violations.get(), "no engine misuse");
    }

    // ---------------------------------------------------------------- single-prompt builder for llama.cpp
    static void promptBuilder() {
        T.section("coder: prompt builder");
        List<PromptPackage.Turn> turns = new ArrayList<PromptPackage.Turn>();
        turns.add(new PromptPackage.Turn(true, "Python mein list sort kaise karte hain?"));
        turns.add(new PromptPackage.Turn(false, "Use sorted(a) ya a.sort()."));
        PromptPackage p = new PromptPackage("SYS", "RELEVANT MEMORY:\n- x", turns, "Ab reverse order mein dikhao");
        String u = CoderPrompt.user(p);
        T.check(u.startsWith("Earlier in this chat:\nUser: Python mein list sort"), "history first");
        T.check(u.endsWith("Ab reverse order mein dikhao"), "current message is last");
        T.check(u.contains("Assistant: Use sorted(a)"), "assistant turns included");
        T.eq("SYS\n\nRELEVANT MEMORY:\n- x", CoderPrompt.system(p), "system = context + memory");

        PromptPackage first = new PromptPackage("SYS", "", new ArrayList<PromptPackage.Turn>(), "hello");
        T.eq("hello", CoderPrompt.user(first), "no history -> just the message");

        // long history is trimmed from the oldest side and the current message is never cut
        List<PromptPackage.Turn> many = new ArrayList<PromptPackage.Turn>();
        char[] filler = new char[700]; Arrays.fill(filler, 'x');
        for (int i = 0; i < 20; i++) many.add(new PromptPackage.Turn(i % 2 == 0, "T" + i + new String(filler)));
        PromptPackage big = new PromptPackage("SYS", "", many, "LAST-QUESTION");
        String bu = CoderPrompt.user(big);
        T.check(bu.length() <= CoderPrompt.MAX_USER_PROMPT_CHARS, "user prompt within budget (" + bu.length() + ")");
        T.check(bu.endsWith("LAST-QUESTION"), "current message kept");
        T.check(!bu.contains("T0x"), "oldest turn dropped");
        int firstUser = bu.indexOf("User: ");
        int firstAssistant = bu.indexOf("Assistant: ");
        T.check(firstUser >= 0 && (firstAssistant < 0 || firstUser < firstAssistant), "trimmed history still starts with a user turn");

        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 9000; i++) huge.append('s');
        PromptPackage bigSys = new PromptPackage(huge.toString(), "", new ArrayList<PromptPackage.Turn>(), "q");
        String sys = CoderPrompt.system(bigSys);
        T.check(sys.length() <= CoderPrompt.MAX_SYSTEM_CHARS, "system prompt capped (" + sys.length() + ")");

        T.eq("print(1)", CoderPrompt.cleanReply("  print(1)<|im_end|>\n<|im_start|>user"), "template end marker removed");
        T.eq("ok", CoderPrompt.cleanReply("ok<|endoftext|>garbage"), "endoftext marker removed");
        T.eq("", CoderPrompt.cleanReply(null), "null reply -> empty");
        T.eq("plain reply", CoderPrompt.cleanReply("plain reply\n"), "normal reply only trimmed");

        // the brain produces a coder prompt from the coder system base
        InMemoryStore st = new InMemoryStore();
        ConversationBrain b = new ConversationBrain(st, CLOCK, CoderSpec.SYSTEM_BASE);
        ConversationBrain.Turn t = b.beginTurn("Kotlin mein ek function likho jo prime check kare");
        T.check(t.prompt.systemContext.startsWith(CoderSpec.SYSTEM_BASE), "brain uses the coder base");
        String real = CoderPrompt.system(t.prompt);
        T.check(real.length() <= CoderPrompt.MAX_SYSTEM_CHARS, "real coder system prompt fits (" + real.length() + ")");
        T.check(real.contains("Kotlin"), "user's language name is preserved in the prompt");

        Attachment attached = new Attachment(Attachment.Kind.ZIP, "project.zip", 1, "zip")
                .loaded("=== app/src/MainActivity.java ===\nclass MainActivity {}", java.util.Collections.<byte[]>emptyList());
        PromptPackage skillP = new PromptPackage("SYS", "", new ArrayList<PromptPackage.Turn>(),
                "Word document banao", java.util.Collections.singletonList(attached))
                .withSkillContext("CREATE Word requires [[SKILL_FILE type=docx title=\"Title\"]]");
        String skillUser = CoderPrompt.user(skillP);
        T.check(skillUser.contains("project.zip") && skillUser.contains("MainActivity.java"),
                "coder prompt includes extracted attachment text");
        T.check(skillUser.contains("SKILL_FILE"), "coder prompt includes the skill execution contract");

        String longCurrent = new String(new char[4000]).replace('\0', 'x');
        PromptPackage longP = new PromptPackage("SYS", "", new ArrayList<PromptPackage.Turn>(), longCurrent)
                .withSkillContext("SKILL");
        String longUser = CoderPrompt.user(longP);
        T.check(longUser.length() <= CoderPrompt.MAX_USER_PROMPT_CHARS, "coder prompt remains within budget for max-length current message");
        T.check(longUser.endsWith(longCurrent), "coder prompt never cuts the current message");
    }
}
