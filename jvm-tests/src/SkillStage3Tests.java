package tests;

import com.neonhud.app.core.engine.Attachment;
import com.neonhud.app.core.engine.SelectableAttachmentLoader;
import com.neonhud.app.core.chat.ChatController;
import com.neonhud.app.core.memory.ConversationBrain;
import com.neonhud.app.core.memory.InMemoryStore;
import com.neonhud.app.core.module.ModuleManager;
import com.neonhud.app.core.engine.AttachmentReadCache;
import com.neonhud.app.core.engine.ReadBudget;
import com.neonhud.app.core.engine.ReadSelection;
import com.neonhud.app.core.office.PptxSkill;
import com.neonhud.app.core.office.XlsxSkill;
import com.neonhud.app.core.skill.RouterContext;
import com.neonhud.app.core.skill.SkillKind;
import com.neonhud.app.core.skill.SkillPlan;
import com.neonhud.app.core.skill.SkillRouter;
import com.neonhud.app.core.skill.SkillTask;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Stage 3: prompt read budget, numbered media metadata, explicit selections, and cached read follow-ups. */
final class SkillStage3Tests {
    private static List<Attachment> files(String... kinds) {
        List<Attachment> out = new ArrayList<Attachment>();
        for (int i = 0; i < kinds.length; i++) {
            Attachment.Kind k = Attachment.Kind.valueOf(kinds[i]);
            out.add(new Attachment(k, "file" + (i + 1) + "." + k.name().toLowerCase(), 1000, "u" + i));
        }
        return out;
    }

    static void run() throws Exception {
        T.section("stage 3: read budget");
        StringBuilder huge = new StringBuilder();
        while (huge.length() < ReadBudget.MAX_FILE_TEXT_CHARS + 2000) huge.append("line\n");
        Attachment a = new Attachment(Attachment.Kind.PDF, "big.pdf", 100, "u")
                .loaded(huge.toString(), Arrays.asList(new byte[]{1}, new byte[]{2}, new byte[]{3}, new byte[]{4}, new byte[]{5}),
                        Arrays.asList("page 3", "page 4", "page 5", "page 6", "page 7"));
        Attachment b = new Attachment(Attachment.Kind.IMAGE, "image.png", 100, "i")
                .loaded("small", Arrays.asList(new byte[]{9}, new byte[]{8}, new byte[]{7}), Arrays.asList("image 1", "image 2", "image 3"));
        List<Attachment> budgeted = ReadBudget.apply(Arrays.asList(a, b));
        T.check(budgeted.get(0).text.length() <= ReadBudget.MAX_FILE_TEXT_CHARS, "one file stays within the per-file text budget");
        int total = 0, images = 0;
        for (Attachment x : budgeted) { total += x.text.length(); images += x.images.size(); }
        T.check(total <= ReadBudget.MAX_TOTAL_TEXT_CHARS, "all file text stays within the total prompt budget");
        T.check(images <= ReadBudget.MAX_TOTAL_IMAGES, "all media stays within the total image budget");
        T.eq("page 3", budgeted.get(0).imageLabels.get(0), "image labels survive the read budget");
        T.check(budgeted.get(0).text.contains("content clipped by read budget"), "clipping is explicit rather than silent");
        Attachment nearLimit1 = new Attachment(Attachment.Kind.DOCX, "one.docx", 1, "n1").loaded(repeat('x', 7999), Collections.<byte[]>emptyList());
        Attachment nearLimit2 = new Attachment(Attachment.Kind.DOCX, "two.docx", 1, "n2").loaded(repeat('y', 7999), Collections.<byte[]>emptyList());
        Attachment nearLimit3 = new Attachment(Attachment.Kind.DOCX, "three.docx", 1, "n3").loaded(repeat('z', 7999), Collections.<byte[]>emptyList());
        Attachment nearLimit4 = new Attachment(Attachment.Kind.DOCX, "four.docx", 1, "n4").loaded(repeat('q', 100), Collections.<byte[]>emptyList());
        List<Attachment> tinyRemaining = ReadBudget.apply(Arrays.asList(nearLimit1, nearLimit2, nearLimit3, nearLimit4));
        int strictTotal = 0; for (Attachment x : tinyRemaining) strictTotal += x.text.length();
        T.check(strictTotal <= ReadBudget.MAX_TOTAL_TEXT_CHARS, "budget remains strict even when the final file has only a few characters left");
        T.check(tinyRemaining.get(tinyRemaining.size() - 1).text.length() <= 3, "tiny remaining budget cannot leak an oversized clip notice");
        T.eq("page 3", new Attachment(Attachment.Kind.PDF, "p.pdf", 1, "p").loaded("", Arrays.asList(new byte[]{1}, new byte[]{2}), Arrays.asList("page 3", "page 4")).imageLabels.get(0), "PDF image labels have a deterministic page number");
        T.eq("frame 2", new Attachment(Attachment.Kind.VIDEO, "v.mp4", 1, "v").loaded("", Arrays.asList(new byte[]{1}, new byte[]{2}), Arrays.asList("frame 1", "frame 2")).imageLabels.get(1), "video image labels remain attached to the correct frame");

        T.section("stage 3: page / slide / sheet selection");
        SkillPlan pdf = SkillRouter.route("page 3 padho", files("PDF"));
        T.eq(SkillKind.PDF_READER, pdf.tasks.get(0).skill, "page request stays a PDF read");
        T.eq("page:3-3", pdf.tasks.get(0).selection.signature(), "single PDF page is parsed");
        SkillPlan pdfRange = SkillRouter.route("pages 3 to 6 padho", files("PDF"));
        T.eq("page:3-6", pdfRange.tasks.get(0).selection.signature(), "PDF page range is parsed");

        SkillPlan ppt = SkillRouter.route("slide 2 read karo", files("PPTX"));
        T.eq("slide:2-2", ppt.tasks.get(0).selection.signature(), "single PPTX slide is parsed");
        SkillPlan pptRange = SkillRouter.route("slides 2-4 padho", files("PPTX"));
        T.eq("slide:2-4", pptRange.tasks.get(0).selection.signature(), "PPTX slide range is parsed");

        SkillPlan xls = SkillRouter.route("sheet Data padho", files("XLSX"));
        T.eq("sheet:data", xls.tasks.get(0).selection.signature(), "Excel sheet name is parsed");
        T.eq("Data", xls.tasks.get(0).selection.sheet, "Excel sheet keeps its exact case");
        SkillPlan cellEdit = SkillRouter.route("sheet me C3 me 250 likho", files("XLSX"));
        T.eq("all", cellEdit.tasks.get(0).selection.signature(), "Excel cell edits do not mistake the cell for a sheet name");

        T.section("stage 3: cached read follow-ups");
        List<Attachment> cached = files("PDF");
        SkillPlan cachedPage = SkillRouter.route("page 3 padho", Collections.<Attachment>emptyList(),
                new RouterContext(true, null, cached));
        T.eq(SkillTask.Source.CACHE, cachedPage.tasks.get(0).source, "page follow-up can use a cached file source");
        T.eq(0, cachedPage.tasks.get(0).fileIndex, "cached file index is stable");
        SkillPlan cachedSummary = SkillRouter.route("isko summarize karo", Collections.<Attachment>emptyList(),
                new RouterContext(true, null, cached));
        T.eq(SkillTask.Source.CACHE, cachedSummary.tasks.get(0).source, "pronoun read follow-up uses the cache");
        SkillPlan cachedAmbiguous = SkillRouter.route("isko padho", Collections.<Attachment>emptyList(),
                new RouterContext(true, null, files("PDF", "PDF")));
        T.eq("CLARIFY", cachedAmbiguous.signature(), "two saved files require an explicit choice");

        T.section("stage 3: read cache behaviour");
        AttachmentReadCache cache = new AttachmentReadCache();
        Attachment loaded3 = a.loaded("page 3 payload", Collections.singletonList(new byte[]{3}), Collections.singletonList("page 3"));
        cache.put(a, ReadSelection.pages(3, 3), loaded3);
        T.check(cache.get(a, ReadSelection.pages(3, 3)) == loaded3, "exact selection cache hit returns the loaded payload");
        T.check(cache.getLatest(a) == loaded3, "latest cache hit supports follow-up without a new selection");
        T.eq(1, cache.sources().size(), "cache exposes one unique source metadata entry");


        T.section("stage 3: ChatController cache wiring");
        Fakes.FakeEngine engine = new Fakes.FakeEngine();
        Fakes.FakeStorage storage = new Fakes.FakeStorage();
        ModuleManager modules = new ModuleManager(engine, storage, new Fakes.MemStateStore(null));
        modules.requestImport(Fakes.src("gemma.litertlm", 1)); Fakes.awaitIdle(modules);
        modules.requestLoad(); Fakes.awaitIdle(modules);
        InMemoryStore store = new InMemoryStore();
        ConversationBrain brain = new ConversationBrain(store, new ConversationBrain.Clock() { public long now() { return System.currentTimeMillis(); } });
        ChatController chat = new ChatController(modules, brain, store);
        final List<String> seenSelections = new ArrayList<String>();
        chat.setAttachmentLoader(new SelectableAttachmentLoader() {
            @Override public Attachment load(Attachment source, ReadSelection selection) {
                String sig = selection == null ? "all" : selection.signature();
                seenSelections.add(sig);
                return source.loaded("loaded " + sig, Collections.singletonList(new byte[]{1}), Collections.singletonList(sig));
            }
        });
        Attachment savedPdf = new Attachment(Attachment.Kind.PDF, "guide.pdf", 1000, "content://guide");
        T.eq(ChatController.SendResult.ACCEPTED, chat.send("page 3 padho", Collections.singletonList(savedPdf)), "attached page read accepted");
        ChatTests.waitIdle(chat);
        T.eq(1, seenSelections.size(), "first page read hits the real loader once");
        T.eq("page:3-3", seenSelections.get(0), "first page selection reaches the loader");
        T.check(engine.lastPrompt != null && engine.lastPrompt.attachments.size() == 1, "selected attachment reaches the model prompt");

        engine.lastPrompt = null;
        T.eq(ChatController.SendResult.ACCEPTED, chat.send("page 4 padho"), "cached source page follow-up accepted");
        ChatTests.waitIdle(chat);
        T.eq(2, seenSelections.size(), "new page selection causes exactly one new load");
        T.eq("page:4-4", seenSelections.get(1), "second page selection reaches the loader");

        engine.lastPrompt = null;
        T.eq(ChatController.SendResult.ACCEPTED, chat.send("isko summarize karo"), "cached read follow-up accepted");
        ChatTests.waitIdle(chat);
        T.eq(2, seenSelections.size(), "summary follow-up reuses cached read without reloading");
        T.check(engine.lastPrompt != null && engine.lastPrompt.attachments.size() == 1
                        && engine.lastPrompt.attachments.get(0).text.contains("loaded page:4-4"),
                "summary follow-up receives the latest cached page payload");

        T.section("stage 3: real Office selections");
        File root = Files.createTempDirectory("neon-stage3-office").toFile();
        try {
            XlsxSkill x = new XlsxSkill();
            File workbook = new File(root, "book.xlsx");
            // The current creator emits one sheet, so add a second minimal sheet as a package-level test fixture below.
            x.create("Data", Arrays.asList(Arrays.asList("A", "B"), Arrays.asList("1", "2")), workbook);
            T.check(x.read(workbook, "Data").contains("SHEET: Data"), "Excel selected-sheet read returns the requested sheet");
            boolean missingSheet = false;
            try { x.read(workbook, "Missing"); } catch (Exception expected) { missingSheet = true; }
            T.check(missingSheet, "Excel missing-sheet selection fails honestly");

            PptxSkill p = new PptxSkill();
            File deck = new File(root, "deck.pptx");
            p.create(Arrays.asList(
                    new PptxSkill.Slide("One", Collections.singletonList("alpha")),
                    new PptxSkill.Slide("Two", Collections.singletonList("beta")),
                    new PptxSkill.Slide("Three", Collections.singletonList("gamma"))), deck);
            String selected = p.read(deck, 2, 2);
            T.check(selected.contains("SLIDE 2") && selected.contains("Two") && !selected.contains("SLIDE 1") && !selected.contains("SLIDE 3"),
                    "PowerPoint selected-slide read excludes unselected slides");
        } finally {
            delete(root);
        }
    }

    private static String repeat(char c, int n) {
        StringBuilder s = new StringBuilder(n);
        for (int i = 0; i < n; i++) s.append(c);
        return s.toString();
    }

    private static void delete(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) delete(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
