package com.neonhud.app.core.office;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/** Lightweight PPTX read/create/edit skill. */
public final class PptxSkill {
    private static final int MAX_CHARS = 200_000;

    public String read(File file) throws IOException { return read(file, 0, 0); }

    /** Reads the whole deck or an inclusive slide range when startSlide/endSlide are positive. */
    public String read(File file, int startSlide, int endSlide) throws IOException {
        ZipFile zip = new ZipFile(file);
        try {
            List<String> slideParts = new ArrayList<String>();
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (n.matches("ppt/slides/slide[0-9]+\\.xml")) slideParts.add(n);
            }
            slideParts.sort((a,b) -> Integer.compare(slideNo(a), slideNo(b)));
            if (slideParts.isEmpty()) throw new IOException("PPTX has no readable slides");
            if (startSlide > 0) {
                if (startSlide > slideNo(slideParts.get(slideParts.size() - 1))) throw new IOException("PPTX slide " + startSlide + " does not exist");
                int end = endSlide > 0 ? endSlide : startSlide;
                if (end < startSlide) throw new IOException("invalid slide range");
                List<String> selected = new ArrayList<String>();
                for (String part : slideParts) { int n = slideNo(part); if (n >= startSlide && n <= end) selected.add(part); }
                slideParts = selected;
                if (slideParts.isEmpty()) throw new IOException("PPTX slides " + startSlide + "-" + end + " do not exist");
            }
            StringBuilder out = new StringBuilder("PPTX: ").append(file.getName()).append('\n');
            for (String part : slideParts) {
                String xml = OoxmlUtil.readZipText(zip, part);
                out.append("SLIDE ").append(slideNo(part)).append(": ");
                Matcher pm = Pattern.compile("<a:p(?:\\s[^>]*?)?(?<!/)>(.*?)</a:p>", Pattern.DOTALL).matcher(xml);
                boolean first = true;
                while (pm.find()) {
                    String para = paragraphText(pm.group(1));
                    if (para.isEmpty()) continue;
                    if (!first) out.append(" | ");
                    first = false;
                    out.append(para);
                }
                out.append('\n');
                if (out.length() >= MAX_CHARS) break;
            }
            return trimLimit(out.toString(), MAX_CHARS);
        } finally { zip.close(); }
    }

    public File create(List<Slide> slides, File output) throws IOException {
        List<Slide> safe = slides == null ? new ArrayList<Slide>() : slides;
        if (safe.isEmpty()) safe.add(new Slide("MJ Presentation", java.util.Collections.singletonList("Empty presentation")));
        File parent = output.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) throw new IOException("cannot create output directory");
        OutputStream out = new FileOutputStream(output);
        try {
            java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(out);
            put(z, "[Content_Types].xml", contentTypes(safe.size()));
            put(z, "_rels/.rels", rootRels());
            put(z, "ppt/presentation.xml", presentationXml(safe.size()));
            put(z, "ppt/_rels/presentation.xml.rels", presentationRels(safe.size()));
            put(z, "ppt/theme/theme1.xml", themeXml());
            put(z, "ppt/slideMasters/slideMaster1.xml", slideMasterXml());
            put(z, "ppt/slideMasters/_rels/slideMaster1.xml.rels", slideMasterRels());
            put(z, "ppt/slideLayouts/slideLayout1.xml", slideLayoutXml());
            put(z, "ppt/slideLayouts/_rels/slideLayout1.xml.rels", slideLayoutRels());
            for (int i = 0; i < safe.size(); i++) {
                int n = i + 1;
                put(z, "ppt/slides/slide" + n + ".xml", slideXml(safe.get(i), n));
                put(z, "ppt/slides/_rels/slide" + n + ".xml.rels", slideRels());
            }
            z.close();
            return output;
        } finally { try { out.close(); } catch (IOException ignored) {} }
    }

    public File replaceText(File source, String oldText, String newText, File output) throws IOException {
        if (oldText == null || oldText.isEmpty()) throw new IllegalArgumentException("old text is required");
        final String replacement = newText == null ? "" : newText;
        final int[] hits = {0};
        OoxmlUtil.rewriteZip(source, output, (name, entry, zip) -> {
            if (!name.matches("ppt/slides/slide[0-9]+\\.xml")) return null;
            String xml = OoxmlUtil.readZipText(zip, name);
            return OoxmlUtil.replaceInTextTags(xml, "a:t", oldText, replacement, false, hits).getBytes(StandardCharsets.UTF_8);
        });
        if (hits[0] == 0) {
            //noinspection ResultOfMethodCallIgnored
            output.delete();
            throw new IOException("text was not found inside a single PowerPoint text run (it may be split across formatting runs)");
        }
        return output;
    }

    /** Text of one slide paragraph: its runs joined in order (a sentence split into several runs stays one sentence). */
    private static String paragraphText(String paragraphXml) {
        StringBuilder out = new StringBuilder();
        Matcher m = Pattern.compile("<a:t(?:\\s[^>]*?)?(?<!/)>(.*?)</a:t>|<a:br\\b[^>]*/>", Pattern.DOTALL).matcher(paragraphXml == null ? "" : paragraphXml);
        while (m.find()) out.append(m.group(1) != null ? OoxmlUtil.xmlUnescape(m.group(1)) : " ");
        return out.toString().trim();
    }

    public static final class Slide {
        public final String title;
        public final List<String> bullets;
        public Slide(String title, List<String> bullets) {
            this.title = title == null ? "" : title;
            this.bullets = bullets == null ? new ArrayList<String>() : new ArrayList<String>(bullets);
        }
    }

    private static int slideNo(String s) { Matcher m = Pattern.compile("slide(\\d+)\\.xml$").matcher(s); return m.find() ? Integer.parseInt(m.group(1)) : 0; }
    private static void put(java.util.zip.ZipOutputStream z, String name, String value) throws IOException { z.putNextEntry(new java.util.zip.ZipEntry(name)); z.write(value.getBytes(StandardCharsets.UTF_8)); z.closeEntry(); }
    private static final String RPR_END = "<a:latin typeface=\"Arial\"/></a:rPr>";
    private static String slideXml(Slide s, int n) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><p:sld xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\"><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/><p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Title\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"800000\" y=\"400000\"/><a:ext cx=\"7544000\" cy=\"900000\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr lang=\"en-US\" sz=\"2800\" b=\"1\">" + RPR_END + "<a:t>" + OoxmlUtil.xmlEscape(s.title) + "</a:t></a:r></a:p></p:txBody></p:sp>" + bodyShape(s.bullets) + "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>";
    }
    private static String bodyShape(List<String> bullets) {
        StringBuilder paras = new StringBuilder();
        for (String b : bullets) {
            String t = b == null ? "" : b;
            if (t.isEmpty()) paras.append("<a:p><a:endParaRPr lang=\"en-US\" sz=\"1800\"/></a:p>");
            else paras.append("<a:p><a:r><a:rPr lang=\"en-US\" sz=\"1800\">").append(RPR_END).append("<a:t>").append(OoxmlUtil.xmlEscape("\u2022 " + t)).append("</a:t></a:r></a:p>");
        }
        if (paras.length() == 0) paras.append("<a:p><a:endParaRPr lang=\"en-US\" sz=\"1800\"/></a:p>");
        // 9144000 x 5143500 EMU slide: the box ends at 4.9M so long text stays inside the slide
        return "<p:sp><p:nvSpPr><p:cNvPr id=\"3\" name=\"Body\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"1000000\" y=\"1500000\"/><a:ext cx=\"7144000\" cy=\"3400000\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr><p:txBody><a:bodyPr><a:normAutofit/></a:bodyPr><a:lstStyle/>" + paras + "</p:txBody></p:sp>";
    }
    private static String presentationXml(int n) { StringBuilder s = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><p:presentation xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\"><p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst><p:sldIdLst>"); for(int i=1;i<=n;i++) s.append("<p:sldId id=\"").append(255+i).append("\" r:id=\"rId").append(i+1).append("\"/>"); return s.append("</p:sldIdLst><p:sldSz cx=\"9144000\" cy=\"5143500\" type=\"screen16x9\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>").toString(); }
    private static String presentationRels(int n) { StringBuilder s = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>"); for(int i=1;i<=n;i++) s.append("<Relationship Id=\"rId").append(i+1).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide").append(i).append(".xml\"/>"); return s.append("</Relationships>").toString(); }
    private static String slideRels(){ return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/></Relationships>"; }
    private static String slideMasterRels(){ return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" Target=\"../theme/theme1.xml\"/></Relationships>"; }
    private static String slideLayoutRels(){ return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"../slideMasters/slideMaster1.xml\"/></Relationships>"; }
    private static String slideLayoutXml(){ return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><p:sldLayout xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" type=\"blank\"><p:cSld name=\"Blank\"><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/></p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"; }
    private static String slideMasterXml(){ return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><p:sldMaster xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\"><p:cSld name=\"Office Master\"><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/></p:spTree></p:cSld><p:clrMap accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" hlink=\"hlink\" folHlink=\"folHlink\"/><p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst><p:txStyles><p:titleStyle/><p:bodyStyle/><p:otherStyle/></p:txStyles></p:sldMaster>"; }
    private static String themeXml(){ return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><a:theme xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" name=\"Office Theme\"><a:themeElements><a:clrScheme name=\"Office\"><a:dk1><a:srgbClr val=\"000000\"/></a:dk1><a:lt1><a:srgbClr val=\"FFFFFF\"/></a:lt1><a:dk2><a:srgbClr val=\"1F1F1F\"/></a:dk2><a:lt2><a:srgbClr val=\"F2F2F2\"/></a:lt2><a:accent1><a:srgbClr val=\"4472C4\"/></a:accent1><a:accent2><a:srgbClr val=\"ED7D31\"/></a:accent2><a:accent3><a:srgbClr val=\"A5A5A5\"/></a:accent3><a:accent4><a:srgbClr val=\"FFC000\"/></a:accent4><a:accent5><a:srgbClr val=\"5B9BD5\"/></a:accent5><a:accent6><a:srgbClr val=\"70AD47\"/></a:accent6><a:hlink><a:srgbClr val=\"0563C1\"/></a:hlink><a:folHlink><a:srgbClr val=\"954F72\"/></a:folHlink></a:clrScheme><a:fontScheme name=\"Office\"><a:majorFont><a:latin typeface=\"Arial\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont><a:minorFont><a:latin typeface=\"Arial\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont></a:fontScheme><a:fmtScheme name=\"Office\"><a:fillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:fillStyleLst><a:lnStyleLst><a:ln w=\"6350\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln><a:ln w=\"12700\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln><a:ln w=\"19050\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln></a:lnStyleLst><a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst><a:bgFillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:bgFillStyleLst></a:fmtScheme></a:themeElements></a:theme>"; }
    private static String rootRels(){ return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"ppt/presentation.xml\"/></Relationships>"; }
    private static String contentTypes(int n){ StringBuilder b=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/><Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/><Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/><Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>"); for(int i=1;i<=n;i++) b.append("<Override PartName=\"/ppt/slides/slide").append(i).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>"); return b.append("</Types>").toString(); }
    private static String trimLimit(String s,int max){return s.length()<=max?s:s.substring(0,max)+"\n[content clipped]";}
}
