package com.neonhud.app.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.neonhud.app.core.search.TavilyKeyManager;
import com.neonhud.app.core.search.TavilySnapshot;

/**
 * Settings card for Tavily API keys - same glass card look as the model modules:
 * title, status line, message, an input with Add, and one small row per saved key with Delete.
 * Everything shown comes from {@link TavilySnapshot}.
 */
final class TavilyKeysCard extends LinearLayout {

    interface Handler {
        void onAdd(String key);
        void onDelete(String key);
    }

    private final Handler handler;
    private final View dot;
    private final TextView status;
    private final TextView message;
    private final EditText input;
    private final NeonUi.NeonButton btnAdd;
    private final LinearLayout list;
    private String shownSignature = null;
    private boolean wasAdding = false;

    TavilyKeysCard(Context c, final Handler handler) {
        super(c);
        this.handler = handler;
        setOrientation(VERTICAL);
        setBackground(NeonUi.glass(c, 14));
        int pad = NeonUi.dp(c, 14);
        setPadding(pad, pad, pad, pad);

        TextView name = new TextView(c);
        name.setText("Tavily API");
        name.setTextSize(18f);
        name.setTextColor(NeonUi.TEXT);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        addView(name);

        TextView sub = new TextView(c);
        sub.setText("Web Search Keys");
        sub.setTextSize(12.5f);
        sub.setTextColor(NeonUi.DIM);
        addView(sub);

        LinearLayout statusRow = new LinearLayout(c);
        statusRow.setOrientation(HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(0, NeonUi.dp(c, 10), 0, NeonUi.dp(c, 4));
        dot = new View(c);
        statusRow.addView(dot, new LayoutParams(NeonUi.dp(c, 9), NeonUi.dp(c, 9)));
        status = new TextView(c);
        status.setTextSize(14f);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setPadding(NeonUi.dp(c, 8), 0, 0, 0);
        statusRow.addView(status);
        addView(statusRow);

        message = new TextView(c);
        message.setTextSize(12f);
        message.setPadding(0, 0, 0, NeonUi.dp(c, 6));
        addView(message);

        // ---- input + Add
        LinearLayout inputRow = new LinearLayout(c);
        inputRow.setOrientation(HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        input = new EditText(c);
        input.setHint("tvly-...");
        input.setHintTextColor(NeonUi.DIM);
        input.setTextColor(NeonUi.TEXT);
        input.setTextSize(14f);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_ACTION_DONE);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(300)});
        input.setBackground(NeonUi.glass(c, 12));
        input.setPadding(NeonUi.dp(c, 12), NeonUi.dp(c, 8), NeonUi.dp(c, 12), NeonUi.dp(c, 8));
        inputRow.addView(input, new LayoutParams(0, NeonUi.dp(c, 40), 1f));

        btnAdd = new NeonUi.NeonButton(c, "Add", NeonUi.CYAN);
        LayoutParams alp = new LayoutParams(NeonUi.dp(c, 84), ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.leftMargin = NeonUi.dp(c, 6);
        inputRow.addView(btnAdd, alp);
        addView(inputRow, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- saved keys
        list = new LinearLayout(c);
        list.setOrientation(VERTICAL);
        LayoutParams llp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = NeonUi.dp(c, 8);
        addView(list, llp);

        btnAdd.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { submit(); }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent e) {
                if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); return true; }
                return false;
            }
        });
    }

    private void submit() {
        String text = input.getText().toString();
        input.clearFocus();                          // close the keyboard so the test result is visible
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        handler.onAdd(text);
    }

    void bind(TavilySnapshot s) {
        int count = s.entries.size();
        String text;
        int color;
        if (s.adding) { text = "Testing\u2026"; color = NeonUi.CYAN; }
        else if (count == 0) { text = "No key added"; color = NeonUi.DIM; }
        else { text = count + (count == 1 ? " key added" : " keys added"); color = NeonUi.GREEN; }
        status.setText(text);
        status.setTextColor(color);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        dot.setBackground(d);

        int mc;
        switch (s.tone) {
            case OK: mc = NeonUi.GREEN; break;
            case ERROR: mc = NeonUi.RED; break;
            case WARN: mc = NeonUi.AMBER; break;
            default: mc = NeonUi.CYAN; break;
        }
        message.setTextColor(mc);
        message.setText(s.message);
        message.setVisibility(s.message.isEmpty() ? View.GONE : View.VISIBLE);

        btnAdd.setEnabled(s.canAdd());

        // The typed key stays in the box while it is being tested (so a typo can be fixed); once it is saved, empty the box.
        if (wasAdding && !s.adding && !s.entries.isEmpty()) {
            String last = s.entries.get(s.entries.size() - 1).key;
            if (last.equals(TavilyKeyManager.normalize(input.getText().toString()))) input.setText("");
        }
        wasAdding = s.adding;

        // The chat refreshes this page often; only rebuild the rows when the keys really changed.
        String sig = s.signature();
        if (!sig.equals(shownSignature)) {
            shownSignature = sig;
            rebuildRows(s);
        }
    }

    private void rebuildRows(TavilySnapshot s) {
        Context c = getContext();
        list.removeAllViews();
        for (final TavilySnapshot.Entry e : s.entries) {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(NeonUi.glass(c, 12, 0x5538B6FF, 0x22FFFFFF, 0x0FFFFFFF));
            row.setPadding(NeonUi.dp(c, 12), NeonUi.dp(c, 4), NeonUi.dp(c, 4), NeonUi.dp(c, 4));

            View rowDot = new View(c);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(e.health.equals("healthy") || e.health.equals("ready") ? NeonUi.GREEN : e.health.equals("limit") ? NeonUi.AMBER : NeonUi.RED);
            rowDot.setBackground(g);
            row.addView(rowDot, new LayoutParams(NeonUi.dp(c, 8), NeonUi.dp(c, 8)));

            TextView key = new TextView(c);
            String health = e.health.equals("healthy") ? "Healthy" : e.health.equals("limit") ? "Limit" : e.health.equals("rejected") ? "Rejected" : "Ready";
            if (e.restMs > 0) health += " • " + Math.max(1, e.restMs / 60000) + "m";
            key.setText(e.masked + "  ·  " + health);
            key.setTextSize(13.5f);
            key.setTextColor(NeonUi.TEXT);
            key.setTypeface(Typeface.MONOSPACE);
            key.setSingleLine(true);
            key.setContentDescription(e.masked + " — " + health);
            key.setPadding(NeonUi.dp(c, 10), 0, NeonUi.dp(c, 6), 0);
            row.addView(key, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            NeonUi.NeonButton del = new NeonUi.NeonButton(c, "Delete", NeonUi.RED);
            del.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) { handler.onDelete(e.key); }
            });
            row.addView(del, new LayoutParams(NeonUi.dp(c, 84), ViewGroup.LayoutParams.WRAP_CONTENT));

            LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = NeonUi.dp(c, 6);
            list.addView(row, lp);
        }
    }
}
