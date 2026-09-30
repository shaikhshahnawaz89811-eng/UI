package com.neonhud.app.core.web;

/** Why one pasted link (or web picture) could not be read. Each has a short hint the user can act on. */
public enum ReadIssue {
    UNSAFE("ye link safe ya sahi nahi lag raha"),
    NO_KEY("Tavily key nahi hai (Settings > Tavily API)"),
    KEYS_LIMIT("Tavily keys ki limit khatam hai"),
    KEYS_INVALID("Tavily key galat ya expire lag rahi hai"),
    NO_INTERNET("internet nahi mil raha"),
    TIMEOUT("site ne der se jawab diya"),
    SERVER("search service abhi theek se kaam nahi kar rahi"),
    BLOCKED("site ne padhne nahi diya"),
    EMPTY("page par padhne layak text nahi mila"),
    TOO_BIG("file bahut badi hai"),
    NOT_SUPPORTED("ye page ya file padhne layak nahi hai");

    public final String hint;
    ReadIssue(String hint) { this.hint = hint; }

    /** The issue that matches a whole-step problem of the search service. */
    static ReadIssue of(WebTurn.Problem p) {
        switch (p) {
            case NO_KEY: return NO_KEY;
            case ALL_KEYS_LIMIT: return KEYS_LIMIT;
            case ALL_KEYS_INVALID: return KEYS_INVALID;
            case NO_INTERNET: return NO_INTERNET;
            case TIMEOUT: return TIMEOUT;
            case EMPTY: return EMPTY;
            default: return SERVER;
        }
    }
}
