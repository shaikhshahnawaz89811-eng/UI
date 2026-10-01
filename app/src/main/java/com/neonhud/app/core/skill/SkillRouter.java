package com.neonhud.app.core.skill;

import com.neonhud.app.core.engine.Attachment;
import com.neonhud.app.core.engine.ReadSelection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic router: one chat message (text + attached files) -> {@link SkillPlan}.
 * Pure Java, no model. Rules are written down in SKILLS_WIRING_PLAN.md section 3.
 * It never guesses: a missing file, format, topic or edit parameter becomes ONE clarifying question.
 */
public final class SkillRouter {
    private SkillRouter() { }

    public static final int MAX_WRITE_TASKS = 3;

    // ---------- vocabulary ----------
    private static final Map<String, String> SPELL = new HashMap<String, String>();
    static {
        for (String s : new String[]{"banaao", "banaaoo", "bnao", "banaw", "banau", "banaoo", "bnaao", "banaao"}) SPELL.put(s, "banao");
        for (String s : new String[]{"excle", "exel", "excell", "exal", "xcel", "excl", "eksel"}) SPELL.put(s, "excel");
        for (String s : new String[]{"xlxs", "xlx", "xlsxx", "xls"}) SPELL.put(s, "xlsx");
        for (String s : new String[]{"wrod", "wod", "wrd"}) SPELL.put(s, "word");
        for (String s : new String[]{"pdff", "pdfs", "pfd", "pdf's"}) SPELL.put(s, "pdf");
        for (String s : new String[]{"presentaion", "presenation", "prezentation", "presentatoin", "presntation", "prsentation"}) SPELL.put(s, "presentation");
        for (String s : new String[]{"powerpnt", "pwerpoint", "powerpiont", "powrpoint"}) SPELL.put(s, "powerpoint");
        for (String s : new String[]{"spredsheet", "spreadsheat", "spreadshet"}) SPELL.put(s, "spreadsheet");
        for (String s : new String[]{"dcoument", "documnt", "documet", "docment"}) SPELL.put(s, "document");
        for (String s : new String[]{"slids", "slides", "sldes"}) SPELL.put(s, "slide");
        for (String s : new String[]{"pptx", "ppts", "pptt"}) SPELL.put(s, "ppt");
        for (String s : new String[]{"doc", "docs"}) SPELL.put(s, "docxfmt");
        for (String s : new String[]{"nahin", "nhi", "nai"}) SPELL.put(s, "nahi");
    }

    private static final Set<String> CREATE_VERBS = set("banao", "bana", "bnao", "banado", "banade", "banakar", "banakr", "banaiye",
            "banaye", "make", "create", "generate", "export", "convert", "nikal", "nikalo", "nikaal", "nikaalo", "daal", "daalo",
            "dal", "dalo", "daaldo", "daldo", "dedo", "chahiye", "chahie", "chaiye", "save", "build", "prepare", "tayar", "taiyar");
    private static final Set<String> READ_VERBS = set("padho", "padh", "dekho", "dekh", "read", "summarize", "summarise", "batao", "bata",
            "explain", "samjhao", "extract", "check", "review", "analyze", "analyse", "translate", "open", "kholo");
    private static final Set<String> EDIT_CUES = set("likh", "likho", "daal", "daalo", "dal", "set", "kar", "karo", "rakh", "lagao", "laga",
            "bhar", "update", "change", "badlo", "badal", "put", "write", "enter", "fill", "type", "replace", "likhdo", "likhde", "daaldo", "kardo", "rakhdo", "likhna", "karna");
    private static final Set<String> PURE_EDIT_WORDS = set("edit", "update", "modify", "sudharo", "sudhaar", "fix", "correct", "change", "badlav");
    private static final Set<String> NEG_HI = set("mat", "nahi", "na");
    private static final Set<String> NEG_EN = set("dont", "no", "never", "without", "not", "bina");
    private static final Set<String> PRONOUNS = set("isko", "ise", "isse", "usko", "use", "iska", "iski", "iske", "uska", "uski", "uske",
            "ye", "yeh", "yah", "this", "it", "that", "these", "isme", "usme");
    private static final Set<String> HOWTO = set("kaise", "kese", "kaisey", "how", "python", "java", "kotlin", "javascript", "api", "library",
            "code", "coding", "function", "script", "syntax", "matlab", "meaning", "difference", "farak", "shortcut", "tutorial", "formula");
    private static final Set<String> AMBIG_DOC = set("document", "report", "resume", "cv", "letter", "invoice", "certificate");
    private static final Set<String> UNSUPPORTED_CREATE = set("csv", "odt", "rtf", "epub", "html", "json", "txt");
    private static final Set<String> FILLER = set("par", "pe", "ka", "ki", "ke", "me", "mein", "mai", "main", "liye", "about", "on", "a", "an",
            "the", "ek", "mujhe", "mere", "meri", "mera", "please", "pls", "plz", "bhai", "yaar", "ab", "file", "format", "ko", "se", "to",
            "of", "for", "aur", "and", "bhi", "sirf", "only", "hai", "ho", "hain", "chahiye", "dono", "teeno", "mere", "apne", "apna", "ye", "yeh");
    private static final Set<String> TRANSCRIPT = set("transcript", "transcribe", "transcription", "subtitle", "subtitles", "caption", "captions",
            "srt");
    private static final Set<String> AV_WORDS = set("audio", "video", "recording", "mp3", "mp4", "wav", "voice", "awaaz", "gaana", "gana", "song");
    private static final Set<String> NOT_CELLS = set("mp3", "mp4", "m4a", "h264", "h265", "e2e", "b2b", "b2c", "p2p");

    private static final Set<String> QUESTION = set("kya", "kaise", "kab", "kyun", "kyu", "kitna", "kitne", "kaun", "kaunsa", "what", "why", "when", "bolna", "likhna");

    private static Set<String> set(String... v) { return new HashSet<String>(Arrays.asList(v)); }

    private enum Fmt { PDF, DOCX, XLSX, PPTX }

    private static SkillKind skillOf(Fmt f) {
        switch (f) {
            case PDF: return SkillKind.PDF_CREATOR;
            case DOCX: return SkillKind.DOCX;
            case XLSX: return SkillKind.XLSX;
            default: return SkillKind.PPTX;
        }
    }

    private static String nameOf(Fmt f) {
        switch (f) {
            case PDF: return "PDF";
            case DOCX: return "Word";
            case XLSX: return "Excel";
            default: return "PowerPoint";
        }
    }

    // ---------- public API ----------
    public static SkillPlan route(String rawText, List<Attachment> files) {
        return route(rawText, files, RouterContext.EMPTY);
    }

    public static SkillPlan route(String rawText, List<Attachment> filesIn, RouterContext ctx) {
        String raw = rawText == null ? "" : rawText.replace('\u2018', '\'').replace('\u2019', '\'').replace('\u201c', '"').replace('\u201d', '"');
        List<Attachment> files = filesIn == null ? new ArrayList<Attachment>() : filesIn;
        if (ctx == null) ctx = RouterContext.EMPTY;
        List<SkillTask> tasks = new ArrayList<SkillTask>();
        List<String> unsupported = new ArrayList<String>();
        List<String> notDone = new ArrayList<String>();

        // 1) reading follows the attachments, never the words (so "music theory" cannot select the audio reader).
        // An explicit page/slide/sheet selection is carried on the READ task; the real Android loader applies it.
        List<String> allTokens = tokens(raw);
        ReadSelection currentSelection = readSelection(raw, allTokens);
        int targetedRead = resolveExplicitReadAttachment(raw, allTokens, files);
        if (targetedRead == -2) {
            return new SkillPlan(tasks, "Kaunsi file padhni hai? Naam ya \"pehli / doosri\" batao.", unsupported, notDone);
        }
        boolean hasAv = false;
        for (int i = 0; i < files.size(); i++) {
            Attachment a = files.get(i);
            if (a == null || a.kind == null) continue;
            if (targetedRead >= 0 && i != targetedRead) continue;
            SkillKind k = readerFor(a.kind);
            if (k != null) tasks.add(SkillTask.read(k, i, selectionForAttachment(a.kind, currentSelection)));
            if (a.kind == Attachment.Kind.AUDIO || a.kind == Attachment.Kind.VIDEO) hasAv = true;
            if (LEGACY_NAME.matcher(a.name).find()) unsupported.add("Purani file (.doc / .xls / .ppt) padhna abhi supported nahi; .docx / .xlsx / .pptx chahiye.");
        }

        // A follow-up can reuse the last read file without forcing the user to attach it again.
        if (files.isEmpty() && !ctx.cachedReads.isEmpty() && isCachedReadRequest(allTokens, currentSelection)) {
            List<Integer> candidates = cachedCandidates(ctx.cachedReads, currentSelection);
            if (candidates.size() == 1) {
                int idx = candidates.get(0);
                Attachment a = ctx.cachedReads.get(idx);
                SkillKind k = readerFor(a.kind);
                if (k != null) tasks.add(SkillTask.readCached(k, idx, selectionForAttachment(a.kind, currentSelection)));
            } else if (candidates.size() > 1) {
                String chosen = chooseCachedByOrdinalOrName(raw, allTokens, ctx.cachedReads, candidates);
                if (chosen == null) return new SkillPlan(tasks, "Kaunsi saved file padhni hai? Naam ya \"pehli / doosri\" batao.", unsupported, notDone);
                int idx = Integer.parseInt(chosen);
                Attachment a = ctx.cachedReads.get(idx);
                SkillKind k = readerFor(a.kind);
                if (k != null) tasks.add(SkillTask.readCached(k, idx, selectionForAttachment(a.kind, currentSelection)));
            }
        }
        if (files.isEmpty() && !tasks.isEmpty() && isCachedReadRequest(allTokens, currentSelection)) {
            return new SkillPlan(tasks, "", unsupported, notDone);
        }
        if (LEGACY_TEXT.matcher(raw).find() && unsupported.isEmpty())
            unsupported.add("Purani file (.doc / .xls / .ppt) abhi supported nahi; .docx / .xlsx / .pptx chahiye.");

        if (!unsupported.isEmpty()) return new SkillPlan(tasks, "", unsupported, notDone);   // .doc / .xls / .ppt: say so, do not guess

        // 2) transcript requests are honest "not available"
        boolean transcriptWord = false;
        for (String t : allTokens) if (TRANSCRIPT.contains(t)) transcriptWord = true;
        if (containsSeq(allTokens, "speech", "to", "text") || containsSeq(allTokens, "kya", "bola") || containsSeq(allTokens, "audio", "ko", "text")
                || containsSeq(allTokens, "video", "ko", "text") || containsSeq(allTokens, "awaaz", "ko", "text")) transcriptWord = true;
        boolean avWord = false;
        for (String t : allTokens) if (AV_WORDS.contains(t)) avWord = true;
        if (transcriptWord && (hasAv || avWord)) unsupported.add("Speech transcript abhi available nahi (koi speech-to-text backend nahi); sirf audio metadata / video frames padh sakta hoon.");

        // 3) how-to questions without a file are plain questions
        if (files.isEmpty() && isHowTo(allTokens) && howToDominatesRequest(allTokens)) {
            return new SkillPlan(tasks, "", unsupported, notDone);
        }

        // 4) segments -> create / edit tasks
        List<String> segments = segments(raw);
        List<SkillTask> writes = new ArrayList<SkillTask>();
        String clarify = "";
        boolean prevCreate = false;
        for (int si = 0; si < segments.size() && clarify.isEmpty(); si++) {
            Seg r = analyse(segments.get(si), si, prevCreate, files, ctx, tasks, writes);
            if (r.clarify != null && !r.clarify.isEmpty()) clarify = r.clarify;
            unsupported.addAll(r.unsupported);
            prevCreate = r.created;
        }
        if (!clarify.isEmpty()) return new SkillPlan(new ArrayList<SkillTask>(), clarify, unsupported, notDone);

        for (int i = 0; i < writes.size(); i++) {
            if (i < MAX_WRITE_TASKS) tasks.add(writes.get(i));
            else notDone.add(writes.get(i).action + " " + writes.get(i).skill + " (limit " + MAX_WRITE_TASKS + " jobs per message)");
        }
        return new SkillPlan(tasks, "", unsupported, notDone);
    }

    /**
     * When several attachments are present, an explicit read reference such as "first file padho", "second
     * attachment dekho", or "report.pdf read karo" must not silently send every attachment to the model.
     * Returns -1 for normal/all-file reads, -2 when clarification is required, or the selected attachment index.
     * Callers use -2 to ask one short question before model execution.
     */
    private static int resolveExplicitReadAttachment(String raw, List<String> t, List<Attachment> files) {
        if (files == null || files.size() <= 1) return -1;
        boolean read = false, write = false, ordinal = false, all = false;
        for (String x : t) {
            if (READ_VERBS.contains(x)) read = true;
            if (x.equals("first") || x.equals("pehli") || x.equals("pehle") || x.equals("second") || x.equals("dusri") || x.equals("doosri")
                    || x.equals("third") || x.equals("teesri") || x.equals("last") || x.equals("aakhri") || x.equals("aakhiri")) ordinal = true;
            if (CREATE_VERBS.contains(x) || EDIT_CUES.contains(x) || PURE_EDIT_WORDS.contains(x)) write = true;
            if (x.equals("all") || x.equals("both") || x.equals("dono") || x.equals("sab") || x.equals("saare") || x.equals("saari")) all = true;
        }
        if (!read || write || all || !ordinal) {
            // A filename is also an explicit target, but ordinary pronouns such as "isko dekho" intentionally
            // continue to mean the whole current attachment set for backwards compatibility.
            if (read && !write && !all) {
                List<Integer> named = new ArrayList<Integer>();
                for (int i = 0; i < files.size(); i++) {
                    Attachment a = files.get(i);
                    if (a == null || a.kind == null || a.name == null || a.name.trim().isEmpty()) continue;
                    String lowRaw = raw.toLowerCase(Locale.ROOT);
                    String full = a.name.toLowerCase(Locale.ROOT);
                    String stem = full;
                    int dot = stem.lastIndexOf('.');
                    if (dot > 0) stem = stem.substring(0, dot);
                    if ((full.length() >= 3 && lowRaw.contains(full)) || (stem.length() >= 3 && lowRaw.contains(stem))) named.add(i);
                }
                if (named.size() == 1) return named.get(0);
            }
            return -1;
        }
        List<Integer> candidates = new ArrayList<Integer>();
        for (int i = 0; i < files.size(); i++) if (files.get(i) != null && files.get(i).kind != null) candidates.add(i);
        if (candidates.size() <= 1) return candidates.isEmpty() ? -1 : candidates.get(0);
        String chosen = chooseCachedByOrdinalOrName(raw, t, files, candidates);
        if (chosen == null) return -2;
        return Integer.parseInt(chosen);
    }

    private static ReadSelection selectionForAttachment(Attachment.Kind kind, ReadSelection selected) {
        if (selected == null) return ReadSelection.none();
        if (kind == Attachment.Kind.PDF && selected.hasPages()) return selected;
        if (kind == Attachment.Kind.PPTX && selected.hasSlides()) return selected;
        if (kind == Attachment.Kind.XLSX && selected.hasSheet()) return selected;
        return ReadSelection.none();
    }

    private static boolean isCachedReadRequest(List<String> t, ReadSelection selection) {
        if (selection != null && !selection.isEmpty()) return true;
        boolean read = false, pronoun = false, ordinal = false, write = false;
        for (String x : t) {
            if (READ_VERBS.contains(x)) read = true;
            if (PRONOUNS.contains(x)) pronoun = true;
            if (x.equals("first") || x.equals("pehli") || x.equals("pehle") || x.equals("second") || x.equals("dusri") || x.equals("doosri")
                    || x.equals("third") || x.equals("teesri") || x.equals("last") || x.equals("aakhri") || x.equals("aakhiri")) ordinal = true;
            if (CREATE_VERBS.contains(x) || EDIT_CUES.contains(x) || PURE_EDIT_WORDS.contains(x)) write = true;
        }
        // Natural Hindi read requests commonly end in "karo"/"kar do"; when a pronoun +
        // explicit read verb is present, that polite verb must not turn the request into an edit.
        if (read && pronoun && !containsExplicitWriteCue(t)) write = false;
        return read && !write && (pronoun || ordinal || t.contains("read") || t.contains("padho") || t.contains("padh") || t.contains("summary"));
    }

    private static boolean containsExplicitWriteCue(List<String> t) {
        for (String x : t) {
            if (PURE_EDIT_WORDS.contains(x)) return true;
            if (x.equals("change") || x.equals("badlo") || x.equals("badal") || x.equals("modify") || x.equals("update")
                    || x.equals("replace") || x.equals("likho") || x.equals("likh") || x.equals("daalo") || x.equals("daal")
                    || x.equals("set") || x.equals("bhar") || x.equals("fill") || x.equals("enter") || x.equals("put")) return true;
        }
        return false;
    }

    private static List<Integer> cachedCandidates(List<Attachment> cache, ReadSelection selection) {
        List<Integer> out = new ArrayList<Integer>();
        for (int i = 0; i < cache.size(); i++) {
            Attachment a = cache.get(i);
            if (a == null || a.kind == null) continue;
            if (selection == null || selection.isEmpty()
                    || (selection.hasPages() && a.kind == Attachment.Kind.PDF)
                    || (selection.hasSlides() && a.kind == Attachment.Kind.PPTX)
                    || (selection.hasSheet() && a.kind == Attachment.Kind.XLSX)) out.add(i);
        }
        return out;
    }

    private static String chooseCachedByOrdinalOrName(String raw, List<String> t, List<Attachment> cache, List<Integer> candidates) {
        for (int i = 0; i < candidates.size(); i++) {
            int idx = candidates.get(i);
            Attachment a = cache.get(idx);
            String n = a.name == null ? "" : a.name.toLowerCase(Locale.ROOT);
            int dot = n.lastIndexOf('.');
            String stem = dot > 0 ? n.substring(0, dot) : n;
            if (stem.length() >= 3 && raw.toLowerCase(Locale.ROOT).contains(stem)) return String.valueOf(idx);
        }
        if (t.contains("pehli") || t.contains("pehle") || t.contains("first")) return String.valueOf(candidates.get(0));
        if (t.contains("dusri") || t.contains("doosri") || t.contains("second")) return String.valueOf(candidates.get(Math.min(1, candidates.size() - 1)));
        if (t.contains("teesri") || t.contains("third")) return String.valueOf(candidates.get(Math.min(2, candidates.size() - 1)));
        if (t.contains("last") || t.contains("aakhri") || t.contains("aakhiri")) return String.valueOf(candidates.get(candidates.size() - 1));
        return null;
    }

    private static ReadSelection readSelection(String raw, List<String> tokens) {
        String s = raw == null ? "" : raw;
        if (s.trim().matches("(?i)^sheet\\s+(?:name\\s+)?(?:me|mein|ko)\\b.*")) return ReadSelection.none();
        Matcher p = Pattern.compile("(?i)\\bpages?\\s+(?:number\\s+)?(\\d{1,5})(?:\\s*(?:-|to|through|thru)\\s*(\\d{1,5}))?\\b").matcher(s);
        if (p.find()) {
            int a = parsePositive(p.group(1));
            int b = p.group(2) == null ? a : parsePositive(p.group(2));
            if (a > 0 && b >= a && b <= 10000) return ReadSelection.pages(a, b);
        }
        Matcher sl = Pattern.compile("(?i)\\bslides?\\s+(?:number\\s+)?(\\d{1,5})(?:\\s*(?:-|to|through|thru)\\s*(\\d{1,5}))?\\b").matcher(s);
        if (sl.find()) {
            int a = parsePositive(sl.group(1));
            int b = sl.group(2) == null ? a : parsePositive(sl.group(2));
            if (a > 0 && b >= a && b <= 10000) return ReadSelection.slides(a, b);
        }
        Matcher sh = Pattern.compile("(?i)\\bsheet\\s+(?:name\\s+)?(?:\"([^\"]+)\"|\\'([^\\']+)\\'|([^,.;]+?))(?=\\s+(?:me|mein|ko|padho|padh|read|dekho|batao|samjhao|summarize|summarise|check|review|analyze|analyse)\\b|\\s*$)").matcher(s.trim());
        if (sh.find()) {
            String name = sh.group(1) != null ? sh.group(1) : (sh.group(2) != null ? sh.group(2) : sh.group(3));
            if (name != null) {
                name = name.trim().replaceAll("[.!?]+$", "").trim();
                if (!name.isEmpty() && !name.equalsIgnoreCase("me") && !name.equalsIgnoreCase("mein")
                        && !name.matches("(?i)^[A-Z]{1,3}\\d{1,7}$")) return ReadSelection.sheet(name);
            }
        }
        return ReadSelection.none();
    }

    private static int parsePositive(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return -1; }
    }

    private static SkillKind readerFor(Attachment.Kind k) {
        switch (k) {
            case IMAGE: return SkillKind.IMAGE_READER;
            case PDF: return SkillKind.PDF_READER;
            case AUDIO: return SkillKind.AUDIO_READER;
            case VIDEO: return SkillKind.VIDEO_READER;
            case ZIP: return SkillKind.PROJECT_ZIP_READER;
            case DOCX: return SkillKind.DOCX;
            case XLSX: return SkillKind.XLSX;
            case PPTX: return SkillKind.PPTX;
            default: return null;
        }
    }

    private static final Pattern LEGACY_NAME = Pattern.compile("(?i)\\.(doc|xls|ppt)$");
    private static final Pattern LEGACY_TEXT = Pattern.compile("(?i)\\.(doc|xls|ppt)(?![a-z0-9])");

    // ---------- tokens ----------
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+|[,;]");

    static List<String> tokens(String s) {
        String lower = s.toLowerCase(Locale.ROOT).replace("'", "");
        Matcher m = TOKEN.matcher(lower);
        List<String> out = new ArrayList<String>();
        while (m.find()) {
            String t = m.group();
            if (t.equals(";")) t = ",";
            String sp = SPELL.get(t);
            if (sp != null) t = sp;
            if (t.equals("point") && !out.isEmpty() && out.get(out.size() - 1).equals("power")) { out.set(out.size() - 1, "powerpoint"); continue; }
            out.add(t);
        }
        return out;
    }

    private static boolean containsSeq(List<String> t, String... seq) {
        for (int i = 0; i + seq.length <= t.size(); i++) {
            boolean ok = true;
            for (int j = 0; j < seq.length && ok; j++) if (!t.get(i + j).equals(seq[j])) ok = false;
            if (ok) return true;
        }
        return false;
    }

    private static boolean isHowTo(List<String> t) {
        for (String x : t) if (HOWTO.contains(x)) return true;
        return false;
    }

    /** Preserve explicit file requests containing topic words like API/Python, but keep clear how-to/tool phrasing as questions. */
    private static boolean howToDominatesRequest(List<String> t) {
        if (t.isEmpty()) return false;
        if (t.get(0).equals("how") || t.get(0).equals("kaise") || t.get(0).equals("kese") || t.get(0).equals("kaisey")) return true;
        int firstFmt = -1;
        for (int i = 0; i < t.size(); i++) if (fmtAt(t, i) != null) { firstFmt = i; break; }
        if (firstFmt < 0) return true;
        for (int i = 0; i < firstFmt; i++) {
            if (!TOOL_WORDS.contains(t.get(i))) continue;
            String next = i + 1 < t.size() ? t.get(i + 1) : "";
            if (next.equals("se") || next.equals("mein") || next.equals("me") || next.equals("using") || next.equals("with")) return true;
        }
        return false;
    }

    // ---------- segments ----------
    private static final Pattern HARD_SPLIT = Pattern.compile(
            "(?i)(?:\\s*[.!?]\\s+|\\s+)(?:(?:aur\\s+|and\\s+)?(?:phir|fir|then)|(?:uske|iske|us|is)\\s+(?:baad|bad)|after\\s+that)\\s+");

    static List<String> segments(String raw) {
        List<String> hard = new ArrayList<String>();
        Matcher m = HARD_SPLIT.matcher(raw);
        int last = 0;
        while (m.find()) { hard.add(raw.substring(last, m.start())); last = m.end(); }
        hard.add(raw.substring(last));
        List<String> out = new ArrayList<String>();
        for (String h : hard) splitSoft(h, out);
        List<String> clean = new ArrayList<String>();
        for (String s : out) if (!s.trim().isEmpty()) clean.add(s.trim());
        if (clean.isEmpty()) clean.add(raw.trim());
        return clean;
    }

    private static final Pattern SOFT = Pattern.compile("(?i)(?:\\s*,\\s*|\\s+)(?:aur|and)?\\s*(?=\\S)");

    /** Split at "aur / and / comma" only when both sides carry a verb. */
    private static void splitSoft(String seg, List<String> out) {
        Matcher m = Pattern.compile("(?i)(\\s*,\\s*|\\s+aur\\s+|\\s+and\\s+)").matcher(seg);
        while (m.find()) {
            String left = seg.substring(0, m.start());
            String right = seg.substring(m.end());
            if (hasVerb(tokens(left)) && hasVerb(tokens(right))) {
                out.add(left);
                splitSoft(right, out);
                return;
            }
        }
        out.add(seg);
    }

    private static boolean hasVerb(List<String> t) {
        for (int i = 0; i < t.size(); i++) {
            String x = t.get(i);
            if (CREATE_VERBS.contains(x) || READ_VERBS.contains(x) || EDIT_CUES.contains(x) || PURE_EDIT_WORDS.contains(x)) return true;
            if ((x.equals("de") || x.equals("do")) && i > 0) return true;
        }
        return false;
    }

    // ---------- one segment ----------
    private static final class Seg {
        String clarify = "";
        final List<String> unsupported = new ArrayList<String>();
        boolean created;
    }

    private static final Set<String> WORD_NEG_NEXT = set("count", "limit", "meaning", "matlab", "problem", "game", "search", "ka", "ki", "ke", "bol", "spelling", "by", "wise");
    private static final Set<String> TOPIC_FORMAT_FOLLOWERS = set("skills", "skill", "formulas", "formula", "history", "meaning", "matlab", "definition", "definitions", "tutorial", "tips", "course", "lesson", "training", "rules", "shortcuts");
    private static final Set<String> TOOL_WORDS = set("python", "java", "kotlin", "javascript", "api", "code", "coding", "script", "library");
    private static final Set<String> WORD_FILE_NEXT = set("file", "document", "docxfmt", "format", "banao", "bana", "bnao", "me", "mein", "mai", "main", "ko", "aur",
            "and", "or", "ya", "dono", "chahiye", "make", "create", "export", "convert", "daal", "de", "dedo", "kar", "karo", "kardo");
    private static final Set<String> WORD_PREV_BLOCK = set("ek", "one", "single", "any", "each", "every", "hindi", "english", "magic", "key", "pass", "last", "first", "ye", "wo", "har");

    /** All formats named in the tokens, honouring negation. */
    private static List<Fmt> formatsIn(List<String> t, List<Integer> posOut) {
        List<Fmt> out = new ArrayList<Fmt>();
        for (int i = 0; i < t.size(); i++) {
            Fmt f = fmtAt(t, i);
            if (f == null) continue;
            if (negated(t, i)) continue;
            if (i + 1 < t.size() && (t.get(i + 1).equals("ka") || t.get(i + 1).equals("ki") || t.get(i + 1).equals("ke"))) {
                boolean laterFmt = false;
                for (int j = i + 2; j < t.size(); j++) if (fmtAt(t, j) != null) laterFmt = true;
                if (laterFmt) continue;
            }
            out.add(f);
            posOut.add(i);
        }
        return out;
    }

    private static Fmt fmtAt(List<String> t, int i) {
        String x = t.get(i);
        String prev = i > 0 ? t.get(i - 1) : "";
        String next = i + 1 < t.size() ? t.get(i + 1) : "";
        if (x.equals("pdf")) return Fmt.PDF;
        if (x.equals("excel") || x.equals("xlsx") || x.equals("spreadsheet")) return TOPIC_FORMAT_FOLLOWERS.contains(next) ? null : Fmt.XLSX;
        if (x.equals("powerpoint") || x.equals("ppt") || x.equals("presentation")) return TOPIC_FORMAT_FOLLOWERS.contains(next) ? null : Fmt.PPTX;
        if (x.equals("sheet")) return (prev.equals("bed") || prev.equals("pillow") || prev.equals("answer") || prev.equals("cheat")) ? null : Fmt.XLSX;
        if (x.equals("slide")) {
            if (next.equals("karna") || next.equals("kar") || next.equals("karo") || next.equals("hona") || next.equals("ho")
                    || next.equals("down") || next.equals("up") || next.equals("rule")) return null;
            return Fmt.PPTX;
        }
        if (x.equals("deck")) return (prev.equals("pitch") || prev.equals("ppt") || prev.equals("slide") || prev.equals("powerpoint")
                || CREATE_VERBS.contains(next)) ? Fmt.PPTX : null;
        if (x.equals("docx")) return Fmt.DOCX;
        if (x.equals("docxfmt")) return WORD_NEG_NEXT.contains(next) ? null : Fmt.DOCX;
        if (x.equals("word")) {
            if (WORD_NEG_NEXT.contains(next)) return null;
            boolean fileNext = next.equals("file") || next.equals("document") || next.equals("format") || next.equals("docxfmt");
            if (WORD_PREV_BLOCK.contains(prev) && !fileNext) {
                if (CREATE_VERBS.contains(next)) return Fmt.DOCX;
                if (i + 2 < t.size() && CREATE_VERBS.contains(t.get(i + 2))) return Fmt.DOCX;
                return null;
            }
            if (WORD_FILE_NEXT.contains(next) || isFormatWord(next) || isFormatWord(prev) || next.equals(",") || prev.equals(",")) return Fmt.DOCX;
            if (prev.equals("to") || prev.equals("into") || prev.equals("as") || prev.equals("in") || prev.equals("a") || prev.equals("an")
                    || prev.equals("ms") || prev.equals("microsoft") || CREATE_VERBS.contains(prev)) return Fmt.DOCX;
            if (next.isEmpty() && (prev.equals("me") || prev.equals("mein") || prev.equals("mai"))) return Fmt.DOCX;
            if (!TOPIC_FORMAT_FOLLOWERS.contains(next) && hasCreateVerb(t, new ArrayList<Integer>())) return Fmt.DOCX;
            return null;
        }
        return null;
    }

    private static boolean isFormatWord(String x) {
        return x.equals("pdf") || x.equals("excel") || x.equals("xlsx") || x.equals("spreadsheet") || x.equals("powerpoint")
                || x.equals("ppt") || x.equals("presentation") || x.equals("slide");
    }

    /** Hindi negation goes after the thing ("pdf mat banao", "word nahi"), English before ("dont make a pdf", "no pdf"). */
    private static boolean negated(List<String> t, int i) {
        for (int j = i + 1; j <= Math.min(t.size() - 1, i + 2); j++) {
            if (NEG_HI.contains(t.get(j))) return true;
            if (t.get(j).equals(",")) break;
        }
        for (int j = i - 1; j >= Math.max(0, i - 3); j--) {
            if (NEG_EN.contains(t.get(j))) return true;
            if (t.get(j).equals("do") && j > 0 && t.get(j - 1).equals("do") ) return true;
            if (t.get(j).equals(",") || CREATE_VERBS.contains(t.get(j)) && !NEG_EN.contains(t.get(Math.max(0, j - 1)))) {
                if (j > 0 && NEG_EN.contains(t.get(j - 1))) return true;
                break;
            }
        }
        return false;
    }

    private static boolean hasCreateVerb(List<String> t, List<Integer> fmtPos) {
        for (int i = 0; i < t.size(); i++) {
            String x = t.get(i);
            if (x.equals("chahiye") || x.equals("chahie") || x.equals("chaiye")) {
                // "pdf chahiye" is a request; "presentation me kya bolna chahiye" is a question
                boolean near = false;
                for (int p : fmtPos) if (p < i && p >= i - 3) near = true;
                for (int j = Math.max(0, i - 4); j < i; j++) if (QUESTION.contains(t.get(j))) near = false;
                if (near) return true;
                continue;
            }
            if (CREATE_VERBS.contains(x)) return true;
            if (x.equals("de") && i + 1 < t.size() && (t.get(i + 1).equals("do") || t.get(i + 1).equals("de"))) return true;
            if (x.equals("do") && i > 0 && t.get(i - 1).equals("de")) return true;
            if ((x.equals("kar") || x.equals("karo") || x.equals("kardo")) && i + 1 < t.size()
                    && (t.get(i + 1).equals("do") || t.get(i + 1).equals("de") || x.equals("kardo") || x.equals("karo"))) {
                for (int p : fmtPos) if (p == i - 1) return true;
            }
        }
        return false;
    }

    private static Seg analyse(String segRaw, int segIndex, boolean prevCreate, List<Attachment> files, RouterContext ctx,
                               List<SkillTask> tasks, List<SkillTask> writes) {
        Seg res = new Seg();
        List<String> t = tokens(segRaw);

        // ---- EDIT first ----
        List<Map<String, String>> cellEdits = cellEdits(segRaw, t);
        Map<String, String> replace = cellEdits.isEmpty() ? replacePair(segRaw) : null;
        if (!cellEdits.isEmpty() || replace != null) {
            List<Integer> pos = new ArrayList<Integer>();
            List<Fmt> hint = formatsIn(t, pos);
            boolean cell = !cellEdits.isEmpty();
            Resolve rs = resolveEditTarget(segRaw, t, files, ctx, cell, hint);
            if (rs.message != null) {
                if (rs.unsupported) res.unsupported.add(rs.message); else res.clarify = rs.message;
                return res;
            }
            SkillKind kind = cell ? SkillKind.XLSX : kindOfIndex(files, ctx, rs.index);
            if (cell) for (Map<String, String> p : cellEdits) writes.add(new SkillTask(SkillTask.Action.EDIT, kind, SkillTask.Source.NONE, rs.index, "", p));
            else writes.add(new SkillTask(SkillTask.Action.EDIT, kind, SkillTask.Source.NONE, rs.index, "", replace));
            return res;
        }
        // an edit verb with nothing to change -> ask
        boolean pureEdit = false;
        for (String x : t) if (PURE_EDIT_WORDS.contains(x)) pureEdit = true;

        // ---- CREATE ----
        List<Integer> fmtPos = new ArrayList<Integer>();
        List<Fmt> fmts = formatsIn(t, fmtPos);
        boolean verb = hasCreateVerb(t, fmtPos);
        boolean convert = t.contains("convert") || (verb && t.contains("kar") && !t.contains("banao"));
        boolean negatedVerb = false;
        for (int i = 0; i < t.size(); i++) if (CREATE_VERBS.contains(t.get(i)) && i + 0 < t.size()) {
            if (i > 0 && NEG_EN.contains(t.get(i - 1))) negatedVerb = true;
            if (i + 1 < t.size() && NEG_HI.contains(t.get(i + 1))) negatedVerb = true;
        }
        // convert target: the format that follows me / mein / to / into / as
        if (convert && fmts.size() > 1) {
            List<Fmt> target = new ArrayList<Fmt>();
            List<Integer> tp = new ArrayList<Integer>();
            for (int k = 0; k < fmtPos.size(); k++) {
                int p = fmtPos.get(k);
                String prev = p > 0 ? t.get(p - 1) : "";
                if (prev.equals("me") || prev.equals("mein") || prev.equals("mai") || prev.equals("to") || prev.equals("into") || prev.equals("as")) {
                    target.add(fmts.get(k)); tp.add(p);
                }
            }
            if (target.isEmpty()) { target.add(fmts.get(fmts.size() - 1)); tp.add(fmtPos.get(fmtPos.size() - 1)); }
            fmts = target; fmtPos = tp;
        }
        // "uska pdf bhi" right after a create segment
        boolean inherited = false;
        if (!verb && !fmts.isEmpty() && prevCreate && !negatedVerb && !hasVerb(t)) { verb = true; inherited = true; }

        if (verb && !negatedVerb && fmts.isEmpty()) {
            for (String x : t) if (UNSUPPORTED_CREATE.contains(x)) {
                res.unsupported.add(x.toUpperCase(Locale.ROOT) + " file banana abhi supported nahi; PDF, Word, Excel ya PowerPoint bana sakta hoon.");
                return res;
            }
            for (String x : t) if (AMBIG_DOC.contains(x)) {
                res.clarify = "Kis format me chahiye - PDF ya Word?";
                return res;
            }
        }
        if (verb && !negatedVerb && !fmts.isEmpty()) {
            // source of the content
            SkillTask.Source src; String topic = "";
            boolean pron = false;
            for (String x : t) if (PRONOUNS.contains(x)) pron = true;
            boolean prevWala = containsSeq(t, "pehle", "wala") || containsSeq(t, "pehle", "wale") || containsSeq(t, "pichla") || containsSeq(t, "pichle");
            boolean wholeChat = false;
            for (int i = 0; i < t.size(); i++) {
                String x = t.get(i);
                if ((x.equals("poori") || x.equals("puri") || x.equals("pura") || x.equals("saari") || x.equals("sari") || x.equals("whole") || x.equals("entire") || x.equals("full"))
                        && (containsAfter(t, i, "chat") || containsAfter(t, i, "conversation") || containsAfter(t, i, "baat") || containsAfter(t, i, "baatcheet"))) wholeChat = true;
            }
            if (containsSeq(t, "chat", "ka") || containsSeq(t, "chat", "ko") || containsSeq(t, "conversation", "ka")) wholeChat = true;
            int sourceFile = firstOfficeOrAny(files);
            int colon = segRaw.indexOf(':');
            String typed = colon >= 0 ? segRaw.substring(colon + 1).trim() : "";
            String rest = remainder(t);
            if (wholeChat) src = SkillTask.Source.WHOLE_CHAT;
            else if (!typed.isEmpty()) { src = SkillTask.Source.TEXT; topic = typed; }
            else if (inherited || (segIndex > 0 && pron && prevCreate)) src = SkillTask.Source.PREVIOUS_STEP;
            else if (pron || convert) {
                if (sourceFile >= 0) src = SkillTask.Source.ATTACHED;
                else if (ctx.lastOutput != null && convert) src = SkillTask.Source.PREVIOUS_STEP;
                else if (ctx.hasLastReply) src = SkillTask.Source.LAST_REPLY;
                else { res.clarify = "Kya convert / save karna hai? File attach karo ya text bhejo."; return res; }
            } else if (prevWala) {
                if (ctx.hasLastReply) src = SkillTask.Source.LAST_REPLY;
                else { res.clarify = "Pehle wala kaunsa? Abhi koi purana jawab nahi hai - kya likhna hai batao."; return res; }
            } else if (!rest.isEmpty()) { src = SkillTask.Source.TOPIC; topic = rest; }
            else if (sourceFile >= 0) src = SkillTask.Source.ATTACHED;
            else if (ctx.hasLastReply) src = SkillTask.Source.LAST_REPLY;
            else { res.clarify = "File me kya likhna hai? Topic batao ya text bhejo."; return res; }
            for (Fmt f : fmts) {
                boolean dup = false;
                for (SkillTask w : writes) if (w.action == SkillTask.Action.CREATE && w.skill == skillOf(f) && w.source == src) dup = true;
                if (!dup) writes.add(new SkillTask(SkillTask.Action.CREATE, skillOf(f), src, SkillTask.NO_FILE, topic, null));
            }
            res.created = true;
            return res;
        }
        if (pureEdit && !negatedVerb) {
            boolean office = false;
            for (Attachment a : files) if (a != null && (a.kind == Attachment.Kind.DOCX || a.kind == Attachment.Kind.XLSX || a.kind == Attachment.Kind.PPTX)) office = true;
            if (office || !fmts.isEmpty()) {
                res.clarify = "Kya badalna hai? Abhi text replace (\"Ravi ki jagah Raj\") ya Excel cell (\"B2 me 500\") kar sakta hoon.";
                return res;
            }
        }
        return res;
    }

    private static boolean containsAfter(List<String> t, int i, String w) {
        for (int j = i + 1; j <= Math.min(t.size() - 1, i + 2); j++) if (t.get(j).equals(w)) return true;
        return false;
    }

    private static int firstOfficeOrAny(List<Attachment> files) {
        for (int i = 0; i < files.size(); i++) if (files.get(i) != null) return i;
        return -1;
    }

    /** Topic words left after removing format names, verbs and filler. */
    private static String remainder(List<String> t) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.size(); i++) {
            String x = t.get(i);
            if (x.equals(",") || CREATE_VERBS.contains(x) || FILLER.contains(x) || PRONOUNS.contains(x) || NEG_HI.contains(x) || NEG_EN.contains(x)) continue;
            if (x.equals("de") || x.equals("do") || x.equals("kar") || x.equals("karo") || x.equals("kardo") || x.equals("dena") || x.equals("sakte") || x.equals("sakta")
                    || x.equals("sakti") || x.equals("can") || x.equals("you") || x.equals("tum") || x.equals("aap") || x.equals("kya") || x.equals("me") || x.equals("i") || x.equals("need") || x.equals("want") || x.equals("chahta")
                    || x.equals("chahti") || x.equals("hoon") || x.equals("pehle") || x.equals("wala") || x.equals("wale") || x.equals("is") || x.equals("us") || x.equals("sab") || x.equals("bas")) continue;
            if (fmtAt(t, i) != null || x.equals("word") || x.equals("docxfmt") || x.equals("sheet") || x.equals("deck") || AMBIG_DOC.contains(x)) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(x);
        }
        return sb.toString();
    }

    // ---------- edit helpers ----------
    private static final Pattern CELL_A = Pattern.compile(
            "(?i)(?:^|[\\s,(])(?:cell\\s+)?([a-z]{1,3}[1-9][0-9]{0,4})\\s*(?:me|mein|mai|ko|ka|ki|=|:)\\s*(.+?)(?=\\s+(?:aur|and|phir|fir|then)\\s+(?:cell\\s+)?[a-z]{1,3}[1-9][0-9]{0,4}\\s*(?:me|mein|mai|ko|=|:)|$)");
    private static final Pattern CELL_B = Pattern.compile(
            "(?i)\\bset\\s+(?:cell\\s+)?([a-z]{1,3}[1-9][0-9]{0,4})\\s+(?:to|=|as)\\s+(.+?)(?=\\s+(?:and|aur)\\s+set\\b|$)");
    private static final Pattern CELL_C = Pattern.compile(
            "(?i)\\b(?:put|write|enter|type|fill)\\s+(.+?)\\s+(?:in|into|at)\\s+(?:cell\\s+)?([a-z]{1,3}[1-9][0-9]{0,4})\\b");
    private static final Pattern SHEET_A = Pattern.compile("(?i)\\bsheet\\s*[\"']?([\\p{L}\\p{N}_]+)[\"']?");
    private static final Pattern SHEET_B = Pattern.compile("(?i)([\\p{L}\\p{N}_]+)\\s+sheet\\s+(?:me|mein|mai|ki|ka|ke|in)\\b");

    private static List<Map<String, String>> cellEdits(String seg, List<String> t) {
        List<Map<String, String>> out = new ArrayList<Map<String, String>>();
        boolean cue = false;
        for (String x : t) if (EDIT_CUES.contains(x)) cue = true;
        if (!cue) return out;
        String sheet = "";
        Matcher sm = SHEET_B.matcher(seg);
        if (sm.find() && !sm.group(1).toLowerCase(Locale.ROOT).equals("excel")) sheet = sm.group(1);
        else { sm = SHEET_A.matcher(seg); if (sm.find() && !sm.group(1).matches("(?i)(me|mein|mai|ki|ka|ke|in|banao)")) sheet = sm.group(1); }
        Matcher m = CELL_B.matcher(seg);
        while (m.find()) addCell(out, m.group(1), m.group(2), sheet);
        if (out.isEmpty()) {
            m = CELL_C.matcher(seg);
            while (m.find()) addCell(out, m.group(2), m.group(1), sheet);
        }
        if (out.isEmpty()) {
            m = CELL_A.matcher(seg);
            while (m.find()) addCell(out, m.group(1), m.group(2), sheet);
        }
        return out;
    }

    private static void addCell(List<Map<String, String>> out, String ref, String valueRaw, String sheet) {
        String r = ref.toUpperCase(Locale.ROOT);
        if (NOT_CELLS.contains(ref.toLowerCase(Locale.ROOT))) return;
        String v = cleanParam(valueRaw);
        if (v.isEmpty()) return;
        Map<String, String> p = new LinkedHashMap<String, String>();
        if (!sheet.isEmpty()) p.put("sheet", sheet);
        p.put("cell", r);
        p.put("value", v);
        out.add(p);
    }

    private static final Pattern[] REPLACE = new Pattern[]{
            Pattern.compile("(?i)^(?:word|docx|ppt|powerpoint)\\s+(?:me|mein|mai|main)\\s+(.+?)\\s+ko\\s+(.+?)\\s+(?:kar do|kardo|karo|likh do|likho)\\b.*$"),
            Pattern.compile("(?i)^(.+?)\\s+(?:likh do|likho|rakh do|laga do|lagao|daal do|kar do)\\s+(.+?)\\s+(?:ki|ke)\\s+jagah\\b.*$"),
            Pattern.compile("(?i)^.*?\\b(?:replace|change|badlo|update)\\s+(.+?)\\s+(?:with|to|by|as)\\s+(.+)$"),
            Pattern.compile("(?i)^(.+?)\\s+(?:ki|ke|ka)\\s+jagah\\s+(.+)$"),
            Pattern.compile("(?i)^(.+?)\\s+ko\\s+(.+?)\\s+se\\s+(?:badlo|badal|badlna|replace|change)\\b.*$"),
            Pattern.compile("(?i)^(.+?)\\s+ko\\s+(.+?)\\s+(?:kar do|kardo|kar de|karo|likh do|bana do|banado)\\s*$"),
    };
    private static final boolean[] REVERSED = new boolean[]{false, true, false, false, false, false};

    private static Map<String, String> replacePair(String seg) {
        String s = seg.trim();
        for (int i = 0; i < REPLACE.length; i++) {
            Matcher m = REPLACE[i].matcher(s);
            if (!m.matches()) continue;
            // format words are checked BEFORE cleaning (cleaning strips "word me" etc.): "chat ko word me save kar do" is not a replace
            if (REVERSED[i] ? false : (isPronounOrFormat(m.group(2)))) continue;
            String a = cleanParam(m.group(1)), b = cleanParam(m.group(2));
            String oldV = REVERSED[i] ? b : a, newV = REVERSED[i] ? a : b;
            if (oldV.isEmpty() || newV.isEmpty() || oldV.equalsIgnoreCase(newV)) continue;
            if (isPronounOrFormat(oldV) || isPronounOrFormat(newV)) continue;
            if (oldV.length() > 80 || newV.length() > 80) continue;
            Map<String, String> p = new LinkedHashMap<String, String>();
            p.put("old", oldV);
            p.put("new", newV);
            return p;
        }
        return null;
    }

    private static boolean isPronounOrFormat(String v) {
        List<String> t = tokens(v);
        if (t.isEmpty()) return true;
        if (t.size() == 1 && PRONOUNS.contains(t.get(0))) return true;
        // "is word file ko pdf me convert karo" is a conversion, not a text replace
        for (int i = 0; i < t.size(); i++) if (fmtAt(t, i) != null || t.get(i).equals("convert")) return true;
        return false;
    }

    private static final Pattern LEAD = Pattern.compile("(?i)^(?:isme|usme|is file me|file me|document me|doc me|word me|ppt me|slide(?: \\d+)? me|sheet me|excel me|me|mein|ki|ka|ke|ko|jahan|jahaan|jaha|sab|saare|har)\\s+");
    private static final Pattern TRAIL = Pattern.compile("(?i)\\s+(?:likh do|likho|rakh do|laga do|lagao|kar do|kardo|kar de|karo|do|de|dena|bhi|please|pls)$");

    private static String cleanParam(String s) {
        if (s == null) return "";
        String v = s.trim();
        for (int guard = 0; guard < 6; guard++) {
            String before = v;
            Matcher l = LEAD.matcher(v);
            if (l.find()) v = v.substring(l.end()).trim();
            Matcher tr = TRAIL.matcher(v);
            if (tr.find()) v = v.substring(0, tr.start()).trim();
            if (v.equals(before)) break;
        }
        v = v.replaceAll("^[\"']+|[\"']+$", "").trim();
        v = v.replaceAll("[.!?]+$", "").trim();
        return v;
    }

    private static final class Resolve {
        int index = SkillTask.NO_FILE;
        String message;
        boolean unsupported;
    }

    private static SkillKind kindOfIndex(List<Attachment> files, RouterContext ctx, int idx) {
        Attachment.Kind k = idx == SkillTask.LAST_OUTPUT ? (ctx.lastOutput == null ? null : ctx.lastOutput.kind) : files.get(idx).kind;
        if (k == Attachment.Kind.PPTX) return SkillKind.PPTX;
        if (k == Attachment.Kind.XLSX) return SkillKind.XLSX;
        return SkillKind.DOCX;
    }

    private static Resolve resolveEditTarget(String seg, List<String> t, List<Attachment> files, RouterContext ctx, boolean cell, List<Fmt> hint) {
        Resolve r = new Resolve();
        if (!cell) {
            for (Fmt f : hint) if (f == Fmt.XLSX) {
                r.message = "Excel me text replace nahi hota - cell batao, jaise \"B2 me 500 likh do\".";
                return r;
            }
        }
        List<Integer> cand = new ArrayList<Integer>();
        boolean pdfOnly = false, anyPdf = false;
        for (int i = 0; i < files.size(); i++) {
            Attachment a = files.get(i);
            if (a == null || a.kind == null) continue;
            if (a.kind == Attachment.Kind.PDF) anyPdf = true;
            boolean ok = cell ? a.kind == Attachment.Kind.XLSX : (a.kind == Attachment.Kind.DOCX || a.kind == Attachment.Kind.PPTX);
            if (ok) cand.add(i);
        }
        if (ctx.lastOutput != null && ctx.lastOutput.kind != null) {
            Attachment.Kind k = ctx.lastOutput.kind;
            boolean ok = cell ? k == Attachment.Kind.XLSX : (k == Attachment.Kind.DOCX || k == Attachment.Kind.PPTX);
            if (ok && cand.isEmpty()) cand.add(SkillTask.LAST_OUTPUT);
        }
        // narrow by a named format
        if (cand.size() > 1 && !hint.isEmpty()) {
            List<Integer> narrowed = new ArrayList<Integer>();
            for (int c : cand) {
                Attachment.Kind k = c == SkillTask.LAST_OUTPUT ? ctx.lastOutput.kind : files.get(c).kind;
                for (Fmt f : hint) if ((f == Fmt.DOCX && k == Attachment.Kind.DOCX) || (f == Fmt.PPTX && k == Attachment.Kind.PPTX) || (f == Fmt.XLSX && k == Attachment.Kind.XLSX)) narrowed.add(c);
            }
            if (narrowed.size() == 1) cand = narrowed;
        }
        if (cand.size() > 1) {
            String low = seg.toLowerCase(Locale.ROOT);
            for (int c : cand) {
                if (c < 0) continue;
                String n = files.get(c).name.toLowerCase(Locale.ROOT);
                int dot = n.lastIndexOf('.');
                String stem = dot > 0 ? n.substring(0, dot) : n;
                if (stem.length() >= 3 && low.contains(stem)) { r.index = c; return r; }
            }
            if (t.contains("pehla") || t.contains("pehle") || t.contains("first")) { r.index = cand.get(0); return r; }
            if (t.contains("dusra") || t.contains("doosra") || t.contains("second")) { r.index = cand.get(Math.min(1, cand.size() - 1)); return r; }
            if (t.contains("teesra") || t.contains("third")) { r.index = cand.get(Math.min(2, cand.size() - 1)); return r; }
            if (t.contains("last") || t.contains("aakhri") || t.contains("aakhiri")) { r.index = cand.get(cand.size() - 1); return r; }
            r.message = "Kaunsi file me badalna hai? Naam ya \"pehli / doosri\" batao.";
            return r;
        }
        if (cand.size() == 1) { r.index = cand.get(0); return r; }
        if (anyPdf && !cell) { r.message = "PDF ko edit karna abhi supported nahi (sirf padh sakta hoon). Word ya PowerPoint file do."; r.unsupported = true; return r; }
        if (cell) r.message = "Cell badalne ke liye Excel (.xlsx) file attach karo.";
        else r.message = "Kaunsi Word / PowerPoint file me badalna hai? File attach karo.";
        return r;
    }
}
