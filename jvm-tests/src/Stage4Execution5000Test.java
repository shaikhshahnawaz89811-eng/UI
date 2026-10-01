package tests;

import com.neonhud.app.core.engine.Attachment;
import com.neonhud.app.core.office.DocxSkill;
import com.neonhud.app.core.office.PptxSkill;
import com.neonhud.app.core.office.XlsxSkill;
import com.neonhud.app.core.skill.PdfWriter;
import com.neonhud.app.core.skill.SkillExecution;
import com.neonhud.app.core.skill.SkillKind;
import com.neonhud.app.core.skill.SkillPlan;
import com.neonhud.app.core.skill.SkillRouter;
import com.neonhud.app.core.skill.SkillTask;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stage 4 layer 2: every WRITE task emitted from all 5,000 rows is executed with the real JVM Office skills and
 * the real SkillExecution pipeline. CREATE tasks use a fake model response; edit tasks always operate on a copy.
 * The manifest is validated by Python's python-docx/openpyxl/python-pptx before the temporary corpus is removed.
 */
final class Stage4Execution5000Test {
    public static void main(String[] args) throws Exception { run(); }
    static final String DATA = "../phase5/stage4_5000.tsv";
    static final String PY_VALIDATOR = "../phase5/validate_stage4_artifacts.py";
    private static final List<String> OLD = Arrays.asList("Ravi", "Draft", "Old title", "Pending", "Alpha", "Version 1", "Mumbai", "Monday");
    private static final List<String> NEW = Arrays.asList("Raj", "Final", "New title", "Done", "Beta", "Version 2", "Pune", "Friday");
    private static final List<String> SHEETS = Arrays.asList("Data", "Sales", "Summary", "Budget", "Report", "Input", "Output", "Sheet1");
    private static final List<String> CELLS = Arrays.asList("B2", "C3", "D4", "E5", "F6", "G7", "H8", "A10");

    static void run() throws Exception {
        T.section("STAGE 4: 5,000-row real skill execution + file validation");
        List<Stage4QuestionSetTest.Row> rows = Stage4QuestionSetTest.load(new File(System.getProperty("stage4.questions", DATA)));
        File root = Files.createTempDirectory("neon-stage4-deep-").toFile();
        File outputs = new File(root, "outputs");
        if (!outputs.mkdirs() && !outputs.isDirectory()) throw new IOException("cannot create stage4 output directory");
        File manifest = new File(root, "manifest.tsv");
        int writeTasks = 0, successful = 0, creates = 0, edits = 0;
        try {
            final BufferedWriter mw = new BufferedWriter(new FileWriter(manifest));
            mw.write("row_id\ttask_index\taction\tskill\tpath\n");
            try {
                Harness h = new Harness(outputs, mw);
                for (Stage4QuestionSetTest.Row row : rows) {
                    List<Attachment> descriptors = fixtureDescriptors(root, row, row.id);
                    SkillPlan plan = SkillRouter.route(row.command, descriptors);
                    List<Attachment> attachments = materializeFixtures(root, descriptors, plan, row);
                    if (plan.needsClarify()) {
                        T.check(!plan.hasWriteTask(), "#" + row.id + " clarify has no writes");
                        continue;
                    }
                    String model = modelFor(plan, row.id);
                    Map<SkillKind,Integer> ordinals = new HashMap<SkillKind,Integer>();
                    Attachment lastOutput = null;
                    for (int ti = 0; ti < plan.tasks.size(); ti++) {
                        SkillTask task = plan.tasks.get(ti);
                        if (!task.writes()) continue;
                        writeTasks++;
                        if (task.action == SkillTask.Action.CREATE) creates++; else edits++;
                        int ordinal = 0;
                        if (task.action == SkillTask.Action.CREATE) {
                            Integer n = ordinals.get(task.skill); ordinal = n == null ? 0 : n;
                            ordinals.put(task.skill, ordinal + 1);
                        }
                        String before = null;
                        Attachment source = null;
                        if (task.action == SkillTask.Action.EDIT && task.fileIndex >= 0 && task.fileIndex < attachments.size()) {
                            source = attachments.get(task.fileIndex);
                            before = readSource(h, source);
                        }
                        SkillExecution.Result r = h.execution.execute(task, model, attachments, lastOutput, ordinal);
                        T.check(r.success, "#" + row.id + " task " + ti + " " + task + " executes");
                        if (!r.success) {
                            if (task.action == SkillTask.Action.EDIT) {
                                T.check(false, "#" + row.id + " edit failure: " + r.message);
                            }
                            continue;
                        }
                        successful++;
                        T.check(r.file != null && r.file.isFile() && r.file.length() > 0, "#" + row.id + " produced a real output file");
                        if (task.action == SkillTask.Action.EDIT && source != null && before != null) {
                            String after = readSource(h, source);
                            T.eq(before, after, "#" + row.id + " edit leaves original source unchanged");
                        }
                        if (task.action == SkillTask.Action.CREATE) lastOutput = r.output;
                    }
                }
            } finally { mw.close(); }
            T.eq(writeTasks, successful, "every routed write task succeeds in real SkillExecution");
            T.check(creates > 1000, "deep execution covers the 1,000 create rows plus write tasks from multi cases");
            T.check(edits >= 400, "deep execution covers the 600 edit rows except the intentional Excel clarify cases");
            runPythonValidator(manifest);
        } finally {
            delete(root);
        }
        System.out.println("  writeTasks=" + writeTasks + ", creates=" + creates + ", edits=" + edits + ", successful=" + successful);
    }

    private static String readSource(Harness h, Attachment a) throws Exception {
        return h.read(a);
    }

    private static List<Attachment> fixtureDescriptors(File root, Stage4QuestionSetTest.Row row, int id) throws Exception {
        List<Attachment> out = new ArrayList<Attachment>();
        if (row.attachments.equals("-")) return out;
        String[] specs = row.attachments.split(",");
        for (int i = 0; i < specs.length; i++) {
            Attachment.Kind k = Attachment.Kind.valueOf(specs[i]);
            File f = new File(root, "src-" + id + "-" + i + extension(k));
            out.add(new Attachment(k, f.getName(), 100, f.toURI().toString()));
        }
        return out;
    }

    private static List<Attachment> materializeFixtures(File root, List<Attachment> descriptors, SkillPlan plan, Stage4QuestionSetTest.Row row) throws Exception {
        Map<Integer,String> sheetByIndex = new HashMap<Integer,String>();
        for (SkillTask t : plan.tasks) {
            if (t.action == SkillTask.Action.EDIT && t.skill == SkillKind.XLSX && t.fileIndex >= 0) {
                String sheet = t.params.get("sheet");
                if (sheet != null && !sheet.trim().isEmpty()) sheetByIndex.put(t.fileIndex, sheet.trim());
            }
        }
        List<Attachment> out = new ArrayList<Attachment>();
        for (int i = 0; i < descriptors.size(); i++) {
            Attachment d = descriptors.get(i);
            File f = new File(root, d.name);
            String sheet = sheetByIndex.containsKey(i) ? sheetByIndex.get(i) : "Data";
            createFixture(d.kind, f, row.command, sheet);
            out.add(new Attachment(d.kind, f.getName(), f.length(), f.toURI().toString()));
        }
        return out;
    }

    private static void createFixture(Attachment.Kind kind, File f, String command, String xlsxSheet) throws Exception {
        if (kind == Attachment.Kind.DOCX) {
            new DocxSkill().create("Fixture", OLD, f);
        } else if (kind == Attachment.Kind.PPTX) {
            new PptxSkill().create(Collections.singletonList(new PptxSkill.Slide("Fixture", OLD)), f);
        } else if (kind == Attachment.Kind.XLSX) {
            List<List<String>> rows = new ArrayList<List<String>>();
            rows.add(Arrays.asList("Fixture", "2", "3", "4", "5", "6", "7", "8"));
            rows.add(Arrays.asList("row2", "OLD", "OLD", "OLD", "OLD", "OLD", "OLD", "OLD"));
            new XlsxSkill().create(xlsxSheet == null || xlsxSheet.isEmpty() ? "Data" : xlsxSheet, rows, f);
        } else if (kind == Attachment.Kind.PDF) {
            writeBytes(f, "%PDF-1.4\n% fixture\n%%EOF\n");
        } else if (kind == Attachment.Kind.ZIP) {
            java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(new FileOutputStream(f));
            z.putNextEntry(new java.util.zip.ZipEntry("fixture.txt")); z.write(command.getBytes(StandardCharsets.UTF_8)); z.closeEntry(); z.close();
        } else if (kind == Attachment.Kind.IMAGE) {
            writeBytes(f, "not-a-real-image");
        } else if (kind == Attachment.Kind.AUDIO) {
            writeBytes(f, "fixture-audio");
        } else if (kind == Attachment.Kind.VIDEO) {
            writeBytes(f, "fixture-video");
        }
    }

    private static void writeBytes(File f, String s) throws Exception {
        FileOutputStream out = new FileOutputStream(f); try { out.write(s.getBytes(StandardCharsets.UTF_8)); } finally { out.close(); }
    }

    private static String modelFor(SkillPlan plan, int rowId) {
        StringBuilder b = new StringBuilder();
        int n = 1;
        for (SkillTask t : plan.tasks) {
            if (t.action != SkillTask.Action.CREATE) continue;
            if (b.length() > 0) b.append('\n');
            b.append(marker(t.skill, rowId, n++));
        }
        return b.toString();
    }

    private static String marker(SkillKind kind, int id, int n) {
        String title = "Stage4 Row " + id + " Item " + n;
        if (kind == SkillKind.DOCX) return "[[SKILL_FILE type=docx title=\"" + title + "\"]]\nStage4 row " + id + "\ncreated document\n[[END_SKILL_FILE]]";
        if (kind == SkillKind.PDF_CREATOR) return "[[SKILL_FILE type=pdf title=\"" + title + "\"]]\nStage4 row " + id + "\ncreated document\n[[END_SKILL_FILE]]";
        if (kind == SkillKind.XLSX) return "[[SKILL_FILE type=xlsx title=\"" + title + "\"]]\n[[SHEET name=\"Data\"]]\nID\t" + id + "\nText\tStage4 row " + id + "\n[[END_SHEET]]\n[[END_SKILL_FILE]]";
        if (kind == SkillKind.PPTX) return "[[SKILL_FILE type=pptx title=\"" + title + "\"]]\n[[SLIDE title=\"Stage4\"]]\nStage4 row " + id + "\ncreated presentation\n[[END_SLIDE]]\n[[END_SKILL_FILE]]";
        throw new IllegalArgumentException("unsupported create skill " + kind);
    }

    private static void runPythonValidator(File manifest) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("python3", PY_VALIDATOR, manifest.getAbsolutePath());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        InputStream in = p.getInputStream();
        byte[] buf = new byte[4096]; int n; while ((n = in.read(buf)) >= 0) baos.write(buf, 0, n);
        int exit = p.waitFor();
        String output = new String(baos.toByteArray(), StandardCharsets.UTF_8).trim();
        System.out.println("  python-office-validation: " + output.replace('\n', ' '));
        T.eq(0, exit, "python-docx/openpyxl/python-pptx reopen every generated Office output");
    }

    private static String extension(Attachment.Kind k) {
        switch (k) { case DOCX: return ".docx"; case XLSX: return ".xlsx"; case PPTX: return ".pptx"; case PDF: return ".pdf"; case ZIP: return ".zip"; case IMAGE: return ".bin"; case AUDIO: return ".bin"; case VIDEO: return ".bin"; default: return ".bin"; }
    }

    private static void delete(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles(); if (kids != null) for (File k : kids) delete(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    static final class Harness {
        final DocxSkill docx = new DocxSkill(); final XlsxSkill xlsx = new XlsxSkill(); final PptxSkill pptx = new PptxSkill(); final PdfWriter pdf;
        final SkillExecution execution;
        final File outputs;
        Harness(File outputs, final BufferedWriter manifest) {
            this.outputs = outputs;
            this.pdf = new PdfWriter() {
                public File createTextPdf(String title, String text, File output) throws IOException {
                    File parent=output.getParentFile(); if(parent!=null && !parent.exists() && !parent.mkdirs()) throw new IOException("pdf output dir");
                    FileOutputStream o=new FileOutputStream(output); try { o.write(("%PDF-1.4\n% Stage4 JVM PDF\n" + title + "\n" + text + "\n%%EOF\n").getBytes(StandardCharsets.UTF_8)); } finally {o.close();} return output;
                }
                public void verify(File file) throws IOException {
                    if (!file.isFile() || file.length()<12) throw new IOException("PDF output missing/empty");
                    FileInputStream in=new FileInputStream(file); byte[] b=new byte[5]; try {if(in.read(b)!=5 || !"%PDF-".equals(new String(b,StandardCharsets.US_ASCII))) throw new IOException("PDF header invalid");} finally {in.close();}
                }
            };
            execution = new SkillExecution(docx,xlsx,pptx,pdf,
                    new SkillExecution.FileResolver(){ public File materialize(Attachment a)throws IOException{try{return new File(new URI(a.uri));}catch(Exception e){throw new IOException(e);}}},
                    new SkillExecution.OutputPublisher(){ public Attachment publish(File f, SkillKind k)throws IOException{if(!f.isFile()||f.length()==0)throw new IOException("invalid output"); Attachment.Kind ak=attachmentKind(k); try {manifest.write("-1\t-1\tWRITE\t"+k.name()+"\t"+f.getAbsolutePath().replace('\t','_')+"\n"); manifest.flush();}catch(IOException e){throw e;} return new Attachment(ak,f.getName(),f.length(),f.toURI().toString());}},outputs);
        }
        String read(Attachment a)throws Exception{File f=new File(new URI(a.uri));switch(a.kind){case DOCX:return docx.read(f);case XLSX:return xlsx.read(f);case PPTX:return pptx.read(f);default:return a.name;} }
        static Attachment.Kind attachmentKind(SkillKind k){switch(k){case DOCX:return Attachment.Kind.DOCX;case XLSX:return Attachment.Kind.XLSX;case PPTX:return Attachment.Kind.PPTX;case PDF_CREATOR:return Attachment.Kind.PDF;default:return Attachment.Kind.ZIP;}}
    }
}
