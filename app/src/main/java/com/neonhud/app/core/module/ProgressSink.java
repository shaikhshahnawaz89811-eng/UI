package com.neonhud.app.core.module;

public interface ProgressSink {
    /** @param percent 0..100 */
    void onProgress(int percent);
}
