package com.neonhud.app.core.search;

/** Asks Tavily whether an API key works. Blocking: always call it from a worker thread. */
public interface TavilyClient {

    enum Kind {
        /** Tavily accepted the key. */
        VALID,
        /** Tavily accepted the key but its plan / credit limit is used up. */
        LIMIT,
        /** Tavily rejected the key. */
        INVALID,
        /** No internet / timeout - nothing can be said about the key. */
        NETWORK,
        /** Any other answer. */
        ERROR
    }

    final class Outcome {
        public final Kind kind;
        public final String detail;

        public Outcome(Kind kind, String detail) {
            this.kind = kind;
            this.detail = detail == null ? "" : detail;
        }
    }

    Outcome check(String apiKey);
}
