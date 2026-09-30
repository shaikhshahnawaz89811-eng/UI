package com.neonhud.app.core.module;

import java.io.IOException;

/** Where the imported model file lives. */
public interface ModelStorage {
    /** True if a complete model file is present. */
    boolean isModelPresent();

    String modelPath();

    /** Copies the source into private storage. Must leave nothing behind on failure. */
    void importModel(ImportSource source, ProgressSink progress) throws IOException;

    /** Removes the model (and anything derived from it). Must succeed if nothing is there. */
    void deleteModel() throws IOException;

    /** Removes half-copied leftovers of a crashed/cancelled import. */
    void cleanupPartial();
}
