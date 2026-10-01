package com.neonhud.app.core.engine;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Tells what a file REALLY is from its first bytes, so a PDF or zip is accepted from any app, folder or cloud drive
 * no matter what name or MIME type that source reports (WhatsApp, Telegram, Drive, Downloads often say
 * "application/octet-stream" or give no extension at all). Pure Java.
 */
public final class FileSniffer {

    /** How many bytes from the start of the file are needed. */
    public static final int HEAD_BYTES = 1024;

    private FileSniffer() { }

    /** @return PDF, ZIP, AUDIO, VIDEO, or null when the bytes are neither; OOXML documents still look like ZIP at header level. */
    public static Attachment.Kind detect(byte[] head, int len) {
        if (head == null || len <= 0) return null;
        len = Math.min(len, head.length);
        // zip: "PK" + 03 04 (normal), 05 06 (empty zip) or 07 08 (spanned)
        if (len >= 4 && head[0] == 'P' && head[1] == 'K'
                && ((head[2] == 3 && head[3] == 4) || (head[2] == 5 && head[3] == 6) || (head[2] == 7 && head[3] == 8))) {
            return Attachment.Kind.ZIP;
        }
        // ISO base media (MP4/MOV/M4A/HEIC ...): an ftyp box appears within the first 32 bytes. The 4-letter brand after
        // it says what the file is: M4A/M4B are AUDIO, HEIC/AVIF are pictures (not video), everything else is VIDEO.
        for (int i = 4; i + 4 <= len && i < 32; i++) {
            if (head[i] == 'f' && head[i + 1] == 't' && head[i + 2] == 'y' && head[i + 3] == 'p') {
                if (i + 8 <= len) {
                    String brand = new String(new char[]{(char) (head[i + 4] & 0xFF), (char) (head[i + 5] & 0xFF), (char) (head[i + 6] & 0xFF), (char) (head[i + 7] & 0xFF)});
                    if (brand.equals("M4A ") || brand.equals("M4B ") || brand.equals("M4P ") || brand.equals("F4A ") || brand.equals("F4B ")) return Attachment.Kind.AUDIO;
                    if (brand.equals("heic") || brand.equals("heix") || brand.equals("heim") || brand.equals("heis") || brand.equals("hevc")
                            || brand.equals("hevx") || brand.equals("mif1") || brand.equals("msf1") || brand.equals("avif") || brand.equals("avis")) return null;
                }
                return Attachment.Kind.VIDEO;
            }
        }
        // WebM/Matroska starts with EBML 1A 45 DF A3.
        if (len >= 4 && (head[0] & 0xFF) == 0x1A && (head[1] & 0xFF) == 0x45 && (head[2] & 0xFF) == 0xDF && (head[3] & 0xFF) == 0xA3) return Attachment.Kind.VIDEO;
        // Common audio containers. Keep these before PDF/ZIP so raw audio is never mistaken for generic data.
        if (len >= 4 && head[0] == 'f' && head[1] == 'L' && head[2] == 'a' && head[3] == 'C') return Attachment.Kind.AUDIO;
        if (len >= 4 && head[0] == 'O' && head[1] == 'g' && head[2] == 'g' && head[3] == 'S') return Attachment.Kind.AUDIO;
        if (len >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F' && head[8] == 'W' && head[9] == 'A' && head[10] == 'V' && head[11] == 'E') return Attachment.Kind.AUDIO;
        if (len >= 3 && head[0] == 'I' && head[1] == 'D' && head[2] == '3') return Attachment.Kind.AUDIO;
        if (len >= 5 && head[0] == '#' && head[1] == '!' && head[2] == 'A' && head[3] == 'M' && head[4] == 'R') return Attachment.Kind.AUDIO;   // AMR voice notes
        if (len >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xE0) == 0xE0) return Attachment.Kind.AUDIO;
        // pdf: "%PDF-" at the start; the PDF spec lets a few junk bytes come first, so look in the first 1024
        for (int i = 0; i + 5 <= len; i++) {
            if (head[i] == '%' && head[i + 1] == 'P' && head[i + 2] == 'D' && head[i + 3] == 'F' && head[i + 4] == '-') {
                return Attachment.Kind.PDF;
            }
        }
        return null;
    }

    /**
     * Inspects a ZIP-based office package by its internal OOXML parts, not just the filename.
     * Returns null for a normal project ZIP. The caller should call this only after detect(...) returned ZIP.
     */
    public static Attachment.Kind detectZipContainer(InputStream input) throws IOException {
        if (input == null) return null;
        boolean docx = false, xlsx = false, pptx = false;
        ZipInputStream zin = new ZipInputStream(input);
        try {
            ZipEntry e;
            int seen = 0;
            while ((e = zin.getNextEntry()) != null && seen++ < 3000) {
                String n = e.getName();
                if ("word/document.xml".equals(n)) docx = true;
                else if ("xl/workbook.xml".equals(n)) xlsx = true;
                else if ("ppt/presentation.xml".equals(n)) pptx = true;
                if (docx) return Attachment.Kind.DOCX;
                if (xlsx) return Attachment.Kind.XLSX;
                if (pptx) return Attachment.Kind.PPTX;
            }
            return null;
        } finally { try { zin.close(); } catch (IOException ignored) {} }
    }
}

