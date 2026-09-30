package com.neonhud.app.core.module;

import java.io.IOException;
import java.io.InputStream;

/** Something a model file can be imported from (an Android Uri in the app, a File in tests). */
public interface ImportSource {
    String displayName();
    /** Size in bytes, or -1 if unknown. */
    long sizeBytes();
    InputStream open() throws IOException;
}
