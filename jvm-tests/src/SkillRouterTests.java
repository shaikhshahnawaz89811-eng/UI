package tests;

import com.neonhud.app.core.chat.ChatController;
import com.neonhud.app.core.engine.Attachment;
import com.neonhud.app.core.memory.ConversationBrain;
import com.neonhud.app.core.memory.InMemoryStore;
import com.neonhud.app.core.module.ModuleManager;
import com.neonhud.app.core.skill.RouterContext;
import com.neonhud.app.core.skill.SkillPlan;
import com.neonhud.app.core.skill.SkillPrompt;
import com.neonhud.app.core.skill.SkillRouter;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stage 1: SkillRouter on the seed corpus (phase5/router_corpus.tsv) + the honesty rules of the capability note. */
final class SkillRouterTests {
    private static final String DATA = "../phase5/router_corpus.tsv";
    static final double GATE = 0.98;

    static List<Attachment> files(String attach) {
        List<Attachment> out = new ArrayList<Attachment>();
        if (attach.equals("-")) return out;
        String[] parts = attach.split(",");
        for (int i = 0; i < parts.length; i++) {
            Attachment.Kind k = Attachment.Kind.valueOf(parts[i]);
            out.add(new Attachment(k, "file" + (i + 1) + "." + k.name().toLowerCase(), 1000, "u" + i));
        }
        return out;
    }

    static void run() throws Exception {
        T.section("skill router: seed corpus");
        File f = new File(System.getProperty("phase5.corpus", DATA));
        if (!f.isFile()) throw new IllegalStateException("Missing router corpus: " + f.getAbsolutePath());
        List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
        int total = 0, ok = 0;
        Map<String, int[]> byBlock = new LinkedHashMap<String, int[]>();
        Map<String, int[]> byStyle = new LinkedHashMap<String, int[]>();
        List<String> misses = new ArrayList<String>();
        for (int i = 1; i < lines.size(); i++) {
            String[] c = lines.get(i).split("\t", -1);
            if (c.length < 7) continue;
            String block = c[1], style = c[2], attach = c[3], text = c[5], expected = c[6];
            boolean last = c[4].equals("1");
            SkillPlan plan = SkillRouter.route(text, files(attach), new RouterContext(last, null));
            String got = plan.signature();
            total++;
            int[] b = byBlock.get(block); if (b == null) { b = new int[2]; byBlock.put(block, b); }
            int[] s = byStyle.get(style); if (s == null) { s = new int[2]; byStyle.put(style, s); }
            b[1]++; s[1]++;
            if (got.equals(expected)) { ok++; b[0]++; s[0]++; }
            else misses.add("#" + c[0] + " [" + block + "/" + style + "] attach=" + attach + " last=" + (last ? 1 : 0) + " \"" + text + "\"  expected " + expected + "  got " + got);
        }
        System.out.println("  router corpus: " + ok + "/" + total + " = " + String.format("%.1f%%", 100.0 * ok / total));
        for (Map.Entry<String, int[]> e : byBlock.entrySet()) System.out.println("    block " + e.getKey() + ": " + e.getValue()[0] + "/" + e.getValue()[1]);
        for (Map.Entry<String, int[]> e : byStyle.entrySet()) System.out.println("    style " + e.getKey() + ": " + e.getValue()[0] + "/" + e.getValue()[1]);
        for (String m : misses) System.out.println("  MISS " + m);
        T.check(total >= 300, "corpus has at least 300 rows (got " + total + ")");
        T.check(ok >= GATE * total, "router accuracy >= 98% (got " + ok + "/" + total + ")");

        T.section("skill router: rules");
        // Multiple attached files: explicit read references select one instead of silently reading every file.
        List<com.neonhud.app.core.engine.Attachment> twoPdfs = java.util.Arrays.asList(
                new com.neonhud.app.core.engine.Attachment(com.neonhud.app.core.engine.Attachment.Kind.PDF, "one.pdf", 10, "file:/one.pdf"),
                new com.neonhud.app.core.engine.Attachment(com.neonhud.app.core.engine.Attachment.Kind.PDF, "two.pdf", 10, "file:/two.pdf"));
        SkillPlan firstFile = SkillRouter.route("first file ko padho", twoPdfs);
        T.check(!firstFile.needsClarify() && firstFile.tasks.size() == 1 && firstFile.tasks.get(0).fileIndex == 0, "explicit first-file read selects only first attachment");
        SkillPlan secondFile = SkillRouter.route("second file ko padho", twoPdfs);
        T.check(!secondFile.needsClarify() && secondFile.tasks.size() == 1 && secondFile.tasks.get(0).fileIndex == 1, "explicit second-file read selects only second attachment");
        SkillPlan namedFile = SkillRouter.route("two.pdf ko padho", twoPdfs);
        T.check(!namedFile.needsClarify() && namedFile.tasks.size() == 1 && namedFile.tasks.get(0).fileIndex == 1, "explicit attached filename selects only that file");

        List<Attachment> none = new ArrayList<Attachment>();
        // never selects a reader from words alone
        T.eq("NONE", SkillRouter.route("music theory samjhao", none).signature(), "music theory with no audio file selects nothing");
        T.eq("NONE", SkillRouter.route("ogg vorbis kya hai", none).signature(), "ogg vorbis question selects nothing");
        // original file is never the edit target of a create
        SkillPlan p = SkillRouter.route("Ravi ki jagah Raj likh do", files("DOCX"));
        T.eq("Ravi", p.tasks.get(1).params.get("old"), "edit old text parsed");
        T.eq("Raj", p.tasks.get(1).params.get("new"), "edit new text parsed");
        p = SkillRouter.route("Raj likh do Ravi ki jagah", files("DOCX"));
        T.eq("Ravi", p.tasks.get(1).params.get("old"), "reversed word order: old text");
        T.eq("Raj", p.tasks.get(1).params.get("new"), "reversed word order: new text");
        p = SkillRouter.route("B2 me 500 likh do", files("XLSX"));
        T.eq("B2", p.tasks.get(1).params.get("cell"), "cell parsed");
        T.eq("500", p.tasks.get(1).params.get("value"), "value parsed");
        p = SkillRouter.route("sheet Data me C3 me 250 likh do", files("XLSX"));
        T.eq("Data", p.tasks.get(1).params.get("sheet"), "sheet name parsed");
        p = SkillRouter.route("second file me Ravi ki jagah Raj likh do", files("DOCX,DOCX"));
        T.eq(1, p.tasks.get(2).fileIndex, "ordinal picks the second Word file");
        T.check(SkillRouter.route("word aur excel dono banao", none, new RouterContext(true, null)).signature().equals("CREATE:DOCX>CREATE:XLSX"),
                "\"word aur excel dono banao\" stays one segment with two formats");
        T.eq("CLARIFY", SkillRouter.route("document banao", none).signature(), "bare document asks the format, never guesses");
        T.check(SkillRouter.route("document banao", none).clarify.contains("PDF"), "format question names PDF");

        T.section("skill router: honest capability note");
        SkillPlan create = SkillRouter.route("AI par pdf banao", none);
        String note = SkillPrompt.build(create);
        T.check(note.contains("SKILL_FILE"), "create request -> model receives the file marker contract");
        T.check(note.contains("type=pdf") && note.contains("own line"), "create contract gives exact PDF marker syntax");
        T.check(note.contains("Do not claim the file is ready"), "model cannot claim a file before app verification");
        T.check(note.contains("PDF"), "note names the requested format");
        T.eq("", SkillPrompt.build(SkillRouter.route("namaste", none)), "plain chat gets no extra prompt text");
        T.eq("", SkillPrompt.build(SkillRouter.route("ye padho", files("PDF"))), "read-only message gets no extra prompt text");
        T.check(SkillPrompt.build(SkillRouter.route("transcript do", files("AUDIO"))).contains("Not possible"), "transcript -> honest not-possible note");
        T.check(SkillPrompt.build(SkillRouter.route("AI par word excel ppt aur pdf banao", none)).contains("Not done"), "4th job reported as not done");

        T.section("skill router: inside ChatController (fake model)");
        Fakes.FakeEngine e = new Fakes.FakeEngine();
        ModuleManager mm = new ModuleManager(e, new Fakes.FakeStorage(), new Fakes.MemStateStore(null));
        InMemoryStore store = new InMemoryStore();
        ConversationBrain brain = new ConversationBrain(store, new ConversationBrain.Clock() { public long now() { return System.currentTimeMillis(); } });
        ChatController chat = new ChatController(mm, brain, store);
        mm.requestImport(Fakes.src("g.litertlm", 5)); Fakes.awaitIdle(mm);
        mm.requestLoad(); Fakes.awaitIdle(mm);
        // A valid marker keeps this regression focused on the original prompt contract;
        // the separate recovery test below covers the no-marker path.
        e.cannedReply = "[[SKILL_FILE type=pdf title=\"Test\"]]\nhello\n[[END_SKILL_FILE]]";

        chat.send("document banao");
        ChatTests.waitIdle(chat);
        List<ChatController.Item> items = chat.items();
        ChatController.Item last = items.get(items.size() - 1);
        T.eq(ChatController.Kind.AI, last.kind, "clarify arrives as the AI bubble");
        T.check(last.text.contains("PDF ya Word"), "clarify asks the format (got: " + last.text + ")");
        T.check(e.lastPrompt == null, "clarify never calls the model");

        chat.send("AI par pdf banao");
        ChatTests.waitIdle(chat);
        T.check(e.lastPrompt != null, "create request reaches the model");
        T.check(e.lastPrompt.skillContext.contains("SKILL_FILE"), "model prompt carries the Stage 2 file marker contract");
        T.check(e.lastPrompt.flatten().contains("Do not claim the file is ready"), "flattened prompt carries the Stage 2 honesty contract");

        e.lastPrompt = null;
        chat.send("namaste");
        ChatTests.waitIdle(chat);
        T.check(e.lastPrompt != null && e.lastPrompt.skillContext.isEmpty(), "normal chat: no skill note added");

        // a short, bare file request right after a reply uses that reply (no question)
        e.lastPrompt = null;
        chat.send("isko word me daal do");
        ChatTests.waitIdle(chat);
        T.check(e.lastPrompt != null && e.lastPrompt.skillContext.contains("Word"), "follow-up 'isko word me daal do' goes to the model with the Word note");

        chat.setSkillsEnabled(false);
        e.lastPrompt = null;
        chat.send("document banao");
        ChatTests.waitIdle(chat);
        T.check(e.lastPrompt != null && e.lastPrompt.skillContext.isEmpty(), "skills switched off (coder chat): router does nothing");

        T.section("skill router: CREATE recovery after missing marker");
        Fakes.FakeEngine recoveryEngine = new Fakes.FakeEngine();
        ModuleManager recoveryMm = new ModuleManager(recoveryEngine, new Fakes.FakeStorage(), new Fakes.MemStateStore(null));
        InMemoryStore recoveryStore = new InMemoryStore();
        ConversationBrain recoveryBrain = new ConversationBrain(recoveryStore, new ConversationBrain.Clock() { public long now() { return System.currentTimeMillis(); } });
        ChatController recoveryChat = new ChatController(recoveryMm, recoveryBrain, recoveryStore);
        recoveryMm.requestImport(Fakes.src("g.litertlm", 5)); Fakes.awaitIdle(recoveryMm);
        recoveryMm.requestLoad(); Fakes.awaitIdle(recoveryMm);
        recoveryEngine.setReplySequence(
                "Sure, I will make the PDF for you.",
                "[[SKILL_FILE type=pdf title=\"Recovered\"]]\nRecovered body\n[[END_SKILL_FILE]]");
        recoveryChat.send("AI par pdf banao");
        ChatTests.waitIdle(recoveryChat);
        T.eq(2, recoveryEngine.generateCalls.get(), "missing marker triggers exactly one recovery generation");
        T.check(recoveryEngine.lastPrompt != null && recoveryEngine.lastPrompt.skillContext.contains("SKILL RECOVERY"), "recovery prompt is sent to the same active model");
        T.check(recoveryChat.items().get(recoveryChat.items().size() - 1).text.contains("file execution is not connected"), "without an executor the app still refuses to claim a file");
    }
}
