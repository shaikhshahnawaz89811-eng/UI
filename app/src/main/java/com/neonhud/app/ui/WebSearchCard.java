package com.neonhud.app.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.neonhud.app.core.web.WebMode;

/**
 * Settings card for internet search: Auto / Always / Off. Same glass card look as the other cards.
 * The choice is read by the search service on every message, so it applies to the very next one.
 */
final class WebSearchCard extends LinearLayout {

    interface Handler { void onMode(WebMode mode); }

    private final NeonUi.NeonButton auto, always, off;
    private final TextView explain, hint;

    WebSearchCard(Context c, final Handler handler) {
        super(c);
        setOrientation(VERTICAL);
        setBackground(NeonUi.glass(c, 14));
        int pad = NeonUi.dp(c, 14);
        setPadding(pad, pad, pad, pad);

        TextView name = new TextView(c);
        name.setText("Web search");
        name.setTextSize(18f);
        name.setTextColor(NeonUi.TEXT);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        addView(name);

        TextView sub = new TextView(c);
        sub.setText("Internet answers, links and pictures in the chat");
        sub.setTextSize(12.5f);
        sub.setTextColor(NeonUi.DIM);
        addView(sub);

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(HORIZONTAL);
        LayoutParams rlp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = NeonUi.dp(c, 10);
        auto = add(c, row, "Auto", WebMode.AUTO, handler, false);
        always = add(c, row, "Always", WebMode.ALWAYS, handler, true);
        off = add(c, row, "Off", WebMode.OFF, handler, true);
        addView(row, rlp);

        explain = new TextView(c);
        explain.setTextSize(12.5f);
        explain.setTextColor(NeonUi.TEXT);
        explain.setPadding(0, NeonUi.dp(c, 8), 0, 0);
        addView(explain);

        hint = new TextView(c);
        hint.setTextSize(12f);
        hint.setTextColor(NeonUi.AMBER);
        hint.setPadding(0, NeonUi.dp(c, 4), 0, 0);
        addView(hint);
    }

    private static NeonUi.NeonButton add(Context c, LinearLayout row, String label, final WebMode mode,
                                         final Handler handler, boolean margin) {
        NeonUi.NeonButton b = new NeonUi.NeonButton(c, label, NeonUi.DIM);
        LayoutParams lp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (margin) lp.leftMargin = NeonUi.dp(c, 6);
        row.addView(b, lp);
        b.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { handler.onMode(mode); } });
        return b;
    }

    void bind(WebMode mode, int keyCount) {
        auto.setAccent(mode == WebMode.AUTO ? NeonUi.CYAN : NeonUi.DIM);
        always.setAccent(mode == WebMode.ALWAYS ? NeonUi.CYAN : NeonUi.DIM);
        off.setAccent(mode == WebMode.OFF ? NeonUi.CYAN : NeonUi.DIM);
        switch (mode) {
            case ALWAYS:
                explain.setText("Always: every real question is searched on the internet (uses more Tavily credits).");
                break;
            case OFF:
                explain.setText("Off: the chat never goes online. Your saved keys stay saved.");
                break;
            default:
                explain.setText("Auto: searches only when it helps - news, prices, scores, links, pictures, steps, comparisons.");
                break;
        }
        boolean needKey = mode != WebMode.OFF && keyCount == 0;
        hint.setText(needKey ? "Add a Tavily API key below - search cannot work without one." : "");
        hint.setVisibility(needKey ? View.VISIBLE : View.GONE);
    }
}
