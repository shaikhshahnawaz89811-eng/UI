package com.neonhud.app.core.skill;

import com.neonhud.app.core.engine.Attachment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Conversation metadata available to the deterministic skill router. */
public final class RouterContext {
    public static final RouterContext EMPTY = new RouterContext(false, null, null);

    public final boolean hasLastReply;
    /** The file the app created last (Stage 2 fills this); null when none. */
    public final Attachment lastOutput;
    /** Read-source metadata retained for file follow-ups; content is not required by the router. */
    public final List<Attachment> cachedReads;

    public RouterContext(boolean hasLastReply, Attachment lastOutput) {
        this(hasLastReply, lastOutput, null);
    }

    public RouterContext(boolean hasLastReply, Attachment lastOutput, List<Attachment> cachedReads) {
        this.hasLastReply = hasLastReply;
        this.lastOutput = lastOutput;
        this.cachedReads = cachedReads == null
                ? Collections.<Attachment>emptyList()
                : Collections.unmodifiableList(new ArrayList<Attachment>(cachedReads));
    }
}
