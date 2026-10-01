package com.neonhud.app.android;

import android.content.Context;
import android.net.Uri;

import androidx.core.content.FileProvider;

import com.neonhud.app.core.engine.Attachment;
import com.neonhud.app.core.skill.SkillExecution;
import com.neonhud.app.core.skill.SkillKind;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Bridges content:///FileProvider URIs to the Android-free skill executor. */
public final class SkillOutputBridge implements SkillExecution.FileResolver, SkillExecution.OutputPublisher {
    private static final long MAX_COPY_BYTES = 50L * 1024 * 1024;
    private final Context context;
    private final File tempDir;
    private final File outputDir;

    public SkillOutputBridge(Context context, File outputDir) {
        this.context = context.getApplicationContext();
        this.outputDir = outputDir;
        this.tempDir = new File(this.context.getCacheDir(), "skill-inputs");
    }

    @Override public File materialize(Attachment attachment) throws IOException {
        if (attachment == null || attachment.uri == null || attachment.uri.trim().isEmpty())
            throw new IOException("source file URI is missing");
        Uri uri = Uri.parse(attachment.uri);
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            File f = new File(uri.getPath());
            if (!f.isFile()) throw new IOException("source file does not exist");
            return f;
        }
        if (!tempDir.exists() && !tempDir.mkdirs() && !tempDir.exists()) throw new IOException("cannot create temporary skill input directory");
        String suffix = extension(attachment.name);
        File copy = File.createTempFile("input-", suffix.isEmpty() ? ".bin" : suffix, tempDir);
        InputStream in = null;
        FileOutputStream out = null;
        long total = 0;
        try {
            in = context.getContentResolver().openInputStream(uri);
            if (in == null) throw new IOException("cannot open source file");
            out = new FileOutputStream(copy);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_COPY_BYTES) throw new IOException("source file is too large (max 50 MB)");
                out.write(buf, 0, n);
            }
            return copy;
        } catch (Throwable e) {
            //noinspection ResultOfMethodCallIgnored
            copy.delete();
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("cannot copy source file", e);
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) { }
            if (out != null) try { out.close(); } catch (IOException ignored) { }
        }
    }

    @Override public Attachment publish(File file, SkillKind kind) throws IOException {
        if (file == null || !file.isFile() || file.length() <= 0) throw new IOException("output file is missing or empty");
        if (!outputDir.exists() && !outputDir.mkdirs() && !outputDir.exists()) throw new IOException("cannot create output directory");
        Uri uri;
        try {
            uri = FileProvider.getUriForFile(context, context.getPackageName() + ".files", file);
        } catch (IllegalArgumentException e) {
            throw new IOException("output path is not exposed by FileProvider", e);
        }
        return new Attachment(attachmentKind(kind), file.getName(), file.length(), uri.toString());
    }

    private static Attachment.Kind attachmentKind(SkillKind k) {
        switch (k) {
            case PDF_CREATOR: return Attachment.Kind.PDF;
            case DOCX: return Attachment.Kind.DOCX;
            case XLSX: return Attachment.Kind.XLSX;
            case PPTX: return Attachment.Kind.PPTX;
            default: throw new IllegalArgumentException("not an output skill: " + k);
        }
    }

    private static String extension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot) : "";
    }
}
