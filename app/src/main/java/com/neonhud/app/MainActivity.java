package com.neonhud.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.DisplayCutout;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.neonhud.app.android.App;
import com.neonhud.app.android.ModelService;
import com.neonhud.app.android.UriImportSource;
import com.neonhud.app.core.chat.ChatController;
import com.neonhud.app.core.module.ModuleState;
import com.neonhud.app.core.module.ModuleManager;
import com.neonhud.app.core.module.ModuleSnapshot;
import com.neonhud.app.core.search.TavilyKeyManager;
import com.neonhud.app.core.search.TavilySnapshot;
import com.neonhud.app.ui.ChatPage;
import com.neonhud.app.ui.HudLayout;
import com.neonhud.app.ui.SettingsPage;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opening screen: the neon HUD frame. Minimize / Settings / Close sit exactly over the icons drawn in the frame.
 * This Activity is ONLY a view: the model, the chat and the memory live in {@link App}, so going to the
 * background and coming back (or the Activity being re-created) never stops a load or a reply.
 */
public class MainActivity extends Activity implements ModuleManager.Listener, ChatController.Listener, TavilyKeyManager.Listener {

    private static final int REQ_PICK_GEMMA = 41;
    private static final int REQ_NOTIFICATIONS = 42;
    private static final int REQ_PICK_CODER = 43;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean refreshQueued = new AtomicBoolean(false);
    private final Runnable refreshRunnable = new Runnable() {
        @Override public void run() {
            refreshQueued.set(false);
            refresh();
        }
    };

    private App app;
    private HudLayout hud;
    private ChatPage chatPage;
    private SettingsPage settingsPage;
    private boolean showingSettings;
    private int imeInset;
    private boolean exiting;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = App.get(this);

        // Let the (black) window extend into the camera cut-out instead of the system letterboxing it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }

        setContentView(R.layout.activity_main);
        hud = (HudLayout) findViewById(R.id.hud_root);

        chatPage = new ChatPage(this);
        settingsPage = new SettingsPage(this, new SettingsPage.Actions() {
            @Override public void onBack() { showSettings(false); }
            @Override public void onImport(int module) { pickModel(module); }
            @Override public void onLoad(int module) { doLoad(module); }
            @Override public void onUnload(int module) { doUnload(module); }
            @Override public void onDelete(int module) { doDelete(module); }
            @Override public void onAddTavilyKey(String key) { app.tavily().requestAdd(key); }
            @Override public void onDeleteTavilyKey(String key) { app.tavily().requestDelete(key); }
        });
        FrameLayout content = hud.content();
        content.addView(chatPage, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        content.addView(settingsPage, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        settingsPage.setVisibility(View.GONE);

        chatPage.setSendHandler(new ChatPage.SendHandler() {
            @Override public void onSend(String text) { sendMessage(text); }
        });
        chatPage.setModeHandler(new ChatPage.ModeHandler() {
            @Override public void onSwitch() {
                app.setChatMode(app.chatMode() == App.MODE_CODER ? App.MODE_GEMMA : App.MODE_CODER);
                refresh();
            }
        });

        // top-right icons
        hud.minimizeButton().setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { moveTaskToBack(true); }
        });
        hud.settingsButton().setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSettings(!showingSettings); }
        });
        hud.closeButton().setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { exitApp(); }
        });

        installInsets();
        refresh();
    }

    // ------------------------------------------------------------------ safe area + keyboard

    @SuppressWarnings("deprecation")
    private void installInsets() {
        hud.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int l, t, r, b;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // "ignoring visibility": the area stays reserved even while the bars are hidden, so a swipe that
                    // reveals the status bar / battery never lands on top of the content.
                    Insets bars = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    l = bars.left; t = bars.top; r = bars.right; b = bars.bottom;
                    imeInset = insets.getInsets(WindowInsets.Type.ime()).bottom;
                } else {
                    l = insets.getStableInsetLeft(); t = insets.getStableInsetTop();
                    r = insets.getStableInsetRight(); b = insets.getStableInsetBottom();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        DisplayCutout dc = insets.getDisplayCutout();
                        if (dc != null) {
                            l = Math.max(l, dc.getSafeInsetLeft()); t = Math.max(t, dc.getSafeInsetTop());
                            r = Math.max(r, dc.getSafeInsetRight()); b = Math.max(b, dc.getSafeInsetBottom());
                        }
                    }
                }
                hud.setSafeInsets(l, t, r, b);
                updateKeyboard();
                return insets;
            }
        });
        hud.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() { updateKeyboard(); }
        });
        hud.requestApplyInsets();
    }

    /** Keyboard height from the insets (Android 11+) or from how much of the window is still visible (older). */
    private void updateKeyboard() {
        Rect visible = new Rect();
        hud.getWindowVisibleDisplayFrame(visible);
        int[] loc = new int[2];
        hud.getLocationOnScreen(loc);
        int overlap = Math.max(0, loc[1] + hud.getHeight() - visible.bottom);
        int fromFrame = overlap > hud.getHeight() / 5 ? overlap : 0;   // small overlaps are system bars, not a keyboard
        hud.setKeyboardHeight(Math.max(imeInset, fromFrame));
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
        }
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    // ------------------------------------------------------------------ lifecycle (view only)

    @Override protected void onStart() {
        super.onStart();
        app.modules().addListener(this);
        app.coderModules().addListener(this);
        app.chat().addListener(this);
        app.coderChat().addListener(this);
        app.tavily().addListener(this);
        refresh();
    }

    @Override protected void onStop() {
        app.modules().removeListener(this);
        app.coderModules().removeListener(this);
        app.chat().removeListener(this);
        app.coderChat().removeListener(this);
        app.tavily().removeListener(this);
        ui.removeCallbacks(refreshRunnable);
        refreshQueued.set(false);
        super.onStop();
    }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** Back never kills the app or the reply: from Settings it returns to the chat, from the chat it goes to the background. */
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (showingSettings) showSettings(false); else moveTaskToBack(true);
    }

    // listeners fire on worker threads; coalesce into at most one UI refresh every ~33 ms
    @Override public void onModuleChanged(ModuleSnapshot snapshot) { scheduleRefresh(); }
    @Override public void onChatChanged() { scheduleRefresh(); }
    @Override public void onKeysChanged(TavilySnapshot snapshot) { scheduleRefresh(); }

    private void scheduleRefresh() {
        if (refreshQueued.compareAndSet(false, true)) ui.postDelayed(refreshRunnable, 33);
    }

    private boolean coderMode() { return app.chatMode() == App.MODE_CODER; }
    private ChatController currentChat() { return coderMode() ? app.coderChat() : app.chat(); }
    private ModuleManager manager(int module) { return module == SettingsPage.CODER ? app.coderModules() : app.modules(); }

    private void refresh() {
        ModuleSnapshot gemma = app.modules().snapshot();
        ModuleSnapshot coder = app.coderModules().snapshot();
        settingsPage.bind(gemma, coder, app.tavily().snapshot());
        ChatController chat = currentChat();
        ModuleState shown = coderMode() ? coder.state : gemma.state;
        chatPage.bind(chat.items(), chat.isGenerating(), shown,
                coderMode() ? ChatPage.MODE_CODER : ChatPage.MODE_GEMMA);
    }

    private void showSettings(boolean show) {
        showingSettings = show;
        settingsPage.setVisibility(show ? View.VISIBLE : View.GONE);
        chatPage.setVisibility(show ? View.GONE : View.VISIBLE);
        if (show) {
            View f = getCurrentFocus();
            if (f != null) f.clearFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(hud.getWindowToken(), 0);
        }
        refresh();
    }

    // ------------------------------------------------------------------ chat

    private void sendMessage(String text) {
        ChatController.SendResult r = currentChat().send(text);
        switch (r) {
            case ACCEPTED:
                chatPage.clearInput();
                ModelService.ensureRunning(this);
                break;
            case MODEL_NOT_READY:
                break;                      // the typed text stays in the box; a notice tells the user to load the model
            case BUSY:
                Toast.makeText(this, "Please wait for the current reply.", Toast.LENGTH_SHORT).show();
                break;
            default:
                break;
        }
        refresh();
    }

    // ------------------------------------------------------------------ module actions

    private void requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    private void pickModel(int module) {
        if (!manager(module).snapshot().canImport) return;
        requestNotificationPermissionOnce();
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, module == SettingsPage.CODER ? REQ_PICK_CODER : REQ_PICK_GEMMA);
        } catch (RuntimeException e) {
            Toast.makeText(this, "No file picker available on this phone.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode != REQ_PICK_GEMMA && requestCode != REQ_PICK_CODER) || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        ModuleManager target = requestCode == REQ_PICK_CODER ? app.coderModules() : app.modules();
        ModuleManager.Result r = target.requestImport(new UriImportSource(this, uri));
        if (r.accepted) ModelService.ensureRunning(this);
        refresh();
    }

    private void doLoad(int module) {
        ModuleManager.Result r = manager(module).requestLoad();
        if (r.accepted) ModelService.ensureRunning(this);
        refresh();
    }

    private void doUnload(int module) {
        manager(module).requestUnload();
        refresh();
    }

    private void doDelete(int module) {
        manager(module).requestDelete();
        refresh();
    }

    // ------------------------------------------------------------------ exit

    /** The close icon: stop any reply, unload both models, stop the service and end the process. */
    private void exitApp() {
        if (exiting) return;
        exiting = true;
        app.chat().cancel();
        app.coderChat().cancel();
        new Thread(new Runnable() {
            @Override public void run() {
                long end = System.currentTimeMillis() + 4000;
                while ((app.chat().isGenerating() || app.coderChat().isGenerating()) && System.currentTimeMillis() < end) {
                    try { Thread.sleep(30); } catch (InterruptedException ignored) { }
                }
                app.modules().shutdownQuietly();
                app.coderModules().shutdownQuietly();
                ui.post(new Runnable() {
                    @Override public void run() {
                        stopService(new Intent(MainActivity.this, ModelService.class));
                        finishAndRemoveTask();
                        ui.postDelayed(new Runnable() {
                            @Override public void run() { Process.killProcess(Process.myPid()); }
                        }, 250);
                    }
                });
            }
        }, "exit").start();
    }
}
