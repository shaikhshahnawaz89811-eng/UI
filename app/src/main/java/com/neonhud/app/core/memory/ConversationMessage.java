package com.neonhud.app.core.memory;

public final class ConversationMessage {
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    public final long id;
    public final long timestamp;
    public final String role;
    public final String content;
    public final long conversationId;
    public final long topicId;

    public ConversationMessage(long id, long timestamp, String role, String content,
                               long conversationId, long topicId) {
        this.id = id;
        this.timestamp = timestamp;
        this.role = role;
        this.content = content;
        this.conversationId = conversationId;
        this.topicId = topicId;
    }

    public boolean isUser() { return ROLE_USER.equals(role); }
}
