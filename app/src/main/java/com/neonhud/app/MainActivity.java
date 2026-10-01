package com.neonhud.app;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
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
import android.provider.MediaStore;
import android.view.DisplayCutout;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.neonhud.app.android.App;
import com.neonhud.app.android.ModelService;
import com.neonhud.app.android.UriImportSource;
import com.neonhud.app.core.chat.ChatController;
import com.neonhud.app.core.engine.Attachment;
import com.neonhud.app.core.engine.FileSniffer;
import com.neonhud.app.core.module.ModuleState;
import com.neonhud.app.core.module.ModuleManager;
import com.neonhud.app.core.module.ModuleSnapshot;
import com.neonhud.app.core.module.ModelRuntimeCoordinator;
import com.neonhud.app.core.chat.ModelTaskRouter;
import com.neonhud.app.core.search.TavilyKeyManager;
import com.neonhud.app.core.search.TavilySnapshot;
import com.neonhud.app.ui.ChatPage;
import com.neonhud.app.ui.HudLayout;
import com.neonhud.app.core.web.WebMode;
import com.neonhud.app.ui.SettingsPage;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opening screen: the neon HUD frame. Minimize / Settings / Close sit exactly over the icons drawn in the frame.
 * This Activity is ONLY a view: the model, the chat and the memory live in {@link App}, so going to the
 * background and coming back (or the Activity being re-created) never stops a load or a reply.
 */
public class MainActivity extends Activity implements ModuleManager.Listener, ChatController.Listener, TavilyKeyManager.Listener, ModelRuntimeCoordinator.Listener {

    private static final int REQ_PICK_GEMMA = 41;
    private static final int REQ_NOTIFICATIONS = 42;
    private static final int REQ_PICK_CODER = 43;
    private static final int REQ_ATTACH_IMAGE = 51;
    private static final int REQ_ATTACH_ZIP = 52;
    private static final int REQ_ATTACH_CAMERA = 54;
    private static final long MAX_ATTACH_BYTES = 50L * 1024 * 1024;   // per file

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
    private File cameraFile;             // the photo the camera app is writing right now

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
            @Override public void onDelete(int module) { doDelete(module); }
            @Override public void onAddTavilyKey(String key) { app.tavily().requestAdd(key); }
            @Override public void onDeleteTavilyKey(String key) { app.tavily().requestDelete(key); }
            @Override public void onWebMode(WebMode mode) { app.webSettings().set(mode); refresh(); }
        });
        FrameLayout content = hud.content();
        content.addView(chatPage, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        content.addView(settingsPage, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        settingsPage.setVisibility(View.GONE);

        chatPage.setSendHandler(new ChatPage.SendHandler() {
            @Override public void onSend(String text, List<Attachment> files) { sendMessage(text, files); }
        });
        chatPage.setAttachHandler(new ChatPage.AttachHandler() {
            @Override public void onPick(int kind) { pickAttachment(kind); }
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
        app.runtime().addListener(this);
        lastRuntimeActive = app.runtime().snapshot().active;
        app.modules().addListener(this);
        app.coderModules().addListener(this);
        app.chat().addListener(this);
        app.coderChat().addListener(this);
        app.tavily().addListener(this);
        refresh();
    }

    @Override protected void onStop() {
        app.runtime().removeListener(this);
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

    @Override public void onRuntimeChanged(ModelRuntimeCoordinator.RuntimeSnapshot snapshot) {
        ModelRuntimeCoordinator.Target active = snapshot.active;
        if (active != null && active != lastRuntimeActive) {
            if (active == ModelRuntimeCoordinator.Target.CODER) {
                app.coderChat().setHandoffContext(app.chat().recentHandoffContext());
                app.setChatMode(App.MODE_CODER);
            } else {
                app.chat().setHandoffContext(app.coderChat().recentHandoffContext());
                app.setChatMode(App.MODE_GEMMA);
            }
            lastRuntimeActive = active;
        }
        scheduleRefresh();
    }

    private void scheduleRefresh() {
        if (refreshQueued.compareAndSet(false, true)) ui.postDelayed(refreshRunnable, 33);
    }

    private ModelRuntimeCoordinator.Target lastRuntimeActive;

    private boolean coderMode() { return app.chatMode() == App.MODE_CODER; }
    private ChatController currentChat() { return coderMode() ? app.coderChat() : app.chat(); }
    private void refresh() {
        ModuleSnapshot gemma = app.modules().snapshot();
        ModuleSnapshot coder = app.coderModules().snapshot();
        settingsPage.bind(gemma, coder, app.tavily().snapshot(), app.webSettings().mode(), app.runtime().snapshot());
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

    private void sendMessage(String text, List<Attachment> files) {
        ModelTaskRouter.Target current = coderMode()
                ? ModelTaskRouter.Target.CODER : ModelTaskRouter.Target.GEMMA;
        ModelTaskRouter.Target target = ModelTaskRouter.route(text, files, current);
        if (target != current) {
            ChatController source = currentChat();
            ChatController destination = target == ModelTaskRouter.Target.CODER ? app.coderChat() : app.chat();
            destination.setHandoffContext(source.recentHandoffContext());
            app.setChatMode(target == ModelTaskRouter.Target.CODER ? App.MODE_CODER : App.MODE_GEMMA);
        }
        ChatController rChat = target == ModelTaskRouter.Target.CODER ? app.coderChat() : app.chat();
        ChatController.SendResult r = rChat.send(text, files);
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

    // Keep module selection centralized so Settings actions cannot accidentally target the wrong model.
    private ModuleManager manager(int module) {
        return module == SettingsPage.CODER ? app.coderModules() : app.modules();
    }

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

    // ------------------------------------------------------------------ attachments (+ button)

    private void pickAttachment(int kind) {
        if (kind == ChatPage.ATTACH_CAMERA) { openCamera(); return; }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        int code;
        if (kind == ChatPage.ATTACH_IMAGE) {
            i.setType("image/*");
            code = REQ_ATTACH_IMAGE;
        } else {
            // The single Files entry intentionally allows any file provider type. The attachment checker below
            // sniffs real content (ZIP/PDF/OOXML/audio/video) instead of trusting a provider MIME declaration.
            // Unsupported files are rejected after selection with a clear message.
            i.setType("*/*");
            code = REQ_ATTACH_ZIP;
        }
        try {
            startActivityForResult(i, code);
        } catch (RuntimeException e) {
            Toast.makeText(this, "No file picker available on this phone.", Toast.LENGTH_LONG).show();
        }
    }

    private void openCamera() {
        try {
            File dir = new File(getCacheDir(), "camera");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            cameraFile = new File(dir, "IMG_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg");
            //noinspection ResultOfMethodCallIgnored
            cameraFile.createNewFile();
            Uri out = FileProvider.getUriForFile(this, getPackageName() + ".files", cameraFile);
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, out);
            i.setClipData(ClipData.newRawUri("photo", out));
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, REQ_ATTACH_CAMERA);
        } catch (IOException | RuntimeException e) {
            Toast.makeText(this, "Camera is not available.", Toast.LENGTH_LONG).show();
        }
    }

    private void handleAttachResult(int requestCode, int resultCode, Intent data) {
        List<Attachment> picked = new ArrayList<Attachment>();
        if (requestCode == REQ_ATTACH_CAMERA) {
            File f = cameraFile;
            cameraFile = null;
            if (f == null) return;
            if (resultCode == RESULT_OK && f.length() > 0) {
                Uri u = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
                picked.add(new Attachment(Attachment.Kind.IMAGE, f.getName(), f.length(), u.toString()));
            } else {
                //noinspection ResultOfMethodCallIgnored
                f.delete();                                   // cancelled: no empty file left behind
            }
        } else if (resultCode == RESULT_OK && data != null) {
            Attachment.Kind kind = requestCode == REQ_ATTACH_IMAGE ? Attachment.Kind.IMAGE : Attachment.Kind.ZIP;
            List<Uri> uris = new ArrayList<Uri>();
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int k = 0; k < clip.getItemCount(); k++) uris.add(clip.getItemAt(k).getUri());
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            if (kind == Attachment.Kind.IMAGE) {
                // unchanged: the image picker already only offers pictures
                boolean tooBig = false;
                for (Uri u : uris) {
                    if (u == null) continue;
                    UriImportSource info = new UriImportSource(this, u);
                    if (info.sizeBytes() > MAX_ATTACH_BYTES) { tooBig = true; continue; }
                    picked.add(new Attachment(kind, info.displayName(), info.sizeBytes(), u.toString()));
                }
                if (tooBig) Toast.makeText(this, "File too big (max 50 MB).", Toast.LENGTH_SHORT).show();
            } else {
                checkAndAttach(uris);                       // ZIP / PDF / video: checked off the UI thread, then added
                return;
            }
        }
        addPicked(picked);
    }

    private void addPicked(List<Attachment> picked) {
        if (picked.isEmpty()) return;
        if (chatPage.addAttachments(picked) < picked.size()) {
            Toast.makeText(this, "Up to " + ChatController.MAX_ATTACHMENTS + " files per message.", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * PDF / Zip from any source: every file is opened and its first bytes are checked, so a real PDF or zip is accepted
     * whatever its name, extension or MIME type says, and a wrong file is refused with a clear message. Done on a
     * worker thread because a cloud provider (Drive ...) may download the file first.
     */
    private void checkAndAttach(final List<Uri> uris) {
        chatPage.setAttachmentBusy(true);
        new Thread(new Runnable() {
            @Override public void run() {
                final List<Attachment> ok = new ArrayList<Attachment>();
                final List<String> problems = new ArrayList<String>();
                try {
                    for (Uri u : uris) {
                        if (u == null) continue;
                        UriImportSource info = new UriImportSource(MainActivity.this, u);
                        String name = info.displayName().isEmpty() ? "This file" : info.displayName();
                        if (info.sizeBytes() > MAX_ATTACH_BYTES) { problems.add(name + " is too big (max 50 MB)."); continue; }
                        byte[] head = new byte[FileSniffer.HEAD_BYTES];
                        int len = 0;
                        try {
                            InputStream in = info.open();
                            try {
                                int n;
                                while (len < head.length && (n = in.read(head, len, head.length - len)) > 0) len += n;
                            } finally {
                                in.close();
                            }
                        } catch (IOException | RuntimeException e) {
                            problems.add(name + " could not be opened.");
                            continue;
                        }
                        Attachment.Kind real = FileSniffer.detect(head, len);
                        String mime = getContentResolver().getType(u);
                        String lower = name.toLowerCase(Locale.ROOT);
                        if (real == Attachment.Kind.VIDEO && mime != null && mime.startsWith("audio/")) real = Attachment.Kind.AUDIO;
                        if (real == Attachment.Kind.VIDEO && (lower.endsWith(".m4a") || lower.endsWith(".aac") || lower.endsWith(".m4b") || lower.endsWith(".3ga"))) real = Attachment.Kind.AUDIO;
                        if (real == Attachment.Kind.ZIP) {
                            // OOXML office files are ZIP packages; identify them from their internal package parts.
                            try {
                                Attachment.Kind office = FileSniffer.detectZipContainer(info.open());
                                if (office != null) real = office;
                            } catch (IOException ignored) { /* leave it as a normal ZIP */ }
                        }
                        if (real == null) {
                            if (mime != null && mime.startsWith("audio/")) real = Attachment.Kind.AUDIO;
                            else if (mime != null && mime.startsWith("video/")) real = Attachment.Kind.VIDEO;
                            else if (lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".m4a") || lower.endsWith(".aac") || lower.endsWith(".ogg") || lower.endsWith(".flac") || lower.endsWith(".opus") || lower.endsWith(".amr") || lower.endsWith(".3ga")) real = Attachment.Kind.AUDIO;
                            else if (lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm") || lower.endsWith(".mov") || lower.endsWith(".3gp") || lower.endsWith(".avi")) real = Attachment.Kind.VIDEO;
                            else if (lower.endsWith(".csv") || "text/csv".equalsIgnoreCase(mime)) real = Attachment.Kind.XLSX;
                            else if (lower.endsWith(".tsv") || "text/tab-separated-values".equalsIgnoreCase(mime)) real = Attachment.Kind.XLSX;
                            else if (lower.endsWith(".docx")) real = Attachment.Kind.DOCX;
                            else if (lower.endsWith(".xlsx")) real = Attachment.Kind.XLSX;
                            else if (lower.endsWith(".pptx")) real = Attachment.Kind.PPTX;
                        }
                        if (real == Attachment.Kind.PDF || real == Attachment.Kind.ZIP || real == Attachment.Kind.AUDIO || real == Attachment.Kind.VIDEO
                                || real == Attachment.Kind.DOCX || real == Attachment.Kind.XLSX || real == Attachment.Kind.PPTX) {
                            ok.add(new Attachment(real, info.displayName(), info.sizeBytes(), u.toString()));
                        } else {
                            problems.add(name + " is not a supported image, audio, video, PDF, ZIP, Word, Excel or PowerPoint file.");
                        }
                    }
                } catch (Throwable t) {
                    problems.add("Attachment check failed; no file was added.");
                } finally {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            try {
                                if (!problems.isEmpty()) {
                                    Toast.makeText(MainActivity.this, problems.get(0) + (problems.size() > 1 ? " (+" + (problems.size() - 1) + " more)" : ""),
                                            Toast.LENGTH_LONG).show();
                                }
                                addPicked(ok);
                            } finally {
                                chatPage.setAttachmentBusy(false);
                            }
                        }
                    });
                }
            }
        }, "attach-check").start();
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode >= REQ_ATTACH_IMAGE && requestCode <= REQ_ATTACH_CAMERA) {
            handleAttachResult(requestCode, resultCode, data);
            return;
        }
        if ((requestCode != REQ_PICK_GEMMA && requestCode != REQ_PICK_CODER) || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        ModuleManager target = requestCode == REQ_PICK_CODER ? app.coderModules() : app.modules();
        ModuleManager.Result r = target.requestImport(new UriImportSource(this, uri));
        if (r.accepted) ModelService.ensureRunning(this);
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
                app.runtime().shutdownQuietly();
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
