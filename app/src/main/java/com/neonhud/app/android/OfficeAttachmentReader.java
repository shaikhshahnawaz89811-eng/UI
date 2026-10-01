package com.neonhud.app.android;

import android.content.Context;
import android.net.Uri;

import com.neonhud.app.core.engine.Attachment;
import com.neonhud.app.core.engine.ReadSelection;
import com.neonhud.app.core.engine.SelectableAttachmentLoader;
import com.neonhud.app.core.office.DocxSkill;
import com.neonhud.app.core.office.PptxSkill;
import com.neonhud.app.core.office.XlsxSkill;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Android bridge for the pure-Java Office skills. It copies a provider URI to a private cache file, then parses it. */
public final class OfficeAttachmentReader implements SelectableAttachmentLoader {
    private static final int COPY_BUFFER = 64 * 1024;
    private final Context app;
    private final DocxSkill docx = new DocxSkill();
    private final XlsxSkill xlsx = new XlsxSkill();
    private final PptxSkill pptx = new PptxSkill();

    public OfficeAttachmentReader(Context context) { this.app = context.getApplicationContext(); }

    @Override public Attachment load(Attachment a, ReadSelection selection) throws Exception {
        Uri uri = Uri.parse(a.uri);
        InputStream in = app.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("cannot open Office file");
        File tmp = null;
        try {
            File dir = new File(app.getCacheDir(), "office");
            if (!dir.exists() && !dir.mkdirs() && !dir.exists()) throw new IOException("cannot create office cache");
            String name = a.name == null ? "" : a.name.toLowerCase(java.util.Locale.ROOT);
            String ext;
            if (a.kind == Attachment.Kind.DOCX) ext = ".docx";
            else if (a.kind == Attachment.Kind.PPTX) ext = ".pptx";
            else if (name.endsWith(".csv")) ext = ".csv";
            else if (name.endsWith(".tsv")) ext = ".tsv";
            else ext = ".xlsx";
            tmp = File.createTempFile("office-read-", ext, dir);
            java.io.OutputStream out = new java.io.FileOutputStream(tmp);
            try {
                byte[] buf = new byte[COPY_BUFFER]; int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > 50L * 1024 * 1024) throw new IOException("file exceeds 50 MB attachment limit");
                    out.write(buf, 0, n);
                }
            } finally { out.close(); }
            String report;
            switch (a.kind) {
                case DOCX: report = docx.read(tmp); break;
                case XLSX: report = selection != null && selection.hasSheet() ? xlsx.read(tmp, selection.sheet) : xlsx.read(tmp); break;
                case PPTX: report = selection != null && selection.hasSlides() ? pptx.read(tmp, selection.slideStart, selection.slideEnd) : pptx.read(tmp); break;
                default: return a;
            }
            return a.loaded(report, java.util.Collections.<byte[]>emptyList());
        } finally {
            try { in.close(); } catch (IOException ignored) {}
            if (tmp != null) tmp.delete();
        }
    }
}
