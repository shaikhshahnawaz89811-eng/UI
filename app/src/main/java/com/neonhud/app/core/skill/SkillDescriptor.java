package com.neonhud.app.core.skill;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Human-readable contract for one skill; kept pure Java so the registry is easy to test. */
public final class SkillDescriptor {
    public final SkillKind kind;
    public final String id;
    public final String name;
    public final String purpose;
    public final boolean offline;
    public final boolean needsVision;
    public final List<String> inputs;
    public final List<String> outputs;

    public SkillDescriptor(SkillKind kind, String id, String name, String purpose,
                           boolean offline, boolean needsVision,
                           List<String> inputs, List<String> outputs) {
        this.kind = kind;
        this.id = id;
        this.name = name;
        this.purpose = purpose;
        this.offline = offline;
        this.needsVision = needsVision;
        this.inputs = immutable(inputs);
        this.outputs = immutable(outputs);
    }

    private static List<String> immutable(List<String> in) {
        return in == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(in));
    }
}
