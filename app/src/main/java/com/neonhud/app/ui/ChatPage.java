package com.neonhud.app.ui;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.net.Uri;
import android.view.Gravity;
import android.view.inputmethod.InputMethodManager;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import com.neonhud.app.android.WebImageLoader;
import com.neonhud.app.core.chat.ChatController;
import com.neonhud.app.core.coder.CoderSpec;
import com.neonhud.app.core.engine.Attachment;
import com.neonhud.app.core.engine.AttachmentSendGate;
import com.neonhud.app.core.module.ModuleState;
import com.neonhud.app.core.web.UrlTools;
import com.neonhud.app.core.web.WebLink;
import com.neonhud.app.core.web.WebPic;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The chat screen: conversation centred with free space on both sides, AI text shown as plain glowing text
 * (no card), user text in a glass bubble, input with a 4-line limit that scrolls, and smart auto-scroll that
 * follows new text only while the user is at the bottom.
 */
public final class ChatPage extends FrameLayout {

    public interface SendHandler { void onSend(String text, List<Attachment> files); }

    /** The user chose a source in the attachment cart; the Activity opens the camera / a file picker. */
    public interface AttachHandler { void onPick(int kind); }

    public static final int ATTACH_CAMERA = AttachIcon.CAMERA, ATTACH_IMAGE = AttachIcon.IMAGE,
            ATTACH_ZIP = AttachIcon.ZIP;

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
    private final AttachCart cart;
    private final LinearLayout pendingPanel;
    private final LinearLayout pendingList;
    private final AttachIcon clearAll;
    private final List<Attachment> pending = new ArrayList<Attachment>();
    private final AttachmentSendGate attachmentGate = new AttachmentSendGate();
    private boolean attachEnabled = true;
    private AttachHandler attachHandler;

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

        // ---- attachment cart: opens over the bottom of the chat, just above the input bar
        cart = new AttachCart(c, new AttachCart.Listener() {
            @Override public void onPick(int kind) {
                cart.hide();
                if (attachHandler != null) attachHandler.onPick(kind);
            }
            @Override public void onClose() { cart.hide(); }
        });
        LayoutParams cartLp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        cartLp.leftMargin = side;
        cartLp.rightMargin = side;
        cartLp.bottomMargin = NeonUi.dp(c, 4);
        listWrap.addView(cart, cartLp);

        // ---- attached files waiting to be sent (between the chat and the input bar; scrolls after ~2 rows)
        pendingPanel = new LinearLayout(c);
        pendingPanel.setOrientation(LinearLayout.VERTICAL);
        pendingPanel.setBackground(NeonUi.glass(c, 14, 0xAA38B6FF, 0x55123C7A, 0x440A2250));
        pendingPanel.setVisibility(View.GONE);
        clearAll = new AttachIcon(c, AttachIcon.CLOSE);
        clearAll.setContentDescription("Remove all files");
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(NeonUi.dp(c, 22), NeonUi.dp(c, 22));
        clp.gravity = Gravity.END;
        clp.topMargin = NeonUi.dp(c, 3);
        clp.rightMargin = NeonUi.dp(c, 6);
        pendingPanel.addView(clearAll, clp);
        pendingList = new LinearLayout(c);
        pendingList.setOrientation(LinearLayout.VERTICAL);
        MaxHeightScroll pendingScroll = new MaxHeightScroll(c, NeonUi.dp(c, 112));
        pendingScroll.addView(pendingList, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pendingPanel.addView(pendingScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pendingPanel.setPadding(NeonUi.dp(c, 6), 0, NeonUi.dp(c, 6), NeonUi.dp(c, 5));
        LinearLayout.LayoutParams ppLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ppLp.leftMargin = side;
        ppLp.rightMargin = side;
        ppLp.topMargin = NeonUi.dp(c, 4);
        column.addView(pendingPanel, ppLp);

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
        plus.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { toggleCart(); }
        });
        clearAll.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { pending.clear(); rebuildPending(); refreshSendEnabled(); }
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
    public void setAttachHandler(AttachHandler h) { attachHandler = h; }

    private void submit() {
        String text = input.getText().toString().trim();
        if (!attachmentGate.canSend(generating, !text.isEmpty(), !pending.isEmpty(), handler != null)) return;
        handler.onSend(text, new ArrayList<Attachment>(pending));
    }

    /** Blocks send/picker actions while an attachment provider is still being validated or materialized. */
    public void setAttachmentBusy(boolean busy) {
        if (busy) attachmentGate.beginValidation(); else attachmentGate.endValidation();
        boolean enabled = !busy && attachEnabled;
        plus.setEnabled(enabled);
        plus.setAlpha(enabled ? 1f : 0.35f);
        if (busy) cart.hide();
        refreshSendEnabled();
    }

    /** Called by the activity after the message was accepted: empties the box, the waiting files and the cart. */
    public void clearInput() {
        input.setText("");
        pending.clear();
        rebuildPending();
        cart.hide();
        stick = true;
    }

    // ------------------------------------------------------------------ attachments

    private void toggleCart() {
        if (!attachEnabled || attachmentGate.isBusy()) return;
        if (cart.isOpen()) { cart.hide(); return; }
        input.clearFocus();
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);     // the keyboard would cover the cart
        cart.show();
    }

    /** Adds files chosen by the Activity. @return how many were added (duplicates and anything over the limit are skipped). */
    public int addAttachments(List<Attachment> files) {
        int added = 0;
        for (Attachment a : files) {
            if (pending.size() >= ChatController.MAX_ATTACHMENTS) break;
            boolean dup = false;
            for (Attachment p : pending) if (p.uri.equals(a.uri)) { dup = true; break; }
            if (dup) continue;
            pending.add(a);
            added++;
        }
        rebuildPending();
        refreshSendEnabled();
        return added;
    }

    private void rebuildPending() {
        Context c = getContext();
        pendingList.removeAllViews();
        for (final Attachment a : new ArrayList<Attachment>(pending)) {
            View card = AttachViews.card(c, a, new OnClickListener() {
                @Override public void onClick(View v) { pending.remove(a); rebuildPending(); refreshSendEnabled(); }
            });
            if (a.kind == Attachment.Kind.IMAGE) {
                card.setClickable(true);
                card.setOnClickListener(new OnClickListener() {
                    @Override public void onClick(View v) { showImagePreview(getContext(), a); }
                });
            } else if (a.kind == Attachment.Kind.VIDEO) {
                card.setClickable(true);
                card.setOnClickListener(new OnClickListener() {
                    @Override public void onClick(View v) { openAttachment(getContext(), a); }
                });
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = NeonUi.dp(c, 5);
            pendingList.addView(card, lp);
        }
        pendingPanel.setVisibility(pending.isEmpty() ? View.GONE : View.VISIBLE);
        clearAll.setVisibility(pending.size() > 1 ? View.VISIBLE : View.GONE);        // one file has its own x
        pendingPanel.setPadding(NeonUi.dp(c, 6), pending.size() > 1 ? 0 : NeonUi.dp(c, 6), NeonUi.dp(c, 6), NeonUi.dp(c, 1));
    }

    /** A ScrollView that never grows past maxHeight, so a few files cannot push the chat off the small HUD. */
    private static final class MaxHeightScroll extends ScrollView {
        private final int maxHeight;
        MaxHeightScroll(Context c, int maxHeight) { super(c); this.maxHeight = maxHeight; setOverScrollMode(OVER_SCROLL_NEVER); }
        @Override protected void onMeasure(int w, int h) {
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST));
        }
    }

    public void focusInput() { input.requestFocus(); }

    private void refreshSendEnabled() {
        boolean on = attachmentGate.canSend(generating,
                input.getText().toString().trim().length() > 0, !pending.isEmpty(), handler != null);
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
        attachEnabled = !coder;                 // the coding model reads text only
        boolean plusOn = attachEnabled && !attachmentGate.isBusy();
        plus.setEnabled(plusOn);
        plus.setAlpha(plusOn ? 1f : 0.35f);
        if (!attachEnabled || attachmentGate.isBusy()) cart.hide();
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
            if (type == USER) return userRow(c, it, convert, parent);
            if (type == AI) return aiRow(c, it, convert, parent);
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
            float widthFraction = 0.82f;
            int maxW = listW > 0 ? Math.round(listW * widthFraction) : ViewGroup.LayoutParams.WRAP_CONTENT;
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            lp.topMargin = NeonUi.dp(c, 4);
            lp.bottomMargin = NeonUi.dp(c, 4);
            tv.setLayoutParams(lp);
            if (maxW > 0) tv.setMaxWidth(maxW);
            tv.setMinWidth(0);

            tv.setText(it.text);
            return row;
        }

        /** The AI reply: glowing plain text, then (once finished) the pages and pictures of a web answer. */
        private View aiRow(Context c, ChatController.Item it, View convert, ViewGroup parent) {
            FrameLayout row;
            AiBlock block;
            if (convert == null) {
                row = new FrameLayout(c);
                block = new AiBlock(c);
                row.addView(block);
                row.setTag(block);
            } else {
                row = (FrameLayout) convert;
                block = (AiBlock) row.getTag();
            }
            int listW = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
            int maxW = listW > 0 ? Math.round(listW * 0.88f) : NeonUi.dp(c, 420);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.START;
            lp.topMargin = NeonUi.dp(c, 7);
            lp.bottomMargin = NeonUi.dp(c, 7);
            block.setLayoutParams(lp);
            block.bind(it, maxW);
            return row;
        }

        /** The user's message: text, then its file cards (up to 3 per row), then the send time with two ticks. */
        private View userRow(Context c, ChatController.Item it, View convert, ViewGroup parent) {
            FrameLayout row;
            UserBubble bubble;
            if (convert == null) {
                row = new FrameLayout(c);
                bubble = new UserBubble(c);
                row.addView(bubble);
                row.setTag(bubble);
            } else {
                row = (FrameLayout) convert;
                bubble = (UserBubble) row.getTag();
            }
            int listW = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
            int maxW = listW > 0 ? Math.round(listW * 0.78f) : NeonUi.dp(c, 300);
            int perRow = Math.min(3, it.attachments.size());
            int width = perRow == 0 ? ViewGroup.LayoutParams.WRAP_CONTENT
                    : Math.min(maxW, NeonUi.dp(c, 150) * perRow + NeonUi.dp(c, 28));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.END;
            lp.topMargin = NeonUi.dp(c, 7);
            lp.bottomMargin = NeonUi.dp(c, 7);
            bubble.setLayoutParams(lp);
            bubble.bind(it, (width > 0 ? width : maxW) - NeonUi.dp(c, 28));
            return row;
        }

        private void styleFor(Context c, TextView tv, int type) {
            tv.setTextColor(NeonUi.AMBER);
            tv.setTextSize(12.5f);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(NeonUi.dp(c, 10), NeonUi.dp(c, 6), NeonUi.dp(c, 10), NeonUi.dp(c, 6));
        }
    }

    // ------------------------------------------------------------------ AI reply block (text + links + pictures)

    private static final class AiBlock extends LinearLayout {
        private final TextView text;
        private final LinearLayout linkBox;
        private final LinearLayout picBox;
        private final LinearLayout fileBox;
        private List<WebLink> shownLinks;
        private List<WebPic> shownPics;
        private int shownWidth = -1;

        AiBlock(Context c) {
            super(c);
            setOrientation(VERTICAL);
            text = new TextView(c);
            // plain text, no card - lit like glass with a soft blue glow
            text.setTextSize(15.5f);
            text.setLineSpacing(0f, 1.20f);
            text.setShadowLayer(NeonUi.dp(c, 7), 0, 0, 0xAA2FA8FF);
            text.setPadding(NeonUi.dp(c, 2), NeonUi.dp(c, 2), NeonUi.dp(c, 2), NeonUi.dp(c, 2));
            text.setGravity(Gravity.START);
            addView(text, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            fileBox = new LinearLayout(c);
            fileBox.setOrientation(VERTICAL);
            fileBox.setVisibility(GONE);
            LayoutParams flp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            flp.topMargin = NeonUi.dp(c, 6);
            addView(fileBox, flp);

            picBox = new LinearLayout(c);
            picBox.setOrientation(HORIZONTAL);
            picBox.setVisibility(GONE);
            LayoutParams plp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.topMargin = NeonUi.dp(c, 6);
            addView(picBox, plp);

            linkBox = new LinearLayout(c);
            linkBox.setOrientation(VERTICAL);
            linkBox.setVisibility(GONE);
            LayoutParams llp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.topMargin = NeonUi.dp(c, 4);
            addView(linkBox, llp);
        }

        void bind(ChatController.Item it, int maxW) {
            Context c = getContext();
            text.setMaxWidth(maxW);
            if (it.text.isEmpty()) {
                // nothing written yet: show what the app is doing (e.g. "Searching the web...") or a plain ellipsis
                boolean busy = !it.status.isEmpty();
                text.setText(busy ? it.status : "\u2026");
                text.setTextColor(busy ? NeonUi.CYAN : 0xFFF2FBFF);
                text.setTypeface(Typeface.DEFAULT, busy ? Typeface.ITALIC : Typeface.NORMAL);
            } else {
                text.setText(it.text);
                text.setTextColor(0xFFF2FBFF);
                text.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
            }
            fileBox.removeAllViews();
            if (it.outputFiles.isEmpty()) {
                fileBox.setVisibility(GONE);
            } else {
                fileBox.setVisibility(VISIBLE);
                for (final Attachment file : it.outputFiles) {
                    View card = AttachViews.outputCard(c, file, new OnClickListener() {
                        @Override public void onClick(View v) { openAttachment(getContext(), file); }
                    }, new OnClickListener() {
                        @Override public void onClick(View v) { shareAttachment(getContext(), file); }
                    });
                    LayoutParams fp = new LayoutParams(Math.min(maxW, NeonUi.dp(c, 520)), ViewGroup.LayoutParams.WRAP_CONTENT);
                    fp.bottomMargin = NeonUi.dp(c, 4);
                    fileBox.addView(card, fp);
                }
            }

            // rebuilt only when the lists (or the width) really changed - not on every streamed word
            if (it.pics != shownPics || maxW != shownWidth) { shownPics = it.pics; buildPics(c, it.pics, maxW); }
            if (it.links != shownLinks || maxW != shownWidth) { shownLinks = it.links; buildLinks(c, it.links, maxW); }
            shownWidth = maxW;
        }

        private void buildPics(Context c, List<WebPic> pics, int maxW) {
            picBox.removeAllViews();
            if (pics.isEmpty()) { picBox.setVisibility(GONE); return; }
            picBox.setVisibility(VISIBLE);
            int gap = NeonUi.dp(c, 6);
            int n = pics.size();
            int w = Math.min(NeonUi.dp(c, 130), (maxW - gap * (n - 1)) / n);
            int h = Math.round(w * 0.68f);
            for (int i = 0; i < n; i++) {
                final WebPic p = pics.get(i);
                final ImageView iv = new ImageView(c);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackground(NeonUi.glass(c, 10));
                iv.setContentDescription(p.caption);
                iv.setTag(p.url);
                iv.setClickable(true);
                iv.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { open(v.getContext(), p.url); } });
                LayoutParams lp = new LayoutParams(w, h);
                if (i > 0) lp.leftMargin = gap;
                picBox.addView(iv, lp);
                WebImageLoader.get().load(p.url, Math.max(w, h) * 2, new WebImageLoader.Callback() {
                    @Override public void onLoaded(Bitmap b) { if (p.url.equals(iv.getTag())) iv.setImageBitmap(b); }
                    @Override public void onFailed() { if (p.url.equals(iv.getTag())) iv.setVisibility(View.INVISIBLE); }
                });
            }
        }

        private void buildLinks(Context c, List<WebLink> links, int maxW) {
            linkBox.removeAllViews();
            if (links.isEmpty()) { linkBox.setVisibility(GONE); return; }
            linkBox.setVisibility(VISIBLE);
            for (final WebLink l : links) {
                LinearLayout chip = new LinearLayout(c);
                chip.setOrientation(VERTICAL);
                chip.setBackground(NeonUi.glass(c, 10, 0x8838D6FF, 0x3A3C9BE6, 0x1A205FA8));
                chip.setPadding(NeonUi.dp(c, 10), NeonUi.dp(c, 6), NeonUi.dp(c, 10), NeonUi.dp(c, 6));
                chip.setClickable(true);
                chip.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { open(v.getContext(), l.url); } });

                TextView title = new TextView(c);
                title.setText(l.title.isEmpty() ? l.domain : l.title);
                title.setTextColor(NeonUi.CYAN);
                title.setTextSize(13.5f);
                title.setTypeface(Typeface.DEFAULT_BOLD);
                title.setSingleLine(true);
                title.setEllipsize(TextUtils.TruncateAt.END);
                chip.addView(title);

                TextView dom = new TextView(c);
                dom.setText(l.domain + "  \u2197");
                dom.setTextColor(NeonUi.DIM);
                dom.setTextSize(11.5f);
                dom.setSingleLine(true);
                dom.setEllipsize(TextUtils.TruncateAt.END);
                chip.addView(dom);

                LayoutParams lp = new LayoutParams(Math.min(maxW, NeonUi.dp(c, 360)), ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = NeonUi.dp(c, 4);
                linkBox.addView(chip, lp);
            }
        }

        /** Opens a page / picture in the browser. Only the addresses the search returned, and only normal public ones. */
        private static void open(Context c, String url) {
            if (!UrlTools.isSafe(url)) return;
            try {
                c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException ignored) {
            } catch (SecurityException ignored) {
            }
        }
    }

    private static void openAttachment(Context c, Attachment a) {
        if (a == null || a.uri == null || a.uri.trim().isEmpty()) return;
        try {
            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(a.uri));
            view.setType(mimeFor(a.kind));
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(view);
        } catch (ActivityNotFoundException ignored) {
        } catch (SecurityException ignored) {
        } catch (Throwable ignored) {
        }
    }

    private static void shareAttachment(Context c, Attachment a) {
        try {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType(mimeFor(a.kind));
            share.putExtra(Intent.EXTRA_STREAM, Uri.parse(a.uri));
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(Intent.createChooser(share, "Share " + a.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignored) { }
    }

    private static String mimeFor(Attachment.Kind k) {
        switch (k) {
            case PDF: return "application/pdf";
            case DOCX: return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case XLSX: return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case PPTX: return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case ZIP: return "application/zip";
            case VIDEO: return "video/*";
            case AUDIO: return "audio/*";
            case IMAGE: return "image/*";
            default: return "application/octet-stream";
        }
    }

    private static void showImagePreview(Context c, Attachment a) {
        final android.app.Dialog d = new android.app.Dialog(c);
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(NeonUi.dp(c, 10), NeonUi.dp(c, 10), NeonUi.dp(c, 10), NeonUi.dp(c, 10));
        root.setBackgroundColor(0xFF07111F);
        ImageView image = new ImageView(c);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackgroundColor(0xFF02070D);
        root.addView(image, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        TextView name = new TextView(c);
        name.setText(a.name); name.setTextColor(NeonUi.TEXT); name.setTextSize(12f); name.setGravity(Gravity.CENTER);
        root.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        d.setContentView(root);
        android.view.Window w = d.getWindow();
        if (w != null) { w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT)); w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT); }
        d.show();
        if (d.getWindow() != null) d.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        com.neonhud.app.ui.AttachViews.Thumbs.load(c, a.kind, a.uri, image, NeonUi.dp(c, 900));
        image.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { d.dismiss(); } });
    }

    // ------------------------------------------------------------------ user message bubble

    private static final class UserBubble extends LinearLayout {
        private static final SimpleDateFormat CLOCK = new SimpleDateFormat("h:mm a", Locale.getDefault());
        private final TextView text;
        private final LinearLayout cards;
        private final LinearLayout footer;
        private final TextView time;

        UserBubble(Context c) {
            super(c);
            setOrientation(VERTICAL);
            setBackground(NeonUi.glass(c, 16, 0xAA38D6FF, 0x4A3C9BE6, 0x26205FA8));
            setPadding(NeonUi.dp(c, 14), NeonUi.dp(c, 8), NeonUi.dp(c, 14), NeonUi.dp(c, 6));

            text = new TextView(c);
            text.setTextColor(NeonUi.TEXT);
            text.setTextSize(15f);
            addView(text, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            cards = new LinearLayout(c);
            cards.setOrientation(VERTICAL);
            LayoutParams cp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.topMargin = NeonUi.dp(c, 6);
            addView(cards, cp);

            footer = new LinearLayout(c);
            footer.setOrientation(HORIZONTAL);
            footer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            time = new TextView(c);
            time.setTextColor(NeonUi.DIM);
            time.setTextSize(10.5f);
            footer.addView(time);
            AttachIcon ticks = new AttachIcon(c, AttachIcon.TICKS);
            LayoutParams tp = new LayoutParams(NeonUi.dp(c, 17), NeonUi.dp(c, 10));
            tp.leftMargin = NeonUi.dp(c, 4);
            footer.addView(ticks, tp);
            LayoutParams fp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            fp.topMargin = NeonUi.dp(c, 2);
            addView(footer, fp);
        }

        void bind(ChatController.Item it, int textMaxWidth) {
            Context c = getContext();
            text.setVisibility(it.text.isEmpty() ? View.GONE : View.VISIBLE);
            text.setText(it.text);
            text.setMaxWidth(Math.max(NeonUi.dp(c, 60), textMaxWidth));

            cards.removeAllViews();
            List<Attachment> files = it.attachments;
            cards.setVisibility(files.isEmpty() ? View.GONE : View.VISIBLE);
            for (int i = 0; i < files.size(); i += 3) {
                LinearLayout row = new LinearLayout(c);
                row.setOrientation(HORIZONTAL);
                for (int j = i; j < Math.min(i + 3, files.size()); j++) {
                    LayoutParams lp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    lp.rightMargin = NeonUi.dp(c, 5);
                    final Attachment file = files.get(j);
                    View card = AttachViews.card(c, file, null);
                    if (file.kind == Attachment.Kind.IMAGE || file.kind == Attachment.Kind.VIDEO) {
                        card.setClickable(true);
                        card.setOnClickListener(new OnClickListener() {
                            @Override public void onClick(View v) {
                                if (file.kind == Attachment.Kind.IMAGE) showImagePreview(getContext(), file);
                                else openAttachment(getContext(), file);
                            }
                        });
                    }
                    row.addView(card, lp);
                }
                LayoutParams rp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rp.bottomMargin = NeonUi.dp(c, 5);
                cards.addView(row, rp);
            }

            footer.setVisibility(it.time > 0 ? View.VISIBLE : View.GONE);      // old history has no send time
            if (it.time > 0) time.setText(CLOCK.format(new Date(it.time)));
        }
    }
}
