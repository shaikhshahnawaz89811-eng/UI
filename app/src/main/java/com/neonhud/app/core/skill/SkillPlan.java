package com.neonhud.app.core.skill;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** What one chat message asks for: ordered tasks, OR one clarifying question, plus honest "not possible" notes. */
public final class SkillPlan {
    public final List<SkillTask> tasks;
    /** One short question for the user; when non-empty the app answers with it and does NOT call the model. */
    public final String clarify;
    /** Things the user asked for that this build cannot do (transcript, legacy .doc, PDF edit ...). */
    public final List<String> unsupported;
    /** Explicit jobs beyond the per-message limit; reported as not done. */
    public final List<String> notDone;

    public SkillPlan(List<SkillTask> tasks, String clarify, List<String> unsupported, List<String> notDone) {
        this.tasks = tasks == null ? Collections.<SkillTask>emptyList() : Collections.unmodifiableList(new ArrayList<SkillTask>(tasks));
        this.clarify = clarify == null ? "" : clarify;
        this.unsupported = unsupported == null ? Collections.<String>emptyList() : Collections.unmodifiableList(new ArrayList<String>(unsupported));
        this.notDone = notDone == null ? Collections.<String>emptyList() : Collections.unmodifiableList(new ArrayList<String>(notDone));
    }

    public boolean needsClarify() { return !clarify.isEmpty(); }

    /** True when a file would have to be created or edited. */
    public boolean hasWriteTask() {
        for (SkillTask t : tasks) if (t.writes()) return true;
        return false;
    }

    /** True when the model needs a capability note (it must not claim a file / transcript it did not make). */
    public boolean needsNote() { return hasWriteTask() || !unsupported.isEmpty() || !notDone.isEmpty(); }

    /** Compact text form used by the tests: "CLARIFY", "NONE", or "READ:X>CREATE:Y" (+ "|UNSUP" / "|LIMIT"). */
    public String signature() {
        if (needsClarify()) return "CLARIFY";
        StringBuilder sb = new StringBuilder();
        for (SkillTask t : tasks) {
            if (sb.length() > 0) sb.append('>');
            sb.append(t.action).append(':').append(t.skill);
        }
        if (sb.length() == 0 && unsupported.isEmpty() && notDone.isEmpty()) return "NONE";
        String sep = sb.length() == 0 ? "" : "|";
        if (!unsupported.isEmpty()) { sb.append(sep).append("UNSUP"); sep = "|"; }
        if (!notDone.isEmpty()) sb.append(sep).append("LIMIT");
        return sb.toString();
    }
}
