package tests;

import com.neonhud.app.core.engine.Attachment;
import com.neonhud.app.core.engine.FileSniffer;

import java.nio.charset.StandardCharsets;

final class FileSnifferTests {
    private static Attachment.Kind d(byte[] b) { return FileSniffer.detect(b, b.length); }
    private static byte[] s(String x) { return x.getBytes(StandardCharsets.ISO_8859_1); }

    static void run() {
        T.section("file check: pdf / zip / video recognised by content, not by name");
        T.eq(Attachment.Kind.PDF, d(s("%PDF-1.7\n%....")), "normal pdf");
        T.eq(Attachment.Kind.PDF, d(s("\n\n  junk before header %PDF-1.4 rest")), "pdf with a few bytes before the header");
        T.eq(Attachment.Kind.ZIP, d(new byte[]{'P', 'K', 3, 4, 20, 0}), "normal zip");
        T.eq(Attachment.Kind.ZIP, d(new byte[]{'P', 'K', 5, 6, 0, 0}), "empty zip");
        T.eq(Attachment.Kind.ZIP, d(new byte[]{'P', 'K', 7, 8, 0, 0}), "spanned zip");
        T.eq(Attachment.Kind.VIDEO, d(new byte[]{0,0,0,20,'f','t','y','p','i','s','o','m'}), "MP4/MOV ftyp video");
        T.eq(Attachment.Kind.VIDEO, d(new byte[]{0x1A,0x45,(byte)0xDF,(byte)0xA3,0,0}), "WebM/Matroska video");
        T.eq(null, d(s("hello world, plain text")), "text is neither");
        T.eq(null, d(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}), "jpeg is neither (images keep their own button)");
        T.eq(null, d(new byte[]{(byte) 0x89, 'P', 'N', 'G'}), "png is neither");
        T.eq(null, d(new byte[0]), "empty file is neither");
        T.eq(null, FileSniffer.detect(null, 0), "null is neither");
        T.eq(null, d(new byte[]{'P', 'K', 9, 9}), "PK with wrong signature is not a zip");
        T.eq(null, d(s("%PDF")), "'%PDF' without the dash is not enough");
        byte[] far = new byte[2000];
        java.util.Arrays.fill(far, (byte) 'a');
        byte[] mark = s("%PDF-1.4");
        System.arraycopy(mark, 0, far, 1500, mark.length);
        T.eq(null, FileSniffer.detect(far, 1024), "header beyond the first 1024 bytes is ignored (only 1024 are read)");
        T.eq(Attachment.Kind.AUDIO, FileSniffer.detect(new byte[]{'I','D','3',3}, 4), "ID3 audio detected");
        T.eq(Attachment.Kind.AUDIO, FileSniffer.detect(new byte[]{'f','L','a','C'}, 4), "FLAC audio detected");
        T.eq(Attachment.Kind.AUDIO, FileSniffer.detect(new byte[]{'O','g','g','S'}, 4), "OGG audio detected");
        T.eq(Attachment.Kind.AUDIO, FileSniffer.detect(new byte[]{'R','I','F','F',0,0,0,0,'W','A','V','E'}, 12), "WAV audio detected");
    }
}
