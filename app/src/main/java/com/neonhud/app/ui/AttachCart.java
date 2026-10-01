package com.neonhud.app.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The small panel that opens above the input bar when "+" is tapped: Camera / Image / Zip, with Zip opening PDF, ZIP and video files. */
final class AttachCart extends FrameLayout {

    interface Listener {
        void onPick(int kind);      // AttachIcon.CAMERA / IMAGE / ZIP
        void onClose();
    }

    private boolean open;

    AttachCart(Context c, final Listener l) {
        super(c);
        setBackground(NeonUi.glass(c, 16, 0xCC38B6FF, 0xF00B2350, 0xF0061637));
        setClickable(true);                                   // taps on the panel never reach the chat behind it

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.addView(item(c, AttachIcon.CAMERA, "Camera", "(Photo)", l), weight());
        row.addView(item(c, AttachIcon.IMAGE, "Image", "(Photo)", l), weight());
        row.addView(item(c, AttachIcon.ZIP, "Zip", "(PDF / ZIP / Video)", l), weight());
        row.setPadding(NeonUi.dp(c, 10), NeonUi.dp(c, 16), NeonUi.dp(c, 10), NeonUi.dp(c, 8));
        addView(row, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AttachIcon x = new AttachIcon(c, AttachIcon.CLOSE);
        x.setContentDescription("Close");
        x.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { l.onClose(); }
        });
        LayoutParams xp = new LayoutParams(NeonUi.dp(c, 24), NeonUi.dp(c, 24));
        xp.gravity = Gravity.TOP | Gravity.END;
        xp.topMargin = NeonUi.dp(c, 4);
        xp.rightMargin = NeonUi.dp(c, 6);
        addView(x, xp);
        setVisibility(GONE);
    }

    private static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private static View item(Context c, final int kind, String label, String sub, final Listener l) {
        LinearLayout it = new LinearLayout(c);
        it.setOrientation(LinearLayout.VERTICAL);
        it.setGravity(Gravity.CENTER_HORIZONTAL);
        it.setPadding(NeonUi.dp(c, 4), NeonUi.dp(c, 4), NeonUi.dp(c, 4), NeonUi.dp(c, 4));
        StateListDrawable press = new StateListDrawable();
        press.addState(new int[]{android.R.attr.state_pressed}, NeonUi.glass(c, 12, 0x6638D6FF, 0x4438D6FF, 0x2238D6FF));
        press.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        it.setBackground(press);
        it.setClickable(true);
        it.setContentDescription(label + " " + sub);
        it.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { l.onPick(kind); }
        });
        int icon = NeonUi.dp(c, 42);
        it.addView(new AttachIcon(c, kind), new LinearLayout.LayoutParams(icon, icon));
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextColor(NeonUi.TEXT);
        t.setTextSize(12f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, NeonUi.dp(c, 3), 0, 0);
        TextView s = new TextView(c);
        s.setText(sub);
        s.setTextColor(NeonUi.DIM);
        s.setTextSize(9.5f);
        s.setGravity(Gravity.CENTER);
        it.addView(t);
        it.addView(s);
        return it;
    }

    boolean isOpen() { return open; }

    void show() {
        if (open) return;
        open = true;
        setVisibility(VISIBLE);
        setAlpha(0f);
        setTranslationY(NeonUi.dp(getContext(), 12));
        animate().alpha(1f).translationY(0f).setDuration(160).start();
    }

    void hide() {
        if (!open) return;
        open = false;
        animate().alpha(0f).translationY(NeonUi.dp(getContext(), 12)).setDuration(120).withEndAction(new Runnable() {
            @Override public void run() { if (!open) setVisibility(GONE); }
        }).start();
    }
}
