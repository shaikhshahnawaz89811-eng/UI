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
            sb.append("For each CREATE task, output one block in task order. Do not use markdown code fences inside the block. ");
            sb.append("DOCX/PDF: [[SKILL_FILE type=docx or type=pdf title=\"Title\"]], then the body, then [[END_SKILL_FILE]]. ");
            sb.append("XLSX: [[SKILL_FILE type=xlsx title=\"Title\"]], [[SHEET name=\"Sheet1\"]], one row per line with TAB between cells, [[END_SHEET]], [[END_SKILL_FILE]]. ");
            sb.append("PPTX: [[SKILL_FILE type=pptx title=\"Title\"]], then for every slide [[SLIDE title=\"Title\"]], one bullet per line, [[END_SLIDE]], and finally [[END_SKILL_FILE]]. ");
            sb.append("Keep the requested names, numbers and source facts exactly. Do not claim the file is ready yourself; the app says that only after it creates and verifies the file. ");
        }
        for (String u : plan.unsupported) sb.append("Not possible: ").append(u).append(' ');
        for (String n : plan.notDone) sb.append("Not done: ").append(n).append(". ");
        sb.append("Never claim an unverified file or operation exists.]");
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
