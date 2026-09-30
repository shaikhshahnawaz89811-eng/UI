package com.neonhud.app.core.engine;

public interface GenerationCallback {
    /** A new piece of the reply (delta, not cumulative). May be called from any thread. */
    void onToken(String delta);
}
