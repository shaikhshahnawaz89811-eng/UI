package com.neonhud.app.core.skill;

import java.io.File;
import java.io.IOException;

/** Android-independent contract for PDF creation/verification. */
public interface PdfWriter {
    File createTextPdf(String title, String text, File output) throws IOException;
    void verify(File file) throws IOException;
}
