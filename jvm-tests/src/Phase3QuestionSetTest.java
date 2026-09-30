package tests;

import com.neonhud.app.core.web.PlanContext;
import com.neonhud.app.core.web.SearchPlan;
import com.neonhud.app.core.web.SearchPlanner;
import com.neonhud.app.core.web.WebMode;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Phase 3: deterministic 1000-question web-planner corpus.
 * The corpus is deliberately external (phase3/questions.tsv) so it can also be fed to the live runner.
 */
final class Phase3QuestionSetTest {
    private static final String DATA = "../phase3/questions.tsv";

    static void run() throws Exception {
        T.section("phase 3: 1000-question web-planner corpus");
        File file = new File(System.getProperty("phase3.questions", DATA));
        if (!file.isFile()) throw new IllegalStateException("Missing phase 3 dataset: " + file.getAbsolutePath());

        List<Row> rows = load(file);
        T.eq(1000, rows.size(), "phase 3 dataset has exactly 1000 questions");

        Map<String, Integer> categoryCounts = new LinkedHashMap<String, Integer>();
        Map<String, Integer> mismatches = new LinkedHashMap<String, Integer>();
        int searchExpected = 0, searchActual = 0, flagMismatch = 0;
        long t0 = System.nanoTime();

        for (Row r : rows) {
            PlanContext ctx = r.context();
            SearchPlan p = SearchPlanner.plan(r.question, ctx);
            inc(categoryCounts, r.category);
            if (r.search) searchExpected++;
            if (p.search) searchActual++;

            boolean okSearch = p.search == r.search;
            boolean okFlags = flags(p).equals(norm(r.flags));
            if (!okSearch || !okFlags) {
                flagMismatch += okFlags ? 0 : 1;
                String key = r.category + (okSearch ? ":flags" : ":search");
                inc(mismatches, key);
                System.out.println("  PHASE3 MISS #" + r.id + " [" + r.category + "] expected search=" + r.search
                        + " flags=" + norm(r.flags) + " got=" + p);
            }
            T.check(okSearch, "#" + r.id + " search decision: " + r.question);
            T.check(okFlags, "#" + r.id + " flags: " + r.question);
        }

        long ms = (System.nanoTime() - t0) / 1_000_000L;
        T.eq(searchExpected, searchActual, "dataset-wide search count matches expected");
        T.eq(0, mismatches.values().stream().mapToInt(Integer::intValue).sum(), "1000-question planner corpus has no mismatches");
        T.check(ms < 15000, "1000-question planner corpus completes in under 15s");
        System.out.println("  categories=" + categoryCounts);
        System.out.println("  expected searches=" + searchExpected + ", actual searches=" + searchActual + ", flag mismatches=" + flagMismatch + ", time=" + ms + " ms");
    }

    static List<Row> load(File file) throws Exception {
        List<Row> out = new ArrayList<Row>();
        BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
        try {
            String line = br.readLine();
            if (line == null || !line.startsWith("id\tcategory\tquestion\tsearch\tflags\tmode"))
                throw new IllegalArgumentException("Bad phase 3 TSV header");
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] c = line.split("\\t", -1);
                if (c.length != 9) throw new IllegalArgumentException("Bad row columns: " + line);
                out.add(new Row(Integer.parseInt(c[0]), c[1], c[2], "1".equals(c[3]), c[4], c[5],
                        Integer.parseInt(c[6]), c[7], c[8]));
            }
        } finally { br.close(); }
        return out;
    }

    static String flags(SearchPlan p) {
        List<String> f = new ArrayList<String>();
        if (p.showImages) f.add("img");
        if (p.readImages) f.add("readimg");
        if (p.links == SearchPlan.Links.ONE) f.add("link1");
        if (p.links == SearchPlan.Links.MANY) f.add("linkN");
        if (p.steps) f.add("steps");
        if (p.compare) f.add("cmp");
        if (p.readOnly) f.add("ro");
        Collections.sort(f);
        return join(f);
    }

    static String norm(String s) {
        if (s == null || s.trim().isEmpty()) return "";
        List<String> f = new ArrayList<String>(Arrays.asList(s.trim().split("\\s+")));
        Collections.sort(f);
        return join(f);
    }

    private static String join(List<String> f) {
        StringBuilder b = new StringBuilder();
        for (String s : f) { if (b.length() > 0) b.append(' '); b.append(s); }
        return b.toString();
    }

    static final class Row {
        final int id; final String category, question; final boolean search; final String flags, mode;
        final int attachments; final String prevQuery, prevText;
        Row(int id, String category, String question, boolean search, String flags, String mode, int attachments, String prevQuery, String prevText) {
            this.id=id; this.category=category; this.question=question; this.search=search; this.flags=flags; this.mode=mode;
            this.attachments=attachments; this.prevQuery=prevQuery; this.prevText=prevText;
        }
        PlanContext context() {
            WebMode m = WebMode.valueOf(mode.toUpperCase(Locale.ROOT));
            return new PlanContext(m, attachments != 0, prevQuery, prevText, 2026);
        }
    }

    private static void inc(Map<String,Integer> m, String k) { m.put(k, m.containsKey(k) ? m.get(k)+1 : 1); }
}
