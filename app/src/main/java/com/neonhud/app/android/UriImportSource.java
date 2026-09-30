package com.neonhud.app.android;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.neonhud.app.core.module.ImportSource;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/** A model file chosen with the system file picker (no storage permission needed). */
public final class UriImportSource implements ImportSource {
    private final ContentResolver resolver;
    private final Uri uri;
    private final String name;
    private final long size;

    public UriImportSource(Context ctx, Uri uri) {
        this.resolver = ctx.getApplicationContext().getContentResolver();
        this.uri = uri;
        String n = null;
        long s = -1;
        Cursor c = null;
        try {
            c = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null);
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0 && !c.isNull(ni)) n = c.getString(ni);
                if (si >= 0 && !c.isNull(si)) s = c.getLong(si);
            }
        } catch (RuntimeException ignored) {
        } finally {
            if (c != null) c.close();
        }
        if (n == null) {
            String p = uri.getLastPathSegment();
            n = p == null ? "" : p.substring(p.lastIndexOf('/') + 1);
        }
        this.name = n;
        this.size = s;
    }

    @Override public String displayName() { return name; }
    @Override public long sizeBytes() { return size; }

    @Override public InputStream open() throws IOException {
        InputStream in = resolver.openInputStream(uri);
        if (in == null) throw new FileNotFoundException("Cannot open the selected file.");
        return in;
    }
}
