package com.neonhud.app.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.neonhud.app.core.chat.ChatController;
import com.neonhud.app.core.coder.CoderSpec;
import com.neonhud.app.core.module.ModuleState;

import java.util.ArrayList;
import java.util.List;

/**
 * The chat screen: conversation centred with free space on both sides, AI text shown as plain glowing text
 * (no card), user text in a glass bubble, input with a 4-line limit that scrolls, and smart auto-scroll that
 * follows new text only while the user is at the bottom.
 */
public final class ChatPage extends FrameLayout {

    public interface SendHandler { void onSend(String text); }

    /** Kept for later: the model switch button was removed from the input bar for now. */
    public interface ModeHandler { void onSwitch(); }

    public static final int MODE_GEMMA = 0, MODE_CODER = 1;

    private final ListView list;
    private final EditText input;
    private final NeonUi.IconView send;
    private final LinearLayout emptyBox;
    private final TextView emptyHint;
    private final TextView emptyTitle, emptySub;
    private final NeonUi.IconView plus;
    private final Adapter adapter = new Adapter();

    private SendHandler handler;
    private ModeHandler modeHandler;
    private int mode = MODE_GEMMA;
    private List<ChatController.Item> items = new ArrayList<ChatController.Item>();
    private boolean generating;
    private boolean stick = true;
    private int scrollState = AbsListView.OnScrollListener.SCROLL_STATE_IDLE;

    public ChatPage(Context c) {
        super(c);
        final int side = NeonUi.dp(c, 12);

        LinearLayout column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        addView(column, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout listWrap = new FrameLayout(c);
        column.addView(listWrap, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        list = new ListView(c);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setClipToPadding(true);
        list.setPadding(side, NeonUi.dp(c, 5), side, NeonUi.dp(c, 5));
        list.setAdapter(adapter);
        list.setTranscriptMode(ListView.TRANSCRIPT_MODE_DISABLED);
        listWrap.addView(list, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- empty state (mirrors the reference screen: title + subtitle)
        emptyBox = new LinearLayout(c);
        emptyBox.setOrientation(LinearLayout.VERTICAL);
        emptyBox.setGravity(Gravity.CENTER);
        TextView title = new TextView(c);
        emptyTitle = title;
        title.setText("Gemma 4 E2B");
        title.setTextSize(24f);
        title.setTextColor(NeonUi.TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setShadowLayer(NeonUi.dp(c, 8), 0, 0, 0xCC38B6FF);
        TextView sub = new TextView(c);
        emptySub = sub;
        sub.setText("OFFLINE AI ASSISTANT");
        sub.setTextSize(11f);
        sub.setLetterSpacing(0.18f);
        sub.setTextColor(NeonUi.CYAN);
        sub.setGravity(Gravity.CENTER);
        emptyHint = new TextView(c);
        emptyHint.setTextSize(12f);
        emptyHint.setTextColor(NeonUi.DIM);
        emptyHint.setGravity(Gravity.CENTER);
        emptyHint.setPadding(0, NeonUi.dp(c, 8), 0, 0);
        emptyBox.addView(title);
        emptyBox.addView(sub);
        emptyBox.addView(emptyHint);
        emptyBox.setPadding(NeonUi.dp(c, 12), 0, NeonUi.dp(c, 12), 0);
        listWrap.addView(emptyBox, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- input bar
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.BOTTOM);
        bar.setPadding(side, NeonUi.dp(c, 4), side, NeonUi.dp(c, 2));
        column.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // one long glass bar like the reference: [ + ] [ Type your message... ] [ send ]
        LinearLayout pill = new LinearLayout(c);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.BOTTOM);
        pill.setBackground(NeonUi.glass(c, 11, 0xCC38B6FF, 0x55123C7A, 0x440A2250));
        pill.setPadding(NeonUi.dp(c, 3), NeonUi.dp(c, 3), NeonUi.dp(c, 3), NeonUi.dp(c, 3));
        bar.addView(pill, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        plus = new NeonUi.IconView(c, NeonUi.IconView.PLUS);
        plus.setContentDescription("Add");
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(NeonUi.dp(c, 34), NeonUi.dp(c, 34));
        plp.rightMargin = NeonUi.dp(c, 5);
        pill.addView(plus, plp);

        input = new EditText(c);
        input.setHint("Type your message...");
        input.setHintTextColor(0xFF8FC4EE);
        input.setTextColor(NeonUi.TEXT);
        input.setTextSize(14.5f);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(ChatController.MAX_INPUT_CHARS)});
        input.setMinLines(1);
        input.setMinHeight(NeonUi.dp(c, 34));       // same height as the + box and the send icon
        input.setMaxLines(4);                       // line limit: grows to 4 lines, then scrolls inside
        input.setHorizontallyScrolling(false);
        input.setVerticalScrollBarEnabled(true);
        input.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        input.setBackground(NeonUi.glass(c, 9, 0x3338B6FF, 0x99051A40, 0x99041238));   // darker field inside the bar
        input.setPadding(NeonUi.dp(c, 12), NeonUi.dp(c, 6), NeonUi.dp(c, 12), NeonUi.dp(c, 6));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pill.addView(input, ilp);

        send = new NeonUi.IconView(c, NeonUi.IconView.SEND);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(NeonUi.dp(c, 48), ViewGroup.LayoutParams.MATCH_PARENT);   // tap area = full bar height
        slp.leftMargin = NeonUi.dp(c, 2);
        pill.addView(send, slp);

        wire();
        refreshSendEnabled();
    }

    private void wire() {
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) { refreshSendEnabled(); }
        });
        input.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) { if (hasFocus) { stick = true; scrollToBottomSoon(); } }
        });
        input.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { stick = true; scrollToBottomSoon(); }
        });
        send.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { submit(); }
        });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int state) {
                scrollState = state;
                if (state == SCROLL_STATE_IDLE) stick = isAtBottom();
            }
            @Override public void onScroll(AbsListView v, int first, int visible, int total) {
                // while the finger is dragging, the user's position wins over auto-follow
                if (scrollState == SCROLL_STATE_TOUCH_SCROLL) stick = isAtBottom();
            }
        });
        list.addOnLayoutChangeListener(new OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (stick && (b - t) != (ob - ot)) scrollToBottomSoon();   // keyboard opened / closed
            }
        });
    }

    public void setSendHandler(SendHandler h) { handler = h; }
    public void setModeHandler(ModeHandler h) { modeHandler = h; }

    private void submit() {
        String text = input.getText().toString().trim();
        if (text.isEmpty() || generating || handler == null) return;
        handler.onSend(text);
    }

    /** Called by the activity after the message was accepted. */
    public void clearInput() {
        input.setText("");
        stick = true;
    }

    public void focusInput() { input.requestFocus(); }

    private void refreshSendEnabled() {
        boolean on = !generating && input.getText().toString().trim().length() > 0;
        if (send.isEnabled() != on) { send.setEnabled(on); }
        send.invalidate();
    }

    // ------------------------------------------------------------------ data

    /** @param newMode MODE_GEMMA or MODE_CODER: which chat (and which model's texts) this page shows. */
    public void bind(List<ChatController.Item> newItems, boolean isGenerating, ModuleState moduleState, int newMode) {
        boolean wasEmpty = items.isEmpty();
        boolean modeChanged = newMode != mode;
        mode = newMode;
        items = newItems;
        generating = isGenerating;
        boolean coder = mode == MODE_CODER;
        emptyTitle.setText(coder ? CoderSpec.DISPLAY_NAME : "Gemma 4 E2B");
        emptyTitle.setTextSize(coder ? 17f : 24f);
        emptySub.setText(coder ? "OFFLINE CODING ASSISTANT" : "OFFLINE AI ASSISTANT");
        input.setHint(coder ? "Ask a coding question..." : "Type your message...");
        emptyBox.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        emptyHint.setText(moduleState == ModuleState.LOADED
                ? "Type your message below."
                : "Open Settings \u2699 to import and load " + (coder ? CoderSpec.DISPLAY_NAME : "Gemma 4 E2B") + ".");
        adapter.notifyDataSetChanged();
        refreshSendEnabled();
        if (modeChanged) { stick = true; list.setSelection(Math.max(0, adapter.getCount() - 1)); }
        if (stick || wasEmpty || modeChanged) scrollToBottomSoon();
    }

    // ------------------------------------------------------------------ scrolling

    private boolean isAtBottom() {
        int n = adapter.getCount();
        if (n == 0) return true;
        if (list.getLastVisiblePosition() < n - 1) return false;
        View last = list.getChildAt(list.getChildCount() - 1);
        if (last == null) return true;
        int slack = NeonUi.dp(getContext(), 24);
        return last.getBottom() <= list.getHeight() - list.getPaddingBottom() + slack;
    }

    private void scrollToBottomSoon() {
        post(new Runnable() {
            @Override public void run() { scrollToBottom(); }
        });
    }

    private void scrollToBottom() {
        final int n = adapter.getCount();
        if (n == 0) return;
        final int last = n - 1;
        if (list.getLastVisiblePosition() < last) list.setSelection(last);
        list.post(new Runnable() {
            @Override public void run() {
                View lastChild = list.getChildAt(list.getChildCount() - 1);
                if (lastChild == null || list.getLastVisiblePosition() != last) return;
                int over = lastChild.getBottom() - (list.getHeight() - list.getPaddingBottom());
                if (over > 0) list.scrollListBy(over);   // long reply: show its newest lines, not its first
            }
        });
    }

    // ------------------------------------------------------------------ adapter

    private final class Adapter extends BaseAdapter {
        private static final int USER = 0, AI = 1, NOTICE = 2;

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return items.get(i).key; }
        @Override public boolean hasStableIds() { return true; }
        @Override public int getViewTypeCount() { return 3; }
        @Override public boolean isEnabled(int position) { return false; }

        @Override public int getItemViewType(int i) {
            switch (items.get(i).kind) {
                case USER: return USER;
                case AI: return AI;
                default: return NOTICE;
            }
        }

        @Override public View getView(int position, View convert, ViewGroup parent) {
            Context c = parent.getContext();
            ChatController.Item it = items.get(position);
            int type = getItemViewType(position);
            FrameLayout row;
            TextView tv;
            if (convert == null) {
                row = new FrameLayout(c);
                tv = new TextView(c);
                row.addView(tv);
                row.setTag(tv);
                styleFor(c, tv, type);
            } else {
                row = (FrameLayout) convert;
                tv = (TextView) row.getTag();
            }
            int listW = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();

            // Keep the conversation visually inside the HUD instead of letting AI text touch both sides.
            // The frame is fixed; only this inner list scrolls.
            float widthFraction = type == USER ? 0.78f : (type == AI ? 0.88f : 0.82f);
            int maxW = listW > 0 ? Math.round(listW * widthFraction) : ViewGroup.LayoutParams.WRAP_CONTENT;
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = type == USER ? Gravity.END : (type == NOTICE ? Gravity.CENTER_HORIZONTAL : Gravity.START);
            lp.topMargin = NeonUi.dp(c, type == NOTICE ? 4 : 7);
            lp.bottomMargin = NeonUi.dp(c, type == NOTICE ? 4 : 7);
            tv.setLayoutParams(lp);
            if (maxW > 0) tv.setMaxWidth(maxW);
            tv.setMinWidth(0);

            if (type == AI && it.text.isEmpty()) {
                tv.setText("\u2026");
            } else {
                tv.setText(it.text);
            }
            return row;
        }

        private void styleFor(Context c, TextView tv, int type) {
            if (type == USER) {
                tv.setTextColor(NeonUi.TEXT);
                tv.setTextSize(15f);
                tv.setBackground(NeonUi.glass(c, 16, 0xAA38D6FF, 0x4A3C9BE6, 0x26205FA8));
                tv.setPadding(NeonUi.dp(c, 14), NeonUi.dp(c, 8), NeonUi.dp(c, 14), NeonUi.dp(c, 8));
                tv.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            } else if (type == AI) {
                // plain text, no card - lit like glass with a soft blue glow
                tv.setTextColor(0xFFF2FBFF);
                tv.setTextSize(15.5f);
                tv.setLineSpacing(0f, 1.20f);
                tv.setShadowLayer(NeonUi.dp(c, 7), 0, 0, 0xAA2FA8FF);
                // AI remains plain text (no card), but has breathing room on both sides.
                tv.setPadding(NeonUi.dp(c, 2), NeonUi.dp(c, 2), NeonUi.dp(c, 2), NeonUi.dp(c, 2));
                tv.setGravity(Gravity.START);
            } else {
                tv.setTextColor(NeonUi.AMBER);
                tv.setTextSize(12.5f);
                tv.setGravity(Gravity.CENTER);
                tv.setPadding(NeonUi.dp(c, 10), NeonUi.dp(c, 6), NeonUi.dp(c, 10), NeonUi.dp(c, 6));
            }
        }
    }
}
