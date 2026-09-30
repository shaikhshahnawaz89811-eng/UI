package com.neonhud.app.core.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;

/**
 * Talks to Tavily over HTTPS (POST /search, key in the Authorization header). Only moves bytes: the request body and
 * the answer are built / read by {@link TavilyJson}, and the HTTP status is mapped there too, so every failure ends
 * as one {@link WebApi.Fail} the service knows how to handle (next key, simpler retry, clear message).
 *
 * Plain java.net, no Android class: the JVM tests run it against a small local server.
 * Blocking: call from a worker thread. The API key is never put into an exception message.
 */
public final class HttpWebApi implements WebApi {

    public static final String TAVILY_SEARCH = "https://api.tavily.com/search";
    public static final String TAVILY_EXTRACT = "https://api.tavily.com/extract";
    /** A search answer is a few KB; anything far bigger is not one. */
    static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    /** Extracted pages are long text (3 pages at most are asked for at once); still capped so a bad answer cannot fill the phone. */
    static final int MAX_EXTRACT_BYTES = 6 * 1024 * 1024;

    private final String endpoint, extractEndpoint;
    private final int connectMs, readMs;

    public HttpWebApi() { this(TAVILY_SEARCH, TAVILY_EXTRACT, 10000, 22000); }

    /** The extract address is derived: ".../search" becomes ".../extract" (anything else gets "/extract" appended). */
    public HttpWebApi(String endpoint, int connectMs, int readMs) {
        this(endpoint, endpoint.endsWith("/search") ? endpoint.substring(0, endpoint.length() - "/search".length()) + "/extract"
                : endpoint + "/extract", connectMs, readMs);
    }

    public HttpWebApi(String endpoint, String extractEndpoint, int connectMs, int readMs) {
        this.endpoint = endpoint; this.extractEndpoint = extractEndpoint; this.connectMs = connectMs; this.readMs = readMs;
    }

    @Override public SearchResponse search(String apiKey, SearchRequest request) throws WebApiException {
        String text = post(endpoint, apiKey, TavilyJson.searchBody(request), request.advanced ? readMs + 8000 : readMs, MAX_BODY_BYTES);
        return TavilyJson.parseSearch(text);
    }

    @Override public ExtractResponse extract(String apiKey, ExtractRequest request) throws WebApiException {
        // reading whole pages is slower than searching, and "advanced" slower still
        String text = post(extractEndpoint, apiKey, TavilyJson.extractBody(request), request.advanced ? readMs + 18000 : readMs + 8000, MAX_EXTRACT_BYTES);
        return TavilyJson.parseExtract(text);
    }

    /** One POST with the key in the Authorization header. Returns the body of a 2xx answer; every other outcome is a {@link WebApiException}. */
    private String post(String url, String apiKey, String json, int readTimeoutMs, int maxBytes) throws WebApiException {
        HttpURLConnection c = null;
        try {
            byte[] body = json.getBytes("UTF-8");
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readTimeoutMs);
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Authorization", "Bearer " + apiKey);
            c.setFixedLengthStreamingMode(body.length);
            OutputStream out = c.getOutputStream();
            try { out.write(body); } finally { out.close(); }

            int code = c.getResponseCode();
            String text = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream(), maxBytes);
            Fail f = TavilyJson.failFor(code);
            if (f != null) throw new WebApiException(f, "HTTP " + code);
            return text;
        } catch (SocketTimeoutException e) {
            throw new WebApiException(Fail.TIMEOUT, "timeout");
        } catch (IOException e) {
            throw new WebApiException(Fail.NETWORK, String.valueOf(e.getClass().getSimpleName()));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String readAll(InputStream in, int maxBytes) throws IOException, WebApiException {
        if (in == null) return "";
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (bos.size() + n > maxBytes) throw new WebApiException(Fail.PARSE, "answer too large");
                bos.write(buf, 0, n);
            }
            return bos.toString("UTF-8");
        } finally {
            try { in.close(); } catch (IOException ignored) { }
        }
    }
}
