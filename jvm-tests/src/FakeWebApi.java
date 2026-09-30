package tests;

import com.neonhud.app.core.web.WebApi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Scripted internet: every Tavily behaviour (good answer, bad key, limit, timeout, garbage ...) can be replayed on a JVM. */
final class FakeWebApi implements WebApi {
    interface Handler { SearchResponse handle(String key, SearchRequest r) throws WebApiException; }

    interface ExtractHandler { ExtractResponse handle(String key, ExtractRequest r) throws WebApiException; }

    volatile Handler handler;
    volatile ExtractHandler extractHandler = new ExtractHandler() {
        @Override public ExtractResponse handle(String key, ExtractRequest r) {
            List<Page> pages = new ArrayList<Page>();
            for (String u : r.urls) pages.add(new Page(u, "Page text about " + com.neonhud.app.core.web.UrlTools.display(u).replace('.', ' ') + ".\nIt has a second line with some facts."));
            return new ExtractResponse(pages, null);
        }
    };
    final List<ExtractRequest> extracts = Collections.synchronizedList(new ArrayList<ExtractRequest>());
    final List<String> calls = Collections.synchronizedList(new ArrayList<String>());
    final List<SearchRequest> requests = Collections.synchronizedList(new ArrayList<SearchRequest>());

    FakeWebApi(Handler h) { handler = h; }

    @Override public SearchResponse search(String key, SearchRequest r) throws WebApiException {
        calls.add(key);
        requests.add(r);
        return handler.handle(key, r);
    }

    @Override public ExtractResponse extract(String key, ExtractRequest r) throws WebApiException {
        calls.add(key);
        extracts.add(r);
        return extractHandler.handle(key, r);
    }

    static Hit hit(String title, String url, String text, double score) { return new Hit(title, url, text, score); }

    static SearchResponse resp(String answer, List<Hit> hits, List<Image> imgs) { return new SearchResponse(answer, hits, imgs); }

    static List<Hit> hits(Hit... h) { return java.util.Arrays.asList(h); }
    static List<Image> imgs(Image... i) { return java.util.Arrays.asList(i); }

    /** A normal answer for any query. */
    static Handler ok() {
        return new Handler() {
            @Override public SearchResponse handle(String key, SearchRequest r) {
                return resp("Short summary for " + r.query + ".",
                        hits(hit("Result one about " + r.query, "https://www.example.com/a/" + r.query.replace(' ', '-'), "Facts about " + r.query + " from source one.", 0.9),
                             hit("Result two", "https://docs.example.org/guide/two", "More facts about " + r.query + " from source two.", 0.7),
                             hit("Result three", "https://news.example.net/story", "Third source on " + r.query + ".", 0.5)),
                        r.includeImages ? imgs(new Image("https://img.example.com/p1.jpg", r.query + " photo one"), new Image("https://img.example.com/p2.jpg", "second view")) : Collections.<Image>emptyList());
            }
        };
    }

    static WebApiException fail(Fail f) { return new WebApiException(f, f.name()); }
}
