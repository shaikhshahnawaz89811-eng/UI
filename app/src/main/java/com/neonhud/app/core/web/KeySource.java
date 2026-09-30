package com.neonhud.app.core.web;

import java.util.List;

/** Where the search service gets the user's saved Tavily keys (in the order they were added). */
public interface KeySource {
    List<String> keys();
}
