package tests;

import com.neonhud.app.core.engine.Attachment;
import com.neonhud.app.core.office.DocxSkill;
import com.neonhud.app.core.office.PptxSkill;
import com.neonhud.app.core.office.XlsxSkill;
import com.neonhud.app.core.skill.PdfWriter;
import com.neonhud.app.core.skill.SkillContentMarker;
import com.neonhud.app.core.skill.SkillExecution;
import com.neonhud.app.core.skill.SkillKind;
import com.neonhud.app.core.skill.SkillTask;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stage 2: parser + real Office execution + copy-on-edit + honest failure paths. */
final class SkillStage2Tests {
    static void run() throws Exception {
        T.section("stage 2: marker parser");
        parserTests();
        T.section("stage 2: real create execution");
        createTests();
        T.section("stage 2: copy-on-edit execution");
        editTests();
        T.section("stage 2: ChatController execution wiring");
        chatControllerTests();
        T.section("stage 2: failure cleanup and unsupported paths");
        failureTests();
    }

    private static void parserTests() {
        String all = "" +
                "Intro\n" +
                "[[SKILL_FILE type=docx title=Report]]\n" +
                "Alpha\nBeta\n[[END_SKILL_FILE]]\n" +
                "[[SKILL_FILE type=pdf title='Notes']]\n" +
                "PDF line\n[[END_SKILL_FILE]]\n" +
                "[[SKILL_FILE type=xlsx title=Sales]]\n" +
                "[[SHEET name=Data]]\n" +
                "Product\tUnits\nPhone\t10\n[[END_SHEET]]\n" +
                "[[END_SKILL_FILE]]\n" +
                "[[SKILL_FILE type=pptx title=Deck]]\n" +
                "[[SLIDE title=Overview]]\n" +
                "One\nTwo\n[[END_SLIDE]]\n" +
                "[[END_SKILL_FILE]]\n";
        SkillContentMarker.ParseResult r = SkillContentMarker.parse(all);
        T.check(r.ok(), "all four Stage 2 marker types parse");
        T.eq(4, r.artifacts.size(), "four artifacts are parsed in order");
        T.eq(SkillKind.DOCX, r.artifacts.get(0).kind, "DOCX marker kind");
        T.check(r.artifacts.get(0).text.contains("Alpha") && r.artifacts.get(0).text.contains("Beta"), "DOCX body kept");
        T.eq(SkillKind.PDF_CREATOR, r.artifacts.get(1).kind, "PDF marker kind");
        T.eq("Notes", r.artifacts.get(1).title, "quoted PDF title parsed");
        T.eq(1, r.artifacts.get(2).sheets.size(), "one XLSX sheet parsed");
        T.eq("10", r.artifacts.get(2).sheets.get(0).rows.get(1).get(1), "XLSX tab-separated cell parsed");
        T.eq(1, r.artifacts.get(3).slides.size(), "one PPTX slide parsed");
        T.eq(2, r.artifacts.get(3).slides.get(0).bullets.size(), "PPTX bullet lines parsed");

        SkillContentMarker.ParseResult fenced = SkillContentMarker.parse(
                "```[[SKILL_FILE type=docx title=\"Fence Test\"]]\nbody\n[[END_SKILL_FILE]]```");
        T.check(fenced.ok() && fenced.artifacts.size() == 1 && fenced.artifacts.get(0).text.contains("body"),
                "accidental markdown fence around a marker remains executable");

        T.check(!SkillContentMarker.parse("[[SKILL_FILE type=docx title=X]]\nbody").ok(), "missing end marker is rejected");
        T.check(!SkillContentMarker.parse("[[SKILL_FILE type=xlsx title=X]]\n[[SHEET name=S]]\na\tb\n[[END_SKILL_FILE]]").ok(), "missing XLSX end-sheet marker is rejected");
        T.check(!SkillContentMarker.parse("[[SKILL_FILE type=bogus]]\nbody\n[[END_SKILL_FILE]]").ok(), "unknown marker type is rejected");
    }

    private static void createTests() throws Exception {
        File root = Files.createTempDirectory("mj-stage2-create").toFile();
        try {
            Harness h = new Harness(root);

            SkillExecution.Result doc = h.exec(new SkillTask(SkillTask.Action.CREATE, SkillKind.DOCX,
                    SkillTask.Source.TEXT, SkillTask.NO_FILE, "", null),
                    "[[SKILL_FILE type=docx title=\"Stage2 Report\"]]\nHello from Stage 2\nSecond paragraph\n[[END_SKILL_FILE]]");
            T.check(doc.success && doc.file.isFile(), "DOCX CREATE produces a real file");
            T.check(h.docx.read(doc.file).contains("Hello from Stage 2") && h.docx.read(doc.file).contains("Second paragraph"), "DOCX CREATE content survives read-back");

            SkillExecution.Result xls = h.exec(new SkillTask(SkillTask.Action.CREATE, SkillKind.XLSX,
                    SkillTask.Source.TEXT, SkillTask.NO_FILE, "", null),
                    "[[SKILL_FILE type=xlsx title=\"Stage2 Sales\"]]\n[[SHEET name=Sales]]\nProduct\tUnits\nPhone\t10\nLaptop\t4\n[[END_SHEET]]\n[[END_SKILL_FILE]]");
            T.check(xls.success && xls.file.isFile(), "XLSX CREATE produces a real file");
            String xr = h.xlsx.read(xls.file);
            T.check(xr.contains("SHEET: Sales") && xr.contains("A1=Product") && xr.contains("B3=4"), "XLSX CREATE content survives read-back");

            SkillExecution.Result ppt = h.exec(new SkillTask(SkillTask.Action.CREATE, SkillKind.PPTX,
                    SkillTask.Source.TEXT, SkillTask.NO_FILE, "", null),
                    "[[SKILL_FILE type=pptx title=\"Stage2 Deck\"]]\n[[SLIDE title=Overview]]\nOne\nTwo\n[[END_SLIDE]]\n[[SLIDE title=Next]]\nThree\n[[END_SLIDE]]\n[[END_SKILL_FILE]]");
            T.check(ppt.success && ppt.file.isFile(), "PPTX CREATE produces a real file");
            String pr = h.pptx.read(ppt.file);
            T.check(pr.contains("Overview") && pr.contains("One") && pr.contains("Next") && pr.contains("Three"), "PPTX CREATE content survives read-back");

            SkillExecution.Result pdf = h.exec(new SkillTask(SkillTask.Action.CREATE, SkillKind.PDF_CREATOR,
                    SkillTask.Source.TEXT, SkillTask.NO_FILE, "", null),
                    "[[SKILL_FILE type=pdf title=\"Stage2 Notes\"]]\nHello PDF\nSecond line\n[[END_SKILL_FILE]]");
            T.check(pdf.success && pdf.file.isFile() && pdf.file.length() > 0, "PDF CREATE uses the PDF writer interface and creates output");
            T.eq(1, h.pdfCreateCalls, "PDF writer create was called exactly once");
            T.eq(1, h.pdfVerifyCalls, "PDF writer verification was called before success");

            T.check(h.published.size() == 4, "all successful creates are published exactly once");
            for (Attachment a : h.published) T.check(a.uri.startsWith("file:"), "published output has a resolvable file URI");

            String twoDocs = "[[SKILL_FILE type=docx title=\"First\"]]\nOne\n[[END_SKILL_FILE]]\n" +
                    "[[SKILL_FILE type=docx title=\"Second\"]]\nTwo\n[[END_SKILL_FILE]]";
            SkillTask firstTask = new SkillTask(SkillTask.Action.CREATE, SkillKind.DOCX, SkillTask.Source.TEXT, SkillTask.NO_FILE, "", null);
            SkillExecution.Result first = h.execution.execute(firstTask, twoDocs, Collections.<Attachment>emptyList(), null, 0);
            SkillExecution.Result second = h.execution.execute(firstTask, twoDocs, Collections.<Attachment>emptyList(), null, 1);
            T.check(first.success && second.success && !first.file.equals(second.file), "same-kind CREATE tasks consume distinct ordered markers");
            T.check(h.docx.read(first.file).contains("One") && h.docx.read(second.file).contains("Two"), "same-kind CREATE outputs keep their matching content");
        } finally { delete(root); }
    }

    private static void editTests() throws Exception {
        File root = Files.createTempDirectory("mj-stage2-edit").toFile();
        try {
            Harness h = new Harness(root);
            File doc = new File(root, "original.docx");
            h.docx.create("Original", Collections.singletonList("Ravi is here"), doc);
            Attachment da = new Attachment(Attachment.Kind.DOCX, doc.getName(), doc.length(), doc.toURI().toString());
            Map<String,String> dp = map("old", "Ravi is here", "new", "Raj is here");
            SkillExecution.Result dr = h.exec(new SkillTask(SkillTask.Action.EDIT, SkillKind.DOCX, SkillTask.Source.ATTACHED, 0, "", dp), "", Collections.singletonList(da), null);
            T.check(dr.success && dr.file.isFile(), "DOCX EDIT creates an edited copy");
            T.check(h.docx.read(doc).contains("Ravi is here"), "DOCX source remains unchanged");
            T.check(h.docx.read(dr.file).contains("Raj is here"), "DOCX edited copy contains replacement");

            File xls = new File(root, "sheet.xlsx");
            h.xlsx.create("Data", Arrays.asList(Arrays.asList("A", "B"), Arrays.asList("one", "2")), xls);
            Attachment xa = new Attachment(Attachment.Kind.XLSX, xls.getName(), xls.length(), xls.toURI().toString());
            Map<String,String> xp = map("sheet", "Data", "cell", "B2", "value", "500");
            SkillExecution.Result xr = h.exec(new SkillTask(SkillTask.Action.EDIT, SkillKind.XLSX, SkillTask.Source.ATTACHED, 0, "", xp), "", Collections.singletonList(xa), null);
            T.check(xr.success && h.xlsx.read(xls).contains("B2=2"), "XLSX source remains unchanged");
            T.check(xr.success && h.xlsx.read(xr.file).contains("B2=500"), "XLSX edited copy contains new value");

            File ppt = new File(root, "slides.pptx");
            h.pptx.create(Collections.singletonList(new PptxSkill.Slide("Overview", Collections.singletonList("Ravi here"))), ppt);
            Attachment pa = new Attachment(Attachment.Kind.PPTX, ppt.getName(), ppt.length(), ppt.toURI().toString());
            Map<String,String> pp = map("old", "Ravi here", "new", "Raj here");
            SkillExecution.Result pr = h.exec(new SkillTask(SkillTask.Action.EDIT, SkillKind.PPTX, SkillTask.Source.ATTACHED, 0, "", pp), "", Collections.singletonList(pa), null);
            T.check(pr.success && h.pptx.read(ppt).contains("Ravi here"), "PPTX source remains unchanged");
            T.check(pr.success && h.pptx.read(pr.file).contains("Raj here"), "PPTX edited copy contains replacement");
        } finally { delete(root); }
    }


    private static void chatControllerTests() throws Exception {
        File root = Files.createTempDirectory("mj-stage2-chat").toFile();
        try {
            Harness h = new Harness(root);
            Fakes.FakeEngine e = new Fakes.FakeEngine();
            com.neonhud.app.core.module.ModuleManager mm = new com.neonhud.app.core.module.ModuleManager(
                    e, new Fakes.FakeStorage(), new Fakes.MemStateStore(null));
            com.neonhud.app.core.memory.InMemoryStore store = new com.neonhud.app.core.memory.InMemoryStore();
            com.neonhud.app.core.memory.ConversationBrain brain = new com.neonhud.app.core.memory.ConversationBrain(
                    store, new com.neonhud.app.core.memory.ConversationBrain.Clock() {
                        @Override public long now() { return System.currentTimeMillis(); }
                    });
            com.neonhud.app.core.chat.ChatController chat = new com.neonhud.app.core.chat.ChatController(mm, brain, store);
            chat.setSkillExecution(h.execution);
            mm.requestImport(Fakes.src("g.litertlm", 5));
            Fakes.awaitIdle(mm);
            mm.requestLoad();
            Fakes.awaitIdle(mm);
            e.cannedReply = "[[SKILL_FILE type=pdf title=\"Chat Result\"]]\nCreated from the chat\n[[END_SKILL_FILE]]";

            T.eq(com.neonhud.app.core.chat.ChatController.SendResult.ACCEPTED, chat.send("AI par pdf banao"), "ChatController accepts a CREATE request");
            ChatTests.waitIdle(chat);
            List<com.neonhud.app.core.chat.ChatController.Item> items = chat.items();
            com.neonhud.app.core.chat.ChatController.Item last = items.get(items.size() - 1);
            T.check(last.kind == com.neonhud.app.core.chat.ChatController.Kind.AI && !last.pending, "CREATE ends as a finished AI item");
            T.eq(1, last.outputFiles.size(), "CREATE result is attached to the AI item");
            T.check(last.text.contains("File ready:") && !last.text.contains("SKILL_FILE"), "final chat text reports the verified file, not the marker protocol");
            T.eq(1, h.pdfCreateCalls, "ChatController reached the real skill executor once");
        } finally { delete(root); }
    }

    private static void failureTests() throws Exception {
        File root = Files.createTempDirectory("mj-stage2-fail").toFile();
        try {
            Harness h = new Harness(root);
            SkillTask create = new SkillTask(SkillTask.Action.CREATE, SkillKind.DOCX, SkillTask.Source.TEXT, SkillTask.NO_FILE, "", null);
            SkillExecution.Result missing = h.exec(create, "ordinary model answer without markers");
            T.check(!missing.success, "missing marker is a hard failure");
            T.eq(0, h.published.size(), "missing marker publishes nothing");
            T.eq(0, fileCount(root), "missing marker leaves no output file");

            SkillExecution.Result wrong = h.exec(create,
                    "[[SKILL_FILE type=xlsx title=Wrong]]\n[[SHEET name=S]]\na\tb\n[[END_SHEET]]\n[[END_SKILL_FILE]]");
            T.check(!wrong.success, "wrong output type is rejected instead of silently converted");
            T.eq(0, fileCount(root), "wrong marker leaves no output file");

            File failingOut = new File(root, "publisher-fail");
            SkillExecution failing = new SkillExecution(h.docx, h.xlsx, h.pptx, h.pdf,
                    new SkillExecution.FileResolver() {
                        @Override public File materialize(Attachment a) throws IOException {
                            try { return new File(new URI(a.uri)); }
                            catch (Exception e) { throw new IOException(e); }
                        }
                    },
                    new SkillExecution.OutputPublisher() {
                        @Override public Attachment publish(File f, SkillKind k) throws IOException {
                            throw new IOException("simulated FileProvider publish failure");
                        }
                    }, failingOut);
            SkillExecution.Result publishFail = failing.execute(create,
                    "[[SKILL_FILE type=docx title=No Ghost]]\nThis must be removed on publish failure\n[[END_SKILL_FILE]]",
                    Collections.<Attachment>emptyList(), null);
            T.check(!publishFail.success, "publisher failure is surfaced as a failed operation");
            T.eq(0, fileCount(failingOut), "publisher failure leaves no ghost output file");

            SkillTask pdfEdit = new SkillTask(SkillTask.Action.EDIT, SkillKind.PDF_CREATOR, SkillTask.Source.ATTACHED,
                    0, "", Collections.<String,String>emptyMap());
            Attachment fakePdf = new Attachment(Attachment.Kind.PDF, "a.pdf", 10, new File(root, "a.pdf").toURI().toString());
            T.check(!h.exec(pdfEdit, "", Collections.singletonList(fakePdf), null).success, "PDF EDIT is refused by the executor");
        } finally { delete(root); }
    }

    private static Map<String,String> map(String... kv) {
        Map<String,String> m = new LinkedHashMap<String,String>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static final class Harness {
        final DocxSkill docx = new DocxSkill();
        final XlsxSkill xlsx = new XlsxSkill();
        final PptxSkill pptx = new PptxSkill();
        final FakePdf pdf;
        final List<Attachment> published = new java.util.ArrayList<Attachment>();
        final File out;
        int pdfCreateCalls;
        int pdfVerifyCalls;
        final SkillExecution execution;

        Harness(File root) throws IOException {
            out = new File(root, "outputs");
            pdf = new FakePdf() {
                @Override public File createTextPdf(String title, String text, File output) throws IOException {
                    pdfCreateCalls++;
                    return super.createTextPdf(title, text, output);
                }
                @Override public void verify(File file) throws IOException {
                    super.verify(file);
                    pdfVerifyCalls++;
                }
            };
            execution = new SkillExecution(docx, xlsx, pptx, pdf,
                    new SkillExecution.FileResolver() {
                        @Override public File materialize(Attachment a) throws IOException {
                            try { return new File(new URI(a.uri)); }
                            catch (Exception e) { throw new IOException("bad test URI", e); }
                        }
                    },
                    new SkillExecution.OutputPublisher() {
                        @Override public Attachment publish(File f, SkillKind k) throws IOException {
                            if (!f.isFile() || f.length() <= 0) throw new IOException("publisher received invalid file");
                            Attachment.Kind ak;
                            switch (k) {
                                case DOCX: ak = Attachment.Kind.DOCX; break;
                                case XLSX: ak = Attachment.Kind.XLSX; break;
                                case PPTX: ak = Attachment.Kind.PPTX; break;
                                case PDF_CREATOR: ak = Attachment.Kind.PDF; break;
                                default: throw new IOException("unexpected skill kind");
                            }
                            Attachment a = new Attachment(ak, f.getName(), f.length(), f.toURI().toString());
                            published.add(a);
                            return a;
                        }
                    }, out);
        }

        SkillExecution.Result exec(SkillTask t, String model) { return exec(t, model, Collections.<Attachment>emptyList(), null); }
        SkillExecution.Result exec(SkillTask t, String model, List<Attachment> files, Attachment last) { return execution.execute(t, model, files, last); }
    }

    private static class FakePdf implements PdfWriter {
        @Override public File createTextPdf(String title, String text, File output) throws IOException {
            File parent = output.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) throw new IOException("cannot create pdf output dir");
            FileOutputStream os = new FileOutputStream(output);
            try { os.write(("%PDF-1.4\n% Stage2 fake JVM PDF\n" + title + "\n" + text).getBytes(StandardCharsets.UTF_8)); }
            finally { os.close(); }
            return output;
        }
        @Override public void verify(File file) throws IOException {
            if (!file.isFile() || file.length() <= 8) throw new IOException("fake PDF verification failed");
        }
    }

    private static int fileCount(File root) {
        File[] a = root.listFiles();
        return a == null ? 0 : count(a);
    }

    private static int count(File[] a) {
        int n = 0;
        for (File f : a) {
            n++;
            if (f.isDirectory()) { File[] c = f.listFiles(); if (c != null) n += count(c); }
        }
        return n;
    }

    private static void delete(File f) {
        if (f == null) return;
        if (f.isDirectory()) { File[] kids = f.listFiles(); if (kids != null) for (File k : kids) delete(k); }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
