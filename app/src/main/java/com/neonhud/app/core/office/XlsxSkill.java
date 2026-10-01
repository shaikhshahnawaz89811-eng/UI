package com.neonhud.app.core.office;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/** Lightweight XLSX/CSV/TSV read/create/edit skill. */
public final class XlsxSkill {
    private static final int MAX_CHARS = 240_000;

    private static final Pattern ROW = Pattern.compile("<row\\b([^>]*?)(?:/>|>(.*?)</row>)", Pattern.DOTALL);
    private static final Pattern CELL = Pattern.compile("<c\\b([^>]*?)(?:/>|>(.*?)</c>)", Pattern.DOTALL);
    private static final Pattern PLAIN_NUMBER = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?");

    public String read(File file) throws IOException { return read(file, null); }

    /** Reads either the whole workbook or one named sheet when selectedSheet is non-empty. */
    public String read(File file, String selectedSheet) throws IOException {
        String lower = file.getName().toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".csv") || lower.endsWith(".tsv")) return readDelimited(file, lower.endsWith(".tsv") ? '\t' : ',');
        ZipFile zip = new ZipFile(file);
        try {
            List<String> shared = sharedStrings(zip);
            String wb = OoxmlUtil.readZipText(zip, "xl/workbook.xml");
            Map<String,String> rels = workbookRelationships(zip);
            StringBuilder out = new StringBuilder();
            Matcher sm = Pattern.compile("<sheet\\b([^>]*)/>").matcher(wb);
            int sheets = 0;
            boolean wantedFound = false;
            while (sm.find()) {
                String attrs = sm.group(1);
                String name = OoxmlUtil.attr(attrs, "name");
                if (selectedSheet != null && !selectedSheet.isEmpty() && !selectedSheet.equals(name)) continue;
                wantedFound = selectedSheet == null || selectedSheet.isEmpty() || selectedSheet.equals(name);
                String rid = OoxmlUtil.attr(attrs, "r:id");
                String target = rels.get(rid);
                if (target == null) continue;
                target = resolveWorkbookTarget(target);
                String xml = OoxmlUtil.readZipText(zip, target);
                if (xml.isEmpty()) continue;
                out.append("SHEET: ").append(name).append('\n');
                out.append(sheetText(xml, shared)).append('\n');
                sheets++;
                if (out.length() >= MAX_CHARS) break;
            }
            if (sheets == 0) {
                if (selectedSheet != null && !selectedSheet.isEmpty() && !wantedFound) throw new IOException("sheet not found: " + selectedSheet);
                throw new IOException("XLSX workbook contains no readable sheets");
            }
            return trimLimit("XLSX: " + file.getName() + "\n" + out.toString(), MAX_CHARS);
        } finally { zip.close(); }
    }

    public File create(String sheetName, List<List<String>> rows, File output) throws IOException {
        List<List<String>> data = rows == null ? Collections.<List<String>>emptyList() : rows;
        String safeSheet = OoxmlUtil.xmlEscape(safeSheetName(sheetName));
        String sheetXml = sheetXml(data);
        File parent = output.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) throw new IOException("cannot create output directory");
        OutputStream out = new FileOutputStream(output);
        try {
            java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out);
            put(zip, "[Content_Types].xml", contentTypes());
            put(zip, "_rels/.rels", rootRels());
            put(zip, "xl/workbook.xml", workbookXml(safeSheet));
            put(zip, "xl/_rels/workbook.xml.rels", workbookRels());
            put(zip, "xl/worksheets/sheet1.xml", sheetXml);
            put(zip, "xl/styles.xml", stylesXml());
            zip.close();
            return output;
        } finally { try { out.close(); } catch (IOException ignored) {} }
    }

    /**
     * Edit one cell and preserve every unrelated ZIP part. Sheet names are matched exactly. The cell is written in its
     * correct row/column position; a number is stored as a number, "=..." as a formula, anything else as text.
     * The workbook's calculation chain is dropped (Excel rebuilds it) because it can point at the cell that was changed.
     */
    public File setCell(File source, String sheetName, String cellRef, String value, File output) throws IOException {
        if (sheetName == null || sheetName.isEmpty()) throw new IllegalArgumentException("sheet name is required");
        if (cellRef == null || !cellRef.matches("[A-Za-z]{1,3}[0-9]{1,7}")) throw new IllegalArgumentException("cellRef must look like A1");
        final String ref = cellRef.toUpperCase(java.util.Locale.ROOT);
        if (colIndex(ref) > 16384 || rowOf(ref) < 1 || rowOf(ref) > 1048576) throw new IllegalArgumentException("cellRef is outside the Excel sheet limits");
        final String part;
        final boolean calcChain;
        ZipFile base = new ZipFile(source);
        try {
            Map<String,String> rels = workbookRelationships(base);
            String wb = OoxmlUtil.readZipText(base, "xl/workbook.xml");
            String target = null;
            Matcher sm = Pattern.compile("<sheet\\b([^>]*)/>").matcher(wb);
            while (sm.find()) {
                String attrs = sm.group(1);
                if (sheetName.equals(OoxmlUtil.attr(attrs, "name"))) { target = rels.get(OoxmlUtil.attr(attrs, "r:id")); break; }
            }
            if (target == null) throw new IOException("sheet not found: " + sheetName);
            part = resolveWorkbookTarget(target);
            if (base.getEntry(part) == null) throw new IOException("sheet data is missing from the file: " + sheetName);
            calcChain = base.getEntry("xl/calcChain.xml") != null;
        } finally {
            try { base.close(); } catch (IOException ignored) {}
        }
        final String newValue = value == null ? "" : value;
        OoxmlUtil.rewriteZip(source, output, (name, entry, zip) -> {
            if (calcChain) {
                if ("xl/calcChain.xml".equals(name)) return OoxmlUtil.SKIP;
                if ("[Content_Types].xml".equals(name))
                    return OoxmlUtil.readZipText(zip, name).replaceAll("<Override\\b[^>]*calcChain[^>]*/>", "").getBytes(StandardCharsets.UTF_8);
                if ("xl/_rels/workbook.xml.rels".equals(name))
                    return OoxmlUtil.readZipText(zip, name).replaceAll("<Relationship\\b[^>]*calcChain[^>]*/>", "").getBytes(StandardCharsets.UTF_8);
            }
            if (!part.equals(name)) return null;
            return setCellXml(OoxmlUtil.readZipText(zip, name), ref, newValue).getBytes(StandardCharsets.UTF_8);
        });
        return output;
    }

    private static String setCellXml(String xml, String ref, String value) throws IOException {
        final int rowTarget = rowOf(ref), colTarget = colIndex(ref);
        Matcher sd = Pattern.compile("<sheetData\\b[^>]*?(?:/>|>)").matcher(xml);
        if (!sd.find()) throw new IOException("worksheet has no sheetData");
        String newRow = "<row r=\"" + rowTarget + "\">" + cellXml(ref, "", value) + "</row>";
        if (sd.group().endsWith("/>")) {                                    // <sheetData/> : an empty sheet
            String open = sd.group().substring(0, sd.group().length() - 2);
            return xml.substring(0, sd.start()) + open + ">" + newRow + "</sheetData>" + xml.substring(sd.end());
        }
        int close = xml.indexOf("</sheetData>", sd.end());
        if (close < 0) throw new IOException("worksheet has no sheetData");
        String data = xml.substring(sd.end(), close);

        StringBuilder out = new StringBuilder();
        Matcher rm = ROW.matcher(data);
        int last = 0, implicitRow = 0;
        boolean done = false;
        while (rm.find()) {
            String attrs = rm.group(1);
            String rAttr = OoxmlUtil.attr(attrs, "r");
            int rowNum = rAttr.isEmpty() ? implicitRow + 1 : parseInt(rAttr);
            implicitRow = rowNum;
            if (rowNum < rowTarget) continue;
            out.append(data, last, rm.start());
            if (rowNum == rowTarget) {
                String keep = attrs.replaceAll("\\s+spans\\s*=\\s*\"[^\"]*\"", "");   // the span hint may no longer fit
                out.append("<row").append(keep).append('>').append(setCellInRow(rm.group(2) == null ? "" : rm.group(2), ref, colTarget, value)).append("</row>");
                last = rm.end();
            } else {                                                        // first later row: the new row goes in front of it
                out.append(newRow);
                last = rm.start();
            }
            done = true;
            break;
        }
        if (done) out.append(data, last, data.length());
        else out.append(data).append(newRow);
        return xml.substring(0, sd.end()) + out + xml.substring(close);
    }

    private static String setCellInRow(String body, String ref, int colTarget, String value) throws IOException {
        Matcher cm = CELL.matcher(body);
        StringBuilder out = new StringBuilder();
        int last = 0, implicitCol = 0;
        boolean placed = false;
        while (cm.find()) {
            String attrs = cm.group(1);
            String cr = OoxmlUtil.attr(attrs, "r");
            int col = cr.isEmpty() ? implicitCol + 1 : colIndex(cr);
            implicitCol = col;
            if (col < colTarget) continue;
            out.append(body, last, cm.start());
            if (col == colTarget) {
                String inner = cm.group(2) == null ? "" : cm.group(2);
                Matcher f = Pattern.compile("<f\\b([^>]*?)(?:/>|>)").matcher(inner);
                if (f.find() && (f.group(1).contains("t=\"shared\"") || f.group(1).contains("t=\"array\"")) && f.group(1).contains("ref="))
                    throw new IOException("cell " + ref + " holds a shared/array formula that other cells depend on; editing it is not supported");
                Matcher st = Pattern.compile("\\bs\\s*=\\s*\"([^\"]+)\"").matcher(attrs);
                out.append(cellXml(ref, st.find() ? " s=\"" + st.group(1) + "\"" : "", value));
                last = cm.end();
            } else {                                                        // first later cell: new cell goes in front of it
                out.append(cellXml(ref, "", value));
                last = cm.start();
            }
            placed = true;
            break;
        }
        if (placed) out.append(body, last, body.length());
        else out.append(body).append(cellXml(ref, "", value));
        return out.toString();
    }

    private static String cellXml(String ref, String styleAttr, String v) {
        if (v.startsWith("=") && v.length() > 1)
            return "<c r=\"" + ref + "\"" + styleAttr + "><f>" + OoxmlUtil.xmlEscape(v.substring(1)) + "</f></c>";
        if (isPlainNumber(v))
            return "<c r=\"" + ref + "\"" + styleAttr + "><v>" + v + "</v></c>";
        return "<c r=\"" + ref + "\"" + styleAttr + " t=\"inlineStr\"><is><t xml:space=\"preserve\">" + OoxmlUtil.xmlEscape(v) + "</t></is></c>";
    }

    /** A real number only: no leading zeros (007, phone numbers) and at most 15 digits (Excel's precision). */
    private static boolean isPlainNumber(String v) {
        return PLAIN_NUMBER.matcher(v).matches() && v.replaceAll("[^0-9]", "").length() <= 15;
    }

    private static List<String> sharedStrings(ZipFile zip) throws IOException {
        String xml = OoxmlUtil.readZipText(zip, "xl/sharedStrings.xml");
        if (xml.isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<String>();
        // an empty <si/> still owns an index, so it must be kept or every later string shifts by one
        Matcher m = Pattern.compile("<si\\b[^>]*?(?:/>|>(.*?)</si>)", Pattern.DOTALL).matcher(xml);
        while (m.find()) out.add(richText(m.group(1)));
        return out;
    }

    /** Text of a string item: all <t> parts joined with nothing in between, phonetic (furigana) runs left out. */
    private static String richText(String xml) {
        if (xml == null || xml.isEmpty()) return "";
        String plain = xml.replaceAll("(?s)<rPh\\b.*?</rPh>", "");
        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("<t(?:\\s[^>]*?)?(?<!/)>(.*?)</t>", Pattern.DOTALL).matcher(plain);
        while (m.find()) sb.append(OoxmlUtil.xmlUnescape(m.group(1)));
        return sb.toString();
    }

    private static Map<String,String> workbookRelationships(ZipFile zip) throws IOException {
        Map<String,String> out = new LinkedHashMap<String,String>();
        String xml = OoxmlUtil.readZipText(zip, "xl/_rels/workbook.xml.rels");
        Matcher m = Pattern.compile("<Relationship\\b([^>]*)/>").matcher(xml);
        while (m.find()) {
            String a = m.group(1), id = OoxmlUtil.attr(a, "Id"), target = OoxmlUtil.attr(a, "Target");
            if (!id.isEmpty() && !target.isEmpty()) out.put(id, target);
        }
        return out;
    }

    private static String sheetText(String xml, List<String> shared) {
        StringBuilder out = new StringBuilder();
        Matcher rm = ROW.matcher(xml);
        int implicitRow = 0;
        while (rm.find()) {
            String rowAttrs = rm.group(1), row = rm.group(2) == null ? "" : rm.group(2);
            String rowRef = OoxmlUtil.attr(rowAttrs, "r");
            implicitRow = rowRef.isEmpty() ? implicitRow + 1 : parseInt(rowRef);
            StringBuilder line = new StringBuilder();
            Matcher cell = CELL.matcher(row);
            while (cell.find()) {
                String attrs = cell.group(1);
                String body = cell.group(2) == null ? "" : cell.group(2);
                if (body.isEmpty()) continue;                                // empty styled cell <c .../>: nothing to show
                String ref = OoxmlUtil.attr(attrs, "r");
                String type = OoxmlUtil.attr(attrs, "t");
                String formula = OoxmlUtil.xmlUnescape(firstRaw(body, "<f(?:\\s[^>]*?)?(?<!/)>(.*?)</f>"));
                String raw = firstRaw(body, "<v(?:\\s[^>]*?)?(?<!/)>(.*?)</v>");
                String value;
                if ("s".equals(type)) {
                    int idx = parseInt(raw.trim());
                    value = idx >= 0 && idx < shared.size() ? shared.get(idx) : OoxmlUtil.xmlUnescape(raw);
                } else if ("inlineStr".equals(type)) {
                    value = richText(firstRaw(body, "<is(?:\\s[^>]*?)?(?<!/)>(.*?)</is>"));
                } else value = OoxmlUtil.xmlUnescape(raw);
                if (!formula.isEmpty()) value = value + " [formula:=" + formula + "]";
                if (line.length() > 0) line.append(" | ");
                line.append(ref.isEmpty() ? "cell" : ref).append("=").append(value);
            }
            if (line.length() > 0) out.append("ROW ").append(rowRef.isEmpty() ? String.valueOf(implicitRow) : rowRef).append(": ").append(line).append('\n');
            if (out.length() >= MAX_CHARS) break;
        }
        return out.toString();
    }

    private static String readDelimited(File file, char sep) throws IOException {
        java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(file), StandardCharsets.UTF_8));
        try {
            StringBuilder out = new StringBuilder("TABLE: ").append(file.getName()).append('\n');
            String line; int row = 1;
            while ((line = r.readLine()) != null && out.length() < MAX_CHARS) {
                if (row == 1 && line.startsWith("\uFEFF")) line = line.substring(1);   // Excel's "CSV UTF-8" starts with a BOM
                out.append("ROW ").append(row++).append(": ").append(line).append('\n');
            }
            return trimLimit(out.toString(), MAX_CHARS);
        } finally { r.close(); }
    }

    private static String sheetXml(List<List<String>> rows) {
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
        int r = 1;
        for (List<String> row : rows) {
            b.append("<row r=\"").append(r).append("\">");
            int c = 0;
            if (row != null) for (String value : row) {
                c++;
                b.append(cellXml(colName(c) + r, "", value == null ? "" : value));
            }
            b.append("</row>"); r++;
        }
        if (rows.isEmpty()) b.append("<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t/></is></c></row>");
        return b.append("</sheetData></worksheet>").toString();
    }

    private static String resolveWorkbookTarget(String target) {
        if (target == null || target.isEmpty()) return "";
        if (target.startsWith("/")) return target.substring(1);
        if (target.startsWith("xl/")) return target;
        return "xl/" + target;
    }

    /** Excel sheet names: max 31 characters, none of \ / ? * : [ ], and no apostrophe at either end. */
    private static String safeSheetName(String name) {
        String n = name == null ? "" : name.trim().replaceAll("[\\\\/?*:\\[\\]]", "-");
        if (n.length() > 31) n = n.substring(0, 31);
        n = n.replaceAll("^'+|'+$", "").trim();
        return n.isEmpty() ? "Sheet1" : n;
    }

    private static int colIndex(String ref) {
        int n = 0;
        for (int i = 0; i < ref.length(); i++) {
            char c = Character.toUpperCase(ref.charAt(i));
            if (c < 'A' || c > 'Z') break;
            n = n * 26 + (c - 'A' + 1);
        }
        return n;
    }

    private static int rowOf(String ref) {
        return parseInt(ref.replaceAll("[A-Za-z]", ""));
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (RuntimeException e) { return -1; }
    }

    private static String colName(int n) { StringBuilder s = new StringBuilder(); while (n > 0) { int x = (n - 1) % 26; s.append((char)('A' + x)); n = (n - 1) / 26; } return s.reverse().toString(); }
    private static String workbookXml(String sheet) { return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"" + sheet + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets><calcPr fullCalcOnLoad=\"1\" forceFullCalc=\"1\"/></workbook>"; }
    private static String workbookRels() { return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>"; }
    private static String rootRels() { return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>"; }
    private static String contentTypes() { return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/></Types>"; }
    private static String stylesXml() { return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Arial\"/></font><font><b/><sz val=\"11\"/><name val=\"Arial\"/></font></fonts><fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills><borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles><dxfs count=\"0\"/><tableStyles count=\"0\" defaultTableStyle=\"TableStyleMedium2\" defaultPivotStyle=\"PivotStyleMedium9\"/></styleSheet>"; }
    private static void put(java.util.zip.ZipOutputStream z, String name, String value) throws IOException { z.putNextEntry(new java.util.zip.ZipEntry(name)); z.write(value.getBytes(StandardCharsets.UTF_8)); z.closeEntry(); }
    private static String firstRaw(String xml, String regex) { Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(xml == null ? "" : xml); return m.find() ? m.group(1) : ""; }
    private static String trimLimit(String s, int max) { return s.length() <= max ? s : s.substring(0, max) + "\n[content clipped]"; }
}
