package com.neonhud.app.core.office;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Small Android/JVM-only OOXML helpers. OOXML office files are ZIP packages of XML parts. */
public final class OoxmlUtil {
    private OoxmlUtil() {}

    public static String readZipText(ZipFile zip, String name) throws IOException {
        ZipEntry e = zip.getEntry(name);
        if (e == null) return "";
        InputStream in = zip.getInputStream(e);
        try { return new String(readAll(in, 8 * 1024 * 1024), StandardCharsets.UTF_8); }
        finally { in.close(); }
    }

    public static byte[] readZipBytes(ZipFile zip, String name, int maxBytes) throws IOException {
        ZipEntry e = zip.getEntry(name);
        if (e == null) return null;
        InputStream in = zip.getInputStream(e);
        try { return readAll(in, maxBytes); }
        finally { in.close(); }
    }

    public static byte[] readAll(InputStream in, int maxBytes) throws IOException {
        if (in == null) throw new IOException("input stream is null");
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(64 * 1024, maxBytes));
        byte[] buf = new byte[32 * 1024];
        int total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            if (total + n > maxBytes) throw new IOException("file section is too large");
            out.write(buf, 0, n);
            total += n;
        }
        return out.toByteArray();
    }

    public static File copyToTemp(InputStream in, File dir, String prefix, String suffix) throws IOException {
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) throw new IOException("cannot create cache directory");
        File f = File.createTempFile(prefix, suffix, dir);
        OutputStream out = new FileOutputStream(f);
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            try { in.close(); } catch (IOException ignored) {}
            out.close();
        }
        return f;
    }

    public static List<String> zipEntryNames(InputStream in, int limit) throws IOException {
        List<String> names = new ArrayList<String>();
        ZipInputStream zin = new ZipInputStream(in);
        try {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null && names.size() < limit) names.add(e.getName());
        } finally {
            try { zin.close(); } catch (IOException ignored) {}
        }
        return Collections.unmodifiableList(names);
    }

    /** Copy a ZIP package and replace selected XML/package entries. */
    public static void rewriteZip(File source, File target, EntryTransformer transformer) throws IOException {
        ZipFile zip = new ZipFile(source);
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(target));
        try {
            byte[] buffer = new byte[32 * 1024];
            java.util.Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry src = en.nextElement();
                byte[] replacement = transformer.replace(src.getName(), src, zip);
                if (replacement == SKIP) continue;               // entry dropped from the copy
                ZipEntry dst = new ZipEntry(src.getName());
                dst.setTime(src.getTime());
                out.putNextEntry(dst);
                if (replacement != null) {
                    out.write(replacement);
                } else {
                    InputStream in = zip.getInputStream(src);
                    try {
                        int n;
                        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                    } finally { in.close(); }
                }
                out.closeEntry();
            }
        } finally {
            try { out.close(); } finally { zip.close(); }
        }
    }

    /** Return this from an {@link EntryTransformer} to leave the entry out of the copy. */
    public static final byte[] SKIP = new byte[0];

    public interface EntryTransformer {
        /** null = copy original entry; {@link #SKIP} = leave it out; otherwise use the returned bytes. */
        byte[] replace(String name, ZipEntry sourceEntry, ZipFile zip) throws IOException;
    }

    public static String xmlEscape(String s) {
        if (s == null) return "";
        s = stripInvalidXmlChars(s);
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);");

    /** Decodes XML entities in ONE pass, so a literal "&amp;#65;" becomes "&#65;" and is not decoded a second time. */
    public static String xmlUnescape(String s) {
        if (s == null || s.isEmpty()) return "";
        if (s.indexOf('&') < 0) return s;
        Matcher m = ENTITY.matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String e = m.group(1), rep;
            if ("amp".equals(e)) rep = "&";
            else if ("lt".equals(e)) rep = "<";
            else if ("gt".equals(e)) rep = ">";
            else if ("quot".equals(e)) rep = "\"";
            else if ("apos".equals(e)) rep = "'";
            else {
                try {
                    int cp = e.charAt(1) == 'x' || e.charAt(1) == 'X' ? Integer.parseInt(e.substring(2), 16) : Integer.parseInt(e.substring(1), 10);
                    rep = Character.isValidCodePoint(cp) ? new String(Character.toChars(cp)) : m.group(0);
                } catch (RuntimeException bad) { rep = m.group(0); }
            }
            m.appendReplacement(out, Matcher.quoteReplacement(rep));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Removes control characters and lone surrogates that XML 1.0 does not allow. */
    public static String stripInvalidXmlChars(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) { b.append(c).append(s.charAt(i + 1)); i++; }
                continue;                                              // lone high surrogate: dropped
            }
            if (Character.isLowSurrogate(c) || c == 0xFFFE || c == 0xFFFF) continue;
            if (c < 0x20 && c != 0x9 && c != 0xA && c != 0xD) continue;
            b.append(c);
        }
        return b.toString();
    }

    /**
     * Replaces oldText with newText inside the text of every {@code <tag>...</tag>} element (for example w:t or a:t).
     * The comparison is done on the DECODED text, so apostrophes, quotes, & and < match whichever way the file
     * escaped them. Elements that do not contain the text are copied byte for byte. count[0] gets the number of hits.
     * Text that is split over several runs is not matched (the caller reports that boundary).
     */
    public static String replaceInTextTags(String xml, String tag, String oldText, String newText, boolean preserveSpace, int[] count) {
        Pattern p = Pattern.compile("(<" + Pattern.quote(tag) + "(?:\\s[^>]*?)?(?<!/)>)(.*?)(</" + Pattern.quote(tag) + ">)", Pattern.DOTALL);
        Matcher m = p.matcher(xml);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String open = m.group(1);
            String text = xmlUnescape(m.group(2));
            int hits = 0, at = 0;
            while ((at = text.indexOf(oldText, at)) >= 0) { hits++; at += oldText.length(); }
            if (hits == 0) { m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0))); continue; }
            count[0] += hits;
            String changed = text.replace(oldText, newText);
            if (preserveSpace && !open.contains("xml:space") && !changed.equals(changed.trim())) {
                open = open.substring(0, open.length() - 1) + " xml:space=\"preserve\">";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(open + xmlEscape(changed) + m.group(3)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static String attr(String tag, String name) {
        Matcher m = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*=\\s*\"([^\"]*)\"").matcher(tag);
        return m.find() ? xmlUnescape(m.group(1)) : "";
    }

    public static List<String> matches(String xml, String regex) {
        List<String> out = new ArrayList<String>();
        Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(xml == null ? "" : xml);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    public static String stripXml(String xml) {
        if (xml == null) return "";
        return xml.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }
}
