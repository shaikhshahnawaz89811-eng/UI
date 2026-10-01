package tests;

import com.neonhud.app.core.engine.Attachment;
import com.neonhud.app.core.skill.SkillPlan;
import com.neonhud.app.core.skill.SkillRouter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Stage 4 layer 1: exact 5,000-row planner/router regression. */
final class Stage4QuestionSetTest {
    static final String DATA = "../phase5/stage4_5000.tsv";
    private static final List<String> STYLES = Arrays.asList("hinglish", "english", "spelling", "short", "long", "reversed", "nopunct", "followup");
    private static final Map<String, Integer> BLOCK_EXPECTED = new LinkedHashMap<String, Integer>();
    static {
        BLOCK_EXPECTED.put("read_image", 350); BLOCK_EXPECTED.put("read_pdf", 350); BLOCK_EXPECTED.put("read_audio", 300);
        BLOCK_EXPECTED.put("read_video", 300); BLOCK_EXPECTED.put("read_zip", 300); BLOCK_EXPECTED.put("read_docx", 300);
        BLOCK_EXPECTED.put("read_xlsx", 300); BLOCK_EXPECTED.put("read_pptx", 300);
        BLOCK_EXPECTED.put("create_pdf", 250); BLOCK_EXPECTED.put("create_docx", 250); BLOCK_EXPECTED.put("create_xlsx", 250); BLOCK_EXPECTED.put("create_pptx", 250);
        BLOCK_EXPECTED.put("edit_docx", 200); BLOCK_EXPECTED.put("edit_xlsx", 200); BLOCK_EXPECTED.put("edit_pptx", 200);
        BLOCK_EXPECTED.put("multi", 500); BLOCK_EXPECTED.put("negative", 400);
    }

    static void run() throws Exception {
        T.section("STAGE 4: exact 5,000 question router regression");
        List<Row> rows = load(new File(System.getProperty("stage4.questions", DATA)));
        T.eq(5000, rows.size(), "stage 4 corpus has exactly 5,000 rows");

        Set<String> commands = new HashSet<String>();
        Set<Integer> ids = new HashSet<Integer>();
        Map<String,Integer> blocks = new LinkedHashMap<String,Integer>();
        Map<String,Integer> styles = new LinkedHashMap<String,Integer>();
        Map<String,int[]> blockPass = new LinkedHashMap<String,int[]>();
        Map<String,int[]> stylePass = new LinkedHashMap<String,int[]>();
        int ok = 0;
        int mustNotMalformed = 0;
        List<String> misses = new ArrayList<String>();
        for (Row r : rows) {
            ids.add(r.id);
            if (!commands.add(r.command)) T.check(false, "duplicate command at row " + r.id);
            inc(blocks, r.block); inc(styles, r.style);
            int[] bp = pair(blockPass, r.block); bp[1]++;
            int[] sp = pair(stylePass, r.style); sp[1]++;
            T.check(!r.mustNot.trim().isEmpty(), "#" + r.id + " has must-not constraints");
            if (!r.mustNot.contains("NO_")) mustNotMalformed++;

            SkillPlan plan = SkillRouter.route(r.command, attachments(r.attachments));
            boolean pass = r.expected.equals(plan.signature());
            if (pass) { ok++; bp[0]++; sp[0]++; }
            else if (misses.size() < 80) misses.add("#" + r.id + " " + r.block + "/" + r.style + " | " + r.command + " | expected=" + r.expected + " got=" + plan.signature());
            T.check(pass, "#" + r.id + " route exact");
        }
        T.eq(5000, ids.size(), "all row IDs are unique");
        T.eq(5000, commands.size(), "all commands are unique");
        for (Map.Entry<String,Integer> e : BLOCK_EXPECTED.entrySet()) T.eq(e.getValue(), blocks.get(e.getKey()), e.getKey() + " row count");
        for (Map.Entry<String,int[]> e : blockPass.entrySet()) T.eq(e.getValue()[1], e.getValue()[0], e.getKey() + " exact routing");
        for (String style : STYLES) T.check(styles.containsKey(style) && styles.get(style) > 0, style + " is represented");
        T.eq(0, mustNotMalformed, "every row has explicit NO_* must-not constraints");
        T.check(ok >= 4900, "router gate >= 98% (actual " + ok + "/5000)");
        System.out.println("  stage4 router: " + ok + "/5000 = " + String.format(java.util.Locale.US, "%.2f%%", 100.0 * ok / 5000.0));
        System.out.println("  blocks=" + blocks);
        System.out.println("  styles=" + styles);
        for (String m : misses) System.out.println("  STAGE4 ROUTER MISS: " + m);
    }

    private static int[] pair(Map<String,int[]> m, String k) { int[] p=m.get(k); if(p==null){p=new int[2];m.put(k,p);} return p; }
    private static void inc(Map<String,Integer> m,String k){Integer n=m.get(k);m.put(k,n==null?1:n+1);}

    private static List<Attachment> attachments(String spec) {
        List<Attachment> out = new ArrayList<Attachment>();
        if (spec == null || spec.equals("-")) return out;
        for (String s : spec.split(",")) {
            Attachment.Kind kind = Attachment.Kind.valueOf(s);
            out.add(new Attachment(kind, "fixture." + s.toLowerCase(java.util.Locale.ROOT), 100, "file:/tmp/" + s.toLowerCase(java.util.Locale.ROOT)));
        }
        return out;
    }

    static List<Row> load(File file) throws Exception {
        if (!file.isFile()) throw new IllegalStateException("Missing stage4 corpus: " + file.getAbsolutePath());
        BufferedReader br = new BufferedReader(new FileReader(file));
        try {
            String h = br.readLine();
            if (!"id\tblock\tstyle\tattachments\tlast\tcommand\texpected\tmust_not".equals(h)) throw new IllegalArgumentException("bad stage4 header");
            List<Row> out = new ArrayList<Row>(); String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] c = line.split("\\t", -1);
                if (c.length != 8) throw new IllegalArgumentException("bad stage4 row: " + line);
                out.add(new Row(Integer.parseInt(c[0]), c[1], c[2], c[3], "1".equals(c[4]), c[5], c[6], c[7]));
            }
            return out;
        } finally { br.close(); }
    }

    static final class Row {
        final int id; final String block, style, attachments; final boolean last; final String command, expected, mustNot;
        Row(int id,String block,String style,String attachments,boolean last,String command,String expected,String mustNot){
            this.id=id;this.block=block;this.style=style;this.attachments=attachments;this.last=last;this.command=command;this.expected=expected;this.mustNot=mustNot;
        }
    }
}
