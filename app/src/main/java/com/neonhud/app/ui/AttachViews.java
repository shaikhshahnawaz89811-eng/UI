package com.neonhud.app.ui;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.graphics.Outline;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.neonhud.app.android.BitmapLoader;
import com.neonhud.app.core.engine.Attachment;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/** One file card: coloured icon (or a picture thumbnail for images), name, size and optionally a remove button. */
final class AttachViews {
    private AttachViews() { }

    static int iconKind(Attachment.Kind k) {
        switch (k) {
            case PDF: return AttachIcon.PDF;
            case ZIP: return AttachIcon.ZIP;
            case VIDEO: return AttachIcon.VIDEO;
            default:  return AttachIcon.IMAGE;
        }
    }

    /** @param onRemove null for a card inside a sent message (no remove button) */
    static View card(Context c, Attachment a, View.OnClickListener onRemove) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(NeonUi.glass(c, 12, 0x8838D6FF, 0x55123C7A, 0x440A2250));
        card.setPadding(NeonUi.dp(c, 6), NeonUi.dp(c, 5), NeonUi.dp(c, 6), NeonUi.dp(c, 5));

        int icon = NeonUi.dp(c, 38);
        FrameLayout iconBox = new FrameLayout(c);
        iconBox.addView(new AttachIcon(c, iconKind(a.kind)), new FrameLayout.LayoutParams(icon, icon));
        if (a.kind == Attachment.Kind.IMAGE || a.kind == Attachment.Kind.VIDEO) {
            final int radius = NeonUi.dp(c, 9);
            ImageView thumb = new ImageView(c);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius); }
            });
            thumb.setClipToOutline(true);
            int pad = NeonUi.dp(c, 2);
            FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(icon - pad * 2, icon - pad * 2);
            tp.gravity = Gravity.CENTER;
            iconBox.addView(thumb, tp);
            Thumbs.load(c, a.kind, a.uri, thumb, icon * 2);
        }
        card.addView(iconBox, new LinearLayout.LayoutParams(icon, icon));

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(c);
        name.setText(a.name);
        name.setTextColor(NeonUi.TEXT);
        name.setTextSize(12.5f);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        TextView size = new TextView(c);
        size.setText(a.sizeLabel());
        size.setTextColor(0xFF8FC4EE);
        size.setTextSize(10.5f);
        col.addView(name);
        col.addView(size);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cp.leftMargin = NeonUi.dp(c, 8);
        card.addView(col, cp);

        if (onRemove != null) {
            AttachIcon x = new AttachIcon(c, AttachIcon.CLOSE);
            x.setContentDescription("Remove " + a.name);
            x.setOnClickListener(onRemove);
            LinearLayout.LayoutParams xp = new LinearLayout.LayoutParams(NeonUi.dp(c, 26), NeonUi.dp(c, 26));
            xp.leftMargin = NeonUi.dp(c, 6);
            card.addView(x, xp);
        }
        return card;
    }

    /** Small picture thumbnails, decoded off the UI thread and cached. */
    static final class Thumbs {
        private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(24);
        private static final Handler MAIN = new Handler(Looper.getMainLooper());
        private static final ExecutorService POOL = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "attach-thumbs");
                t.setDaemon(true);
                return t;
            }
        });

        static void load(Context c, final Attachment.Kind kind, final String uri, final ImageView view, final int px) {
            final ContentResolver resolver = c.getApplicationContext().getContentResolver();
            final String cacheKey = kind.name() + ":" + px + ":" + uri;
            view.setTag(cacheKey);
            Bitmap hit = CACHE.get(cacheKey);
            if (hit != null) { view.setImageBitmap(hit); return; }
            view.setImageDrawable(null);                      // the coloured tile shows until the picture is ready
            POOL.execute(new Runnable() {
                @Override public void run() {
                    try {
                        final Bitmap bm;
                        if (kind == Attachment.Kind.VIDEO) {
                            MediaMetadataRetriever r = new MediaMetadataRetriever();
                            try {
                                r.setDataSource(c, Uri.parse(uri));
                                bm = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                            } finally { r.release(); }
                        } else {
                            bm = BitmapLoader.decode(resolver, Uri.parse(uri), px);
                        }
                        if (bm == null) throw new IllegalStateException("no thumbnail");
                        CACHE.put(cacheKey, bm);
                        MAIN.post(new Runnable() {
                            @Override public void run() { if (cacheKey.equals(view.getTag())) view.setImageBitmap(bm); }
                        });
                    } catch (Throwable ignored) {
                        // unreadable picture: the tile stays
                    }
                }
            });
        }
    }
}
