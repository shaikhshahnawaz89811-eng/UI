package com.neonhud.app.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.neonhud.app.core.module.ModuleAction;
import com.neonhud.app.core.module.ModuleSnapshot;
import com.neonhud.app.core.module.ModuleState;

/**
 * Settings: exactly one module card - Gemma 4 E2B / Offline AI Model. Every button mirrors the module's
 * real state ({@link ModuleSnapshot}); the actual protection lives in the module state machine.
 */
public final class SettingsPage extends FrameLayout {

    public interface Actions {
        void onBack();
        void onImport();
        void onLoad();
        void onUnload();
        void onDelete();
    }

    private final TextView status;
    private final View dot;
    private final TextView message;
    private final NeonUi.ThinProgress progress;
    private final NeonUi.NeonButton btnImport, btnLoad, btnUnload, btnDelete;
    private final LinearLayout moduleButtons;

    public SettingsPage(Context c, final Actions actions) {
        super(c);
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        addView(root, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- header: back + title
        LinearLayout header = new LinearLayout(c);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        NeonUi.IconView back = new NeonUi.IconView(c, NeonUi.IconView.BACK);
        back.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { actions.onBack(); } });
        header.addView(back, new LinearLayout.LayoutParams(NeonUi.dp(c, 44), NeonUi.dp(c, 40)));
        TextView title = new TextView(c);
        title.setText("Settings");
        title.setTextSize(20f);
        title.setTextColor(NeonUi.TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setShadowLayer(NeonUi.dp(c, 6), 0, 0, 0xAA38B6FF);
        header.addView(title);
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- scrollable body (tiny landscape screens)
        ScrollView scroll = new ScrollView(c);
        scroll.setFillViewport(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(NeonUi.glass(c, 14));
        int pad = NeonUi.dp(c, 14);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = NeonUi.dp(c, 6);
        clp.leftMargin = NeonUi.dp(c, 8);
        clp.rightMargin = NeonUi.dp(c, 8);
        clp.bottomMargin = NeonUi.dp(c, 6);
        FrameLayout scrollChild = new FrameLayout(c);
        scrollChild.addView(card, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.addView(scrollChild, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ((FrameLayout.LayoutParams) card.getLayoutParams()).setMargins(clp.leftMargin, clp.topMargin, clp.rightMargin, clp.bottomMargin);

        TextView name = new TextView(c);
        name.setText("Gemma 4 E2B");
        name.setTextSize(18f);
        name.setTextColor(NeonUi.TEXT);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(name);

        TextView sub = new TextView(c);
        sub.setText("Offline AI Model");
        sub.setTextSize(12.5f);
        sub.setTextColor(NeonUi.DIM);
        card.addView(sub);

        LinearLayout statusRow = new LinearLayout(c);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(0, NeonUi.dp(c, 10), 0, NeonUi.dp(c, 4));
        dot = new View(c);
        statusRow.addView(dot, new LinearLayout.LayoutParams(NeonUi.dp(c, 9), NeonUi.dp(c, 9)));
        status = new TextView(c);
        status.setTextSize(14f);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setPadding(NeonUi.dp(c, 8), 0, 0, 0);
        statusRow.addView(status);
        card.addView(statusRow);

        progress = new NeonUi.ThinProgress(c);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, NeonUi.dp(c, 5));
        plp.bottomMargin = NeonUi.dp(c, 6);
        card.addView(progress, plp);

        message = new TextView(c);
        message.setTextSize(12f);
        message.setTextColor(NeonUi.AMBER);
        message.setPadding(0, 0, 0, NeonUi.dp(c, 6));
        card.addView(message);

        moduleButtons = new LinearLayout(c);
        moduleButtons.setOrientation(LinearLayout.HORIZONTAL);
        btnLoad = button(c, "Load", NeonUi.CYAN, moduleButtons);
        btnUnload = button(c, "Unload", NeonUi.BLUE, moduleButtons);
        btnDelete = button(c, "Delete", NeonUi.RED, moduleButtons);
        card.addView(moduleButtons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        btnImport = new NeonUi.NeonButton(c, "Import", NeonUi.CYAN);
        card.addView(btnImport, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        btnImport.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { actions.onImport(); } });
        btnLoad.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { actions.onLoad(); } });
        btnUnload.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { actions.onUnload(); } });
        btnDelete.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { actions.onDelete(); } });
    }

    private NeonUi.NeonButton button(Context c, String label, int accent, LinearLayout parent) {
        NeonUi.NeonButton b = new NeonUi.NeonButton(c, label, accent);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        int m = NeonUi.dp(c, 3);
        lp.setMargins(m, 0, m, 0);
        parent.addView(b, lp);
        return b;
    }

    /** Buttons and texts are derived ONLY from the snapshot. */
    public void bind(ModuleSnapshot s) {
        boolean notImported = s.state == ModuleState.NOT_IMPORTED;
        // Before import: only Import. After import: Load / Unload / Delete (no Import).
        btnImport.setVisibility(notImported ? View.VISIBLE : View.GONE);
        moduleButtons.setVisibility(notImported ? View.GONE : View.VISIBLE);
        btnImport.setEnabled(s.canImport);
        btnLoad.setEnabled(s.canLoad);
        btnUnload.setEnabled(s.canUnload);
        btnDelete.setEnabled(s.canDelete);

        String text;
        int color;
        if (s.inFlight == ModuleAction.IMPORT) {
            text = "Importing\u2026 " + Math.max(0, s.importPercent) + "%"; color = NeonUi.CYAN;
        } else if (s.inFlight == ModuleAction.LOAD) {
            text = "Loading\u2026"; color = NeonUi.CYAN;
        } else if (s.inFlight == ModuleAction.UNLOAD) {
            text = "Unloading\u2026"; color = NeonUi.CYAN;
        } else if (s.inFlight == ModuleAction.DELETE) {
            text = "Deleting\u2026"; color = NeonUi.RED;
        } else {
            switch (s.state) {
                case NOT_IMPORTED: text = "Not Imported"; color = NeonUi.DIM; break;
                case IMPORTED: text = "Imported / Ready"; color = NeonUi.CYAN; break;
                case LOADED: text = "Loaded"; color = NeonUi.GREEN; break;
                default: text = "Unloaded"; color = NeonUi.AMBER; break;
            }
        }
        status.setText(text);
        status.setTextColor(color);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        dot.setBackground(d);

        boolean importing = s.inFlight == ModuleAction.IMPORT;
        progress.setVisibility(importing ? View.VISIBLE : View.GONE);
        if (importing) progress.setValue(s.importPercent);

        String msg = s.message;
        if (msg.isEmpty() && s.replyActive) msg = "A reply is being written - Unload is locked until it finishes.";
        message.setText(msg);
        message.setVisibility(msg.isEmpty() ? View.GONE : View.VISIBLE);
    }
}
