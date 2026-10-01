package com.neonhud.app.core.office;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/** Lightweight DOCX read/create/edit skill using the OOXML package directly. */
public final class DocxSkill {
    private static final String DOCUMENT_XML = "word/document.xml";
    private static final int MAX_TEXT = 200_000;

    public String read(File file) throws IOException {
        ZipFile zip = new ZipFile(file);
        try {
            String xml = OoxmlUtil.readZipText(zip, DOCUMENT_XML);
            if (xml.isEmpty()) throw new IOException("DOCX document.xml is missing");
            StringBuilder out = new StringBuilder();
            Matcher pm = Pattern.compile("<w:p(?:\\s[^>]*?)?(?<!/)>(.*?)</w:p>", Pattern.DOTALL).matcher(xml);
            int p = 0;
            while (pm.find() && out.length() < MAX_TEXT) {
                String para = paragraphText(pm.group(1));
                if (!para.isEmpty()) out.append(para);
                out.append('\n');
                p++;
            }
            // Tables are also represented by paragraphs inside cells; the paragraph pass preserves their order.
            return "DOCX: " + file.getName() + "\nParagraphs: " + p + "\n" + trimLimit(out.toString(), MAX_TEXT);
        } finally { zip.close(); }
    }

    public File create(String title, List<String> paragraphs, File output) throws IOException {
        List<String> safe = paragraphs == null ? new ArrayList<String>() : paragraphs;
        StringBuilder body = new StringBuilder();
        boolean titleWritten = false;
        if (title != null && !title.trim().isEmpty()) {
            body.append(paragraphXml(title.trim(), true));
            titleWritten = true;
        }
        for (String s : safe) body.append(paragraphXml(s == null ? "" : s, false));
        if (!titleWritten && body.length() == 0) body.append(paragraphXml("", false));

        File parent = output.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) throw new IOException("cannot create output directory");
        OutputStream out = new FileOutputStream(output);
        try {
            java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out);
            put(zip, "[Content_Types].xml", contentTypes());
            put(zip, "_rels/.rels", rootRels());
            put(zip, "word/document.xml", documentXml(body.toString()));
            put(zip, "word/styles.xml", stylesXml());
            put(zip, "word/_rels/document.xml.rels", documentRels());
            zip.close();
            return output;
        } finally { try { out.close(); } catch (IOException ignored) {} }
    }

    public File replaceText(File source, String oldText, String newText, File output) throws IOException {
        if (oldText == null || oldText.isEmpty()) throw new IllegalArgumentException("old text is required");
        final String replacement = newText == null ? "" : newText;
        final int[] count = {0};
        OoxmlUtil.rewriteZip(source, output, (name, entry, zip) -> {
            if (!DOCUMENT_XML.equals(name)) return null;
            String xml = OoxmlUtil.readZipText(zip, name);
            String changed = OoxmlUtil.replaceInTextTags(xml, "w:t", oldText, replacement, true, count);
            return changed.getBytes(StandardCharsets.UTF_8);
        });
        if (count[0] == 0) {
            //noinspection ResultOfMethodCallIgnored
            output.delete();
            throw new IOException("text was not found inside a single Word text run (it may be split across formatting runs)");
        }
        return output;
    }

    /** Text of one paragraph: runs in order, with tabs and line breaks kept so words are not glued together. */
    static String paragraphText(String paragraphXml) {
        StringBuilder out = new StringBuilder();
        Matcher m = Pattern.compile("<w:t(?:\\s[^>]*?)?(?<!/)>(.*?)</w:t>|<w:tab\\s*/>|<w:(?:br|cr)\\b[^>]*/>", Pattern.DOTALL)
                .matcher(paragraphXml == null ? "" : paragraphXml);
        while (m.find()) {
            if (m.group(1) != null) out.append(OoxmlUtil.xmlUnescape(m.group(1)));
            else if (m.group().startsWith("<w:tab")) out.append('\t');
            else out.append('\n');
        }
        return out.toString().trim();
    }

    private static String paragraphXml(String text, boolean title) {
        String safe = OoxmlUtil.xmlEscape(text == null ? "" : text);
        String rPr = title ? "<w:rPr><w:b/><w:sz w:val=\"32\"/><w:rFonts w:ascii=\"Arial\" w:hAnsi=\"Arial\"/></w:rPr>" : "<w:rPr><w:rFonts w:ascii=\"Arial\" w:hAnsi=\"Arial\"/></w:rPr>";
        return "<w:p><w:r>" + rPr + "<w:t xml:space=\"preserve\">" + safe + "</w:t></w:r></w:p>";
    }
    private static void put(java.util.zip.ZipOutputStream z, String name, String value) throws IOException { z.putNextEntry(new java.util.zip.ZipEntry(name)); z.write(value.getBytes(StandardCharsets.UTF_8)); z.closeEntry(); }
    private static String documentXml(String body) { return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>" + body + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>"; }
    private static String stylesXml() { return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Arial\" w:hAnsi=\"Arial\"/><w:sz w:val=\"22\"/></w:rPr></w:rPrDefault></w:docDefaults></w:styles>"; }
    private static String contentTypes() { return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/><Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/></Types>"; }
    private static String rootRels() { return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>"; }
    private static String documentRels() { return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>"; }
    private static String trimLimit(String s, int max) { return s.length() <= max ? s : s.substring(0, max) + "\n[content clipped]"; }
}
