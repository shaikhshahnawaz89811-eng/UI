package com.neonhud.app.core.search;

import java.util.List;

/** Persists the list of saved Tavily keys (in order). */
public interface TavilyKeyStore {
    List<String> load();
    void save(List<String> keys);
}
