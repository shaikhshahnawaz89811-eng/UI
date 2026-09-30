package com.neonhud.app.core.web;

import java.net.UnknownHostException;

/**
 * Downloads one public file (a picture or a PDF) straight from its web address - no Tavily, no key, no credit.
 * The implementation has to refuse anything that is not a normal public http(s) address, on every redirect too.
 * Blocking: call from a worker thread.
 */
public interface Fetcher {

    enum Why { UNSAFE, NETWORK, TIMEOUT, HTTP, TOO_BIG, WRONG_TYPE }

    final class FetchException extends Exception {
        public final Why why;
        public FetchException(Why why, String detail) { super(detail); this.why = why; }
    }

    final class Fetched {
        public final byte[] bytes;
        public final String contentType, finalUrl;
        public Fetched(byte[] bytes, String contentType, String finalUrl) {
            this.bytes = bytes; this.contentType = contentType == null ? "" : contentType; this.finalUrl = finalUrl == null ? "" : finalUrl;
        }
    }

    /** Decides whether an address may be opened at all (checked before every request and every redirect). */
    interface Guard {
        boolean allows(String url) throws UnknownHostException;

        /** Normal public http(s) only: {@link UrlTools#isSafe} plus: the name must not resolve to a private / local / link-local address. */
        Guard PUBLIC = new Guard() {
            @Override public boolean allows(String url) throws UnknownHostException {
                if (!UrlTools.isSafe(url)) return false;
                for (java.net.InetAddress a : java.net.InetAddress.getAllByName(UrlTools.host(url))) {
                    if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()) return false;
                    byte[] b = a.getAddress();
                    if (b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64) return false;      // 100.64.0.0/10 (carrier-grade NAT)
                    if (b.length == 16 && (b[0] & 0xFE) == 0xFC) return false;                              // fc00::/7 (unique local)
                }
                return true;
            }
        };
    }

    /**
     * @param typePrefixes allowed content types, e.g. "image/", "application/pdf"; empty = any. A missing type or
     *                     "application/octet-stream" is let through (servers often label PDFs that way) - the caller checks the bytes.
     */
    Fetched fetch(String url, int maxBytes, String... typePrefixes) throws FetchException;
}
