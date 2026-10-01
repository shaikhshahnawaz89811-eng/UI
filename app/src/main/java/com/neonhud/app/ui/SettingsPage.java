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

import com.neonhud.app.core.coder.CoderSpec;
import com.neonhud.app.core.module.ModuleAction;
import com.neonhud.app.core.module.ModuleSnapshot;
import com.neonhud.app.core.module.ModuleState;
import com.neonhud.app.core.module.ModelRuntimeCoordinator;
import com.neonhud.app.core.search.TavilySnapshot;
import com.neonhud.app.core.web.WebMode;

/**
 * Settings: one module card per offline model - Gemma 4 E2B (Offline AI Model) and the Qwen2.5-Coder coding model -
 * plus a Tavily API key card (Add / Delete).
 * Models are imported independently, but RAM is controlled automatically: Gemma is the idle/default model and
 * Qwen Coder is loaded only for coding work. At most one model may be resident at a time.
 */
public final class SettingsPage extends FrameLayout {

    public static final int GEMMA = 0, CODER = 1;

    public interface Actions {
        void onBack();
        void onImport(int module);
        void onDelete(int module);
        void onAddTavilyKey(String key);
        void onDeleteTavilyKey(String key);
        void onWebMode(WebMode mode);
    }

    /** One module card (title, status line, progress, import and delete). Loading is runtime-controlled. */
    private static final class Card {
        final LinearLayout view;
        final TextView status;
        final View dot;
        final TextView message;
        final NeonUi.ThinProgress progress;
        final NeonUi.NeonButton btnImport, btnDelete;
        final LinearLayout moduleButtons;

        Card(Context c, String title, String subtitle, final int module, final Actions actions) {
            view = new LinearLayout(c);
            view.setOrientation(LinearLayout.VERTICAL);
            view.setBackground(NeonUi.glass(c, 14));
            int pad = NeonUi.dp(c, 14);
            view.setPadding(pad, pad, pad, pad);

            TextView name = new TextView(c);
            name.setText(title);
            name.setTextSize(18f);
            name.setTextColor(NeonUi.TEXT);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            view.addView(name);

            TextView sub = new TextView(c);
            sub.setText(subtitle);
            sub.setTextSize(12.5f);
            sub.setTextColor(NeonUi.DIM);
            view.addView(sub);

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
            view.addView(statusRow);

            progress = new NeonUi.ThinProgress(c);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, NeonUi.dp(c, 5));
            plp.bottomMargin = NeonUi.dp(c, 6);
            view.addView(progress, plp);

            message = new TextView(c);
            message.setTextSize(12f);
            message.setTextColor(NeonUi.AMBER);
            message.setPadding(0, 0, 0, NeonUi.dp(c, 6));
            view.addView(message);

            moduleButtons = new LinearLayout(c);
            moduleButtons.setOrientation(LinearLayout.HORIZONTAL);
            btnDelete = button(c, "Delete", NeonUi.RED, moduleButtons);
            view.addView(moduleButtons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            btnImport = new NeonUi.NeonButton(c, "Import", NeonUi.CYAN);
            view.addView(btnImport, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            btnImport.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { actions.onImport(module); } });
            btnDelete.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { actions.onDelete(module); } });
        }

        private static NeonUi.NeonButton button(Context c, String label, int accent, LinearLayout parent) {
            NeonUi.NeonButton b = new NeonUi.NeonButton(c, label, accent);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            int m = NeonUi.dp(c, 3);
            lp.setMargins(m, 0, m, 0);
            parent.addView(b, lp);
            return b;
        }

        /** Buttons and texts are derived ONLY from the snapshot. */
        void bind(ModuleSnapshot s) {
            boolean notImported = s.state == ModuleState.NOT_IMPORTED;
            // Before import: only Import. After import: Delete remains available when the model is safely offline.
            btnImport.setVisibility(notImported ? View.VISIBLE : View.GONE);
            moduleButtons.setVisibility(notImported ? View.GONE : View.VISIBLE);
            btnImport.setEnabled(s.canImport);
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
                    case LOADED: text = "Active in RAM"; color = NeonUi.GREEN; break;
                    default: text = "Imported / Offline"; color = NeonUi.AMBER; break;
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
            if (msg.isEmpty() && s.replyActive) msg = "Reply is running; model stays in RAM until the task finishes.";
            message.setText(msg);
            message.setVisibility(msg.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    private final Card gemmaCard, coderCard;
    private final TextView runtimeStatus;
    private final TavilyKeysCard tavilyCard;
    private final WebSearchCard webCard;

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

        runtimeStatus = new TextView(c);
        runtimeStatus.setTextSize(12.5f);
        runtimeStatus.setTextColor(NeonUi.DIM);
        runtimeStatus.setPadding(NeonUi.dp(c, 14), NeonUi.dp(c, 5), NeonUi.dp(c, 14), NeonUi.dp(c, 5));
        root.addView(runtimeStatus, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- scrollable body (tiny landscape screens): the two module cards one under the other
        ScrollView scroll = new ScrollView(c);
        scroll.setFillViewport(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout cards = new LinearLayout(c);
        cards.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(cards, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        gemmaCard = new Card(c, "Gemma 4 E2B", "Offline AI Model", GEMMA, actions);
        coderCard = new Card(c, CoderSpec.DISPLAY_NAME, CoderSpec.SUBTITLE, CODER, actions);
        addCard(c, cards, gemmaCard);
        addCard(c, cards, coderCard);

        // web search switch (Auto / Always / Off), then the Tavily keys it uses
        webCard = new WebSearchCard(c, new WebSearchCard.Handler() {
            @Override public void onMode(WebMode mode) { actions.onWebMode(mode); }
        });
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.setMargins(NeonUi.dp(c, 8), NeonUi.dp(c, 6), NeonUi.dp(c, 8), NeonUi.dp(c, 6));
        cards.addView(webCard, wlp);

        // Tavily API keys (Add tests the key with Tavily, Delete removes it)
        tavilyCard = new TavilyKeysCard(c, new TavilyKeysCard.Handler() {
            @Override public void onAdd(String key) { actions.onAddTavilyKey(key); }
            @Override public void onDelete(String key) { actions.onDeleteTavilyKey(key); }
        });
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.setMargins(NeonUi.dp(c, 8), NeonUi.dp(c, 6), NeonUi.dp(c, 8), NeonUi.dp(c, 6));
        cards.addView(tavilyCard, tlp);
    }

    private static void addCard(Context c, LinearLayout parent, Card card) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(NeonUi.dp(c, 8), NeonUi.dp(c, 6), NeonUi.dp(c, 8), NeonUi.dp(c, 6));
        parent.addView(card.view, lp);
    }

    public void bind(ModuleSnapshot gemma, ModuleSnapshot coder, TavilySnapshot tavily, WebMode webMode,
                     ModelRuntimeCoordinator.RuntimeSnapshot runtime) {
        gemmaCard.bind(gemma);
        coderCard.bind(coder);
        if (runtime.transitioning) runtimeStatus.setText(runtime.status.isEmpty() ? "Switching offline model…" : runtime.status);
        else if (runtime.active == ModelRuntimeCoordinator.Target.CODER) runtimeStatus.setText("RAM: Qwen Coder • Gemma is offline");
        else if (runtime.active == ModelRuntimeCoordinator.Target.GEMMA) runtimeStatus.setText("RAM: Gemma • Qwen Coder is offline");
        else runtimeStatus.setText("RAM: no model loaded • import both models; Gemma loads automatically");
        tavilyCard.bind(tavily);
        webCard.bind(webMode, tavily.entries.size());
    }
}
