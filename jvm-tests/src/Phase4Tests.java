package tests;

import com.neonhud.app.core.search.TavilyKeyManager;
import com.neonhud.app.core.search.TavilyKeyStore;
import com.neonhud.app.core.search.TavilySnapshot;
import com.neonhud.app.core.web.KeyPool;
import com.neonhud.app.core.web.PlanContext;
import com.neonhud.app.core.web.SearchPlan;
import com.neonhud.app.core.web.WebApi;
import com.neonhud.app.core.web.WebCache;
import com.neonhud.app.core.web.WebMode;
import com.neonhud.app.core.web.WebSearchService;
import com.neonhud.app.core.web.WebTurn;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Phase 4: credit-saving cache, page follow-ups, and visible key health. */
final class Phase4Tests {
    private static final String K = "tvly-phase4-abcdefghijklmnop";

    static void run() throws Exception {
        cacheUnit();
        searchCache();
        pageFollowUpCache();
        keyHealth();
    }

    private static void cacheUnit() {
        T.section("phase 4: cache ttl and bounds");
        WebCache c = new WebCache();
        WebApi.SearchRequest q = new WebApi.SearchRequest("taj mahal", 5, false, false, "general", "");
        WebApi.SearchResponse r = new WebApi.SearchResponse("ok", Collections.singletonList(new WebApi.Hit("Taj", "https://x.test/taj", "agra", .9)), Collections.<WebApi.Image>emptyList());
        c.putSearch(q, r, 1000);
        T.check(c.getSearch(q, 1000) != null, "search cache hit");
        T.check(c.getSearch(q, 1000 + WebCache.SEARCH_TTL_MS) == null, "search cache expires at ttl");
        c.putPage("https://example.com/a?utm_source=x", "page text", 1000);
        T.check(c.getPage("https://example.com/a", 1000) != null, "page cache normalizes tracking url");
        T.check(c.getPage("https://example.com/a", 1000 + WebCache.PAGE_TTL_MS) == null, "page cache expires at ttl");
    }

    private static void searchCache() {
        T.section("phase 4: repeated search spends no extra Tavily call");
        FakeWebApi api = new FakeWebApi(FakeWebApi.ok());
        KeySourceOne keys = new KeySourceOne(K);
        WebSearchService w = new WebSearchService(api, keys, new WebSearchService.Settings() { public WebMode mode() { return WebMode.AUTO; } }, new WebSearchService.Clock() { public long now() { return 1000; } });
        SearchPlan p = w.plan("aaj ka mausam Delhi ka kaisa hai", PlanContext.auto());
        WebTurn a = w.prepare(p);
        WebTurn b = w.prepare(p);
        T.check(a.ok() && b.ok(), "both cached search turns succeed");
        T.eq(1, api.calls.size(), "second identical search uses cache");
    }

    private static void pageFollowUpCache() {
        T.section("phase 4: pasted page can be re-read on follow-up without extract");
        FakeWebApi api = new FakeWebApi(FakeWebApi.ok());
        KeySourceOne keys = new KeySourceOne(K);
        WebSearchService w = new WebSearchService(api, keys, new WebSearchService.Settings() { public WebMode mode() { return WebMode.AUTO; } }, new WebSearchService.Clock() { public long now() { return 1000; } });
        SearchPlan first = w.plan("https://example.com/page", PlanContext.auto());
        WebTurn a = w.prepare(first);
        T.check(a.ok(), "first page read succeeds");
        int calls = api.calls.size();
        PlanContext ctx = new PlanContext(WebMode.AUTO, false, "", "https://example.com/page", Collections.singletonList("https://example.com/page"), 2026);
        SearchPlan follow = w.plan("isme aur kya hai?", ctx);
        T.check(follow.readsLinks(), "follow-up becomes cached page read");
        WebTurn b = w.prepare(follow);
        T.check(b.ok(), "cached page follow-up succeeds");
        T.eq(calls, api.calls.size(), "follow-up page does not call Tavily again");
        T.check(b.context.contains("PAGE"), "cached page content reaches the model");
        WebSearchService off = new WebSearchService(api, keys, new WebSearchService.Settings() { public WebMode mode() { return WebMode.OFF; } }, new WebSearchService.Clock() { public long now() { return 1000; } });
        SearchPlan offPlan = off.plan("isme aur kya hai?", ctx);
        T.check(!offPlan.search, "web OFF never bypassed by cache");
    }

    private static void keyHealth() {
        T.section("phase 4: key health");
        KeyPool pool = new KeyPool();
        List<String> keys = Arrays.asList(K, "tvly-phase4-second-key-123456");
        pool.limited(K, 1000);
        T.eq("limit", pool.health(K, 1001), "limited key health visible");
        T.check(pool.restMs(K, 1001) > 0, "limited key has remaining rest time");
        pool.invalid(keys.get(1), 1000);
        T.eq("rejected", pool.health(keys.get(1), 1001), "rejected key health visible");

        MemStore store = new MemStore();
        TavilyKeyManager m = new TavilyKeyManager(new TavilyClientFake(), store);
        store.saved.add(K);
        TavilyKeyManager restarted = new TavilyKeyManager(new TavilyClientFake(), store);
        TavilySnapshot s = restarted.snapshot(pool, 1001);
        T.eq("limit", s.entries.get(0).health, "settings snapshot exposes pool health");
        pool.good(K);
        T.eq("healthy", pool.health(K, 1001), "working key becomes healthy");
    }

    static final class KeySourceOne implements com.neonhud.app.core.web.KeySource {
        final List<String> k; KeySourceOne(String x) { k = Collections.singletonList(x); }
        public List<String> keys() { return k; }
    }
    static final class MemStore implements TavilyKeyStore { List<String> saved = new ArrayList<String>(); public List<String> load(){return new ArrayList<String>(saved);} public void save(List<String> k){saved=new ArrayList<String>(k);} }
    static final class TavilyClientFake implements com.neonhud.app.core.search.TavilyClient { public Outcome check(String k){return new Outcome(Kind.VALID, "");} }
}
