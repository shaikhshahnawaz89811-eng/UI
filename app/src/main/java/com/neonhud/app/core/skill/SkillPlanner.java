package com.neonhud.app.core.skill;

import com.neonhud.app.core.engine.Attachment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Minimal deterministic skill selector for the current attachment capabilities.
 * It does not change the model's answer and does not invent unsupported tools.
 */
public final class SkillPlanner {
    private SkillPlanner() { }

    public static List<SkillKind> forTurn(String userText, List<Attachment> files) {
        List<SkillKind> out = new ArrayList<SkillKind>();
        String t = userText == null ? "" : userText.toLowerCase(Locale.ROOT);
        boolean createPdf = containsAny(t, "pdf banao", "pdf bana do", "pdf bana de", "pdf bana", "create pdf", "create a pdf", "make a pdf", "export as pdf");
        if (createPdf) out.add(SkillKind.PDF_CREATOR);
        if (containsAny(t, "audio", "music", "song", "songs", "recording", "voice note", "voice message", "sound file", "mp3", "wav", "m4a", "flac", "ogg", "opus")) out.add(SkillKind.AUDIO_READER);
        if (containsAny(t, "word banao", "docx banao", "word document banao", "create a word document", "make a docx")) out.add(SkillKind.DOCX);
        if (containsAny(t, "excel banao", "xlsx banao", "spreadsheet banao", "create an excel", "make an xlsx")) out.add(SkillKind.XLSX);
        if (containsAny(t, "powerpoint banao", "ppt banao", "pptx banao", "presentation banao", "create a powerpoint", "make a pptx")) out.add(SkillKind.PPTX);

        if (files != null) for (Attachment a : files) {
            if (a == null || a.kind == null) continue;
            switch (a.kind) {
                case IMAGE: add(out, SkillKind.IMAGE_READER); break;
                case PDF: add(out, SkillKind.PDF_READER); break;
                case AUDIO: add(out, SkillKind.AUDIO_READER); break;
                case VIDEO: add(out, SkillKind.VIDEO_READER); break;
                case ZIP: add(out, SkillKind.PROJECT_ZIP_READER); break;
                case DOCX: add(out, SkillKind.DOCX); break;
                case XLSX: add(out, SkillKind.XLSX); break;
                case PPTX: add(out, SkillKind.PPTX); break;
                default: break;
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * True when one of the phrases appears as whole words. Plain substring matching was wrong here:
     * "password banao" contains "word banao" and "logging" contains "ogg".
     */
    private static boolean containsAny(String text, String... needles) {
        for (String n : needles) {
            int from = 0, at;
            while ((at = text.indexOf(n, from)) >= 0) {
                boolean startOk = at == 0 || !Character.isLetterOrDigit(text.charAt(at - 1));
                int end = at + n.length();
                boolean endOk = end >= text.length() || !Character.isLetterOrDigit(text.charAt(end));
                if (startOk && endOk) return true;
                from = at + 1;
            }
        }
        return false;
    }

    private static void add(List<SkillKind> list, SkillKind kind) {
        if (!list.contains(kind)) list.add(kind);
    }
}
