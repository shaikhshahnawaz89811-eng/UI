package com.neonhud.app.core.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Locale;

/** {@link Fetcher} over plain java.net: manual redirects (each one checked by the guard), size cap, content-type check. */
public final class HttpFetcher implements Fetcher {

    static final int MAX_REDIRECTS = 4;

    private final Guard guard;
    private final int connectMs, readMs;

    public HttpFetcher() { this(Guard.PUBLIC, 8000, 15000); }

    public HttpFetcher(Guard guard, int connectMs, int readMs) {
        this.guard = guard; this.connectMs = connectMs; this.readMs = readMs;
    }

    @Override public Fetched fetch(String start, int maxBytes, String... typePrefixes) throws FetchException {
        String url = start;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpURLConnection c = null;
            try {
                if (!guard.allows(url)) throw new FetchException(Why.UNSAFE, "address not allowed");
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(connectMs);
                c.setReadTimeout(readMs);
                c.setInstanceFollowRedirects(false);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) NeonHud");
                c.setRequestProperty("Accept", accept(typePrefixes));
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null || loc.isEmpty()) throw new FetchException(Why.HTTP, "redirect without a target");
                    url = new URL(new URL(url), loc).toString();
                    continue;
                }
                if (code < 200 || code >= 300) throw new FetchException(Why.HTTP, "HTTP " + code);
                String type = c.getContentType() == null ? "" : c.getContentType().toLowerCase(Locale.ROOT).trim();
                if (!typeAllowed(type, typePrefixes)) throw new FetchException(Why.WRONG_TYPE, type);
                long declared = c.getContentLengthLong();
                if (declared > maxBytes) throw new FetchException(Why.TOO_BIG, "declared " + declared);
                return new Fetched(readCapped(c.getInputStream(), maxBytes), type, url);
            } catch (FetchException e) {
                throw e;
            } catch (SocketTimeoutException e) {
                throw new FetchException(Why.TIMEOUT, "timeout");
            } catch (UnknownHostException e) {
                throw new FetchException(Why.NETWORK, "unknown host");
            } catch (IOException e) {
                throw new FetchException(Why.NETWORK, e.getClass().getSimpleName());
            } catch (RuntimeException e) {
                throw new FetchException(Why.UNSAFE, e.getClass().getSimpleName());     // malformed redirect target etc.
            } finally {
                if (c != null) c.disconnect();
            }
        }
        throw new FetchException(Why.HTTP, "too many redirects");
    }

    private static String accept(String[] prefixes) {
        if (prefixes == null || prefixes.length == 0) return "*/*";
        StringBuilder sb = new StringBuilder();
        for (String p : prefixes) { if (sb.length() > 0) sb.append(", "); sb.append(p.endsWith("/") ? p + "*" : p); }
        return sb.toString();
    }

    private static boolean typeAllowed(String type, String[] prefixes) {
        if (prefixes == null || prefixes.length == 0) return true;
        if (type.isEmpty() || type.startsWith("application/octet-stream") || type.startsWith("binary/")) return true;
        for (String p : prefixes) if (type.startsWith(p)) return true;
        return false;
    }

    private static byte[] readCapped(InputStream in, int max) throws IOException, FetchException {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (bos.size() + n > max) throw new FetchException(Why.TOO_BIG, "more than " + max + " bytes");
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            try { in.close(); } catch (IOException ignored) { }
        }
    }
}
