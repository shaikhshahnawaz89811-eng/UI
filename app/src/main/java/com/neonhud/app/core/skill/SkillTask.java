package com.neonhud.app.core.skill;

import com.neonhud.app.core.engine.ReadSelection;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One step the router wants done. Pure data: the router decides, an executor (Stage 2) does. */
public final class SkillTask {
    public enum Action { READ, CREATE, EDIT }
    /** Where the content of a CREATE comes from. */
    public enum Source { NONE, ATTACHED, CACHE, PREVIOUS_STEP, LAST_REPLY, WHOLE_CHAT, TOPIC, TEXT }

    public static final int NO_FILE = -1;
    public static final int LAST_OUTPUT = -2;

    public final Action action;
    public final SkillKind skill;
    public final Source source;
    /** READ: the attachment read. EDIT: the file edited (index, or LAST_OUTPUT). Otherwise NO_FILE. */
    public final int fileIndex;
    /** TOPIC / TEXT source: what to write about, or the typed text. */
    public final String topic;
    /** EDIT parameters: old/new for a text replace; sheet/cell/value for a cell edit. */
    public final Map<String, String> params;
    /** Optional explicit page/slide/sheet selection for READ tasks. */
    public final ReadSelection selection;

    public SkillTask(Action action, SkillKind skill, Source source, int fileIndex, String topic, Map<String, String> params) {
        this(action, skill, source, fileIndex, topic, params, ReadSelection.none());
    }

    public SkillTask(Action action, SkillKind skill, Source source, int fileIndex, String topic, Map<String, String> params, ReadSelection selection) {
        this.action = action;
        this.skill = skill;
        this.source = source == null ? Source.NONE : source;
        this.fileIndex = fileIndex;
        this.topic = topic == null ? "" : topic;
        this.params = params == null ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(params));
        this.selection = selection == null ? ReadSelection.none() : selection;
    }

    public static SkillTask read(SkillKind skill, int fileIndex) {
        return read(skill, fileIndex, ReadSelection.none());
    }

    public static SkillTask read(SkillKind skill, int fileIndex, ReadSelection selection) {
        return new SkillTask(Action.READ, skill, Source.ATTACHED, fileIndex, "", null, selection);
    }

    public static SkillTask readCached(SkillKind skill, int cacheIndex, ReadSelection selection) {
        return new SkillTask(Action.READ, skill, Source.CACHE, cacheIndex, "", null, selection);
    }

    public boolean writes() { return action != Action.READ; }

    @Override public String toString() { return action + ":" + skill; }
}
