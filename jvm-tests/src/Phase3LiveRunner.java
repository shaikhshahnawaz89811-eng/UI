package tests;

import com.neonhud.app.core.web.KeySource;
import com.neonhud.app.core.web.PlanContext;
import com.neonhud.app.core.web.SearchPlan;
import com.neonhud.app.core.web.SearchPlanner;
import com.neonhud.app.core.web.WebMode;
import com.neonhud.app.core.web.WebSearchService;
import com.neonhud.app.core.web.WebTurn;
import com.neonhud.app.core.web.HttpWebApi;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Phase 3 live runner. Uses the same WebSearchService as the Android app, but reads Tavily keys only
 * from environment variables. It never prints a key or response body.
 *
 * Examples:
 *   TAVILY_API_KEY=... bash jvm-tests/run-phase3-live.sh --limit 25
 *   TAVILY_API_KEYS='key1,key2' bash jvm-tests/run-phase3-live.sh --category url_read --limit 20
 *   TAVILY_API_KEY=... bash jvm-tests/run-phase3-live.sh --all --output phase3/live-results.tsv
 */
final class Phase3LiveRunner {
    private static final String DEFAULT_DATA = "../phase3/questions.tsv";
    private static final String DEFAULT_OUTPUT = "phase3/live-results.tsv";

    public static void main(String[] args) throws Exception {
        Options opt = Options.parse(args);
        File data = new File(opt.data);
        if (!data.isFile()) throw new IllegalStateException("Missing phase 3 dataset: " + data.getAbsolutePath());
        List<Phase3QuestionSetTest.Row> rows = Phase3QuestionSetTest.load(data);

        List<String> selected = new ArrayList<String>();
        for (Phase3QuestionSetTest.Row r : rows) if (opt.category.isEmpty() || opt.category.equals(r.category)) selected.add(Integer.toString(r.id));
        int start = Math.max(0, opt.start);
        int end = opt.all ? selected.size() : Math.min(selected.size(), start + Math.max(1, opt.limit));
        if (start >= selected.size()) throw new IllegalArgumentException("--start is past the selected rows: " + start + " / " + selected.size());

        Map<Integer, Phase3QuestionSetTest.Row> byId = new LinkedHashMap<Integer, Phase3QuestionSetTest.Row>();
        for (Phase3QuestionSetTest.Row r : rows) byId.put(r.id, r);

        String keysEnv = System.getenv("TAVILY_API_KEYS");
        if (keysEnv == null || keysEnv.trim().isEmpty()) keysEnv = System.getenv("TAVILY_API_KEY");
        List<String> keys = splitKeys(keysEnv);
        if (!opt.dryRun && keys.isEmpty()) {
            throw new IllegalStateException("No Tavily key found. Set TAVILY_API_KEY or TAVILY_API_KEYS; keys are never written to output.");
        }

        final List<String> liveKeys = keys;
        WebSearchService service = new WebSearchService(new HttpWebApi(), new KeySource() {
            @Override public List<String> keys() { return liveKeys; }
        }, new WebSearchService.Settings() {
            @Override public WebMode mode() { return WebMode.AUTO; }
        }, new WebSearchService.Clock() {
            @Override public long now() { return System.currentTimeMillis(); }
        });

        PrintWriter out = null;
        if (!opt.dryRun) {
            File f = new File(opt.output);
            File parent = f.getParentFile(); if (parent != null) parent.mkdirs();
            out = new PrintWriter(new FileWriter(f, false));
            out.println("id\tcategory\tquestion\tplan_kind\tplanned_search\tquery\tproblem\tsearched\tkeys_tried\tlinks\tpics\tissues\tms");
        }

        Map<String,Integer> counts = new LinkedHashMap<String,Integer>();
        long totalMs = 0;
        int done = 0;
        int searched = 0;
        int skipped = 0;
        int success = 0;
        int failed = 0;

        try {
            for (int i = start; i < end; i++) {
                Phase3QuestionSetTest.Row r = byId.get(Integer.parseInt(selected.get(i)));
                long t0 = System.nanoTime();
                // Use the dataset context directly so OFF/ALWAYS rows are preserved; the app service can still execute the plan.
                SearchPlan plan = SearchPlanner.plan(r.question, r.context());
                WebTurn turn = opt.dryRun ? WebTurn.none(plan) : service.prepare(plan);
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                totalMs += ms;
                done++;

                if (!plan.search) skipped++;
                if (turn.searched) { searched++; if (turn.ok()) success++; else failed++; }
                String bucket = plan.search ? turn.problem.name() : "NO_SEARCH";
                inc(counts, bucket);

                if (out != null) {
                    out.println(r.id + "\t" + esc(r.category) + "\t" + esc(r.question) + "\t" + plan.kind
                            + "\t" + plan.search + "\t" + esc(plan.query) + "\t" + turn.problem
                            + "\t" + turn.searched + "\t" + turn.keysTried + "\t" + turn.links.size()
                            + "\t" + turn.pics.size() + "\t" + turn.issues.size() + "\t" + ms);
                    out.flush();
                }
                System.out.println(String.format(Locale.ROOT, "[%d/%d] #%d %s -> %s%s (%d ms)", done, end-start, r.id,
                        oneLine(r.question), plan.kind, plan.search ? " / " + turn.problem : " / NO_SEARCH", ms));
            }
        } finally {
            if (out != null) out.close();
        }

        double successRate = searched == 0 ? 0.0 : (100.0 * success / searched);
        System.out.println("\n== phase 3 live summary");
        System.out.println("rows: " + done + "   planned-search: " + (done - skipped) + "   calls-with-network: " + searched);
        System.out.println("success: " + success + "   failed: " + failed + "   success rate: " + String.format(Locale.ROOT, "%.1f%%", successRate));
        System.out.println("problems: " + counts);
        if (!opt.dryRun) System.out.println("results: " + new File(opt.output).getAbsolutePath());
        if (opt.dryRun) System.out.println("DRY RUN: no Tavily request was sent.");
        if (searched > 0) System.out.println("avg row time: " + String.format(Locale.ROOT, "%.1f ms", (double) totalMs / done));
    }

    private static List<String> splitKeys(String raw) {
        List<String> out = new ArrayList<String>();
        if (raw == null) return out;
        for (String part : raw.split("[,\\n\\r]+")) {
            String k = part.trim();
            if (!k.isEmpty() && !out.contains(k)) out.add(k);
        }
        return out;
    }

    private static String esc(String s) { return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '); }
    private static String oneLine(String s) { String x = esc(s); return x.length() > 90 ? x.substring(0, 87) + "..." : x; }
    private static void inc(Map<String,Integer> m, String k) { m.put(k, m.containsKey(k) ? m.get(k)+1 : 1); }

    private static final class Options {
        String data = DEFAULT_DATA, output = DEFAULT_OUTPUT, category = "";
        int limit = 10, start = 0; boolean all = false, dryRun = false;

        static Options parse(String[] args) {
            Options o = new Options();
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                if ("--all".equals(a)) o.all = true;
                else if ("--dry-run".equals(a)) o.dryRun = true;
                else if ("--limit".equals(a)) o.limit = Integer.parseInt(next(args, ++i, a));
                else if ("--start".equals(a)) o.start = Integer.parseInt(next(args, ++i, a));
                else if ("--category".equals(a)) o.category = next(args, ++i, a).trim();
                else if ("--data".equals(a)) o.data = next(args, ++i, a);
                else if ("--output".equals(a)) o.output = next(args, ++i, a);
                else if ("--help".equals(a) || "-h".equals(a)) { usage(); System.exit(0); }
                else throw new IllegalArgumentException("Unknown option: " + a);
            }
            return o;
        }
        private static String next(String[] a, int i, String flag) {
            if (i >= a.length) throw new IllegalArgumentException(flag + " needs a value");
            return a[i];
        }
        private static void usage() {
            System.out.println("Phase 3 Tavily live runner");
            System.out.println("  TAVILY_API_KEY=... bash jvm-tests/run-phase3-live.sh --limit 25");
            System.out.println("  TAVILY_API_KEYS='k1,k2' bash jvm-tests/run-phase3-live.sh --category url_read --limit 20");
            System.out.println("  bash jvm-tests/run-phase3-live.sh --dry-run --all");
            System.out.println("Options: --all | --limit N | --start N | --category NAME | --output FILE | --data FILE | --dry-run");
        }
    }
}
