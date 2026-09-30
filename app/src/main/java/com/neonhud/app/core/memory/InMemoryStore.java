package com.neonhud.app.core.memory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reference {@link MemoryStore} kept in RAM. Used by the JVM tests; mirrors the SQLite semantics. */
public final class InMemoryStore implements MemoryStore {
    private final List<ConversationMessage> messages = new ArrayList<ConversationMessage>();
    private final Map<Long, Topic> topics = new HashMap<Long, Topic>();
    private final Map<Long, MemoryItem> memories = new HashMap<Long, MemoryItem>();
    private final Map<String, String> meta = new HashMap<String, String>();
    private long nextMsg = 1, nextTopic = 1, nextMem = 1;

    @Override public synchronized long addMessage(long ts, String role, String content, long conv, long topicId) {
        long id = nextMsg++;
        messages.add(new ConversationMessage(id, ts, role, content, conv, topicId));
        return id;
    }

    @Override public synchronized void setMessageTopic(long messageId, long topicId) {
        for (int i = 0; i < messages.size(); i++) {
            ConversationMessage m = messages.get(i);
            if (m.id == messageId) {
                messages.set(i, new ConversationMessage(m.id, m.timestamp, m.role, m.content, m.conversationId, topicId));
                return;
            }
        }
    }

    @Override public synchronized ConversationMessage getMessage(long id) {
        for (ConversationMessage m : messages) if (m.id == id) return m;
        return null;
    }

    private List<ConversationMessage> tail(List<ConversationMessage> l, int limit) {
        int from = Math.max(0, l.size() - limit);
        return new ArrayList<ConversationMessage>(l.subList(from, l.size()));
    }

    @Override public synchronized List<ConversationMessage> recentMessages(long conv, int limit) {
        List<ConversationMessage> l = new ArrayList<ConversationMessage>();
        for (ConversationMessage m : messages) if (m.conversationId == conv) l.add(m);
        return tail(l, limit);
    }

    @Override public synchronized List<ConversationMessage> messagesByTopic(long topicId, int limit) {
        List<ConversationMessage> l = new ArrayList<ConversationMessage>();
        for (ConversationMessage m : messages) if (m.topicId == topicId) l.add(m);
        return tail(l, limit);
    }

    @Override public synchronized List<ConversationMessage> userMessages(int limit) {
        List<ConversationMessage> l = new ArrayList<ConversationMessage>();
        for (ConversationMessage m : messages) if (m.isUser()) l.add(m);
        return tail(l, limit);
    }

    @Override public synchronized ConversationMessage assistantReplyAfter(long userMessageId) {
        for (ConversationMessage m : messages) {
            if (m.id > userMessageId && !m.isUser()) return m;
            if (m.id > userMessageId && m.isUser()) return null;   // next user message came first: no reply
        }
        return null;
    }

    @Override public synchronized List<ConversationMessage> lastMessages(int limit) {
        return tail(messages, limit);
    }

    @Override public synchronized List<Topic> topics() {
        List<Topic> l = new ArrayList<Topic>(topics.values());
        java.util.Collections.sort(l, new java.util.Comparator<Topic>() {
            @Override public int compare(Topic a, Topic b) { return Long.compare(b.lastUsedAt, a.lastUsedAt); }
        });
        return l;
    }

    @Override public synchronized Topic getTopic(long id) { return topics.get(id); }

    @Override public synchronized long addTopic(Topic t) {
        t.id = nextTopic++;
        topics.put(t.id, t);
        return t.id;
    }

    @Override public synchronized void updateTopic(Topic t) { topics.put(t.id, t); }

    @Override public synchronized long addMemory(MemoryItem m) {
        m.id = nextMem++;
        memories.put(m.id, m);
        return m.id;
    }

    @Override public synchronized void updateMemory(MemoryItem m) { memories.put(m.id, m); }
    @Override public synchronized void deleteMemory(long id) { memories.remove(id); }

    @Override public synchronized List<MemoryItem> memoriesByTopic(long topicId) {
        List<MemoryItem> l = new ArrayList<MemoryItem>();
        for (MemoryItem m : memories.values()) if (m.topicId == topicId) l.add(m);
        return l;
    }

    @Override public synchronized int countMemories(long topicId) {
        int n = 0;
        for (MemoryItem m : memories.values()) if (m.topicId == topicId) n++;
        return n;
    }

    @Override public synchronized String getMeta(String key) { return meta.get(key); }
    @Override public synchronized void setMeta(String key, String value) { meta.put(key, value); }
}
