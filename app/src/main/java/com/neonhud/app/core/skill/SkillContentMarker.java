package com.neonhud.app.core.skill;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strict, line-oriented format emitted by the model for CREATE tasks.
 * It is intentionally simple so a small on-device model can follow it without producing JSON.
 *
 * DOCX/PDF:
 * [[SKILL_FILE type=docx title="Title"]]
 * body lines...
 * [[END_SKILL_FILE]]
 *
 * XLSX:
 * [[SKILL_FILE type=xlsx title="Title"]]
 * [[SHEET name="Sheet1"]]
 * A1<TAB>header<TAB>...
 * [[END_SHEET]]
 * [[END_SKILL_FILE]]
 *
 * PPTX:
 * [[SKILL_FILE type=pptx title="Title"]]
 * [[SLIDE title="Overview"]]
 * First bullet
 * Second bullet
 * [[END_SLIDE]]
 * [[END_SKILL_FILE]]
 */
public final class SkillContentMarker {
    private static final int MAX_MARKERS = 8;
    private static final int MAX_BODY_CHARS = 120_000;
    private static final int MAX_ROWS = 2_000;
    private static final int MAX_SLIDES = 100;

    private static final Pattern START = Pattern.compile(
            "^\\[\\[SKILL_FILE\\s+type=(pdf|docx|xlsx|pptx)(?:\\s+title=(?:\\\"([^\\\"]*)\\\"|'([^']*)'|(\\S+)))?\\s*\\]\\]$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SHEET = Pattern.compile(
            "^\\[\\[SHEET\\s+name=(?:\\\"([^\\\"]*)\\\"|'([^']*)'|(\\S+))\\s*\\]\\]$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SLIDE = Pattern.compile(
            "^\\[\\[SLIDE\\s+title=(?:\\\"([^\\\"]*)\\\"|'([^']*)'|(\\S+))\\s*\\]\\]$",
            Pattern.CASE_INSENSITIVE);

    private SkillContentMarker() { }

    public static final class Sheet {
        public final String name;
        public final List<List<String>> rows;
        Sheet(String name, List<List<String>> rows) {
            this.name = name;
            this.rows = Collections.unmodifiableList(new ArrayList<List<String>>(rows));
        }
    }

    public static final class Slide {
        public final String title;
        public final List<String> bullets;
        Slide(String title, List<String> bullets) {
            this.title = title;
            this.bullets = Collections.unmodifiableList(new ArrayList<String>(bullets));
        }
    }

    public static final class Artifact {
        public final SkillKind kind;
        public final String title;
        public final String text;
        public final List<Sheet> sheets;
        public final List<Slide> slides;
        private Artifact(SkillKind kind, String title, String text, List<Sheet> sheets, List<Slide> slides) {
            this.kind = kind;
            this.title = title == null ? "" : title.trim();
            this.text = text == null ? "" : text;
            this.sheets = Collections.unmodifiableList(new ArrayList<Sheet>(sheets));
            this.slides = Collections.unmodifiableList(new ArrayList<Slide>(slides));
        }
        static Artifact textArtifact(SkillKind k, String title, String text) {
            return new Artifact(k, title, text, Collections.<Sheet>emptyList(), Collections.<Slide>emptyList());
        }
        static Artifact sheetArtifact(String title, List<Sheet> sheets) {
            return new Artifact(SkillKind.XLSX, title, "", sheets, Collections.<Slide>emptyList());
        }
        static Artifact slideArtifact(String title, List<Slide> slides) {
            return new Artifact(SkillKind.PPTX, title, "", Collections.<Sheet>emptyList(), slides);
        }
    }

    public static final class ParseResult {
        public final List<Artifact> artifacts;
        public final String error;
        private ParseResult(List<Artifact> artifacts, String error) {
            this.artifacts = Collections.unmodifiableList(new ArrayList<Artifact>(artifacts));
            this.error = error == null ? "" : error;
        }
        public boolean ok() { return error.isEmpty(); }
    }

    public static ParseResult parse(String modelText) {
        String src = modelText == null ? "" : modelText.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = src.split("\n", -1);
        List<Artifact> out = new ArrayList<Artifact>();
        int i = 0;
        while (i < lines.length) {
            String line = normalizeMarkerLine(lines[i]);
            Matcher sm = START.matcher(line);
            if (!sm.matches()) { i++; continue; }
            if (out.size() >= MAX_MARKERS) return fail("Too many file markers.");
            SkillKind kind = kind(sm.group(1));
            String title = first(sm.group(2), sm.group(3), sm.group(4));
            i++;
            List<String> body = new ArrayList<String>();
            List<Sheet> sheets = new ArrayList<Sheet>();
            List<Slide> slides = new ArrayList<Slide>();
            boolean closed = false;
            int bodyChars = 0;
            while (i < lines.length) {
                String inner = normalizeMarkerLine(lines[i]);
                if ("[[END_SKILL_FILE]]".equalsIgnoreCase(inner)) { closed = true; i++; break; }
                if (kind == SkillKind.XLSX) {
                    Matcher sh = SHEET.matcher(inner);
                    if (sh.matches()) {
                        String name = first(sh.group(1), sh.group(2), sh.group(3));
                        i++;
                        List<List<String>> rows = new ArrayList<List<String>>();
                        boolean endSheet = false;
                        while (i < lines.length) {
                            String r = lines[i];
                            if ("[[END_SHEET]]".equalsIgnoreCase(r.trim())) { endSheet = true; i++; break; }
                            if (r.trim().startsWith("[[")) return fail("Invalid XLSX marker nesting.");
                            if (++bodyChars > MAX_BODY_CHARS) return fail("Artifact content is too large.");
                            if (rows.size() >= MAX_ROWS) return fail("Too many spreadsheet rows.");
                            rows.add(splitRow(r));
                            i++;
                        }
                        if (!endSheet) return fail("Missing [[END_SHEET]].");
                        sheets.add(new Sheet(safeName(name, "Sheet" + (sheets.size() + 1)), rows));
                        continue;
                    }
                } else if (kind == SkillKind.PPTX) {
                    Matcher sl = SLIDE.matcher(inner);
                    if (sl.matches()) {
                        String slideTitle = first(sl.group(1), sl.group(2), sl.group(3));
                        i++;
                        List<String> bullets = new ArrayList<String>();
                        boolean endSlide = false;
                        while (i < lines.length) {
                            String b = lines[i];
                            if ("[[END_SLIDE]]".equalsIgnoreCase(b.trim())) { endSlide = true; i++; break; }
                            if (b.trim().startsWith("[[")) return fail("Invalid PPTX marker nesting.");
                            if (++bodyChars > MAX_BODY_CHARS) return fail("Artifact content is too large.");
                            String clean = b.trim();
                            if (!clean.isEmpty()) bullets.add(clean);
                            if (bullets.size() > MAX_ROWS) return fail("Too many slide lines.");
                            i++;
                        }
                        if (!endSlide) return fail("Missing [[END_SLIDE]].");
                        if (slides.size() >= MAX_SLIDES) return fail("Too many slides.");
                        slides.add(new Slide(slideTitle == null ? "" : slideTitle.trim(), bullets));
                        continue;
                    }
                }
                if (inner.startsWith("[[")) return fail("Unknown marker inside file block.");
                if (++bodyChars > MAX_BODY_CHARS) return fail("Artifact content is too large.");
                body.add(lines[i]);
                i++;
            }
            if (!closed) return fail("Missing [[END_SKILL_FILE]].");
            if (kind == SkillKind.XLSX) {
                if (sheets.isEmpty()) return fail("XLSX file marker needs at least one sheet.");
                out.add(Artifact.sheetArtifact(title, sheets));
            } else if (kind == SkillKind.PPTX) {
                if (slides.isEmpty()) return fail("PPTX file marker needs at least one slide.");
                out.add(Artifact.slideArtifact(title, slides));
            } else {
                String text = join(body);
                if (text.trim().isEmpty()) return fail("File marker contains no content.");
                out.add(Artifact.textArtifact(kind, title, text));
            }
        }
        return new ParseResult(out, out.isEmpty() ? "No SKILL_FILE marker was returned by the model." : "");
    }

    /** Removes one accidental markdown fence wrapped around a marker line. */
    private static String normalizeMarkerLine(String line) {
        if (line == null) return "";
        String s = line.trim();
        while (s.startsWith("```") && s.endsWith("```") && s.length() >= 6)
            s = s.substring(3, s.length() - 3).trim();
        if (s.startsWith("```") && s.length() > 3) s = s.substring(3).trim();
        if (s.endsWith("```") && s.length() > 3) s = s.substring(0, s.length() - 3).trim();
        return s;
    }

    /** Removes every complete artifact block while leaving normal assistant prose intact. */
    public static String stripArtifacts(String modelText) {
        String src = modelText == null ? "" : modelText.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder out = new StringBuilder();
        String[] lines = src.split("\n", -1);
        boolean inside = false;
        for (String line : lines) {
            String t = line.trim();
            if (!inside && START.matcher(t).matches()) { inside = true; continue; }
            if (inside && "[[END_SKILL_FILE]]".equalsIgnoreCase(t)) { inside = false; continue; }
            if (!inside) out.append(line).append('\n');
        }
        return out.toString().trim();
    }


    private static List<String> splitRow(String line) {
        String[] a = line.split("\\t", -1);
        List<String> row = new ArrayList<String>(a.length);
        for (String s : a) row.add(s.replace("\\t", "\t").trim());
        return row;
    }

    private static String join(List<String> lines) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) b.append('\n');
            b.append(lines.get(i));
        }
        return b.toString();
    }

    private static SkillKind kind(String s) {
        String k = s.toLowerCase(Locale.ROOT);
        if ("pdf".equals(k)) return SkillKind.PDF_CREATOR;
        if ("docx".equals(k)) return SkillKind.DOCX;
        if ("xlsx".equals(k)) return SkillKind.XLSX;
        return SkillKind.PPTX;
    }

    private static String first(String... values) {
        for (String v : values) if (v != null) return v;
        return "";
    }

    private static String safeName(String n, String fallback) {
        String s = n == null ? "" : n.trim();
        return s.isEmpty() ? fallback : s;
    }

    private static ParseResult fail(String error) { return new ParseResult(Collections.<Artifact>emptyList(), error); }
}
