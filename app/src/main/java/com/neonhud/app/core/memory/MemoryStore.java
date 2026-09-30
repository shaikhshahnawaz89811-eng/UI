package com.neonhud.app.core.memory;

import java.util.List;

/** Persistence for the conversation-memory layer (SQLite on Android, in-memory in tests). */
public interface MemoryStore {
    long addMessage(long timestamp, String role, String content, long conversationId, long topicId);
    void setMessageTopic(long messageId, long topicId);
    ConversationMessage getMessage(long id);
    /** Last {@code limit} messages of one conversation, oldest first. */
    List<ConversationMessage> recentMessages(long conversationId, int limit);
    /** Last {@code limit} messages tagged with a topic (all conversations), oldest first. */
    List<ConversationMessage> messagesByTopic(long topicId, int limit);
    /** Last {@code limit} user messages overall, oldest first. */
    List<ConversationMessage> userMessages(int limit);
    /** First assistant message stored after the given user message, or null. */
    ConversationMessage assistantReplyAfter(long userMessageId);
    /** Last {@code limit} messages overall (for the chat screen), oldest first. */
    List<ConversationMessage> lastMessages(int limit);

    List<Topic> topics();
    Topic getTopic(long id);
    long addTopic(Topic topic);       // assigns and returns the id
    void updateTopic(Topic topic);

    long addMemory(MemoryItem m);     // assigns and returns the id
    void updateMemory(MemoryItem m);
    void deleteMemory(long id);
    List<MemoryItem> memoriesByTopic(long topicId);
    int countMemories(long topicId);

    String getMeta(String key);
    void setMeta(String key, String value);
}
