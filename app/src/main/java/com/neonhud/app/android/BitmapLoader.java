package com.neonhud.app.android;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/** Decodes a picture from a Uri down to a sensible size (memory-safe, photo rotation fixed). Shared by the model input and the thumbnails. */
public final class BitmapLoader {
    private BitmapLoader() { }

    /** @return a bitmap whose longest side is at most {@code maxEdge} px, upright. */
    public static Bitmap decode(ContentResolver r, Uri uri, int maxEdge) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream in = open(r, uri);
        try { BitmapFactory.decodeStream(in, null, bounds); } finally { in.close(); }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("not a readable image");

        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap bm;
        in = open(r, uri);
        try { bm = BitmapFactory.decodeStream(in, null, o); } finally { in.close(); }
        if (bm == null) throw new IOException("could not decode the image");

        int rotation = exifRotation(r, uri);
        float scale = Math.min(1f, maxEdge / (float) Math.max(bm.getWidth(), bm.getHeight()));
        if (scale >= 1f && rotation == 0) return bm;
        Matrix m = new Matrix();
        m.postScale(scale, scale);
        if (rotation != 0) m.postRotate(rotation);
        Bitmap out = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
        if (out != bm) bm.recycle();
        return out;
    }

    /** JPEG bytes; transparent parts (PNG) become white so the model does not see black. */
    public static byte[] toJpeg(Bitmap bm, int quality) {
        Bitmap src = bm;
        if (bm.hasAlpha()) {
            src = Bitmap.createBitmap(bm.getWidth(), bm.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(src);
            c.drawColor(Color.WHITE);
            c.drawBitmap(bm, 0, 0, null);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        src.compress(Bitmap.CompressFormat.JPEG, quality, out);
        if (src != bm) src.recycle();
        return out.toByteArray();
    }

    private static InputStream open(ContentResolver r, Uri uri) throws IOException {
        InputStream in = r.openInputStream(uri);
        if (in == null) throw new FileNotFoundException("cannot open " + uri);
        return in;
    }

    private static int exifRotation(ContentResolver r, Uri uri) {
        InputStream in = null;
        try {
            in = r.openInputStream(uri);
            if (in == null) return 0;
            int o = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (o) {
                case ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (IOException | RuntimeException e) {
            return 0;
        } finally {
            if (in != null) { try { in.close(); } catch (IOException ignored) { } }
        }
    }
}
