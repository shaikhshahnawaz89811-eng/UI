package com.neonhud.app.core.skill;

import com.neonhud.app.core.engine.Attachment;
import com.neonhud.app.core.office.DocxSkill;
import com.neonhud.app.core.office.PptxSkill;
import com.neonhud.app.core.office.XlsxSkill;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Executes router tasks and refuses to report success until the output is verified. */
public final class SkillExecution {
    public interface FileResolver {
        File materialize(Attachment attachment) throws IOException;
    }

    public interface OutputPublisher {
        Attachment publish(File file, SkillKind kind) throws IOException;
    }

    public static final class Result {
        public final boolean success;
        public final String message;
        public final File file;
        public final Attachment output;
        private Result(boolean success, String message, File file, Attachment output) {
            this.success = success;
            this.message = message == null ? "" : message;
            this.file = file;
            this.output = output;
        }
        public static Result ok(String message, File file, Attachment output) { return new Result(true, message, file, output); }
        public static Result fail(String message) { return new Result(false, message, null, null); }
    }

    private final DocxSkill docx;
    private final XlsxSkill xlsx;
    private final PptxSkill pptx;
    private final PdfWriter pdf;
    private final FileResolver resolver;
    private final OutputPublisher publisher;
    private final File outputDir;

    public SkillExecution(DocxSkill docx, XlsxSkill xlsx, PptxSkill pptx, PdfWriter pdf,
                          FileResolver resolver, OutputPublisher publisher, File outputDir) {
        if (docx == null || xlsx == null || pptx == null || pdf == null || resolver == null || publisher == null || outputDir == null)
            throw new IllegalArgumentException("all skill execution dependencies are required");
        this.docx = docx; this.xlsx = xlsx; this.pptx = pptx; this.pdf = pdf;
        this.resolver = resolver; this.publisher = publisher; this.outputDir = outputDir;
    }

    public Result execute(SkillTask task, String modelText, List<Attachment> attachments, Attachment lastOutput) {
        return execute(task, modelText, attachments, lastOutput, 0);
    }

    /**
     * Executes one task. For CREATE, artifactOrdinal selects the Nth marker of the same skill kind in the model response,
     * which prevents two same-format CREATE tasks from accidentally producing the first artifact twice.
     */
    public synchronized Result execute(SkillTask task, String modelText, List<Attachment> attachments, Attachment lastOutput, int artifactOrdinal) {
        if (task == null || !task.writes()) return Result.fail("No file-writing task was supplied.");
        if (artifactOrdinal < 0) return Result.fail("Invalid file artifact order.");
        if (task.action == SkillTask.Action.EDIT) {
            try { return executeEdit(task, attachments, lastOutput); }
            catch (Throwable e) { return Result.fail("File not created: " + message(e)); }
        }
        File out = null;
        try {
            SkillContentMarker.ParseResult parsed = SkillContentMarker.parse(modelText);
            if (!parsed.ok()) return Result.fail(parsed.error);
            SkillContentMarker.Artifact artifact = findArtifact(parsed.artifacts, task.skill, artifactOrdinal);
            if (artifact == null) return Result.fail("The model did not return the required " + label(task.skill) + " file marker #" + (artifactOrdinal + 1) + ".");
            out = uniqueOutputFile(artifact.title, extension(task.skill));
            create(task.skill, artifact, out);
            verify(task.skill, artifact, out);
            Attachment published = publisher.publish(out, task.skill);
            return Result.ok("File ready: " + out.getName(), out, published);
        } catch (Throwable e) {
            if (out != null) deleteQuietly(out);
            return Result.fail("File not created: " + message(e));
        }
    }

    private Result executeEdit(SkillTask task, List<Attachment> attachments, Attachment lastOutput) throws IOException {
        Attachment source = resolveAttachment(task.fileIndex, attachments, lastOutput);
        if (source == null) return Result.fail("File not created: the source file is missing.");
        File input = resolver.materialize(source);
        File out = uniqueEditedFile(source.name, extension(task.skill));
        try {
            if (task.skill == SkillKind.DOCX) {
                String oldText = value(task, "old");
                String newText = value(task, "new");
                docx.replaceText(input, oldText, newText, out);
                String read = docx.read(out);
                if (!read.contains(newText)) throw new IOException("read-back verification did not find the replacement text");
            } else if (task.skill == SkillKind.PPTX) {
                String oldText = value(task, "old");
                String newText = value(task, "new");
                pptx.replaceText(input, oldText, newText, out);
                String read = pptx.read(out);
                if (!read.contains(newText)) throw new IOException("read-back verification did not find the replacement text");
            } else if (task.skill == SkillKind.XLSX) {
                String sheet = optionalValue(task, "sheet");
                String cell = value(task, "cell");
                String newValue = value(task, "value");
                if (sheet.isEmpty()) sheet = singleSheetName(input);
                xlsx.setCell(input, sheet, cell, newValue, out);
                String read = xlsx.read(out);
                if (!read.contains(cell.toUpperCase(Locale.ROOT) + "=" + newValue)
                        && !(newValue.startsWith("=") && read.contains(cell.toUpperCase(Locale.ROOT) + "= [formula:" + newValue + "]"))) {
                    throw new IOException("read-back verification did not find the edited cell value");
                }
            } else {
                return Result.fail("PDF editing is not supported.");
            }
            Attachment published = publisher.publish(out, task.skill);
            return Result.ok("File ready: " + out.getName(), out, published);
        } catch (Throwable e) {
            deleteQuietly(out);
            if (e instanceof IOException) throw (IOException)e;
            if (e instanceof RuntimeException) throw (RuntimeException)e;
            throw new IOException(e);
        } finally {
            if (input != null && input.exists() && isTemp(input, source)) deleteQuietly(input);
        }
    }

    private void create(SkillKind kind, SkillContentMarker.Artifact a, File out) throws IOException {
        if (kind == SkillKind.DOCX) {
            List<String> p = splitParagraphs(a.text);
            docx.create(a.title, p, out);
        } else if (kind == SkillKind.PDF_CREATOR) {
            pdf.createTextPdf(a.title, a.text, out);
        } else if (kind == SkillKind.XLSX) {
            if (a.sheets.isEmpty()) throw new IOException("XLSX marker contains no sheets");
            // Current XlsxSkill creates one sheet at a time; keep a single-sheet contract for deterministic output.
            if (a.sheets.size() != 1) throw new IOException("XLSX creation currently requires exactly one sheet");
            xlsx.create(a.sheets.get(0).name, a.sheets.get(0).rows, out);
        } else if (kind == SkillKind.PPTX) {
            List<PptxSkill.Slide> slides = new ArrayList<PptxSkill.Slide>();
            for (SkillContentMarker.Slide s : a.slides) slides.add(new PptxSkill.Slide(s.title, s.bullets));
            pptx.create(slides, out);
        } else throw new IOException("Unsupported create skill: " + kind);
    }

    private void verify(SkillKind kind, SkillContentMarker.Artifact a, File out) throws IOException {
        if (!out.isFile() || out.length() <= 0) throw new IOException("output file is missing or empty");
        if (kind == SkillKind.DOCX) {
            String r = docx.read(out);
            if (!r.contains(firstNeedle(a))) throw new IOException("DOCX read-back verification failed");
        } else if (kind == SkillKind.XLSX) {
            String r = xlsx.read(out);
            String needle = firstSheetNeedle(a);
            if (!needle.isEmpty() && !r.contains(needle)) throw new IOException("XLSX read-back verification failed");
        } else if (kind == SkillKind.PPTX) {
            String r = pptx.read(out);
            if (!a.slides.isEmpty() && !r.contains(a.slides.get(0).title)) throw new IOException("PPTX read-back verification failed");
        } else if (kind == SkillKind.PDF_CREATOR) {
            pdf.verify(out);
        }
    }

    private static String firstNeedle(SkillContentMarker.Artifact a) {
        if (a.text != null) {
            String[] lines = a.text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
            for (String line : lines) if (!line.trim().isEmpty()) return line.trim();
        }
        return a.title == null ? "" : a.title.trim();
    }

    private static String firstSheetNeedle(SkillContentMarker.Artifact a) {
        if (a.sheets.isEmpty() || a.sheets.get(0).rows.isEmpty()) return a.sheets.get(0).name;
        List<String> row = a.sheets.get(0).rows.get(0);
        return row.isEmpty() ? a.sheets.get(0).name : row.get(0);
    }

    private static SkillContentMarker.Artifact findArtifact(List<SkillContentMarker.Artifact> list, SkillKind kind, int ordinal) {
        int seen = 0;
        for (SkillContentMarker.Artifact a : list) {
            if (a.kind != kind) continue;
            if (seen++ == ordinal) return a;
        }
        return null;
    }

    private Attachment resolveAttachment(int index, List<Attachment> files, Attachment lastOutput) {
        if (index == SkillTask.LAST_OUTPUT) return lastOutput;
        if (index < 0 || files == null || index >= files.size()) return null;
        return files.get(index);
    }

    private File uniqueOutputFile(String title, String ext) throws IOException {
        ensureOutputDir();
        String base = safeBase(title, "document");
        return nextAvailable(new File(outputDir, base + ext));
    }

    private File uniqueEditedFile(String sourceName, String ext) throws IOException {
        ensureOutputDir();
        String source = sourceName == null ? "document" : sourceName;
        int dot = source.lastIndexOf('.');
        String base = dot > 0 ? source.substring(0, dot) : source;
        return nextAvailable(new File(outputDir, safeBase(base, "document") + "-edited" + ext));
    }

    private void ensureOutputDir() throws IOException {
        if (!outputDir.exists() && !outputDir.mkdirs() && !outputDir.exists()) throw new IOException("cannot create skill output directory");
    }

    private static File nextAvailable(File first) {
        if (!first.exists()) return first;
        String n = first.getName(), dir = first.getParent();
        int dot = n.lastIndexOf('.');
        String base = dot > 0 ? n.substring(0, dot) : n, ext = dot > 0 ? n.substring(dot) : "";
        for (int i = 2; i < 10000; i++) {
            File f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
        return new File(dir, base + "-" + System.currentTimeMillis() + ext);
    }

    private static String safeBase(String title, String fallback) {
        String s = title == null ? "" : title.trim().replaceAll("[\\\\/:*?\"<>|]+", "_");
        s = s.replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) s = fallback;
        if (s.length() > 80) s = s.substring(0, 80).trim();
        return s;
    }

    private static String extension(SkillKind kind) {
        switch (kind) {
            case DOCX: return ".docx";
            case XLSX: return ".xlsx";
            case PPTX: return ".pptx";
            case PDF_CREATOR: return ".pdf";
            default: return ".bin";
        }
    }

    private static String label(SkillKind kind) {
        switch (kind) {
            case DOCX: return "Word";
            case XLSX: return "Excel";
            case PPTX: return "PowerPoint";
            case PDF_CREATOR: return "PDF";
            default: return kind.name();
        }
    }

    private static String value(SkillTask t, String key) {
        String v = t.params.get(key);
        if (v == null || v.trim().isEmpty()) throw new IllegalArgumentException("edit parameter missing: " + key);
        return v;
    }

    private static String optionalValue(SkillTask t, String key) {
        String v = t.params.get(key);
        return v == null ? "" : v.trim();
    }

    private String singleSheetName(File workbook) throws IOException {
        String report = xlsx.read(workbook);
        List<String> names = new ArrayList<String>();
        String prefix = "SHEET: ";
        String[] lines = report.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        for (String line : lines) if (line.startsWith(prefix)) names.add(line.substring(prefix.length()).trim());
        if (names.size() == 1 && !names.get(0).isEmpty()) return names.get(0);
        if (names.size() > 1) throw new IOException("which Excel sheet should be edited? The request did not name a sheet.");
        throw new IOException("Excel sheet name is missing and the workbook has no readable sheet.");
    }

    private static List<String> splitParagraphs(String text) {
        String s = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
        String[] a = s.split("\\n", -1);
        List<String> out = new ArrayList<String>(a.length);
        Collections.addAll(out, a);
        return out;
    }

    private static void deleteQuietly(File f) {
        if (f != null && f.exists()) {
            try { f.delete(); } catch (Throwable ignored) {}
        }
    }

    private static String message(Throwable e) {
        if (e == null) return "unknown error";
        String m = e.getMessage();
        return m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m;
    }

    // The resolver may return the source itself for a file:// or app-owned content URI.
    private static boolean isTemp(File input, Attachment source) { return input != null && source != null && !source.uri.equals(input.toURI().toString()); }
}
