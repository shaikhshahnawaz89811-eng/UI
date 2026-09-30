package com.neonhud.app.core.module;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Plain java.io implementation of {@link ModelStorage}: copies to "<name>.part", verifies size,
 * then renames, so a model file is either complete or absent - never half-written.
 */
public class FileModelStorage implements ModelStorage {

    private final File dir;
    private final String fileName;
    private final String requiredExtension;
    private final long minBytes;
    private final File[] extraDeletes;
    private final String displayName;
    private final byte[] requiredMagic;     // first bytes every valid file starts with (null = no check)

    /** Original form (Gemma 4 E2B, no header check) - behaviour unchanged. */
    public FileModelStorage(File dir, String fileName, String requiredExtension,
                            long minBytes, File... extraDeletes) {
        this(dir, fileName, requiredExtension, minBytes, "Gemma 4 E2B", null, extraDeletes);
    }

    /** Full form: model name shown in messages and an optional file-header check (GGUF starts with "GGUF"). */
    public FileModelStorage(File dir, String fileName, String requiredExtension, long minBytes,
                            String displayName, byte[] requiredMagic, File... extraDeletes) {
        this.dir = dir;
        this.fileName = fileName;
        this.requiredExtension = requiredExtension.toLowerCase(Locale.ROOT);
        this.minBytes = minBytes;
        this.displayName = displayName;
        this.requiredMagic = requiredMagic;
        this.extraDeletes = extraDeletes == null ? new File[0] : extraDeletes;
    }

    private File finalFile() { return new File(dir, fileName); }
    private File partFile() { return new File(dir, fileName + ".part"); }

    @Override public boolean isModelPresent() {
        File f = finalFile();
        return f.isFile() && f.length() >= minBytes;
    }

    @Override public String modelPath() { return finalFile().getAbsolutePath(); }

    @Override public void importModel(ImportSource source, ProgressSink progress) throws IOException {
        String shown = source.displayName() == null ? "" : source.displayName();
        String name = shown.toLowerCase(Locale.ROOT);
        // Lenient: accept "x.litertlm", "x.litertlm.bin", "x.litertlm (1)", and pickers that give
        // no real file name (e.g. "msf:1234"). Reject only names that clearly are something else.
        boolean hasExt = name.contains(requiredExtension);
        boolean nameless = name.isEmpty() || name.indexOf('.') < 0;
        if (!hasExt && !nameless) {
            throw new IOException("Please choose the " + displayName + " " + requiredExtension
                    + " file (you chose: " + shown + ").");
        }
        long size = source.sizeBytes();
        if (size >= 0 && size < minBytes) {
            throw new IOException("This file is too small to be the " + displayName + " model.");
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create model folder.");
        }
        if (size > 0 && dir.getUsableSpace() < size + 64L * 1024 * 1024) {
            throw new IOException("Not enough free storage to import the model.");
        }
        File part = partFile();
        cleanupPartial();
        boolean ok = false;
        try {
            long copied = 0;
            int lastPct = -1;
            byte[] buf = new byte[1 << 20];
            try (InputStream in = source.open(); FileOutputStream out = new FileOutputStream(part)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    copied += n;
                    if (size > 0 && progress != null) {
                        int pct = (int) Math.min(99, copied * 100 / size);
                        if (pct != lastPct) { lastPct = pct; progress.onProgress(pct); }
                    }
                }
                out.getFD().sync();
            }
            if (copied < minBytes || (size > 0 && copied != size)) {
                throw new IOException("Import incomplete - please try again.");
            }
            if (!hasRequiredMagic(part)) {
                throw new IOException("This is not a valid " + displayName + " model file.");
            }
            Files.move(part.toPath(), finalFile().toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (progress != null) progress.onProgress(100);
            ok = true;
        } finally {
            if (!ok) //noinspection ResultOfMethodCallIgnored
                part.delete();
        }
    }

    private boolean hasRequiredMagic(File f) throws IOException {
        if (requiredMagic == null || requiredMagic.length == 0) return true;
        byte[] head = new byte[requiredMagic.length];
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            int got = 0, n;
            while (got < head.length && (n = in.read(head, got, head.length - got)) > 0) got += n;
            if (got < head.length) return false;
        }
        return java.util.Arrays.equals(head, requiredMagic);
    }

    @Override public void deleteModel() throws IOException {
        File f = finalFile();
        if (f.exists() && !f.delete()) throw new IOException("Could not delete the model file.");
        cleanupPartial();
        for (File extra : extraDeletes) deleteRecursively(extra);
    }

    @Override public void cleanupPartial() {
        File p = partFile();
        if (p.exists()) //noinspection ResultOfMethodCallIgnored
            p.delete();
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursively(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
