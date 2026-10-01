package com.neonhud.app.core.skill;

import java.util.List;

/**
 * The short capability/execution contract the model gets for the current message only.
 * Stage 2 is connected: the model supplies marked content; the app creates/edits and verifies the real file.
 */
public final class SkillPrompt {
    private SkillPrompt() { }

    public static final boolean EXECUTION_CONNECTED = true;

    public static String build(SkillPlan plan) {
        if (plan == null || !plan.needsNote()) return "";
        StringBuilder sb = new StringBuilder("[SKILLS NOTE: ");
        boolean firstWrite = true;
        for (SkillTask t : plan.tasks) {
            if (!t.writes()) continue;
            if (!firstWrite) sb.append(' ');
            firstWrite = false;
            if (t.action == SkillTask.Action.EDIT) {
                sb.append("EDIT ").append(label(t.skill)).append(" is deterministic: do not invent different edit parameters; the app edits a COPY and verifies it. ");
            } else {
                sb.append("CREATE ").append(label(t.skill)).append(" requires exactly one marked artifact block in your response, using the format below. ");
            }
        }
        if (hasCreate(plan)) {
            sb.append("For each CREATE task, output exactly one block in task order. Put every marker on its own line. Do not put normal prose inside a file block. Markdown fences are unnecessary. ");
            for (SkillTask t : plan.tasks) {
                if (t == null || t.action != SkillTask.Action.CREATE) continue;
                switch (t.skill) {
                    case DOCX:
                        sb.append("WORD example: [[SKILL_FILE type=docx title=\"Title\"]] then body lines then [[END_SKILL_FILE]]. ");
                        break;
                    case PDF_CREATOR:
                        sb.append("PDF example: [[SKILL_FILE type=pdf title=\"Title\"]] then body lines then [[END_SKILL_FILE]]. ");
                        break;
                    case XLSX:
                        sb.append("EXCEL example: [[SKILL_FILE type=xlsx title=\"Title\"]] then [[SHEET name=\"Sheet1\"]], rows with TAB-separated cells, [[END_SHEET]], [[END_SKILL_FILE]]. ");
                        break;
                    case PPTX:
                        sb.append("POWERPOINT example: [[SKILL_FILE type=pptx title=\"Title\"]] then [[SLIDE title=\"Slide 1\"]], one bullet per line, [[END_SLIDE]], [[END_SKILL_FILE]]. ");
                        break;
                    default:
                        break;
                }
            }
            sb.append("Keep the requested names, numbers and source facts exactly. Do not claim the file is ready yourself; the app says that only after it creates and verifies the file. ");
        }
        for (String u : plan.unsupported) sb.append("Not possible: ").append(u).append(' ');
        for (String n : plan.notDone) sb.append("Not done: ").append(n).append(". ");
        sb.append("Never claim an unverified file or operation exists.]");
        return sb.toString();
    }


    /** A second-pass format-only instruction used when the model ignored or malformed the first marker contract. */
    public static String recovery(SkillPlan plan) {
        StringBuilder sb = new StringBuilder("[SKILL RECOVERY: The previous model answer was not executable. Do not explain or apologise. Return ONLY valid file marker blocks for the CREATE tasks in this message, in task order. Start each block exactly with [[SKILL_FILE and end with [[END_SKILL_FILE]]. Do not wrap markers in markdown fences. ");
        if (plan != null) {
            for (SkillTask t : plan.tasks) {
                if (t == null || t.action != SkillTask.Action.CREATE) continue;
                switch (t.skill) {
                    case DOCX: sb.append("Word: [[SKILL_FILE type=docx title=\"Title\"]] body text [[END_SKILL_FILE]]. "); break;
                    case PDF_CREATOR: sb.append("PDF: [[SKILL_FILE type=pdf title=\"Title\"]] body text [[END_SKILL_FILE]]. "); break;
                    case XLSX: sb.append("Excel: [[SKILL_FILE type=xlsx title=\"Title\"]] [[SHEET name=\"Sheet1\"]] row1\trow2 [[END_SHEET]] [[END_SKILL_FILE]]. "); break;
                    case PPTX: sb.append("PowerPoint: [[SKILL_FILE type=pptx title=\"Title\"]] [[SLIDE title=\"Slide 1\"]] bullet [[END_SLIDE]] [[END_SKILL_FILE]]. "); break;
                    default: break;
                }
            }
        }
        sb.append("Use the user's requested topic, names and facts. The application, not you, will report the file as ready.]");
        return sb.toString();
    }

    private static boolean hasCreate(SkillPlan plan) {
        for (SkillTask t : plan.tasks) if (t.action == SkillTask.Action.CREATE) return true;
        return false;
    }

    private static String label(SkillKind k) {
        switch (k) {
            case PDF_CREATOR: return "PDF";
            case DOCX: return "Word";
            case XLSX: return "Excel";
            case PPTX: return "PowerPoint";
            default: return k.name();
        }
    }
}
