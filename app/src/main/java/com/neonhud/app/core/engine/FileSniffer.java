package com.neonhud.app.core.engine;

/**
 * Tells what a file REALLY is from its first bytes, so a PDF or zip is accepted from any app, folder or cloud drive
 * no matter what name or MIME type that source reports (WhatsApp, Telegram, Drive, Downloads often say
 * "application/octet-stream" or give no extension at all). Pure Java.
 */
public final class FileSniffer {

    /** How many bytes from the start of the file are needed. */
    public static final int HEAD_BYTES = 1024;

    private FileSniffer() { }

    /** @return PDF, ZIP, VIDEO, or null when the bytes are neither. */
    public static Attachment.Kind detect(byte[] head, int len) {
        if (head == null || len <= 0) return null;
        len = Math.min(len, head.length);
        // zip: "PK" + 03 04 (normal), 05 06 (empty zip) or 07 08 (spanned)
        if (len >= 4 && head[0] == 'P' && head[1] == 'K'
                && ((head[2] == 3 && head[3] == 4) || (head[2] == 5 && head[3] == 6) || (head[2] == 7 && head[3] == 8))) {
            return Attachment.Kind.ZIP;
        }
        // Common MP4/MOV: an ftyp box appears within the first 32 bytes.
        for (int i = 4; i + 4 <= len && i < 32; i++) {
            if (head[i] == 'f' && head[i + 1] == 't' && head[i + 2] == 'y' && head[i + 3] == 'p') return Attachment.Kind.VIDEO;
        }
        // WebM/Matroska starts with EBML 1A 45 DF A3.
        if (len >= 4 && (head[0] & 0xFF) == 0x1A && (head[1] & 0xFF) == 0x45 && (head[2] & 0xFF) == 0xDF && (head[3] & 0xFF) == 0xA3) return Attachment.Kind.VIDEO;
        // pdf: "%PDF-" at the start; the PDF spec lets a few junk bytes come first, so look in the first 1024
        for (int i = 0; i + 5 <= len; i++) {
            if (head[i] == '%' && head[i + 1] == 'P' && head[i + 2] == 'D' && head[i + 3] == 'F' && head[i + 4] == '-') {
                return Attachment.Kind.PDF;
            }
        }
        return null;
    }
}
