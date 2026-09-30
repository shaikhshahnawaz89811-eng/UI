package com.neonhud.app.android;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.neonhud.app.core.memory.ConversationMessage;
import com.neonhud.app.core.memory.MemoryItem;
import com.neonhud.app.core.memory.MemoryStore;
import com.neonhud.app.core.memory.Topic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Local SQLite implementation of {@link MemoryStore}. The database never leaves the phone. */
public final class SqliteMemoryStore extends SQLiteOpenHelper implements MemoryStore {

    private static final int VERSION = 1;
    private SQLiteDatabase db;

    public SqliteMemoryStore(Context ctx) {
        this(ctx, "conversation_memory.db");
    }

    /** Separate database file per model, so the coding chat and the Gemma chat never share history. */
    public SqliteMemoryStore(Context ctx, String dbName) {
        super(ctx.getApplicationContext(), dbName, null, VERSION);
        db = getWritableDatabase();
    }

    @Override public void onCreate(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, role TEXT NOT NULL,"
                + " content TEXT NOT NULL, conv INTEGER NOT NULL, topic INTEGER NOT NULL)");
        d.execSQL("CREATE INDEX idx_msg_conv ON messages(conv, id)");
        d.execSQL("CREATE INDEX idx_msg_topic ON messages(topic, id)");
        d.execSQL("CREATE TABLE topics (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, created INTEGER NOT NULL,"
                + " last_used INTEGER NOT NULL, terms TEXT NOT NULL DEFAULT '', domains TEXT NOT NULL DEFAULT '')");
        d.execSQL("CREATE TABLE memories (id INTEGER PRIMARY KEY AUTOINCREMENT, content TEXT NOT NULL, topic INTEGER NOT NULL,"
                + " importance REAL NOT NULL, created INTEGER NOT NULL, last_used INTEGER NOT NULL, terms TEXT NOT NULL DEFAULT '')");
        d.execSQL("CREATE INDEX idx_mem_topic ON memories(topic)");
        d.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT)");
    }

    @Override public void onUpgrade(SQLiteDatabase d, int oldV, int newV) { /* first version: nothing to migrate */ }

    // ---------------------------------------------------------------- messages

    private static ConversationMessage msg(Cursor c) {
        return new ConversationMessage(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getLong(4), c.getLong(5));
    }

    private static final String MSG_COLS = "id, ts, role, content, conv, topic";

    private List<ConversationMessage> readMessages(String sql, String[] args, boolean reverse) {
        List<ConversationMessage> out = new ArrayList<ConversationMessage>();
        Cursor c = db.rawQuery(sql, args);
        try { while (c.moveToNext()) out.add(msg(c)); } finally { c.close(); }
        if (reverse) Collections.reverse(out);
        return out;
    }

    @Override public synchronized long addMessage(long ts, String role, String content, long conv, long topicId) {
        ContentValues v = new ContentValues();
        v.put("ts", ts); v.put("role", role); v.put("content", content); v.put("conv", conv); v.put("topic", topicId);
        return db.insertOrThrow("messages", null, v);
    }

    @Override public synchronized void setMessageTopic(long messageId, long topicId) {
        ContentValues v = new ContentValues();
        v.put("topic", topicId);
        db.update("messages", v, "id=?", new String[]{Long.toString(messageId)});
    }

    @Override public synchronized ConversationMessage getMessage(long id) {
        List<ConversationMessage> l = readMessages("SELECT " + MSG_COLS + " FROM messages WHERE id=?",
                new String[]{Long.toString(id)}, false);
        return l.isEmpty() ? null : l.get(0);
    }

    @Override public synchronized List<ConversationMessage> recentMessages(long conv, int limit) {
        return readMessages("SELECT " + MSG_COLS + " FROM messages WHERE conv=? ORDER BY id DESC LIMIT ?",
                new String[]{Long.toString(conv), Integer.toString(limit)}, true);
    }

    @Override public synchronized List<ConversationMessage> messagesByTopic(long topicId, int limit) {
        return readMessages("SELECT " + MSG_COLS + " FROM messages WHERE topic=? ORDER BY id DESC LIMIT ?",
                new String[]{Long.toString(topicId), Integer.toString(limit)}, true);
    }

    @Override public synchronized List<ConversationMessage> userMessages(int limit) {
        return readMessages("SELECT " + MSG_COLS + " FROM messages WHERE role='user' ORDER BY id DESC LIMIT ?",
                new String[]{Integer.toString(limit)}, true);
    }

    @Override public synchronized ConversationMessage assistantReplyAfter(long userMessageId) {
        List<ConversationMessage> l = readMessages("SELECT " + MSG_COLS + " FROM messages WHERE id>? ORDER BY id ASC LIMIT 1",
                new String[]{Long.toString(userMessageId)}, false);
        if (l.isEmpty() || l.get(0).isUser()) return null;
        return l.get(0);
    }

    @Override public synchronized List<ConversationMessage> lastMessages(int limit) {
        return readMessages("SELECT " + MSG_COLS + " FROM messages ORDER BY id DESC LIMIT ?",
                new String[]{Integer.toString(limit)}, true);
    }

    // ---------------------------------------------------------------- topics

    private static Topic topic(Cursor c) {
        Topic t = new Topic(c.getLong(0), c.getString(1), c.getLong(2), c.getLong(3));
        Topic.decode(c.getString(4), t.terms);
        Topic.decode(c.getString(5), t.domains);
        return t;
    }

    private static ContentValues topicValues(Topic t) {
        ContentValues v = new ContentValues();
        v.put("name", t.name); v.put("created", t.createdAt); v.put("last_used", t.lastUsedAt);
        v.put("terms", Topic.encode(t.terms)); v.put("domains", Topic.encode(t.domains));
        return v;
    }

    @Override public synchronized List<Topic> topics() {
        List<Topic> out = new ArrayList<Topic>();
        Cursor c = db.rawQuery("SELECT id, name, created, last_used, terms, domains FROM topics ORDER BY last_used DESC", null);
        try { while (c.moveToNext()) out.add(topic(c)); } finally { c.close(); }
        return out;
    }

    @Override public synchronized Topic getTopic(long id) {
        Cursor c = db.rawQuery("SELECT id, name, created, last_used, terms, domains FROM topics WHERE id=?",
                new String[]{Long.toString(id)});
        try { return c.moveToFirst() ? topic(c) : null; } finally { c.close(); }
    }

    @Override public synchronized long addTopic(Topic t) {
        long id = db.insertOrThrow("topics", null, topicValues(t));
        t.id = id;
        return id;
    }

    @Override public synchronized void updateTopic(Topic t) {
        db.update("topics", topicValues(t), "id=?", new String[]{Long.toString(t.id)});
    }

    // ---------------------------------------------------------------- memories

    private static MemoryItem memory(Cursor c) {
        return new MemoryItem(c.getLong(0), c.getString(1), c.getLong(2), c.getDouble(3), c.getLong(4), c.getLong(5), c.getString(6));
    }

    @Override public synchronized long addMemory(MemoryItem m) {
        ContentValues v = new ContentValues();
        v.put("content", m.content); v.put("topic", m.topicId); v.put("importance", m.importance);
        v.put("created", m.createdAt); v.put("last_used", m.lastUsedAt); v.put("terms", m.terms);
        long id = db.insertOrThrow("memories", null, v);
        m.id = id;
        return id;
    }

    @Override public synchronized void updateMemory(MemoryItem m) {
        ContentValues v = new ContentValues();
        v.put("importance", m.importance); v.put("last_used", m.lastUsedAt);
        db.update("memories", v, "id=?", new String[]{Long.toString(m.id)});
    }

    @Override public synchronized void deleteMemory(long id) {
        db.delete("memories", "id=?", new String[]{Long.toString(id)});
    }

    @Override public synchronized List<MemoryItem> memoriesByTopic(long topicId) {
        List<MemoryItem> out = new ArrayList<MemoryItem>();
        Cursor c = db.rawQuery("SELECT id, content, topic, importance, created, last_used, terms FROM memories WHERE topic=?",
                new String[]{Long.toString(topicId)});
        try { while (c.moveToNext()) out.add(memory(c)); } finally { c.close(); }
        return out;
    }

    @Override public synchronized int countMemories(long topicId) {
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM memories WHERE topic=?", new String[]{Long.toString(topicId)});
        try { return c.moveToFirst() ? c.getInt(0) : 0; } finally { c.close(); }
    }

    // ---------------------------------------------------------------- meta

    @Override public synchronized String getMeta(String key) {
        Cursor c = db.rawQuery("SELECT v FROM meta WHERE k=?", new String[]{key});
        try { return c.moveToFirst() ? c.getString(0) : null; } finally { c.close(); }
    }

    @Override public synchronized void setMeta(String key, String value) {
        ContentValues v = new ContentValues();
        v.put("k", key); v.put("v", value);
        db.insertWithOnConflict("meta", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }
}
