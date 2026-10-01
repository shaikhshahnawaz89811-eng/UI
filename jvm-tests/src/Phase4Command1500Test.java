package tests;

import com.neonhud.app.core.memory.ConversationBrain;
import com.neonhud.app.core.memory.InMemoryStore;
import com.neonhud.app.core.memory.TextTools;
import com.neonhud.app.core.module.ModuleManager;
import com.neonhud.app.core.web.PlanContext;
import com.neonhud.app.core.web.SearchPlan;
import com.neonhud.app.core.web.SearchPlanner;
import com.neonhud.app.core.web.WebMode;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.*;

/** Phase 4: 1,500 deliberately varied commands. Planner routing is checked for every row; a
 * conversation brain pass checks that all 1,500 commands are parsed without crashes and keeps
 * a bounded prompt. A smaller deterministic end-to-end web sample lives in the existing web tests. */
final class Phase4Command1500Test {
    static final String DATA = "../phase4/commands1500.tsv";

    static void run() throws Exception {
        T.section("PHASE 4: 1,500 command conversation + web routing corpus");
        List<Row> rows = load(new File(System.getProperty("phase4.commands", DATA)));
        T.eq(1500, rows.size(), "phase 4 corpus has exactly 1500 commands");

        Map<String,Integer> counts = new LinkedHashMap<String,Integer>();
        Map<String,Integer> searchCounts = new LinkedHashMap<String,Integer>();
        InMemoryStore store = new InMemoryStore();
        ConversationBrain brain = new ConversationBrain(store, new ConversationBrain.Clock() {
            long n = 1_000_000L;
            public long now() { return ++n; }
        });

        long started = System.nanoTime();
        int searchExpected = 0, searchActual = 0, flagMismatches = 0, parseOk = 0;
        for (Row r : rows) {
            inc(counts, r.category);
            PlanContext ctx = contextFor(r);
            SearchPlan plan = SearchPlanner.plan(r.command, ctx);
            if (r.search) searchExpected++;
            if (plan.search) searchActual++;
            if (plan.search) inc(searchCounts, r.category);

            boolean okSearch = plan.search == r.search;
            boolean okFlags = flags(plan).equals(norm(r.flags));
            if (!okFlags) flagMismatches++;
            T.check(okSearch, "#" + r.id + " search: " + r.command + " -> " + plan);
            T.check(okFlags, "#" + r.id + " flags: expected " + r.flags + " got " + flags(plan));

            TextTools.Parsed parsed = TextTools.parse(r.command);
            T.check(!parsed.tokens.isEmpty(), "#" + r.id + " parser produced tokens");
            String action = TextTools.actionOf(parsed);
            T.check(action != null && !action.isEmpty(), "#" + r.id + " action is non-empty");
            try {
                ConversationBrain.Turn turn = brain.beginTurn(r.command);
                if (turn == null || turn.prompt == null) throw new IllegalStateException("null turn/prompt");
                brain.finishTurn(turn, "Test reply for command " + r.id + ".", false);
                parseOk++;
                T.check(turn.prompt.flatten().length() < 9000, "#" + r.id + " prompt stays bounded");
            } catch (Throwable t) {
                T.check(false, "#" + r.id + " conversation handling did not crash: " + t);
            }
        }
        long ms = (System.nanoTime() - started) / 1_000_000L;
        T.eq(searchExpected, searchActual, "1500-command search count matches expected");
        T.eq(0, flagMismatches, "1500-command planner flags have no mismatches");
        T.eq(1500, parseOk, "all 1500 commands completed through ConversationBrain");
        T.check(ms < 20000, "1500 command corpus completes under 20 seconds");
        T.check(store.topics().size() <= 80, "conversation topic count remains bounded (" + store.topics().size() + ")");
        System.out.println("  categories=" + counts);
        System.out.println("  searchByCategory=" + searchCounts);
        System.out.println("  expectedSearch=" + searchExpected + ", actualSearch=" + searchActual + ", parseOk=" + parseOk + ", time=" + ms + " ms");
    }

    static PlanContext contextFor(Row r) {
        PlanContext c = PlanContext.auto();
        if ("attachment".equals(r.category)) c = c.withAttachments(true);
        if ("followup".equals(r.category)) {
            String subject = r.id % 2 == 0 ? "Taj Mahal" : "Python";
            c = c.withPrev(subject + " latest information", subject + " ke baare mein latest info");
        }
        return c;
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
        Collections.sort(f); return join(f);
    }
    static String join(List<String> f) { StringBuilder b=new StringBuilder(); for(String x:f){if(b.length()>0)b.append(' ');b.append(x);} return b.toString(); }
    static void inc(Map<String,Integer> m,String k){Integer n=m.get(k);m.put(k,n==null?1:n+1);}

    static List<Row> load(File file) throws Exception {
        if (!file.isFile()) throw new IllegalStateException("Missing phase 4 dataset: " + file.getAbsolutePath());
        List<Row> out=new ArrayList<Row>(); BufferedReader br=new BufferedReader(new FileReader(file));
        try {
            String h=br.readLine(); if(!"id\tcategory\tcommand\tsearch\tflags".equals(h)) throw new IllegalArgumentException("bad header");
            String line; while((line=br.readLine())!=null){ if(line.trim().isEmpty())continue; String[] c=line.split("\\t",-1); if(c.length!=5)throw new IllegalArgumentException("bad row: "+line); out.add(new Row(Integer.parseInt(c[0]),c[1],c[2],"1".equals(c[3]),c[4])); }
        } finally { br.close(); }
        return out;
    }
    static final class Row { final int id; final String category,command,flags; final boolean search; Row(int i,String c,String q,boolean s,String f){id=i;category=c;command=q;search=s;flags=f;} }
}
