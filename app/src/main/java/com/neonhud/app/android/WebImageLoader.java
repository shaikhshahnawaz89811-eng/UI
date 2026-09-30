package com.neonhud.app.android;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import com.neonhud.app.core.web.UrlTools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Downloads the pictures shown under a web answer (the model never touches them). Small and careful:
 * only public http(s) addresses (every redirect is checked too), only real images, at most 4 MB each,
 * decoded down to thumbnail size, cached in memory, two downloads at a time, result delivered on the UI thread.
 */
public final class WebImageLoader {

    public interface Callback {
        void onLoaded(Bitmap bitmap);
        void onFailed();
    }

    private static final int MAX_BYTES = 4 * 1024 * 1024, MAX_REDIRECTS = 3;
    private static final WebImageLoader INSTANCE = new WebImageLoader();

    public static WebImageLoader get() { return INSTANCE; }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(2, new ThreadFactory() {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "web-image");
            t.setDaemon(true);
            return t;
        }
    });
    private final LruCache<String, Bitmap> cache;
    private final Set<String> failed = Collections.synchronizedSet(new HashSet<String>());

    private WebImageLoader() {
        int kb = (int) Math.min(24 * 1024, Runtime.getRuntime().maxMemory() / 1024 / 12);
        cache = new LruCache<String, Bitmap>(Math.max(2048, kb)) {
            @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount() / 1024; }
        };
    }

    /** Call on the UI thread. The callback also runs on the UI thread (at once when the picture is already cached). */
    public void load(final String url, final int maxEdge, final Callback cb) {
        Bitmap hit = cache.get(url);
        if (hit != null) { cb.onLoaded(hit); return; }
        if (failed.contains(url) || !UrlTools.isSafe(url)) { cb.onFailed(); return; }
        pool.execute(new Runnable() {
            @Override public void run() {
                final Bitmap bm = download(url, maxEdge);
                if (bm != null) cache.put(url, bm); else failed.add(url);
                main.post(new Runnable() {
                    @Override public void run() { if (bm != null) cb.onLoaded(bm); else cb.onFailed(); }
                });
            }
        });
    }

    private static Bitmap download(String start, int maxEdge) {
        String url = start;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpURLConnection c = null;
            try {
                if (!UrlTools.isSafe(url)) return null;
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(12000);
                c.setInstanceFollowRedirects(false);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) NeonHud");
                c.setRequestProperty("Accept", "image/*");
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null || loc.isEmpty()) return null;
                    url = new URL(new URL(url), loc).toString();
                    continue;
                }
                if (code < 200 || code >= 300) return null;
                String type = c.getContentType();
                if (type == null || !type.toLowerCase(java.util.Locale.ROOT).startsWith("image/")) return null;
                byte[] data = readCapped(c.getInputStream());
                return data == null ? null : decode(data, maxEdge);
            } catch (IOException e) {
                return null;
            } catch (RuntimeException e) {
                return null;
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return null;
    }

    private static byte[] readCapped(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (bos.size() + n > MAX_BYTES) return null;
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            try { in.close(); } catch (IOException ignored) { }
        }
    }

    private static Bitmap decode(byte[] data, int maxEdge) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }
}
