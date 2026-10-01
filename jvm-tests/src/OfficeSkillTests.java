package tests;

import com.neonhud.app.core.engine.Attachment;
import com.neonhud.app.core.engine.FileSniffer;
import com.neonhud.app.core.office.DocxSkill;
import com.neonhud.app.core.office.PptxSkill;
import com.neonhud.app.core.office.XlsxSkill;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

/** Real package-level tests for the three Office skills; no Android or network is required. */
final class OfficeSkillTests {
    static void run() throws Exception {
        T.section("office skills: create -> read -> targeted edit + package sniffing");
        File dir = Files.createTempDirectory("mj-office-tests").toFile();
        try {
            // DOCX
            File docx = new File(dir, "sample.docx");
            DocxSkill d = new DocxSkill();
            d.create("Research Notes", Arrays.asList("Hello MJ", "Second paragraph"), docx);
            String dr = d.read(docx);
            T.check(dr.contains("Research Notes") && dr.contains("Hello MJ") && dr.contains("Second paragraph"), "DOCX read returns title/body text");
            File docx2 = new File(dir, "sample-edited.docx");
            d.replaceText(docx, "Hello MJ", "Hello Research Lab", docx2);
            T.check(d.read(docx2).contains("Hello Research Lab"), "DOCX targeted replacement works");
            T.eq(Attachment.Kind.DOCX, sniff(docx), "DOCX detected from OOXML package parts");

            // XLSX
            File xlsx = new File(dir, "sample.xlsx");
            XlsxSkill x = new XlsxSkill();
            x.create("Sales", Arrays.asList(
                    Arrays.asList("Product", "Units"),
                    Arrays.asList("Phone", "10"),
                    Arrays.asList("Laptop", "4"),
                    Arrays.asList("Total", "=SUM(B2:B3)")), xlsx);
            String xr = x.read(xlsx);
            T.check(xr.contains("SHEET: Sales") && xr.contains("A1=Product") && xr.contains("B3=4") && xr.contains("B4= [formula:=SUM(B2:B3)]"), "XLSX read returns sheet/cell values and formula metadata");
            File xlsx2 = new File(dir, "sample-edited.xlsx");
            x.setCell(xlsx, "Sales", "B2", "12", xlsx2);
            T.check(x.read(xlsx2).contains("B2=12"), "XLSX targeted cell edit works");
            T.eq(Attachment.Kind.XLSX, sniff(xlsx), "XLSX detected from OOXML package parts");

            // CSV/TSV path is part of the spreadsheet skill
            File csv = new File(dir, "sample.csv");
            Files.write(csv.toPath(), "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8));
            T.check(x.read(csv).contains("ROW 2: 1,2"), "CSV is readable through Excel skill");

            // PPTX
            File pptx = new File(dir, "sample.pptx");
            PptxSkill p = new PptxSkill();
            p.create(Arrays.asList(
                    new PptxSkill.Slide("Overview", Arrays.asList("First point", "Second point")),
                    new PptxSkill.Slide("Next", Collections.singletonList("Research continues"))), pptx);
            String pr = p.read(pptx);
            T.check(pr.contains("SLIDE 1") && pr.contains("Overview") && pr.contains("First point") && pr.contains("SLIDE 2"), "PPTX read returns slide text in order");
            File pptx2 = new File(dir, "sample-edited.pptx");
            p.replaceText(pptx, "First point", "Updated point", pptx2);
            T.check(p.read(pptx2).contains("Updated point"), "PPTX targeted replacement works");
            T.eq(Attachment.Kind.PPTX, sniff(pptx), "PPTX detected from OOXML package parts");

            T.check(new java.util.zip.ZipFile(docx).size() >= 5, "DOCX has a real OOXML package");
            T.check(new java.util.zip.ZipFile(xlsx).size() >= 5, "XLSX has a real OOXML package");
            T.check(new java.util.zip.ZipFile(pptx).size() >= 10, "PPTX has a real OOXML package");
        } finally {
            delete(dir);
        }
    }

    private static Attachment.Kind sniff(File file) throws Exception {
        java.io.InputStream in = new java.io.FileInputStream(file);
        try {
            byte[] head = new byte[FileSniffer.HEAD_BYTES];
            int n = 0, r;
            while (n < head.length && (r = in.read(head, n, head.length - n)) > 0) n += r;
            Attachment.Kind k = FileSniffer.detect(head, n);
            if (k == Attachment.Kind.ZIP) {
                java.io.InputStream again = new java.io.FileInputStream(file);
                try { Attachment.Kind office = FileSniffer.detectZipContainer(again); if (office != null) return office; }
                finally { again.close(); }
            }
            return k;
        } finally { in.close(); }
    }

    private static void delete(File f) {
        if (f == null) return;
        if (f.isDirectory()) { File[] kids = f.listFiles(); if (kids != null) for (File k : kids) delete(k); }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
