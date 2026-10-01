package com.neonhud.app.core.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole web step of one message: plan -> pick a key -> search (next key on limit / rejection, simpler retry on a bad
 * request, one wider retry on an empty answer) -> clean -> choose links and pictures -> build the model context.
 * Never throws: every failure becomes a {@link WebTurn.Problem} with a short message the user can act on.
 */
public final class WebSearchService {

    public interface Clock { long now(); }

    /** The Settings value, read on every message so a change applies at once. */
    public interface Settings { WebMode mode(); }

    public static final long BUDGET_MS = 28000;

    private final WebApi api;
    private final KeySource keys;
    private final KeyPool pool = new KeyPool();
    private final Clock clock;
    private final Settings settings;
    /** Phase 4: short-lived in-memory cache to avoid repeat Tavily calls and enable page follow-ups. */
    private final WebCache cache = new WebCache();
    /** Phase 2: downloads pictures / PDFs for the vision model. Without it links to pictures / PDFs and "read the picture" are not read. */
    private volatile MediaReader media;

    /** Links read per message; more are ignored (each page costs a credit share and phone-model room). */
    public static final int MAX_LINKS = 3;
    /** Web pictures given to the vision model per message. */
    public static final int MAX_READ_PICS = 2;
    /** Pictures + PDF pages the vision model gets for one message, all together. */
    public static final int MAX_VISION_IMAGES = 4;

    public WebSearchService(WebApi api, KeySource keys, Settings settings, Clock clock) {
        this.api = api; this.keys = keys; this.settings = settings; this.clock = clock;
    }

    public KeyPool pool() { return pool; }

    public WebCache cache() { return cache; }

    /** Plugs in picture / PDF downloading (the Android app does this once at start). */
    public void setMedia(MediaReader m) { this.media = m; }

    /** Plans with the current Settings mode (read now, so a change applies to the very next message). */
    public SearchPlan plan(String userText, PlanContext ctx) {
        WebMode m;
        try { m = settings.mode(); } catch (RuntimeException e) { m = WebMode.AUTO; }
        return SearchPlanner.plan(userText, ctx.withMode(m));
    }

    /** Plans with the current Settings mode; see {@link #prepare(SearchPlan)}. */
    public WebTurn prepare(String userText, PlanContext ctx) {
        return prepare(plan(userText, ctx));
    }

    public WebTurn prepare(SearchPlan plan) {
        if (!plan.search) {
            if (plan.reason.startsWith("web search is OFF") && plan.explicit)
                return fail(plan, WebTurn.Problem.OFF, 0);
            if (plan.reason.startsWith("web search is OFF"))
                return fail(plan, WebTurn.Problem.OFF, 0);
            return WebTurn.none(plan);
        }
        if (plan.readsLinks()) return readLinks(plan);
        return searchTurn(plan);
    }

    private WebTurn searchTurn(SearchPlan plan) {
        List<String> all = keys.keys();
        if (all == null || all.isEmpty()) return fail(plan, WebTurn.Problem.NO_KEY, 0);

        long start = clock.now();
        WebApi.SearchRequest req = plan.request(false);
        // Image results stay live: a follow-up can legitimately want a fresh set of pictures.
        WebCache.SearchValue cached = plan.wantsImages() ? null : cache.getSearch(req, start);
        if (cached != null) return buildSearchTurn(plan, cached.response, 0, start);

        Outcome<WebApi.SearchResponse> o = run(all, searchOp(req), start);
        if (o.problem == WebTurn.Problem.NONE && o.response.hits.isEmpty()) {
            // one wider try: fewer words, advanced depth, no topic / time filter
            WebApi.SearchRequest wider = req.plain().withQuery(simplify(req.query)).withAdvanced();
            Outcome<WebApi.SearchResponse> w = run(all, searchOp(wider), start);
            if (w.problem == WebTurn.Problem.NONE && !w.response.hits.isEmpty()) o = w;
            else if (w.problem == WebTurn.Problem.NONE) o = new Outcome<WebApi.SearchResponse>(WebTurn.Problem.EMPTY, null, o.tried + w.tried);
            else o = new Outcome<WebApi.SearchResponse>(w.problem, null, o.tried + w.tried);
        }
        if (o.problem != WebTurn.Problem.NONE) return fail(plan, o.problem, o.tried);

        if (!plan.wantsImages()) cache.putSearch(req, o.response, start);
        return buildSearchTurn(plan, o.response, o.tried, start);
    }

    private WebTurn buildSearchTurn(SearchPlan plan, WebApi.SearchResponse r, int tried, long start) {
        boolean deep = plan.steps || plan.kind == SearchPlan.Kind.PROCEDURE;
        boolean wantHome = looksLikeOfficialSite(plan);
        int linkCount = plan.links == SearchPlan.Links.NONE ? 0 : plan.links == SearchPlan.Links.ONE ? 1 : 3;
        List<WebLink> links = linkCount == 0 ? new ArrayList<WebLink>() : LinkPicker.pick(r.hits, plan.query, linkCount, wantHome, deep);
        List<WebPic> pics = plan.showImages ? ImagePicker.pick(r.images, plan.query, 4) : new ArrayList<WebPic>();
        List<WebPic> readPics = plan.readImages ? ImagePicker.pick(r.images, plan.query, 4) : pics;
        // Phase 2: "read the picture" = the vision model really looks at the best pictures (nothing is shown in the chat)
        List<WebMedia> seen = plan.readImages ? lookAtPictures(readPics, start) : new ArrayList<WebMedia>();
        ContextBuilder.Built b = ContextBuilder.build(plan, plan.query, r, links, plan.showImages ? pics : readPics, seen.size());
        return new WebTurn(plan, true, WebTurn.Problem.NONE, b.text, links, pics, "", plan.query, tried, b.injectionHits, seen, null);
    }

    /** Downloads up to {@link #MAX_READ_PICS} of the picked pictures for the vision model; one that fails is simply skipped. */
    private List<WebMedia> lookAtPictures(List<WebPic> pics, long start) {
        List<WebMedia> out = new ArrayList<WebMedia>();
        MediaReader m = media;
        if (m == null) return out;
        for (WebPic p : pics) {
            if (out.size() >= MAX_READ_PICS || clock.now() - start > BUDGET_MS) break;
            try { out.add(m.image(p.url, p.caption)); } catch (MediaReader.MediaException | RuntimeException ignored) { }
        }
        return out;
    }

    // ------------------------------------------------------------------ Phase 2: links the user pasted

    /**
     * Reads the links of one message. Pages and PDFs go to Tavily Extract (next key on limit / rejection, one deeper try for
     * what came back empty); pictures, and PDFs Tavily could not read, are downloaded directly for the vision model - that
     * needs no key at all. Whatever cannot be read is named, and the model is told not to make the page up.
     */
    private WebTurn readLinks(SearchPlan plan) {
        long start = clock.now();
        List<LinkIssue> issues = new ArrayList<LinkIssue>();
        List<String> urls = new ArrayList<String>();
        java.util.Set<String> seenKeys = new java.util.HashSet<String>();
        for (String raw : plan.urls) {
            String u = UrlTools.normalize(raw);
            if (!UrlTools.isSafe(u)) { issues.add(new LinkIssue(u, ReadIssue.UNSAFE)); continue; }
            if (!seenKeys.add(UrlTools.key(u))) continue;
            if (urls.size() >= MAX_LINKS) break;
            urls.add(UrlTools.clean(u));
        }
        if (urls.isEmpty()) return linksFailed(plan, issues, ReadIssue.UNSAFE, 0);

        List<WebMedia> media = new ArrayList<WebMedia>();
        List<PageReader.Digest> digests = new ArrayList<PageReader.Digest>();
        List<String> forExtract = new ArrayList<String>();
        for (String u : urls) {
            if (UrlTools.mediaKind(u) == UrlTools.Media.IMAGE) readPicture(u, media, issues);
            else forExtract.add(u);
        }

        int tried = 0;
        WebTurn.Problem extractProblem = WebTurn.Problem.NONE;
        Map<String, String> got = new LinkedHashMap<String, String>();        // url -> page text
        Map<String, ReadIssue> why = new LinkedHashMap<String, ReadIssue>();   // url -> why Tavily gave nothing

        // Phase 4: use fresh cached pages first. Only cache misses spend Tavily extract calls.
        List<String> missingForExtract = new ArrayList<String>();
        for (String u : forExtract) {
            WebCache.PageValue cached = cache.getPage(u, start);
            if (cached != null) got.put(u, cached.text);
            else missingForExtract.add(u);
        }
        if (!missingForExtract.isEmpty()) {
            List<String> all = keys.keys();
            if (all == null || all.isEmpty()) {
                extractProblem = WebTurn.Problem.NO_KEY;
            } else {
                Outcome<WebApi.ExtractResponse> o = run(all, extractOp(missingForExtract, false), start);
                tried += o.tried;
                if (o.problem != WebTurn.Problem.NONE) extractProblem = o.problem;
                else collect(o.response, missingForExtract, got, why);
                List<String> missing = missingOf(missingForExtract, got);
                if (extractProblem == WebTurn.Problem.NONE && !missing.isEmpty() && clock.now() - start < BUDGET_MS) {
                    Outcome<WebApi.ExtractResponse> d = run(all, extractOp(missing, true), start);     // one deeper try, only for those
                    tried += d.tried;
                    if (d.problem == WebTurn.Problem.NONE) collect(d.response, missing, got, why);
                    else for (String u : missing) why.put(u, ReadIssue.of(d.problem));
                }
            }
        }
        for (Map.Entry<String, String> e : got.entrySet()) cache.putPage(e.getKey(), e.getValue(), start);

        int injection = 0;
        int budget = ContextBuilder.pageBudget(got.size());
        for (Map.Entry<String, String> e : got.entrySet()) {
            PageReader.Digest d = PageReader.digest(e.getKey(), e.getValue(), plan.question, budget);
            injection += d.injectionHits;
            if (d.empty()) why.put(e.getKey(), ReadIssue.EMPTY); else digests.add(d);
        }

        // what Tavily could not give: a PDF or a picture behind a plain link is still read directly
        for (String u : forExtract) {
            boolean read = false;
            for (PageReader.Digest d : digests) if (d.url.equals(u)) read = true;
            if (read) continue;
            ReadIssue reason = extractProblem != WebTurn.Problem.NONE ? ReadIssue.of(extractProblem) : why.containsKey(u) ? why.get(u) : ReadIssue.EMPTY;
            if (extractProblem != WebTurn.Problem.NO_INTERNET && this.media != null && media.size() < MAX_VISION_IMAGES && clock.now() - start < BUDGET_MS) {
                try {
                    media.add(UrlTools.mediaKind(u) == UrlTools.Media.PDF ? this.media.pdf(u) : this.media.any(u));
                    continue;
                } catch (MediaReader.MediaException ex) {
                    // a plain web page is not a file: keep Tavily's reason; a real file problem (too big, locked pdf) is more useful
                    if (ex.issue == ReadIssue.TOO_BIG || UrlTools.mediaKind(u) == UrlTools.Media.PDF) reason = ex.issue == ReadIssue.NOT_SUPPORTED ? reason : ex.issue;
                } catch (RuntimeException ignored) { }
            }
            issues.add(new LinkIssue(u, reason));
        }
        trimMedia(media);

        if (digests.isEmpty() && media.isEmpty()) {
            if (extractProblem != WebTurn.Problem.NONE && issues.size() == forExtract.size() && onlyFromProblem(issues, extractProblem))
                return fail(plan, extractProblem, tried);
            return linksFailed(plan, issues, ReadIssue.EMPTY, tried);
        }
        int shown = 0;
        for (WebMedia m : media) shown += m.jpegs.size();
        ContextBuilder.BuiltPages b = ContextBuilder.pages(plan, digests, issues, shown);
        return new WebTurn(plan, true, WebTurn.Problem.NONE, b.text, null, null, linkNotice(issues), plan.query, tried, injection, media, issues);
    }

    private void readPicture(String url, List<WebMedia> media, List<LinkIssue> issues) {
        MediaReader m = this.media;
        if (m == null) { issues.add(new LinkIssue(url, ReadIssue.NOT_SUPPORTED)); return; }
        if (media.size() >= MAX_VISION_IMAGES) { return; }
        try { media.add(m.image(url, "")); }
        catch (MediaReader.MediaException e) { issues.add(new LinkIssue(url, e.issue)); }
        catch (RuntimeException e) { issues.add(new LinkIssue(url, ReadIssue.BLOCKED)); }
    }

    /** Keeps the total number of images for the model within {@link #MAX_VISION_IMAGES} (whole files, never half a PDF). */
    private static void trimMedia(List<WebMedia> media) {
        int total = 0;
        for (int i = 0; i < media.size(); i++) {
            total += media.get(i).jpegs.size();
            if (total > MAX_VISION_IMAGES && i > 0) { while (media.size() > i) media.remove(media.size() - 1); break; }
        }
    }

    private static boolean onlyFromProblem(List<LinkIssue> issues, WebTurn.Problem p) {
        ReadIssue r = ReadIssue.of(p);
        for (LinkIssue li : issues) if (li.issue != r) return false;
        return true;
    }

    private static void collect(WebApi.ExtractResponse r, List<String> asked, Map<String, String> got, Map<String, ReadIssue> why) {
        for (WebApi.Page p : r.pages) {
            String u = matchUrl(asked, p.url);
            if (u == null || p.content.trim().isEmpty()) { if (u != null) why.put(u, ReadIssue.EMPTY); continue; }
            got.put(u, p.content);
        }
        for (WebApi.FailedUrl f : r.failed) {
            String u = matchUrl(asked, f.url);
            if (u != null && !got.containsKey(u)) why.put(u, reasonOf(f.reason));
        }
    }

    /** Tavily may hand a url back slightly changed (trailing slash, tracking removed): match by the normal key. */
    private static String matchUrl(List<String> asked, String answered) {
        for (String a : asked) if (a.equals(answered)) return a;
        String k = UrlTools.key(answered);
        for (String a : asked) if (UrlTools.key(a).equals(k)) return a;
        return null;
    }

    private static List<String> missingOf(List<String> asked, Map<String, String> got) {
        List<String> m = new ArrayList<String>();
        for (String a : asked) if (!got.containsKey(a)) m.add(a);
        return m;
    }

    /** Tavily's own words for a failure, only used to tell "site refused" from "nothing to read". */
    private static ReadIssue reasonOf(String reason) {
        String r = reason == null ? "" : reason.toLowerCase(java.util.Locale.ROOT);
        if (r.contains("timeout") || r.contains("timed out")) return ReadIssue.TIMEOUT;
        if (r.contains("empty") || r.contains("no content")) return ReadIssue.EMPTY;
        return ReadIssue.BLOCKED;
    }

    private WebTurn linksFailed(SearchPlan plan, List<LinkIssue> issues, ReadIssue fallback, int tried) {
        String ctx = ContextBuilder.linkFailed(issues, fallback);
        return new WebTurn(plan, false, WebTurn.Problem.UNREADABLE, ctx, null, null, linkNotice(issues.isEmpty()
                ? java.util.Collections.singletonList(new LinkIssue("", fallback)) : issues), plan.query, tried, 0, null, issues);
    }

    /** One short line about the links that could not be read (up to two are named); "" when all were read. */
    static String linkNotice(List<LinkIssue> issues) {
        if (issues.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        if (issues.size() == 1) {
            LinkIssue li = issues.get(0);
            sb.append("Link padh nahi paya").append(li.domain.isEmpty() ? "" : " (" + li.domain + ")").append(": ").append(li.issue.hint).append('.');
        } else {
            sb.append(issues.size()).append(" links padh nahi paye: ");
            for (int i = 0; i < issues.size() && i < 2; i++) {
                LinkIssue li = issues.get(i);
                if (i > 0) sb.append(", ");
                sb.append(li.domain.isEmpty() ? "link" : li.domain).append(" (").append(li.issue.hint).append(')');
            }
            if (issues.size() > 2) sb.append(" ...");
            sb.append('.');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ one search with key failover

    private static final class Outcome<T> {
        final WebTurn.Problem problem;
        final T response;
        final int tried;
        Outcome(WebTurn.Problem p, T r, int tried) { this.problem = p; this.response = r; this.tried = tried; }
    }

    /** One call to the internet that can be retried with the next key. {@code attempt} 1 = the simpler retry after a bad request. */
    private abstract static class Op<T> {
        abstract T call(String key, int attempt) throws WebApi.WebApiException;
        boolean retryBadRequest() { return true; }
    }

    private Op<WebApi.SearchResponse> searchOp(final WebApi.SearchRequest req) {
        return new Op<WebApi.SearchResponse>() {
            @Override WebApi.SearchResponse call(String key, int attempt) throws WebApi.WebApiException {
                return api.search(key, attempt == 0 ? req : req.plain().withQuery(simplify(req.query)));
            }
        };
    }

    private Op<WebApi.ExtractResponse> extractOp(final List<String> urls, final boolean advanced) {
        return new Op<WebApi.ExtractResponse>() {
            @Override WebApi.ExtractResponse call(String key, int attempt) throws WebApi.WebApiException {
                return api.extract(key, new WebApi.ExtractRequest(urls, advanced));
            }
            @Override boolean retryBadRequest() { return false; }       // the same addresses would fail the same way
        };
    }

    private <T> Outcome<T> run(List<String> all, Op<T> op, long start) {
        int tried = 0;
        boolean sawNetwork = false, sawTimeout = false, sawServer = false, sawBad = false;
        List<String> order = pool.order(all, clock.now());
        if (order.isEmpty()) {
            int[] rest = pool.resting(all, clock.now());
            return new Outcome<T>(rest[1] >= rest[0] && rest[1] > 0 ? WebTurn.Problem.ALL_KEYS_INVALID : WebTurn.Problem.ALL_KEYS_LIMIT, null, 0);
        }
        for (String key : order) {
            if (clock.now() - start > BUDGET_MS) { sawTimeout = true; break; }
            for (int attempt = 0; attempt < 2; attempt++) {
                tried++;
                try {
                    T r = op.call(key, attempt);
                    pool.good(key);
                    return new Outcome<T>(WebTurn.Problem.NONE, r, tried);
                } catch (WebApi.WebApiException e) {
                    if (e.fail == WebApi.Fail.BAD_REQUEST && attempt == 0 && op.retryBadRequest()) { sawBad = true; continue; }
                    if (e.fail == WebApi.Fail.BAD_REQUEST) sawBad = true;
                    if (e.fail == WebApi.Fail.INVALID_KEY) pool.invalid(key, clock.now());
                    else if (e.fail == WebApi.Fail.LIMIT) pool.limited(key, clock.now());
                    else if (e.fail == WebApi.Fail.NETWORK) sawNetwork = true;
                    else if (e.fail == WebApi.Fail.TIMEOUT) sawTimeout = true;
                    else if (e.fail == WebApi.Fail.SERVER || e.fail == WebApi.Fail.PARSE) sawServer = true;
                    break;
                } catch (RuntimeException e) {
                    sawServer = true;
                    break;
                }
            }
            if (sawNetwork) break;                       // no internet: the next key will not fix that
        }
        int[] rest = pool.resting(all, clock.now());
        WebTurn.Problem p;
        if (sawNetwork) p = WebTurn.Problem.NO_INTERNET;
        else if (rest[1] + rest[0] >= all.size() && rest[1] > 0 && rest[0] == 0) p = WebTurn.Problem.ALL_KEYS_INVALID;
        else if (rest[1] + rest[0] >= all.size()) p = rest[0] > 0 ? WebTurn.Problem.ALL_KEYS_LIMIT : WebTurn.Problem.ALL_KEYS_INVALID;
        else if (sawTimeout) p = WebTurn.Problem.TIMEOUT;
        else if (sawServer || sawBad) p = WebTurn.Problem.SERVER;
        else p = WebTurn.Problem.SERVER;
        return new Outcome<T>(p, null, tried);
    }

    // ------------------------------------------------------------------ helpers

    private WebTurn fail(SearchPlan plan, WebTurn.Problem p, int tried) {
        boolean mustSay = plan.explicit || plan.showImages || plan.links != SearchPlan.Links.NONE || plan.kind == SearchPlan.Kind.URL
                || plan.kind == SearchPlan.Kind.LINK || plan.kind == SearchPlan.Kind.IMAGE;
        String notice = noticeFor(p, mustSay || p != WebTurn.Problem.NO_KEY);
        boolean giveModelContext = p != WebTurn.Problem.NONE && (plan.kind != SearchPlan.Kind.CHAT);
        String ctx = !giveModelContext ? "" : plan.readsLinks() ? ContextBuilder.linkFailed(java.util.Collections.<LinkIssue>emptyList(), ReadIssue.of(p)) : ContextBuilder.failed(plan, p);
        return new WebTurn(plan, false, p, ctx, null, null, notice, plan.query, tried, 0);
    }

    static String noticeFor(WebTurn.Problem p, boolean say) {
        if (!say) return "";
        switch (p) {
            case OFF: return "Web search Settings mein band hai. Settings mein Web search ko Auto karo.";
            case NO_KEY: return "Internet search ke liye Settings > Tavily API mein key add karo.";
            case ALL_KEYS_LIMIT: return "Saari Tavily keys ki limit khatam hai. Nayi key add karo ya thodi der baad try karo.";
            case ALL_KEYS_INVALID: return "Tavily key galat ya expire lag rahi hai. Settings mein nayi key add karo.";
            case NO_INTERNET: return "Internet nahi mil raha, isliye search nahi ho saka.";
            case TIMEOUT: return "Search ka jawab der se aaya. Dobara try karo.";
            case SERVER: return "Search service abhi theek se kaam nahi kar rahi. Thodi der baad try karo.";
            case EMPTY: return "Net par is sawal ka jawab nahi mila.";
            default: return "";
        }
    }

    /** First few content words, without the helper words the planner added. */
    static String simplify(String q) {
        String[] t = q.replaceAll("\\b(how to|step by step|official website|price|india|comparison)\\b", " ").trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length && i < 6; i++) { if (sb.length() > 0) sb.append(' '); sb.append(t[i]); }
        String s = sb.toString().trim();
        return s.isEmpty() ? q : s;
    }

    private static boolean looksLikeOfficialSite(SearchPlan p) {
        return p.query.contains("official website");
    }
}
