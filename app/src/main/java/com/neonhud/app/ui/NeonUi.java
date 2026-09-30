package com.neonhud.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

/** Colours and small custom controls in the HUD's blue / cyan neon style. */
final class NeonUi {
    static final int CYAN = 0xFF38D6FF;
    static final int BLUE = 0xFF1E90FF;
    static final int TEXT = 0xFFEAF8FF;
    static final int DIM = 0xFF7FA6C2;
    static final int RED = 0xFFFF5468;
    static final int GREEN = 0xFF3CFFB0;
    static final int AMBER = 0xFFFFC857;

    private NeonUi() { }

    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    /** Frosted-glass panel: light translucent gradient + thin neon edge. */
    static GradientDrawable glass(Context c, float radiusDp, int strokeColor, int topFill, int bottomFill) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{topFill, bottomFill});
        d.setCornerRadius(dp(c, radiusDp));
        d.setStroke(Math.max(1, dp(c, 1)), strokeColor);
        return d;
    }

    static GradientDrawable glass(Context c, float radiusDp) {
        return glass(c, radiusDp, 0x9938B6FF, 0x3A8CD2FF, 0x1A1E5A9A);
    }

    // ------------------------------------------------------------------ button

    static final class NeonButton extends TextView {
        private int accent;

        NeonButton(Context c, String label, int accent) {
            super(c);
            this.accent = accent;
            setText(label);
            setGravity(Gravity.CENTER);
            setTextSize(13f);
            setTypeface(Typeface.DEFAULT_BOLD);
            setAllCaps(false);
            setSingleLine(true);
            setClickable(true);
            setFocusable(true);
            setPadding(dp(c, 10), dp(c, 8), dp(c, 10), dp(c, 8));
            setMinHeight(dp(c, 40));
            applyLook(true);
        }

        @Override public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            applyLook(enabled);
        }

        void setAccent(int newAccent) {
            if (newAccent == accent) return;
            accent = newAccent;
            applyLook(isEnabled());
        }

        private void applyLook(boolean enabled) {
            Context c = getContext();
            int a = accent & 0x00FFFFFF;
            if (enabled) {
                GradientDrawable normal = glass(c, 12, accent, (0x55 << 24) | a, (0x22 << 24) | a);
                GradientDrawable pressed = glass(c, 12, accent, (0xAA << 24) | a, (0x66 << 24) | a);
                StateListDrawable s = new StateListDrawable();
                s.addState(new int[]{android.R.attr.state_pressed}, pressed);
                s.addState(new int[]{}, normal);
                setBackground(s);
                setTextColor(TEXT);
            } else {
                setBackground(glass(c, 12, (0x40 << 24) | a, 0x14FFFFFF, 0x0AFFFFFF));
                setTextColor(0x66A9C4D8);
            }
        }
    }

    // ------------------------------------------------------------------ icons

    static final class IconView extends View {
        static final int BACK = 0, SEND = 1, PLUS = 2;
        private final int kind;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        IconView(Context c, int kind) {
            super(c);
            this.kind = kind;
            setClickable(true);
            setFocusable(true);
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float s = Math.min(w, h);
            boolean on = isEnabled();
            int color = on ? CYAN : 0x5538B6FF;
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeWidth(Math.max(2f, s * 0.07f));
            if (on) p.setShadowLayer(s * 0.18f, 0, 0, 0xAA38B6FF); else p.clearShadowLayer();
            path.reset();
            if (kind == BACK) {
                float cx = w / 2f, cy = h / 2f, r = s * 0.20f;
                path.moveTo(cx + r * 0.7f, cy - r * 1.4f);
                path.lineTo(cx - r * 0.7f, cy);
                path.lineTo(cx + r * 0.7f, cy + r * 1.4f);
                cv.drawPath(path, p);
            } else if (kind == SEND) {
                // paper plane (outline + fold line), same shape as the reference bar
                float cx = w / 2f, cy = h / 2f, u = s * 0.58f;          // u = size of the plane
                float x0 = cx - u / 2f, y0 = cy - u / 2f;
                path.moveTo(x0 + u * 0.96f, y0 + u * 0.04f);           // tip
                path.lineTo(x0 + u * 0.04f, y0 + u * 0.42f);           // left wing
                path.lineTo(x0 + u * 0.40f, y0 + u * 0.60f);           // notch
                path.lineTo(x0 + u * 0.58f, y0 + u * 0.96f);           // tail
                path.close();
                if (on) {
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(0x3338D6FF);
                    p.clearShadowLayer();
                    cv.drawPath(path, p);
                    p.setStyle(Paint.Style.STROKE);
                    p.setColor(color);
                    p.setShadowLayer(s * 0.18f, 0, 0, 0xAA38B6FF);
                }
                cv.drawPath(path, p);
                path.reset();
                path.moveTo(x0 + u * 0.40f, y0 + u * 0.60f);           // fold line: notch -> tip
                path.lineTo(x0 + u * 0.96f, y0 + u * 0.04f);
                cv.drawPath(path, p);
            } else {
                // "+" in a square box with cut corners, like the reference
                float cx = w / 2f, cy = h / 2f;
                float half = s * 0.5f - Math.max(2f, s * 0.05f);
                float k = half * 0.22f;                                 // corner cut
                path.moveTo(cx - half + k, cy - half);
                path.lineTo(cx + half - k, cy - half);
                path.lineTo(cx + half, cy - half + k);
                path.lineTo(cx + half, cy + half - k);
                path.lineTo(cx + half - k, cy + half);
                path.lineTo(cx - half + k, cy + half);
                path.lineTo(cx - half, cy + half - k);
                path.lineTo(cx - half, cy - half + k);
                path.close();
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x3338B6FF);
                p.clearShadowLayer();
                cv.drawPath(path, p);
                p.setStyle(Paint.Style.STROKE);
                p.setColor(CYAN);
                p.setStrokeWidth(Math.max(1.5f, s * 0.04f));
                p.setShadowLayer(s * 0.10f, 0, 0, 0xAA38B6FF);
                cv.drawPath(path, p);
                // the plus itself: bright, slightly glowing
                float r = half * 0.40f;
                p.setColor(TEXT);
                p.setStrokeWidth(Math.max(2.5f, s * 0.08f));
                p.setShadowLayer(s * 0.16f, 0, 0, 0xCC38B6FF);
                cv.drawLine(cx - r, cy, cx + r, cy, p);
                cv.drawLine(cx, cy - r, cx, cy + r, p);
            }
        }
    }

    // ------------------------------------------------------------------ progress

    static final class ThinProgress extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private int value;

        ThinProgress(Context c) { super(c); }

        void setValue(int v) {
            v = Math.max(0, Math.min(100, v));
            if (v != value) { value = v; invalidate(); }
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), rad = h / 2f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x3338B6FF);
            r.set(0, 0, w, h);
            cv.drawRoundRect(r, rad, rad, p);
            p.setColor(CYAN);
            r.set(0, 0, Math.max(h, w * value / 100f), h);
            cv.drawRoundRect(r, rad, rad, p);
        }
    }
}
