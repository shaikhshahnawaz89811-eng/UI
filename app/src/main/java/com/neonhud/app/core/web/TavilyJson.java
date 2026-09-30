package com.neonhud.app.core.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Builds Tavily request bodies and reads its answers. Pure Java: the HTTP class only moves the bytes. */
public final class TavilyJson {
    private TavilyJson() { }

    public static String searchBody(WebApi.SearchRequest r) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"query\":").append(MiniJson.quote(r.query));
        sb.append(",\"search_depth\":\"").append(r.advanced ? "advanced" : "basic").append('"');
        sb.append(",\"max_results\":").append(Math.max(1, Math.min(10, r.maxResults)));
        sb.append(",\"include_answer\":\"basic\"");
        sb.append(",\"include_images\":").append(r.includeImages);
        if (r.includeImages) sb.append(",\"include_image_descriptions\":true");
        sb.append(",\"topic\":\"").append("news".equals(r.topic) ? "news" : "general").append('"');
        if (!r.timeRange.isEmpty()) sb.append(",\"time_range\":").append(MiniJson.quote(r.timeRange));
        return sb.append('}').toString();
    }

    /** @throws WebApi.WebApiException PARSE when the body is not a Tavily answer. */
    public static WebApi.SearchResponse parseSearch(String body) throws WebApi.WebApiException {
        Map<String, Object> root;
        try { root = MiniJson.obj(MiniJson.parse(body)); }
        catch (IllegalArgumentException e) { throw new WebApi.WebApiException(WebApi.Fail.PARSE, "not json: " + e.getMessage()); }
        if (root == null) throw new WebApi.WebApiException(WebApi.Fail.PARSE, "not an object");

        List<WebApi.Hit> hits = new ArrayList<WebApi.Hit>();
        List<Object> results = MiniJson.arr(root.get("results"));
        if (results != null) {
            for (Object o : results) {
                Map<String, Object> m = MiniJson.obj(o);
                if (m == null) continue;
                String url = MiniJson.str(m.get("url"));
                if (url.isEmpty()) continue;
                hits.add(new WebApi.Hit(MiniJson.str(m.get("title")), url, MiniJson.str(m.get("content")), MiniJson.num(m.get("score"), 0)));
            }
        }
        List<WebApi.Image> images = new ArrayList<WebApi.Image>();
        List<Object> imgs = MiniJson.arr(root.get("images"));
        if (imgs != null) {
            for (Object o : imgs) {
                if (o instanceof String) { images.add(new WebApi.Image((String) o, "")); continue; }   // older answers: plain urls
                Map<String, Object> m = MiniJson.obj(o);
                if (m != null && !MiniJson.str(m.get("url")).isEmpty())
                    images.add(new WebApi.Image(MiniJson.str(m.get("url")), MiniJson.str(m.get("description"))));
            }
        }
        return new WebApi.SearchResponse(MiniJson.str(root.get("answer")), hits, images);
    }

    public static String extractBody(WebApi.ExtractRequest r) {
        StringBuilder sb = new StringBuilder("{\"urls\":[");
        for (int i = 0; i < r.urls.size(); i++) { if (i > 0) sb.append(','); sb.append(MiniJson.quote(r.urls.get(i))); }
        sb.append("],\"extract_depth\":\"").append(r.advanced ? "advanced" : "basic").append('"');
        sb.append(",\"format\":\"text\",\"include_images\":false}");
        return sb.toString();
    }

    /** @throws WebApi.WebApiException PARSE when the body is not a Tavily extract answer. */
    public static WebApi.ExtractResponse parseExtract(String body) throws WebApi.WebApiException {
        Map<String, Object> root;
        try { root = MiniJson.obj(MiniJson.parse(body)); }
        catch (IllegalArgumentException e) { throw new WebApi.WebApiException(WebApi.Fail.PARSE, "not json: " + e.getMessage()); }
        if (root == null) throw new WebApi.WebApiException(WebApi.Fail.PARSE, "not an object");
        List<WebApi.Page> pages = new ArrayList<WebApi.Page>();
        List<Object> results = MiniJson.arr(root.get("results"));
        if (results != null) {
            for (Object o : results) {
                Map<String, Object> m = MiniJson.obj(o);
                if (m == null) continue;
                String url = MiniJson.str(m.get("url"));
                if (url.isEmpty()) continue;
                pages.add(new WebApi.Page(url, MiniJson.str(m.get("raw_content"))));
            }
        }
        List<WebApi.FailedUrl> failed = new ArrayList<WebApi.FailedUrl>();
        List<Object> bad = MiniJson.arr(root.get("failed_results"));
        if (bad != null) {
            for (Object o : bad) {
                Map<String, Object> m = MiniJson.obj(o);
                if (m == null) continue;
                String url = MiniJson.str(m.get("url"));
                if (!url.isEmpty()) failed.add(new WebApi.FailedUrl(url, MiniJson.str(m.get("error"))));
            }
        }
        return new WebApi.ExtractResponse(pages, failed);
    }

    /** Maps an HTTP status to a failure kind (null = success). 432/433 = plan / pay-as-you-go limit. */
    public static WebApi.Fail failFor(int http) {
        if (http >= 200 && http < 300) return null;
        if (http == 401 || http == 403) return WebApi.Fail.INVALID_KEY;
        if (http == 429 || http == 432 || http == 433) return WebApi.Fail.LIMIT;
        if (http == 408 || http == 504) return WebApi.Fail.TIMEOUT;
        if (http == 400 || http == 422) return WebApi.Fail.BAD_REQUEST;
        return WebApi.Fail.SERVER;
    }
}
