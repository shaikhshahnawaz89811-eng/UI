package tests;

import com.neonhud.app.core.engine.Attachment;
import com.neonhud.app.core.engine.FileSniffer;
import com.neonhud.app.core.office.DocxSkill;
import com.neonhud.app.core.office.OoxmlUtil;
import com.neonhud.app.core.office.PptxSkill;
import com.neonhud.app.core.office.XlsxSkill;
import com.neonhud.app.core.skill.SkillKind;
import com.neonhud.app.core.skill.SkillPlanner;
import com.neonhud.app.core.skill.SkillRegistry;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Regression tests for the bugs found in the skills review (real OOXML shapes that Word / Excel / PowerPoint write). */
final class SkillBugfixTests {

    private static final String CT = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>%s</Types>";

    static void run() throws Exception {
        File dir = Files.createTempDirectory("mj-skillfix").toFile();
        try {
            xlsxReading(dir);
            xlsxEditing(dir);
            xlsxCreating(dir);
            docx(dir);
            pptx(dir);
            planner();
            sniffer();
            xmlHelpers();
        } finally { delete(dir); }
    }

    // ------------------------------------------------------------------ xlsx

    private static File workbook(File dir, String name, String sheetXml, boolean calcChain) throws Exception {
        File f = new File(dir, name);
        ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(f.toPath()));
        put(z, "[Content_Types].xml", String.format(CT, "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + (calcChain ? "<Override PartName=\"/xl/calcChain.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.calcChain+xml\"/>" : "")));
        put(z, "_rels/.rels", "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
        put(z, "xl/workbook.xml", "<?xml version=\"1.0\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"S\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
        put(z, "xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/>"
                + (calcChain ? "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/calcChain\" Target=\"calcChain.xml\"/>" : "") + "</Relationships>");
        put(z, "xl/sharedStrings.xml", "<?xml version=\"1.0\"?><sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"4\" uniqueCount=\"4\"><si><r><t>Hel</t></r><r><t>lo</t></r></si><si><t>Plain</t></si><si/><si><t>After &amp; empty</t></si></sst>");
        put(z, "xl/worksheets/sheet1.xml", "<?xml version=\"1.0\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" + sheetXml + "</worksheet>");
        if (calcChain) put(z, "xl/calcChain.xml", "<?xml version=\"1.0\"?><calcChain xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><c r=\"C1\" i=\"1\"/></calcChain>");
        z.close();
        return f;
    }

    private static void xlsxReading(File dir) throws Exception {
        T.section("bugfix: xlsx reading (self-closing cells/rows, rich text, escaping)");
        XlsxSkill x = new XlsxSkill();
        File f = workbook(dir, "r.xlsx", "<sheetData>"
                + "<row r=\"1\"><c r=\"A1\" s=\"1\"/><c r=\"B1\" t=\"s\"><v>1</v></c><c r=\"C1\"><v>5</v></c></row>"
                + "<row r=\"2\" ht=\"20\" customHeight=\"1\"/>"
                + "<row r=\"3\"><c r=\"B3\" t=\"s\"><v>0</v></c><c r=\"C3\" t=\"inlineStr\"><is><t>x &lt; y &gt; z</t></is></c><c r=\"D3\" t=\"s\"><v>3</v></c><c r=\"E3\" t=\"str\"><f>A1&amp;B1</f><v>&amp;lt;</v></c></row>"
                + "</sheetData>", false);
        String out = x.read(f);
        T.check(out.contains("ROW 1: B1=Plain | C1=5"), "empty styled cell does not swallow the next cell");
        T.check(!out.contains("A1=Plain") && !out.contains("A1=1"), "value is not attributed to the empty cell next to it");
        T.check(out.contains("ROW 3: B3=Hello"), "rich-text shared string is not split with a space (Hel+lo)");
        T.check(out.contains("ROW 3:") && !out.contains("ROW 2:"), "self-closing row does not swallow the following row");
        T.check(out.contains("C3=x < y > z"), "inline text with < and > is not cut as if it were a tag");
        T.check(out.contains("D3=After & empty"), "empty <si/> keeps its index so later shared strings are not shifted");
        T.check(out.contains("E3=&lt; [formula:=A1&B1]"), "entities are decoded exactly once");

        File csv = new File(dir, "bom.csv");
        Files.write(csv.toPath(), "\uFEFFname,qty\nx,1\n".getBytes(StandardCharsets.UTF_8));
        T.check(x.read(csv).contains("ROW 1: name,qty"), "CSV UTF-8 BOM is removed from the first cell");
    }

    private static void xlsxEditing(File dir) throws Exception {
        T.section("bugfix: xlsx setCell keeps neighbours, order and package consistency");
        XlsxSkill x = new XlsxSkill();
        String sheet = "<sheetData><row r=\"1\" spans=\"1:3\"><c r=\"A1\" s=\"1\"/><c r=\"B1\" t=\"s\"><v>1</v></c><c r=\"C1\"><f>1+1</f><v>2</v></c></row>"
                + "<row r=\"2\" ht=\"20\"/>"
                + "<row r=\"4\"><c r=\"B4\"><v>9</v></c><c r=\"D4\"><v>8</v></c></row></sheetData>";
        File src = workbook(dir, "e.xlsx", sheet, true);

        File o1 = new File(dir, "e1.xlsx");
        x.setCell(src, "S", "A1", "NEW", o1);
        String r1 = x.read(o1);
        T.check(r1.contains("ROW 1: A1=NEW | B1=Plain | C1=2 [formula:=1+1]"), "editing an empty styled cell leaves B1 and C1 intact");
        T.check(readEntry(o1, "xl/worksheets/sheet1.xml").contains("<c r=\"A1\" s=\"1\" t=\"inlineStr\">"), "the cell's style index is kept");

        File o2 = new File(dir, "e2.xlsx");
        x.setCell(src, "S", "C4", "mid", o2);
        T.check(x.read(o2).contains("ROW 4: B4=9 | C4=mid | D4=8"), "new cell is inserted between B4 and D4 (column order)");
        File o3 = new File(dir, "e3.xlsx");
        x.setCell(src, "S", "A4", "first", o3);
        T.check(x.read(o3).contains("ROW 4: A4=first | B4=9 | D4=8"), "new cell before the first cell of a row");
        File o4 = new File(dir, "e4.xlsx");
        x.setCell(src, "S", "E4", "last", o4);
        T.check(x.read(o4).contains("ROW 4: B4=9 | D4=8 | E4=last"), "new cell after the last cell of a row");

        File o5 = new File(dir, "e5.xlsx");
        x.setCell(src, "S", "B2", "in-empty-row", o5);
        String r5 = x.read(o5);
        T.check(r5.contains("ROW 2: B2=in-empty-row") && r5.contains("ROW 4: B4=9"), "self-closing row is filled without eating row 4");
        File o6 = new File(dir, "e6.xlsx");
        x.setCell(src, "S", "A3", "between", o6);
        String xml6 = readEntry(o6, "xl/worksheets/sheet1.xml");
        T.check(xml6.indexOf("r=\"3\"") > xml6.indexOf("r=\"2\"") && xml6.indexOf("r=\"3\"") < xml6.indexOf("<row r=\"4\""), "a new row lands between row 2 and row 4 (ascending order)");
        File o7 = new File(dir, "e7.xlsx");
        x.setCell(src, "S", "A9", "end", o7);
        String xml7 = readEntry(o7, "xl/worksheets/sheet1.xml");
        T.check(xml7.indexOf("<row r=\"9\"") > xml7.indexOf("<row r=\"4\""), "a row after the last one goes to the end");

        File o8 = new File(dir, "e8.xlsx");
        x.setCell(src, "S", "B4", "42", o8);
        T.check(readEntry(o8, "xl/worksheets/sheet1.xml").contains("<c r=\"B4\"><v>42</v></c>"), "a number stays a number");
        File o9 = new File(dir, "e9.xlsx");
        x.setCell(src, "S", "B4", "=A1+1", o9);
        T.check(readEntry(o9, "xl/worksheets/sheet1.xml").contains("<f>A1+1</f>"), "=... is stored as a formula");

        // calculation chain would point at cells that changed -> removed together with its references
        ZipFile z = new ZipFile(o1);
        try {
            T.check(z.getEntry("xl/calcChain.xml") == null, "calcChain part is dropped on edit");
            T.check(!readEntry(o1, "[Content_Types].xml").contains("calcChain"), "calcChain content-type override removed");
            T.check(!readEntry(o1, "xl/_rels/workbook.xml.rels").contains("calcChain"), "calcChain relationship removed");
            T.check(z.getEntry("xl/sharedStrings.xml") != null && z.getEntry("xl/workbook.xml") != null, "other parts are kept");
        } finally { z.close(); }

        File shared = workbook(dir, "sh.xlsx", "<sheetData><row r=\"1\"><c r=\"A1\"><f t=\"shared\" ref=\"A1:A3\" si=\"0\">B1*2</f><v>2</v></c><c r=\"A2\"><f t=\"shared\" si=\"0\"/><v>4</v></c></row></sheetData>", false);
        boolean refused = false;
        try { x.setCell(shared, "S", "A1", "x", new File(dir, "sh2.xlsx")); } catch (java.io.IOException e) { refused = true; }
        T.check(refused, "master cell of a shared formula is refused instead of silently corrupting the others");

        File empty = workbook(dir, "em.xlsx", "<sheetData/>", false);
        File em2 = new File(dir, "em2.xlsx");
        x.setCell(empty, "S", "C2", "v", em2);
        T.check(x.read(em2).contains("ROW 2: C2=v"), "<sheetData/> (empty sheet) can be edited");

        boolean bad = false;
        try { x.setCell(src, "S", "ZZZZ1", "x", new File(dir, "bad.xlsx")); } catch (IllegalArgumentException e) { bad = true; }
        T.check(bad, "impossible cell reference is rejected");
    }

    private static void xlsxCreating(File dir) throws Exception {
        T.section("bugfix: xlsx create (numbers vs text, sheet name rules)");
        XlsxSkill x = new XlsxSkill();
        File f = new File(dir, "c.xlsx");
        x.create("Bad/Name:[x]?*-and-a-very-long-sheet-name", Arrays.asList(
                Arrays.asList("007", "9876543210", "1234567890123456789", "3.5", "-2", "it's \"q\" & <b>")), f);
        String wb = readEntry(f, "xl/workbook.xml");
        String name = wb.replaceAll("(?s).*<sheet name=\"([^\"]*)\".*", "$1");
        T.check(name.length() <= 31 && !name.matches(".*[\\\\/?*:\\[\\]].*"), "sheet name is valid for Excel (<=31 chars, no \\ / ? * : [ ])");
        String sheet = readEntry(f, "xl/worksheets/sheet1.xml");
        T.check(sheet.contains("<c r=\"A1\" t=\"inlineStr\"><is><t xml:space=\"preserve\">007</t>"), "leading zeros are kept (stored as text)");
        T.check(sheet.contains("<c r=\"B1\"><v>9876543210</v></c>"), "an ordinary number is stored as a number");
        T.check(sheet.contains("<t xml:space=\"preserve\">1234567890123456789</t>"), "more than 15 digits stay text (Excel would round them)");
        T.check(sheet.contains("<c r=\"D1\"><v>3.5</v></c>") && sheet.contains("<c r=\"E1\"><v>-2</v></c>"), "decimal and negative numbers are numbers");
        T.check(x.read(f).contains("it's \"q\" & <b>"), "special characters survive create -> read");
        File blank = new File(dir, "blank.xlsx");
        x.create("   ", Collections.<java.util.List<String>>emptyList(), blank);
        T.check(readEntry(blank, "xl/workbook.xml").contains("name=\"Sheet1\""), "empty sheet name falls back to Sheet1");
    }

    // ------------------------------------------------------------------ docx

    private static void docx(File dir) throws Exception {
        T.section("bugfix: docx (apostrophes, tabs, self-closing paragraphs, XML-safe text)");
        DocxSkill d = new DocxSkill();
        File f = new File(dir, "w.docx");
        ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(f.toPath()));
        put(z, "[Content_Types].xml", String.format(CT, ""));
        put(z, "word/document.xml", "<?xml version=\"1.0\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                + "<w:p w:rsidR=\"1\"/>"
                + "<w:p><w:pPr><w:tabs><w:tab w:val=\"left\" w:pos=\"720\"/></w:tabs></w:pPr><w:r><w:t>It's \"ok\" &amp; fine</w:t></w:r></w:p>"
                + "<w:p><w:r><w:t>Name</w:t></w:r><w:r><w:tab/></w:r><w:r><w:t>Value</w:t></w:r><w:r><w:br/></w:r><w:r><w:t>Next</w:t></w:r></w:p>"
                + "<w:p><w:r><w:t>  lead</w:t></w:r></w:p>"
                + "</w:body></w:document>");
        z.close();
        String r = d.read(f);
        T.check(r.contains("It's \"ok\" & fine"), "docx read keeps quotes and decodes &amp;");
        T.check(r.contains("Name\tValue\nNext"), "docx read keeps tab and line-break between words");
        T.check(!r.contains("\t\tIt's") && !r.contains("\tIt's"), "tab-stop definitions in paragraph properties are not read as text tabs");
        T.check(r.contains("Paragraphs: 3"), "self-closing empty paragraph does not swallow the next paragraph");

        File out = new File(dir, "w2.docx");
        d.replaceText(f, "It's \"ok\" & fine", "It's \"great\" & <better>", out);
        T.check(d.read(out).contains("It's \"great\" & <better>"), "replace text that contains ' \" & works");
        T.check(readEntry(out, "word/document.xml").contains("&lt;better&gt;"), "replacement text is escaped in the XML");
        File out2 = new File(dir, "w3.docx");
        d.replaceText(f, "lead", "lead ", out2);
        boolean refused = false;
        try { d.replaceText(f, "does not exist", "x", new File(dir, "w4.docx")); } catch (java.io.IOException e) { refused = e.getMessage().contains("single"); }
        T.check(refused, "text split across runs / missing text is reported, not silently ignored");
        T.check(!new File(dir, "w4.docx").exists(), "no half-edited output file is left behind on failure");

        File made = new File(dir, "made.docx");
        d.create("T\u0001itle", Arrays.asList("a\u0000b", "ok"), made);
        T.check(d.read(made).contains("Title") && d.read(made).contains("ab"), "control characters are dropped so the file stays valid XML");
        T.check(readEntry(made, "word/document.xml").contains("w:header=\"708\""), "page margins carry all required attributes");
    }

    // ------------------------------------------------------------------ pptx

    private static void pptx(File dir) throws Exception {
        T.section("bugfix: pptx (valid package structure, reading runs, apostrophes)");
        PptxSkill p = new PptxSkill();
        File f = new File(dir, "p.pptx");
        p.create(Arrays.asList(new PptxSkill.Slide("Deck", Arrays.asList("It's one", "", "two & three"))), f);
        String master = readEntry(f, "ppt/slideMasters/slideMaster1.xml");
        T.check(master.indexOf("<p:clrMap") > 0 && master.indexOf("<p:clrMap") < master.indexOf("<p:sldLayoutIdLst"), "slide master: clrMap comes before sldLayoutIdLst (schema order)");
        T.check(master.indexOf("<p:sldLayoutIdLst") < master.indexOf("<p:txStyles"), "slide master: txStyles comes last");
        T.check(master.contains("sldLayoutId id=\"2147483649\""), "slide layout id is in the valid range (>= 2147483648)");
        String theme = readEntry(f, "ppt/theme/theme1.xml");
        T.check(count(theme, "<a:ln ") == 3 && count(theme, "<a:effectStyle>") == 3, "theme has the 3 line styles and 3 effect styles PowerPoint requires");
        T.check(theme.contains("<a:ea typeface=\"\"/>") && theme.contains("<a:cs typeface=\"\"/>"), "theme fonts have latin + ea + cs");
        String slide = readEntry(f, "ppt/slides/slide1.xml");
        T.check(count(slide, "<a:p>") >= 4, "every bullet is its own paragraph");
        T.check(slide.contains("<a:latin typeface=\"Arial\"/>"), "text runs really use Arial");
        T.check(slide.contains("cy=\"3400000\""), "body box ends inside the 16:9 slide (5143500 EMU high)");
        T.check(p.read(f).contains("Deck | \u2022 It's one | \u2022 two & three"), "read: one entry per paragraph, apostrophes intact");

        File split = new File(dir, "split.pptx");
        copyWithSlide(f, split, "<p:sld xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\"><p:cSld><p:spTree><p:sp><p:txBody><a:p><a:r><a:t>Hel</a:t></a:r><a:r><a:t>lo wor</a:t></a:r><a:r><a:t>ld</a:t></a:r></a:p><a:p><a:r><a:t>Q&amp;A</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>");
        T.check(p.read(split).contains("SLIDE 1: Hello world | Q&A"), "a sentence split into several runs reads as one sentence");

        File out = new File(dir, "p2.pptx");
        p.replaceText(f, "It's one", "It's \"two\" & more", out);
        T.check(p.read(out).contains("It's \"two\" & more"), "replace text containing ' \" & works");
        boolean refused = false;
        try { p.replaceText(split, "Hello world", "x", new File(dir, "p3.pptx")); } catch (java.io.IOException e) { refused = true; }
        T.check(refused, "text split over runs is reported, not silently ignored");
    }

    // ------------------------------------------------------------------ planner / registry / sniffer / xml

    private static void planner() {
        T.section("bugfix: skill planner uses whole words");
        java.util.List<Attachment> none = Collections.<Attachment>emptyList();
        T.check(SkillPlanner.forTurn("password banao", none).isEmpty(), "\"password banao\" is not a Word request");
        T.check(SkillPlanner.forTurn("logging kaise karte hain", none).isEmpty(), "\"logging\" is not an audio request (ogg)");
        T.check(SkillPlanner.forTurn("wave kya hai", none).isEmpty() && SkillPlanner.forTurn("belonging", none).isEmpty(), "\"wave\" / \"belonging\" are not audio requests");
        T.check(SkillPlanner.forTurn("ye song sunao", none).contains(SkillKind.AUDIO_READER), "\"song\" as a word still selects the audio skill");
        T.check(SkillPlanner.forTurn("a.mp3 padho", none).contains(SkillKind.AUDIO_READER), "\"mp3\" next to a dot still counts as a word");
        T.check(SkillPlanner.forTurn("Word banao", none).contains(SkillKind.DOCX) || SkillPlanner.forTurn("word document banao", none).contains(SkillKind.DOCX), "real Word creation phrase still works");
        T.check(SkillPlanner.forTurn("mujhe pdf bana do", none).contains(SkillKind.PDF_CREATOR), "\"pdf bana do\" still selects PDF creation");
        T.check(!new SkillRegistry().get(SkillKind.AUDIO_READER).needsVision, "audio reader does not need the vision model");
        T.check(new SkillRegistry().get(SkillKind.VIDEO_READER).needsVision, "video reader still needs the vision model");
    }

    private static void sniffer() {
        T.section("bugfix: ftyp brand decides audio / picture / video");
        T.eq(Attachment.Kind.AUDIO, FileSniffer.detect(new byte[]{0, 0, 0, 0x20, 'f', 't', 'y', 'p', 'M', '4', 'A', ' ', 0, 0, 0, 0}, 16), "M4A is audio, not video");
        T.eq(Attachment.Kind.AUDIO, FileSniffer.detect(new byte[]{0, 0, 0, 0x20, 'f', 't', 'y', 'p', 'M', '4', 'B', ' '}, 12), "M4B audiobook is audio");
        T.eq(Attachment.Kind.VIDEO, FileSniffer.detect(new byte[]{0, 0, 0, 0x20, 'f', 't', 'y', 'p', 'm', 'p', '4', '2'}, 12), "mp42 stays video");
        T.eq(Attachment.Kind.VIDEO, FileSniffer.detect(new byte[]{0, 0, 0, 0x14, 'f', 't', 'y', 'p', 'q', 't', ' ', ' '}, 12), "QuickTime stays video");
        T.eq(null, FileSniffer.detect(new byte[]{0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c'}, 12), "HEIC picture is not mistaken for video");
        T.eq(null, FileSniffer.detect(new byte[]{0, 0, 0, 0x1C, 'f', 't', 'y', 'p', 'a', 'v', 'i', 'f'}, 12), "AVIF picture is not mistaken for video");
        T.eq(Attachment.Kind.VIDEO, FileSniffer.detect(new byte[]{0, 0, 0, 0x14, 'f', 't', 'y', 'p'}, 8), "ftyp box cut short before the brand still counts as video");
        T.eq(Attachment.Kind.AUDIO, FileSniffer.detect(new byte[]{'#', '!', 'A', 'M', 'R', '\n'}, 6), "AMR voice note is audio");
    }

    private static void xmlHelpers() {
        T.section("bugfix: xml helpers");
        T.eq("&#65;", OoxmlUtil.xmlUnescape("&amp;#65;"), "an escaped entity is not decoded twice");
        T.eq("A<>&\"'", OoxmlUtil.xmlUnescape("&#65;&lt;&gt;&amp;&quot;&apos;"), "all standard entities decode");
        T.eq("\uD83D\uDE00", OoxmlUtil.xmlUnescape("&#x1F600;"), "numeric entity above the BMP decodes");
        T.eq("&#99999999;", OoxmlUtil.xmlUnescape("&#99999999;"), "an invalid code point is left alone");
        T.eq("ab", OoxmlUtil.xmlEscape("a\u0000\u0008b"), "illegal XML control characters are removed");
        T.eq("a\tb\nc", OoxmlUtil.xmlEscape("a\tb\nc"), "tab and newline are kept");
        T.eq("x\uD83D\uDE00y", OoxmlUtil.xmlEscape("x\uD83D\uDE00y"), "a real surrogate pair (emoji) is kept");
        T.eq("xy", OoxmlUtil.xmlEscape("x\uD83Dy"), "a lone surrogate is removed");
    }

    // ------------------------------------------------------------------ helpers

    private static void put(ZipOutputStream z, String name, String text) throws Exception {
        z.putNextEntry(new ZipEntry(name));
        z.write(text.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    private static String readEntry(File f, String name) throws Exception {
        ZipFile z = new ZipFile(f);
        try {
            ZipEntry e = z.getEntry(name);
            if (e == null) return "";
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            java.io.InputStream in = z.getInputStream(e);
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            in.close();
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        } finally { z.close(); }
    }

    private static void copyWithSlide(File src, File dst, String slideXml) throws Exception {
        ZipFile z = new ZipFile(src);
        ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(dst.toPath()));
        try {
            java.util.Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.getName().equals("ppt/slides/slide1.xml")) { put(out, e.getName(), slideXml); continue; }
                out.putNextEntry(new ZipEntry(e.getName()));
                java.io.InputStream in = z.getInputStream(e);
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
                out.closeEntry();
            }
        } finally { out.close(); z.close(); }
    }

    private static int count(String s, String needle) {
        int c = 0, at = 0;
        while ((at = s.indexOf(needle, at)) >= 0) { c++; at += needle.length(); }
        return c;
    }

    private static void delete(File f) {
        if (f == null) return;
        if (f.isDirectory()) { File[] kids = f.listFiles(); if (kids != null) for (File k : kids) delete(k); }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
