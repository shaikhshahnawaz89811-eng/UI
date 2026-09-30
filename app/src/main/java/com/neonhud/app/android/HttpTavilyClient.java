package com.neonhud.app.android;

import com.neonhud.app.core.search.TavilyClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Tests a key with one tiny real search (POST https://api.tavily.com/search, 1 result).
 * 200 = key works, 401 = wrong key, 429 / 432 / 433 = key is fine but rate / plan limit reached.
 * Only ever called from the manager's worker thread.
 */
public final class HttpTavilyClient implements TavilyClient {
    private static final String URL_SEARCH = "https://api.tavily.com/search";
    private static final String BODY = "{\"query\":\"test\",\"max_results\":1,\"search_depth\":\"basic\"}";

    @Override public Outcome check(String apiKey) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(URL_SEARCH).openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(20000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Authorization", "Bearer " + apiKey);
            byte[] body = BODY.getBytes("UTF-8");
            c.setFixedLengthStreamingMode(body.length);
            OutputStream out = c.getOutputStream();
            try { out.write(body); } finally { out.close(); }

            int code = c.getResponseCode();
            drain(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code >= 200 && code < 300) return new Outcome(Kind.VALID, "");
            if (code == 401 || code == 403) return new Outcome(Kind.INVALID, "HTTP " + code);
            if (code == 429 || code == 432 || code == 433) return new Outcome(Kind.LIMIT, "HTTP " + code);
            return new Outcome(Kind.ERROR, "HTTP " + code);
        } catch (IOException e) {
            return new Outcome(Kind.NETWORK, String.valueOf(e.getMessage()));
        } catch (RuntimeException e) {
            return new Outcome(Kind.ERROR, String.valueOf(e.getMessage()));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static void drain(InputStream in) {
        if (in == null) return;
        try {
            byte[] buf = new byte[2048];
            int total = 0, n;
            while ((n = in.read(buf)) > 0 && (total += n) < 64 * 1024) { /* discard */ }
        } catch (IOException ignored) {
        } finally {
            try { in.close(); } catch (IOException ignored) { }
        }
    }
}
