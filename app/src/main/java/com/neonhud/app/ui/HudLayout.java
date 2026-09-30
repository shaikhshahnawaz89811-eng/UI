package com.neonhud.app.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * Lays everything out relative to the HUD frame image (1500 x 878 px).
 *
 *  - the image is fitted INSIDE the safe area (system bars + camera cut-out), so nothing can be hidden behind them
 *  - three touch areas sit exactly over the minimize / (settings) / close icons that are drawn in the image
 *  - one content area sits inside the glass part of the frame; when the keyboard opens the content area only loses
 *    height (its bottom moves above the keyboard), the frame itself never jumps or shrinks
 */
public final class HudLayout extends ViewGroup {

    public static final float IMG_W = 1500f, IMG_H = 878f;
    // icon centres in image pixels (measured from hud_frame.png)
    private static final float MIN_CX = 1233f, GEAR_CX = 1297.5f, CLOSE_CX = 1363f, ICON_CY = 109f;
    // glass area inside the frame that is free of corner decorations (image pixels)
    private static final float CONTENT_L = 165f, CONTENT_T = 138f, CONTENT_R = 1335f, CONTENT_B = 735f;

    private ImageView frame;
    private final View minimize, gear, close;
    private final FrameLayout content;

    private int safeL, safeT, safeR, safeB, keyboard;
    // computed geometry
    private float scale, ox, oy;
    private int cl, ct, cr, cb;

    public HudLayout(Context c) { this(c, null); }

    public HudLayout(Context c, AttributeSet a) {
        super(c, a);
        setClipChildren(true);
        minimize = touchArea("Minimize");
        gear = touchArea("Settings");
        close = touchArea("Close");
        content = new FrameLayout(c);
        content.setClipChildren(true);
        addView(minimize);
        addView(gear);
        addView(close);
        addView(content);
    }

    private View touchArea(String description) {
        View v = new View(getContext());
        v.setContentDescription(description);
        v.setClickable(true);
        v.setFocusable(true);
        return v;
    }

    @Override protected void onFinishInflate() {
        super.onFinishInflate();
        frame = (ImageView) findViewById(com.neonhud.app.R.id.hud_frame);
    }

    public FrameLayout content() { return content; }
    public View minimizeButton() { return minimize; }
    public View settingsButton() { return gear; }
    public View closeButton() { return close; }

    /** Insets that must stay empty (bars + cut-out), in pixels. */
    public void setSafeInsets(int l, int t, int r, int b) {
        if (l == safeL && t == safeT && r == safeR && b == safeB) return;
        safeL = l; safeT = t; safeR = r; safeB = b;
        requestLayout();
    }

    public void setKeyboardHeight(int h) {
        if (h < 0) h = 0;
        if (h == keyboard) return;
        keyboard = h;
        requestLayout();
    }

    public int keyboardHeight() { return keyboard; }

    // ------------------------------------------------------------------ geometry

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void computeGeometry(int w, int h) {
        float availL = safeL, availT = safeT, availR = w - safeR, availB = h - safeB;
        float aw = Math.max(1f, availR - availL), ah = Math.max(1f, availB - availT);
        scale = Math.min(aw / IMG_W, ah / IMG_H);
        ox = availL + (aw - IMG_W * scale) / 2f;
        oy = availT + (ah - IMG_H * scale) / 2f;

        cl = Math.round(ox + CONTENT_L * scale);
        cr = Math.round(ox + CONTENT_R * scale);
        ct = Math.round(oy + CONTENT_T * scale);
        cb = Math.round(oy + CONTENT_B * scale);

        if (keyboard > 0) {
            int limit = h - keyboard - dp(6);            // keep the input above the keyboard
            if (limit < cb) cb = limit;
            int minHeight = dp(104);
            if (cb - ct < minHeight) ct = Math.max(Math.round(availT) + dp(2), cb - minHeight);
            if (cb - ct < minHeight) cb = ct + minHeight; // tiny screens: better slightly overlapping than unusable
        }
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
        setMeasuredDimension(w, h);
        computeGeometry(w, h);
        if (frame != null) {
            frame.measure(MeasureSpec.makeMeasureSpec(Math.round(IMG_W * scale), MeasureSpec.EXACTLY),
                          MeasureSpec.makeMeasureSpec(Math.round(IMG_H * scale), MeasureSpec.EXACTLY));
        }
        int spacing = Math.max(1, Math.round((CLOSE_CX - GEAR_CX) * scale));
        int hitH = Math.max(spacing, dp(40));
        int exactW = MeasureSpec.makeMeasureSpec(spacing, MeasureSpec.EXACTLY);
        int exactH = MeasureSpec.makeMeasureSpec(hitH, MeasureSpec.EXACTLY);
        minimize.measure(exactW, exactH);
        gear.measure(exactW, exactH);
        close.measure(exactW, exactH);
        content.measure(MeasureSpec.makeMeasureSpec(Math.max(0, cr - cl), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(Math.max(0, cb - ct), MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (frame != null) {
            int fl = Math.round(ox), ft = Math.round(oy);
            frame.layout(fl, ft, fl + frame.getMeasuredWidth(), ft + frame.getMeasuredHeight());
        }
        placeAt(minimize, MIN_CX);
        placeAt(gear, GEAR_CX);
        placeAt(close, CLOSE_CX);
        content.layout(cl, ct, cr, cb);
    }

    private void placeAt(View v, float imgCx) {
        int cx = Math.round(ox + imgCx * scale), cy = Math.round(oy + ICON_CY * scale);
        int hw = v.getMeasuredWidth() / 2, hh = v.getMeasuredHeight() / 2;
        v.layout(cx - hw, cy - hh, cx + hw, cy + hh);
    }
}
