package com.neonhud.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * The attachment icons, drawn in code (no image files), each with its own colour like the reference:
 * Camera = blue, Image = green, PDF = red, Zip = amber. Also the round "x" remove button and the double tick.
 */
final class AttachIcon extends View {
    static final int CAMERA = 0, IMAGE = 1, PDF = 2, ZIP = 3, VIDEO = 4, AUDIO = 5, WORD = 6, EXCEL = 7, PPT = 8, CLOSE = 9, TICKS = 10;

    /** {tile top, tile bottom, tile edge} per file type. */
    private static final int[][] TILE = {
            {0xFF38B6FF, 0xFF1565D8, 0xFF9BE3FF},   // camera: blue
            {0xFF3CE6A0, 0xFF119A5E, 0xFFA0FFD8},   // image: green
            {0xFFFF6B7D, 0xFFC81E3A, 0xFFFFB0BA},   // pdf: red
            {0xFFFFD166, 0xFFE08A00, 0xFFFFE9A8},   // zip: amber
            {0xFFB78BFF, 0xFF6B35C8, 0xFFE1C9FF},   // video: purple
            {0xFFFF9A8B, 0xFFD94D36, 0xFFFFD0C9},   // audio: coral
            {0xFF5AA9FF, 0xFF246BCE, 0xFFC8E5FF},   // word: blue
            {0xFF58C878, 0xFF208A48, 0xFFC8F4D5},   // excel: green
            {0xFFFF8A5B, 0xFFD85B18, 0xFFFFD6C3},   // powerpoint: orange
    };

    private final int kind;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();
    private Shader fill;
    private float ox, oy, s;

    AttachIcon(Context c, int kind) {
        super(c);
        this.kind = kind;
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (kind <= PPT) fill = new LinearGradient(0, 0, 0, h, TILE[kind][0], TILE[kind][1], Shader.TileMode.CLAMP);
    }

    private float gx(float f) { return ox + f * s; }
    private float gy(float f) { return oy + f * s; }

    @Override protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        s = Math.min(w, h);
        ox = (w - s) / 2f;
        oy = (h - s) / 2f;
        p.setShader(null);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.clearShadowLayer();
        if (kind == CLOSE) { drawClose(cv); return; }
        if (kind == TICKS) { drawTicks(cv, w, h); return; }

        // coloured rounded tile with a light edge and a soft glow
        float in = s * 0.04f;
        r.set(ox + in, oy + in, ox + s - in, oy + s - in);
        p.setStyle(Paint.Style.FILL);
        p.setShader(fill);
        p.setShadowLayer(s * 0.10f, 0, 0, (TILE[kind][0] & 0x00FFFFFF) | 0x88000000);
        cv.drawRoundRect(r, s * 0.26f, s * 0.26f, p);
        p.setShader(null);
        p.clearShadowLayer();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.2f, s * 0.03f));
        p.setColor(TILE[kind][2]);
        cv.drawRoundRect(r, s * 0.26f, s * 0.26f, p);

        // white glyph
        p.setColor(0xFFFFFFFF);
        p.setStrokeWidth(Math.max(1.6f, s * 0.065f));
        path.reset();
        if (kind == CAMERA) {
            r.set(gx(.22f), gy(.36f), gx(.78f), gy(.72f));
            cv.drawRoundRect(r, s * .06f, s * .06f, p);
            path.moveTo(gx(.37f), gy(.36f)); path.lineTo(gx(.42f), gy(.27f));
            path.lineTo(gx(.58f), gy(.27f)); path.lineTo(gx(.63f), gy(.36f));
            cv.drawPath(path, p);
            cv.drawCircle(gx(.5f), gy(.54f), s * .105f, p);
        } else if (kind == IMAGE) {
            r.set(gx(.23f), gy(.27f), gx(.77f), gy(.73f));
            cv.drawRoundRect(r, s * .06f, s * .06f, p);
            p.setStyle(Paint.Style.FILL);
            cv.drawCircle(gx(.38f), gy(.41f), s * .05f, p);
            p.setStyle(Paint.Style.STROKE);
            path.moveTo(gx(.25f), gy(.69f)); path.lineTo(gx(.43f), gy(.50f)); path.lineTo(gx(.55f), gy(.62f));
            path.lineTo(gx(.63f), gy(.54f)); path.lineTo(gx(.76f), gy(.69f));
            cv.drawPath(path, p);
        } else if (kind == PDF) {
            path.moveTo(gx(.30f), gy(.22f)); path.lineTo(gx(.56f), gy(.22f)); path.lineTo(gx(.71f), gy(.37f));
            path.lineTo(gx(.71f), gy(.78f)); path.lineTo(gx(.30f), gy(.78f)); path.close();
            cv.drawPath(path, p);
            path.reset();
            path.moveTo(gx(.56f), gy(.22f)); path.lineTo(gx(.56f), gy(.37f)); path.lineTo(gx(.71f), gy(.37f));
            cv.drawPath(path, p);
            cv.drawLine(gx(.39f), gy(.54f), gx(.62f), gy(.54f), p);
            cv.drawLine(gx(.39f), gy(.65f), gx(.62f), gy(.65f), p);
        } else if (kind == ZIP) {
            r.set(gx(.27f), gy(.24f), gx(.73f), gy(.78f));
            cv.drawRoundRect(r, s * .06f, s * .06f, p);
            for (int i = 0; i < 4; i++) {
                float y = .30f + i * .075f, x0 = i % 2 == 0 ? .44f : .50f;
                cv.drawLine(gx(x0), gy(y), gx(x0 + .06f), gy(y), p);
            }
            r.set(gx(.45f), gy(.60f), gx(.55f), gy(.70f));
            cv.drawRoundRect(r, s * .03f, s * .03f, p);
        } else if (kind == AUDIO) {
            p.setStyle(Paint.Style.FILL);
            path.reset();
            path.moveTo(gx(.35f), gy(.45f)); path.lineTo(gx(.50f), gy(.36f)); path.lineTo(gx(.50f), gy(.68f));
            path.lineTo(gx(.35f), gy(.59f)); path.close(); cv.drawPath(path, p);
            r.set(gx(.49f), gy(.35f), gx(.55f), gy(.69f)); cv.drawRoundRect(r, s*.02f, s*.02f, p);
            p.setStyle(Paint.Style.STROKE); cv.drawArc(gx(.45f), gy(.38f), gx(.78f), gy(.66f), -65, 130, false, p);
        } else if (kind == WORD) {
            p.setStyle(Paint.Style.FILL); cv.drawRoundRect(gx(.27f), gy(.28f), gx(.72f), gy(.73f), s*.05f, s*.05f, p);
            p.setColor(TILE[kind][0]); cv.drawCircle(gx(.27f), gy(.51f), s*.14f, p);
            p.setColor(0xFFFFFFFF); p.setTextSize(s*.34f); p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); cv.drawText("W", gx(.37f), gy(.61f), p);
            p.setStyle(Paint.Style.STROKE);
        } else if (kind == EXCEL) {
            r.set(gx(.28f), gy(.25f), gx(.74f), gy(.75f)); cv.drawRoundRect(r, s*.05f, s*.05f, p);
            cv.drawLine(gx(.51f), gy(.27f), gx(.51f), gy(.73f), p); cv.drawLine(gx(.30f), gy(.43f), gx(.72f), gy(.43f), p); cv.drawLine(gx(.30f), gy(.59f), gx(.72f), gy(.59f), p);
            p.setStyle(Paint.Style.FILL); cv.drawCircle(gx(.39f), gy(.34f), s*.035f, p);
        } else if (kind == PPT) {
            r.set(gx(.27f), gy(.25f), gx(.73f), gy(.74f)); cv.drawRoundRect(r, s*.05f, s*.05f, p);
            p.setStyle(Paint.Style.FILL);
            cv.drawRect(gx(.35f), gy(.55f), gx(.43f), gy(.66f), p); cv.drawRect(gx(.46f), gy(.45f), gx(.54f), gy(.66f), p); cv.drawRect(gx(.57f), gy(.34f), gx(.65f), gy(.66f), p);
        } else {
            r.set(gx(.22f), gy(.27f), gx(.78f), gy(.73f));
            cv.drawRoundRect(r, s * .08f, s * .08f, p);
            p.setStyle(Paint.Style.FILL);
            path.reset();
            path.moveTo(gx(.44f), gy(.38f)); path.lineTo(gx(.44f), gy(.62f)); path.lineTo(gx(.64f), gy(.50f)); path.close();
            cv.drawPath(path, p);
            p.setStyle(Paint.Style.STROKE);
        }
    }

    private void drawClose(Canvas cv) {
        float cx = ox + s / 2f, cy = oy + s / 2f, rad = s * 0.46f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x66051A40);
        cv.drawCircle(cx, cy, rad, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, s * 0.05f));
        p.setColor(0xCC38D6FF);
        cv.drawCircle(cx, cy, rad, p);
        p.setColor(NeonUi.TEXT);
        p.setStrokeWidth(Math.max(1.4f, s * 0.07f));
        float k = s * 0.17f;
        cv.drawLine(cx - k, cy - k, cx + k, cy + k, p);
        cv.drawLine(cx - k, cy + k, cx + k, cy - k, p);
    }

    /** Two ticks, WhatsApp style, for "sent". */
    private void drawTicks(Canvas cv, float w, float h) {
        p.setStyle(Paint.Style.STROKE);
        p.setColor(NeonUi.CYAN);
        p.setStrokeWidth(Math.max(1.4f, h * 0.14f));
        for (int i = 0; i < 2; i++) {
            float dx = i * 0.28f;
            path.reset();
            path.moveTo((.05f + dx) * w, .55f * h);
            path.lineTo((.22f + dx) * w, .80f * h);
            path.lineTo((.55f + dx) * w, .18f * h);
            cv.drawPath(path, p);
        }
    }
}
