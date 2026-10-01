package com.neonhud.app.core.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small in-process cache for read results. Source files stay authoritative; loaded payloads are reusable per selection. */
public final class AttachmentReadCache {
    private static final int MAX_ENTRIES = 12;
    private final LinkedHashMap<String, Attachment> values = new LinkedHashMap<String, Attachment>(16, 0.75f, true);

    public synchronized void put(Attachment source, ReadSelection selection, Attachment loaded) {
        if (source == null || loaded == null) return;
        values.put(key(source, selection), loaded);
        while (values.size() > MAX_ENTRIES) values.remove(values.keySet().iterator().next());
    }

    public synchronized Attachment get(Attachment source, ReadSelection selection) {
        if (source == null) return null;
        return values.get(key(source, selection));
    }

    public synchronized List<Attachment> sources() {
        LinkedHashMap<String, Attachment> unique = new LinkedHashMap<String, Attachment>();
        for (Attachment a : values.values()) {
            if (a == null) continue;
            String k = sourceKey(a);
            if (!unique.containsKey(k)) unique.put(k, new Attachment(a.kind, a.name, a.sizeBytes, a.uri));
        }
        return new ArrayList<Attachment>(unique.values());
    }


    public synchronized Attachment getLatest(Attachment source) {
        if (source == null) return null;
        String prefix = sourceKey(source) + "|";
        Attachment hit = null;
        for (Map.Entry<String, Attachment> e : values.entrySet()) {
            if (e.getKey().startsWith(prefix)) hit = e.getValue();
        }
        if (hit != null) {
            // Touch the same key through the access-order map so this source survives normal eviction.
            String found = null;
            for (Map.Entry<String, Attachment> e : values.entrySet()) if (e.getValue() == hit) { found = e.getKey(); break; }
            if (found != null) values.get(found);
        }
        return hit;
    }

    public synchronized void clear() { values.clear(); }

    private static String key(Attachment a, ReadSelection s) { return sourceKey(a) + "|" + (s == null ? "all" : s.signature()); }
    private static String sourceKey(Attachment a) { return a.kind + "|" + a.uri + "|" + a.name + "|" + a.sizeBytes; }
}
