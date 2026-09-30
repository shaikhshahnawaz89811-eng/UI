package com.neonhud.app.core.memory;

/** A long-term memory. topicId 0 = global (facts about the user, "remember this"). */
public final class MemoryItem {
    public long id;
    public final String content;
    public final long topicId;
    public double importance;      // 0..1
    public final long createdAt;
    public long lastUsedAt;
    /** Space-separated stems used for retrieval. */
    public final String terms;

    public MemoryItem(long id, String content, long topicId, double importance,
                      long createdAt, long lastUsedAt, String terms) {
        this.id = id;
        this.content = content;
        this.topicId = topicId;
        this.importance = importance;
        this.createdAt = createdAt;
        this.lastUsedAt = lastUsedAt;
        this.terms = terms == null ? "" : terms;
    }
}
