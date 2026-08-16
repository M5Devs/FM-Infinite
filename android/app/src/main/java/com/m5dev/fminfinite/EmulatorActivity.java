/* LICENSE>>
Copyright 2025 M5_Development (FM Infinite Authors)

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

<< LICENSE */

package com.m5dev.fminfinite;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import java.io.File;

public class EmulatorActivity extends AppCompatActivity {
    private static final String TAG = "FMInfinite_Emu";
    private static final String PREFS_NAME = "fminfinite_prefs";

    private String gamePath;
    private String gameName;

    private volatile EmulatorSurfaceView surfaceView;
    private volatile EmulatorGLSurfaceView gpuView;
    private FrameLayout rootLayout;

    // Status Bar above controls
    private LinearLayout statusMenuBar;
    private TextView gameNameText;
    private TextView fpsCounterText;
    private TextView kbToggleBtn;
    private TextView menuOpenBtn;

    // Controls & Overlays
    private FrameLayout controlsArea;
    private RelativeLayout virtualPadContainer;
    private LinearLayout keyboardContainer;
    private FrameLayout quickMenuOverlay;

    // Audio Bridge
    private AudioBridge audioBridge;

    // Running states
    private volatile boolean isRunning = false;
    private boolean isCoreInitialized = false;
    private int currentGamepadMask = 0;
    private long fpsLastTime = 0;
    private int fpsFrameCount = 0;

    private static final long FRAME_TIME_NS = 16_666_667; // ~60 FPS
    private long lastFrameTime = 0;

    private HandlerThread emuThread;
    private Handler emuHandler;
    private final Runnable emuRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRunning) {
                long now = System.nanoTime();
                long elapsed = now - lastFrameTime;
                if (elapsed < FRAME_TIME_NS) {
                    long sleepMs = (FRAME_TIME_NS - elapsed) / 1_000_000;
                    int sleepNs = (int) ((FRAME_TIME_NS - elapsed) % 1_000_000);
                    try {
                        Thread.sleep(sleepMs, sleepNs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                lastFrameTime = System.nanoTime();

                EmulatorCore.nativeRunFrame();

                // Use local snapshot to avoid race with fallbackToSoftware
                EmulatorSurfaceView sv = surfaceView;
                EmulatorGLSurfaceView gv = gpuView;
                if (sv != null) {
                    sv.drawFrame();
                } else if (gv != null) {
                    gv.drawFrame();
                }

                fpsFrameCount++;
                long currentRealTime = SystemClock.elapsedRealtime();
                long diff = currentRealTime - fpsLastTime;
                if (diff >= 1000) {
                    final int calculatedFps = Math.round((float) fpsFrameCount * 1000.0f / diff);
                    runOnUiThread(() -> {
                        if (fpsCounterText != null) {
                            fpsCounterText.setText(calculatedFps + " FPS");
                        }
                    });
                    fpsLastTime = currentRealTime;
                    fpsFrameCount = 0;
                }

                if (emuHandler != null && isRunning) {
                    emuHandler.post(this);
                }
            }
        }
    };

    private synchronized void startEmuThread() {
        if (emuThread == null) {
            emuThread = new HandlerThread("FMInfinite_EmuThread");
            emuThread.start();
            emuHandler = new Handler(emuThread.getLooper());
        }
        isRunning = true;
        fpsLastTime = SystemClock.elapsedRealtime();
        fpsFrameCount = 0;
        emuHandler.removeCallbacks(emuRunnable);
        if (!isCoreInitialized) {
            emuHandler.post(() -> {
                initCoreAndLoad();
            });
        } else {
            emuHandler.post(emuRunnable);
        }
    }

    private synchronized void stopEmuThread() {
        isRunning = false;
        if (emuHandler != null) {
            emuHandler.removeCallbacks(emuRunnable);
        }
        if (emuThread != null) {
            emuThread.quitSafely();
            try {
                emuThread.join(1000);
            } catch (InterruptedException e) {
                Log.e(TAG, "stopEmuThread interrupted", e);
            }
            emuThread = null;
            emuHandler = null;
        }
    }

    // Keyboard State (0 = hidden, 1 = Basic, 2 = Full)
    private int keyboardState = 0;
    private boolean isMenuOpen = false;

    // SharedPreferences settings
    private boolean showFps = true;
    private boolean screenFilterBilinear = false;
    private float virtualPadOpacity = 0.7f;
    private String virtualPadSize = "medium";

    // Auto-hide Top Bar
    private final Handler autoHideHandler = new Handler();
    private final Runnable hideTopBarRunnable = new Runnable() {
        @Override
        public void run() {
            if (statusMenuBar != null) {
                statusMenuBar.animate()
                    .alpha(0.0f)
                    .setDuration(300)
                    .withEndAction(() -> statusMenuBar.setVisibility(View.GONE))
                    .start();
            }
        }
    };

    private void showTopBar() {
        if (statusMenuBar != null) {
            statusMenuBar.setVisibility(View.VISIBLE);
            statusMenuBar.animate()
                .alpha(1.0f)
                .setDuration(300)
                .start();
            resetTopBarTimer();
        }
    }

    private void resetTopBarTimer() {
        autoHideHandler.removeCallbacks(hideTopBarRunnable);
        autoHideHandler.postDelayed(hideTopBarRunnable, 3000); // 3 seconds
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FileLogger.init(this);
        FileLogger.log("Java: EmulatorActivity onCreate called");

        // Keep screen on & fullscreen
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        // Get extras
        gamePath = getIntent().getStringExtra("game_path");
        gameName = getIntent().getStringExtra("game_name");
        if (gameName == null) gameName = "FM Towns Game";

        // Load Settings
        loadSettings();

        // Initialize AudioBridge
        audioBridge = new AudioBridge();
        audioBridge.init();
        EmulatorCore.nativeInitAudio(audioBridge);

        // Dynamically set programmatic view based on current orientation
        updateLayoutForOrientation(getResources().getConfiguration().orientation);

        // Initialize core and start emulation loop on background thread
        startEmuThread();
    }

    private void updateLayoutForOrientation(int orientation) {
        boolean isLandscape = (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE);

        // PPSSPP Design: Fully programmatic overlays on top of a single root FrameLayout
        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.parseColor("#0D0D14")); // Near-black with blue tint

        // 1. Game surface wrapper (Base layer)
        FrameLayout surfaceWrapper = new FrameLayout(this);
        surfaceWrapper.setBackgroundColor(Color.BLACK);
        rootLayout.addView(surfaceWrapper, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
        ));

        Config config = ConfigManager.loadConfig(this);
        surfaceWrapper.removeAllViews();
        if ("gpu".equals(config.renderer)) {
            gpuView = new EmulatorGLSurfaceView(this);
            gpuView.setOnRendererFailedListener(reason -> {
                Log.e(TAG, "GPU Renderer failed: " + reason);
                Toast.makeText(this, "GPU rendering failed, falling back to Software", Toast.LENGTH_LONG).show();

                Config failConfig = ConfigManager.loadConfig(this);
                failConfig.renderer = "software";
                ConfigManager.saveConfig(this, failConfig);

                fallbackToSoftware();
            });
            surfaceWrapper.addView(gpuView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
            ));
            surfaceView = null;
        } else {
            surfaceView = new EmulatorSurfaceView(this);
            surfaceWrapper.addView(surfaceView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
            ));
            gpuView = null;
        }

        // 2. Controls Area (Overlay layer)
        controlsArea = new FrameLayout(this);
        rootLayout.addView(controlsArea, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // 3. Virtual Keyboard Container (Translucent overlay layer)
        keyboardContainer = new LinearLayout(this);
        keyboardContainer.setOrientation(LinearLayout.VERTICAL);
        keyboardContainer.setBackgroundColor(Color.parseColor("#F0000000")); // Dark translucent
        keyboardContainer.setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4));
        keyboardContainer.setVisibility(View.GONE);
        FrameLayout.LayoutParams kbParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
        );
        rootLayout.addView(keyboardContainer, kbParams);

        // 4. Status Menu Bar (Translucent top bar overlay)
        statusMenuBar = new LinearLayout(this);
        statusMenuBar.setOrientation(LinearLayout.HORIZONTAL);
        statusMenuBar.setGravity(Gravity.CENTER_VERTICAL);
        statusMenuBar.setBackgroundColor(Color.parseColor("#CC000000")); // 80% black, translucent
        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(40) // Thin top bar: 40dp
        );
        barParams.gravity = Gravity.TOP;
        rootLayout.addView(statusMenuBar, barParams);

        // 5. Quick Menu Overlay (Top sheet translucent layer)
        quickMenuOverlay = new FrameLayout(this);
        quickMenuOverlay.setBackgroundColor(Color.parseColor("#AA000000"));
        quickMenuOverlay.setVisibility(View.GONE);
        rootLayout.addView(quickMenuOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        setContentView(rootLayout);

        setupSurfaceView();
        populateStatusMenuBar();
        populateControlsArea(isLandscape);
        updateKeyboardOverlay();
        populateQuickMenuOverlay();

        // Reset auto-hide timer
        resetTopBarTimer();
    }

    private void fallbackToSoftware() {
        stopEmuThread(); // MUST stop before touching views
        runOnUiThread(() -> {
            // Find root layout dynamically
            if (rootLayout != null) {
                // Rebuild the surface wrapper specifically
                // Let's locate the first child of rootLayout which is the surface wrapper
                View firstChild = rootLayout.getChildAt(0);
                if (firstChild instanceof FrameLayout) {
                    FrameLayout surfaceWrapper = (FrameLayout) firstChild;
                    surfaceWrapper.removeAllViews();
                    gpuView = null;
                    surfaceView = new EmulatorSurfaceView(this);
                    surfaceView.setScreenFilterBilinear(screenFilterBilinear);
                    surfaceWrapper.addView(surfaceView, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            Gravity.CENTER
                    ));
                    setupSurfaceView();
                    if (isCoreInitialized) {
                        startEmuThread(); // Restart only after surface is ready
                    }
                }
            }
        });
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        showFps = prefs.getBoolean("show_fps", true);
        screenFilterBilinear = prefs.getBoolean("screen_filter_bilinear", false);
        virtualPadOpacity = (float) prefs.getInt("virtual_pad_opacity", 70) / 100.0f;
        virtualPadSize = prefs.getString("virtual_pad_size", "medium");
    }

    private void populateStatusMenuBar() {
        if (statusMenuBar == null) return;
        statusMenuBar.removeAllViews();
        statusMenuBar.setPadding(dpToPx(12), 0, dpToPx(12), 0);

        // Left: Back button
        TextView backBtn = new TextView(this);
        backBtn.setText("←");
        backBtn.setTextColor(Color.parseColor("#E8E8FF")); // Text Primary
        backBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        backBtn.setPadding(dpToPx(8), dpToPx(4), dpToPx(16), dpToPx(4));
        backBtn.setOnClickListener(v -> showExitConfirmation());
        statusMenuBar.addView(backBtn);

        // Center: Game Name
        gameNameText = new TextView(this);
        gameNameText.setText(gameName);
        gameNameText.setTextColor(Color.parseColor("#E8E8FF")); // Text Primary
        gameNameText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        gameNameText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        gameNameText.setSingleLine(true);
        gameNameText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f
        );
        nameParams.leftMargin = dpToPx(8);
        gameNameText.setLayoutParams(nameParams);
        statusMenuBar.addView(gameNameText);

        // Right: FPS counter
        fpsCounterText = new TextView(this);
        fpsCounterText.setText("0 FPS");
        fpsCounterText.setTextColor(Color.parseColor("#7B68EE")); // Primary color
        fpsCounterText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        fpsCounterText.setTypeface(Typeface.MONOSPACE);
        fpsCounterText.setPadding(dpToPx(8), 0, dpToPx(16), 0);
        fpsCounterText.setVisibility(showFps ? View.VISIBLE : View.GONE);
        statusMenuBar.addView(fpsCounterText);

        // Right: Menu Button
        menuOpenBtn = new TextView(this);
        menuOpenBtn.setText("☰");
        menuOpenBtn.setTextColor(Color.parseColor("#7B68EE")); // Primary color
        menuOpenBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        menuOpenBtn.setPadding(dpToPx(12), dpToPx(4), dpToPx(12), dpToPx(4));
        menuOpenBtn.setOnClickListener(v -> toggleQuickMenu());
        statusMenuBar.addView(menuOpenBtn);
    }

    private void setupSurfaceView() {
        if (surfaceView != null) {
            surfaceView.setScreenFilterBilinear(screenFilterBilinear);
        } else if (gpuView != null) {
            gpuView.setScreenFilterBilinear(screenFilterBilinear);
        }
        setupMouseInput();
    }

    private void setupMouseInput() {
        final View activeView;
        if (surfaceView != null) {
            activeView = surfaceView;
        } else if (gpuView != null) {
            activeView = gpuView;
        } else {
            activeView = null;
        }

        if (activeView != null) {
            activeView.setOnTouchListener((v, event) -> {
                // Show top bar on touch
                showTopBar();

                int action = event.getAction();
                if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) {
                    int viewWidth = activeView.getWidth();
                    int viewHeight = activeView.getHeight();
                    if (viewWidth > 0 && viewHeight > 0) {
                        float touchX = event.getX();
                        float touchY = event.getY();

                        int width = 640;
                        int height = 480;

                        float scaleX = (float) viewWidth / width;
                        float scaleY = (float) viewHeight / height;
                        float scale = Math.min(scaleX, scaleY);

                        int scaledWidth = Math.round(width * scale);
                        int scaledHeight = Math.round(height * scale);

                        int left = (viewWidth - scaledWidth) / 2;
                        int top = (viewHeight - scaledHeight) / 2;

                        float relativeX = touchX - left;
                        float relativeY = touchY - top;

                        // Ignore touches outside the game viewport
                        if (relativeX < 0 || relativeY < 0 || relativeX > scaledWidth || relativeY > scaledHeight) {
                            return true; // Consume event but don't send to emulator
                        }

                        int mouseX = Math.max(0, Math.min(639, Math.round((relativeX / scaledWidth) * 640)));
                        int mouseY = Math.max(0, Math.min(479, Math.round((relativeY / scaledHeight) * 480)));

                        int clickState = 0;
                        if (action != MotionEvent.ACTION_UP) {
                            clickState |= 1;
                        }

                        int keyOrButton = mouseX | (mouseY << 16);
                        EmulatorCore.nativeSendInput(2, keyOrButton, clickState);
                    }
                    return true;
                }
                return false;
            });
        }
    }

    private void populateControlsArea(boolean isLandscape) {
        if (controlsArea == null) return;
        controlsArea.removeAllViews();

        virtualPadContainer = new RelativeLayout(this);
        virtualPadContainer.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        virtualPadContainer.setAlpha(virtualPadOpacity);
        controlsArea.addView(virtualPadContainer);

        // Classic 4-direction cross style D-Pad (like PPSSPP)
        int dpadContainerSize = dpToPx(108);
        RelativeLayout dpadLayout = new RelativeLayout(this);
        RelativeLayout.LayoutParams dpadParams = new RelativeLayout.LayoutParams(
                dpadContainerSize, dpadContainerSize
        );
        dpadParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
        dpadParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        dpadParams.leftMargin = dpToPx(20);
        dpadParams.bottomMargin = dpToPx(20);
        dpadLayout.setLayoutParams(dpadParams);

        int armSize = dpToPx(44);
        int hubSize = dpToPx(20);

        View upBtn = createPPSSPPButton("▲", 1 << 4, Color.parseColor("#7B68EE"));
        RelativeLayout.LayoutParams upParams = new RelativeLayout.LayoutParams(armSize, armSize);
        upParams.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        upParams.addRule(RelativeLayout.CENTER_HORIZONTAL);
        upBtn.setLayoutParams(upParams);
        dpadLayout.addView(upBtn);

        View downBtn = createPPSSPPButton("▼", 1 << 5, Color.parseColor("#7B68EE"));
        RelativeLayout.LayoutParams downParams = new RelativeLayout.LayoutParams(armSize, armSize);
        downParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        downParams.addRule(RelativeLayout.CENTER_HORIZONTAL);
        downBtn.setLayoutParams(downParams);
        dpadLayout.addView(downBtn);

        View leftBtn = createPPSSPPButton("◀", 1 << 2, Color.parseColor("#7B68EE"));
        RelativeLayout.LayoutParams leftParams = new RelativeLayout.LayoutParams(armSize, armSize);
        leftParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
        leftParams.addRule(RelativeLayout.CENTER_VERTICAL);
        leftBtn.setLayoutParams(leftParams);
        dpadLayout.addView(leftBtn);

        View rightBtn = createPPSSPPButton("▶", 1 << 3, Color.parseColor("#7B68EE"));
        RelativeLayout.LayoutParams rightParams = new RelativeLayout.LayoutParams(armSize, armSize);
        rightParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        rightParams.addRule(RelativeLayout.CENTER_VERTICAL);
        rightBtn.setLayoutParams(rightParams);
        dpadLayout.addView(rightBtn);

        // Center circular hub
        View hub = new View(this);
        GradientDrawable hubBg = new GradientDrawable();
        hubBg.setShape(GradientDrawable.OVAL);
        hubBg.setColor(Color.parseColor("#BB1A1A2E"));
        hubBg.setStroke(dpToPx(1), Color.parseColor("#7B68EE"));
        hub.setBackground(hubBg);
        RelativeLayout.LayoutParams hubParams = new RelativeLayout.LayoutParams(hubSize, hubSize);
        hubParams.addRule(RelativeLayout.CENTER_IN_PARENT);
        hub.setLayoutParams(hubParams);
        dpadLayout.addView(hub);

        virtualPadContainer.addView(dpadLayout);

        // Right Side: Action Buttons (A: Red top, B: Blue bottom)
        LinearLayout actionLayout = new LinearLayout(this);
        actionLayout.setOrientation(LinearLayout.VERTICAL);
        actionLayout.setGravity(Gravity.CENTER_HORIZONTAL);
        RelativeLayout.LayoutParams actionParams = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        actionParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        actionParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        actionParams.rightMargin = dpToPx(20);
        actionParams.bottomMargin = dpToPx(20);
        actionLayout.setLayoutParams(actionParams);

        int actBtnSize = dpToPx(52);
        int actGap = dpToPx(12);

        View aBtn = createPPSSPPActionButton("A", 1 << 0, Color.parseColor("#FF4D4D"));
        LinearLayout.LayoutParams aParams = new LinearLayout.LayoutParams(actBtnSize, actBtnSize);
        aParams.bottomMargin = actGap;
        aBtn.setLayoutParams(aParams);
        actionLayout.addView(aBtn);

        View bBtn = createPPSSPPActionButton("B", 1 << 1, Color.parseColor("#4D94FF"));
        LinearLayout.LayoutParams bParams = new LinearLayout.LayoutParams(actBtnSize, actBtnSize);
        bBtn.setLayoutParams(bParams);
        actionLayout.addView(bBtn);

        virtualPadContainer.addView(actionLayout);

        // Keyboard Toggle (⌨️) positioned top-left
        kbToggleBtn = new TextView(this);
        kbToggleBtn.setText("⌨️");
        kbToggleBtn.setTextColor(keyboardState > 0 ? Color.parseColor("#7B68EE") : Color.parseColor("#9090B0"));
        kbToggleBtn.setGravity(Gravity.CENTER);
        kbToggleBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        GradientDrawable kbtBg = new GradientDrawable();
        kbtBg.setColor(Color.parseColor("#BB1A1A2E"));
        kbtBg.setStroke(dpToPx(1), Color.parseColor("#7B68EE"));
        kbtBg.setCornerRadius(dpToPx(16)); // Pill shape
        kbToggleBtn.setBackground(kbtBg);
        RelativeLayout.LayoutParams kbtParams = new RelativeLayout.LayoutParams(dpToPx(40), dpToPx(32));
        kbtParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
        kbtParams.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        kbtParams.leftMargin = dpToPx(20);
        kbtParams.topMargin = dpToPx(60); // below top bar level
        kbToggleBtn.setLayoutParams(kbtParams);
        kbToggleBtn.setOnClickListener(v -> cycleKeyboardState());
        virtualPadContainer.addView(kbToggleBtn);

        // Center Controls: SEL, RUN, SAVE, LOAD
        LinearLayout centerLayout = new LinearLayout(this);
        centerLayout.setOrientation(LinearLayout.HORIZONTAL);
        centerLayout.setGravity(Gravity.CENTER);
        RelativeLayout.LayoutParams centerParams = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        centerParams.addRule(RelativeLayout.CENTER_HORIZONTAL);
        centerParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        centerParams.bottomMargin = dpToPx(20);
        centerLayout.setLayoutParams(centerParams);

        int pillW = dpToPx(44);
        int pillH = dpToPx(32);
        int pillW_wide = dpToPx(56);

        View selectBtn = createPillButton("SEL", 1 << 7, Color.parseColor("#9090B0"));
        LinearLayout.LayoutParams selParams = new LinearLayout.LayoutParams(pillW, pillH);
        selParams.rightMargin = dpToPx(6);
        selectBtn.setLayoutParams(selParams);
        centerLayout.addView(selectBtn);

        View runBtn = createPillButton("RUN", 1 << 6, Color.parseColor("#9090B0"));
        LinearLayout.LayoutParams runParams = new LinearLayout.LayoutParams(pillW, pillH);
        runParams.rightMargin = dpToPx(12);
        runBtn.setLayoutParams(runParams);
        centerLayout.addView(runBtn);

        View saveBtn = createPillButton("💾 SV", 0, Color.parseColor("#7B68EE"));
        LinearLayout.LayoutParams savParams = new LinearLayout.LayoutParams(pillW_wide, pillH);
        savParams.rightMargin = dpToPx(6);
        saveBtn.setLayoutParams(savParams);
        saveBtn.setOnClickListener(v -> saveStatePrompt());
        centerLayout.addView(saveBtn);

        View loadBtn = createPillButton("📂 LD", 0, Color.parseColor("#7B68EE"));
        LinearLayout.LayoutParams loaParams = new LinearLayout.LayoutParams(pillW_wide, pillH);
        loadBtn.setLayoutParams(loaParams);
        loadBtn.setOnClickListener(v -> loadStatePrompt());
        centerLayout.addView(loadBtn);

        virtualPadContainer.addView(centerLayout);
    }

    private View createPPSSPPButton(String label, int bitmask, int borderHex) {
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setTextColor(Color.parseColor("#E8E8FF"));
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        btn.setGravity(Gravity.CENTER);
        btn.setTypeface(Typeface.DEFAULT_BOLD);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#BB1A1A2E"));
        bg.setCornerRadius(dpToPx(6));
        bg.setStroke(dpToPx(1.5f), borderHex);
        btn.setBackground(bg);

        btn.setOnTouchListener((v, event) -> {
            int action = event.getAction();
            if (action == MotionEvent.ACTION_DOWN) {
                v.setAlpha(0.4f);
                currentGamepadMask |= bitmask;
                EmulatorCore.nativeSendInput(1, currentGamepadMask, 0);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                v.setAlpha(1.0f);
                currentGamepadMask &= ~bitmask;
                EmulatorCore.nativeSendInput(1, currentGamepadMask, 0);
            }
            return true;
        });

        return btn;
    }

    private View createPPSSPPActionButton(String label, int bitmask, int borderHex) {
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        btn.setGravity(Gravity.CENTER);
        btn.setTypeface(Typeface.DEFAULT_BOLD);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#BB1A1A2E"));
        bg.setCornerRadius(dpToPx(26)); // Circular
        bg.setStroke(dpToPx(2), borderHex);
        btn.setBackground(bg);

        btn.setOnTouchListener((v, event) -> {
            int action = event.getAction();
            if (action == MotionEvent.ACTION_DOWN) {
                v.setAlpha(0.4f);
                currentGamepadMask |= bitmask;
                EmulatorCore.nativeSendInput(1, currentGamepadMask, 0);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                v.setAlpha(1.0f);
                currentGamepadMask &= ~bitmask;
                EmulatorCore.nativeSendInput(1, currentGamepadMask, 0);
            }
            return true;
        });

        return btn;
    }

    private View createPillButton(String label, int bitmask, int borderHex) {
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setTextColor(Color.parseColor("#9090B0")); // Text Secondary
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        btn.setGravity(Gravity.CENTER);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#BB252538"));
        bg.setCornerRadius(dpToPx(16)); // Pill shape
        bg.setStroke(dpToPx(1), borderHex);
        btn.setBackground(bg);

        if (bitmask != 0) {
            btn.setOnTouchListener((v, event) -> {
                int action = event.getAction();
                if (action == MotionEvent.ACTION_DOWN) {
                    v.setAlpha(0.4f);
                    currentGamepadMask |= bitmask;
                    EmulatorCore.nativeSendInput(1, currentGamepadMask, 0);
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    v.setAlpha(1.0f);
                    currentGamepadMask &= ~bitmask;
                    EmulatorCore.nativeSendInput(1, currentGamepadMask, 0);
                }
                return true;
            });
        }

        return btn;
    }

    private void cycleKeyboardState() {
        keyboardState = (keyboardState + 1) % 3; // Cycle: 0 -> 1 -> 2 -> 0
        updateKeyboardOverlay();
        if (kbToggleBtn != null) {
            kbToggleBtn.setTextColor(keyboardState > 0 ? Color.parseColor("#7B68EE") : Color.parseColor("#9090B0"));
        }
    }

    private void updateKeyboardOverlay() {
        keyboardContainer.removeAllViews();
        if (keyboardState == 0) {
            if (keyboardContainer.getVisibility() == View.VISIBLE) {
                android.view.animation.TranslateAnimation animate = new android.view.animation.TranslateAnimation(
                        0, 0, 0, keyboardContainer.getHeight());
                animate.setDuration(300);
                animate.setAnimationListener(new android.view.animation.Animation.AnimationListener() {
                    @Override public void onAnimationStart(android.view.animation.Animation animation) {}
                    @Override public void onAnimationRepeat(android.view.animation.Animation animation) {}
                    @Override public void onAnimationEnd(android.view.animation.Animation animation) {
                        keyboardContainer.setVisibility(View.GONE);
                    }
                });
                keyboardContainer.startAnimation(animate);
            }
        } else {
            if (keyboardContainer.getVisibility() != View.VISIBLE) {
                keyboardContainer.setVisibility(View.VISIBLE);
                android.view.animation.TranslateAnimation animate = new android.view.animation.TranslateAnimation(
                        0, 0, 500, 0);
                animate.setDuration(300);
                keyboardContainer.startAnimation(animate);
            }

            if (keyboardState == 1) {
                buildBasicKeyboard();
            } else {
                buildFullKeyboard();
            }
        }
    }

    private void buildBasicKeyboard() {
        String[][] basicLabels = {
            {"ESC", "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10"},
            {"TAB", "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"},
            {"CTRL", "A", "S", "D", "F", "G", "H", "J", "K", "L", "ENTER"},
            {"SHIFT", "Z", "X", "C", "V", "B", "N", "M", "SPACE", "BACK"}
        };
        int[][] basicCodes = {
            {111, 131, 132, 133, 134, 135, 136, 137, 138, 139, 140},
            {61, 45, 51, 33, 46, 48, 53, 49, 37, 43, 44},
            {113, 29, 47, 32, 34, 35, 36, 38, 39, 40, 66},
            {59, 54, 52, 31, 50, 30, 42, 41, 62, 67}
        };

        for (int r = 0; r < basicLabels.length; ++r) {
            keyboardContainer.addView(createHorizontalRow(basicLabels[r], basicCodes[r]));
        }
    }

    private void buildFullKeyboard() {
        String[][] fullLabels = {
            {"BREAK", "COPY", "DEL", "INS", "HOME", "END", "PGUP", "PGDN"},
            {"ESC", "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10"},
            {"`", "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "-", "=", "BACK"},
            {"TAB", "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P", "[", "]", "¥"},
            {"CTRL", "A", "S", "D", "F", "G", "H", "J", "K", "L", ";", "'", "ENTER"},
            {"SHIFT", "Z", "X", "C", "V", "B", "N", "M", ",", ".", "/", "SHIFT"},
            {"SPACE"},
            {"←", "↑", "↓", "→"}
        };
        int[][] fullCodes = {
            {121, 278, 112, 124, 122, 123, 92, 93},
            {111, 131, 132, 133, 134, 135, 136, 137, 138, 139, 140},
            {68, 8, 9, 10, 11, 12, 13, 14, 15, 16, 7, 69, 70, 67},
            {61, 45, 51, 33, 46, 48, 53, 49, 37, 43, 44, 71, 72, 252},
            {113, 29, 47, 32, 34, 35, 36, 38, 39, 40, 74, 75, 66},
            {59, 54, 52, 31, 50, 30, 42, 41, 55, 56, 76, 59},
            {62},
            {21, 19, 20, 22}
        };

        for (int r = 0; r < fullLabels.length; ++r) {
            keyboardContainer.addView(createHorizontalRow(fullLabels[r], fullCodes[r]));
        }
    }

    private View createHorizontalRow(String[] labels, int[] codes) {
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        rowParams.topMargin = dpToPx(4);
        rowParams.bottomMargin = dpToPx(4);
        hsv.setLayoutParams(rowParams);

        LinearLayout rowLayout = new LinearLayout(this);
        rowLayout.setOrientation(LinearLayout.HORIZONTAL);
        rowLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        for (int i = 0; i < labels.length; ++i) {
            rowLayout.addView(createKeyView(labels[i], codes[i]));
        }

        hsv.addView(rowLayout);
        return hsv;
    }

    private View createKeyView(String label, final int code) {
        TextView key = new TextView(this);
        key.setText(label);
        key.setGravity(Gravity.CENTER);
        key.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        key.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);

        boolean isSpecial = isSpecialKey(label);

        // Key style matching instructions
        GradientDrawable kBg = new GradientDrawable();
        kBg.setColor(Color.parseColor(isSpecial ? "#1C1D2E" : "#13141F"));
        kBg.setStroke(dpToPx(1), Color.parseColor("#252538"));
        kBg.setCornerRadius(dpToPx(6));
        key.setBackground(kBg);

        key.setTextColor(Color.parseColor(isSpecial ? "#7B68EE" : "#E8E8FF"));

        // Height & Width constraints (min height 36dp)
        int keyHeight = dpToPx(38);
        int keyWidth = "SPACE".equals(label) ? dpToPx(140) : dpToPx(42);
        if (label.length() > 3 && !"SPACE".equals(label)) {
            keyWidth = dpToPx(56);
        }

        LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(keyWidth, keyHeight);
        kp.leftMargin = dpToPx(4);
        kp.rightMargin = dpToPx(4);
        key.setLayoutParams(kp);

        // Key Touch Events mapping to nativeSendKey
        key.setOnTouchListener((v, event) -> {
            int action = event.getAction();
            if (action == MotionEvent.ACTION_DOWN) {
                v.setAlpha(0.4f);
                EmulatorCore.nativeSendKey(code, true);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                v.setAlpha(1.0f);
                EmulatorCore.nativeSendKey(code, false);
            }
            return true;
        });

        return key;
    }

    private boolean isSpecialKey(String label) {
        return "ESC".equals(label) || "CTRL".equals(label) || "SHIFT".equals(label) ||
               "ENTER".equals(label) || "BREAK".equals(label) || "COPY".equals(label) ||
               "BACK".equals(label) || "TAB".equals(label) || "INS".equals(label) ||
               "DEL".equals(label) || "HOME".equals(label) || "END".equals(label) ||
               "PGUP".equals(label) || "PGDN".equals(label);
    }

    private void populateQuickMenuOverlay() {
        if (quickMenuOverlay == null) return;
        quickMenuOverlay.removeAllViews();
        quickMenuOverlay.setBackgroundColor(Color.parseColor("#AA000000"));
        quickMenuOverlay.setVisibility(isMenuOpen ? View.VISIBLE : View.GONE);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        panelParams.gravity = Gravity.TOP;
        panel.setLayoutParams(panelParams);

        // PPSSPP: Surface elevated bg with primary border and 16dp bottom radius
        GradientDrawable pbBg = new GradientDrawable();
        pbBg.setColor(Color.parseColor("#F0131421"));
        pbBg.setCornerRadii(new float[]{0, 0, 0, 0, dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16)});
        pbBg.setStroke(dpToPx(1.5f), Color.parseColor("#7B68EE"));
        panel.setBackground(pbBg);

        TextView menuTitle = new TextView(this);
        menuTitle.setText("Quick Menu");
        menuTitle.setTextColor(Color.parseColor("#7B68EE"));
        menuTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        menuTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        menuTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams mtParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        mtParams.topMargin = dpToPx(16);
        mtParams.bottomMargin = dpToPx(16);
        menuTitle.setLayoutParams(mtParams);
        panel.addView(menuTitle);

        addButtonToPanel(panel, "💾", "Save State", v -> saveStatePrompt());
        addButtonToPanel(panel, "📂", "Load State", v -> loadStatePrompt());
        addButtonToPanel(panel, "🔄", "Toggle Auto-Rotate", v -> {
            int current = android.provider.Settings.System.getInt(
                getContentResolver(),
                android.provider.Settings.System.ACCELEROMETER_ROTATION, 0);
            if (current == 1) {
                setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED);
                Toast.makeText(this, "Auto-Rotate: OFF (Locked)", Toast.LENGTH_SHORT).show();
            } else {
                setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                Toast.makeText(this, "Auto-Rotate: ON", Toast.LENGTH_SHORT).show();
            }
            toggleQuickMenu();
        });
        addButtonToPanel(panel, "⚙️", "Settings", v -> {
            toggleQuickMenu();
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
        });
        addButtonToPanel(panel, "🔃", "Reset Emulator", v -> {
            EmulatorCore.nativeShutdown();
            initCoreAndLoad();
            toggleQuickMenu();
            Toast.makeText(this, "Emulator Reset Complete", Toast.LENGTH_SHORT).show();
        });
        addButtonToPanel(panel, "✕", "Exit Game", v -> showExitConfirmation());

        // Tap outside panel to dismiss
        quickMenuOverlay.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (event.getY() > panel.getHeight()) {
                    toggleQuickMenu();
                    return true;
                }
            }
            return false;
        });

        quickMenuOverlay.addView(panel);
    }

    private void addButtonToPanel(LinearLayout panel, String emoji, String label, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dpToPx(20), 0, dpToPx(20), 0);

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(52));
        rowParams.bottomMargin = dpToPx(1);
        row.setLayoutParams(rowParams);
        row.setBackgroundColor(Color.parseColor("#1C1D2E"));

        TextView iconView = new TextView(this);
        iconView.setText(emoji);
        iconView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        iconView.setPadding(0, 0, dpToPx(16), 0);
        row.addView(iconView);

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextColor(Color.parseColor("#E8E8FF"));
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        row.addView(labelView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.setOnClickListener(listener);
        panel.addView(row);

        // Divider
        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#252538"));
        panel.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
    }

    private void toggleQuickMenu() {
        if (isMenuOpen) {
            if (quickMenuOverlay != null) {
                android.view.animation.TranslateAnimation animate = new android.view.animation.TranslateAnimation(
                        0, 0, 0, -quickMenuOverlay.getHeight());
                animate.setDuration(300);
                animate.setAnimationListener(new android.view.animation.Animation.AnimationListener() {
                    @Override public void onAnimationStart(android.view.animation.Animation animation) {}
                    @Override public void onAnimationRepeat(android.view.animation.Animation animation) {}
                    @Override public void onAnimationEnd(android.view.animation.Animation animation) {
                        quickMenuOverlay.setVisibility(View.GONE);
                    }
                });
                quickMenuOverlay.startAnimation(animate);
            }
            isMenuOpen = false;
        } else {
            if (quickMenuOverlay != null) {
                quickMenuOverlay.setVisibility(View.VISIBLE);
                android.view.animation.TranslateAnimation animate = new android.view.animation.TranslateAnimation(
                        0, 0, -500, 0);
                animate.setDuration(300);
                quickMenuOverlay.startAnimation(animate);
            }
            isMenuOpen = true;
        }
    }

    private void saveStatePrompt() {
        if (isMenuOpen) toggleQuickMenu();
        String[] items = new String[]{"Slot 1", "Slot 2", "Slot 3"};
        new AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
                .setTitle("Save State")
                .setItems(items, (dialog, which) -> {
                    int slot = which + 1;
                    executeSaveState(slot);
                })
                .show();
    }

    private void loadStatePrompt() {
        if (isMenuOpen) toggleQuickMenu();
        String[] items = new String[]{"Slot 1", "Slot 2", "Slot 3"};
        new AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
                .setTitle("Load State")
                .setItems(items, (dialog, which) -> {
                    int slot = which + 1;
                    executeLoadState(slot);
                })
                .show();
    }

    private void executeSaveState(int slotIndex) {
        File stateFile = getStateFile(slotIndex);
        boolean ok = EmulatorCore.nativeSaveState(stateFile.getAbsolutePath());
        if (ok) {
            Toast.makeText(this, String.format(getString(R.string.state_saved), slotIndex), Toast.LENGTH_SHORT).show();
            Uri storageUri = StorageHelper.getPersistedUri(this);
            if (storageUri != null) {
                StorageHelper.syncLocalSavesToSAF(this, storageUri);
            }
        } else {
            Toast.makeText(this, String.format(getString(R.string.state_save_failed), slotIndex), Toast.LENGTH_LONG).show();
        }
    }

    private void executeLoadState(int slotIndex) {
        File stateFile = getStateFile(slotIndex);
        if (!stateFile.exists()) {
            Toast.makeText(this, "Save state slot " + slotIndex + " is empty.", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean ok = EmulatorCore.nativeLoadState(stateFile.getAbsolutePath());
        if (ok) {
            Toast.makeText(this, String.format(getString(R.string.state_loaded), slotIndex), Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, String.format(getString(R.string.state_load_failed), slotIndex), Toast.LENGTH_LONG).show();
        }
    }

    private File getStateFile(int slotIndex) {
        File localRoot = getExternalFilesDir(null);
        if (localRoot == null) localRoot = getFilesDir();
        File statesDir = new File(localRoot, StorageHelper.SUBFOLDER_STATES);
        if (!statesDir.exists()) statesDir.mkdirs();

        String baseName = gameName;
        int dot = gamePath.lastIndexOf(File.separatorChar);
        if (dot >= 0) {
            String fName = gamePath.substring(dot + 1);
            int dotExt = fName.lastIndexOf('.');
            baseName = dotExt > 0 ? fName.substring(0, dotExt) : fName;
        }
        return new File(statesDir, baseName + "_slot" + slotIndex + ".state");
    }

    private void initCoreAndLoad() {
        if (gamePath == null) {
            Log.e(TAG, "initCoreAndLoad: gamePath is null!");
            runOnUiThread(() -> Toast.makeText(this, "Game path is invalid.", Toast.LENGTH_LONG).show());
            return;
        }

        File localRoot = getExternalFilesDir(null);
        if (localRoot == null) {
            localRoot = getFilesDir();
        }
        if (localRoot == null) {
            Log.e(TAG, "initCoreAndLoad: External and internal files directories are null!");
            return;
        }

        File biosDir = new File(localRoot, StorageHelper.SUBFOLDER_BIOS);
        File romsDir = new File(localRoot, StorageHelper.SUBFOLDER_ROMS);

        FileLogger.log("Java: BIOS loading starting from directory: " + biosDir.getAbsolutePath());

        if (!biosDir.exists()) {
            if (!biosDir.mkdirs()) {
                Log.e(TAG, "initCoreAndLoad: Failed to create bios directory: " + biosDir.getAbsolutePath());
            }
        }
        if (!romsDir.exists()) {
            if (!romsDir.mkdirs()) {
                Log.e(TAG, "initCoreAndLoad: Failed to create roms directory: " + romsDir.getAbsolutePath());
            }
        }

        Log.i(TAG, "initCoreAndLoad: Initializing emulator core...");
        FileLogger.log("Java: EmulatorCore init starting...");
        EmulatorCore.nativeSetLogFilePath(FileLogger.getLogFilePath());

        // Configure BIOS mode and custom mappings before initialization
        BIOSFileMapper.BIOSStatus status = BIOSFileMapper.getStatus(this);

        if (status.hasDeprecated && !status.isOK) {
            runOnUiThread(() -> {
                new AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
                        .setTitle("Deprecated BIOS Found")
                        .setMessage("TOWNS.SYS is deprecated. Use FMT_SYS.ROM from retrobios.")
                        .setCancelable(false)
                        .setPositiveButton("OK", (dialog, which) -> {
                            finish();
                        })
                        .show();
            });
            return;
        }

        if (!status.isOK) {
            final String errorMsg;
            if (status.fileCount == 0) {
                errorMsg = "Please add BIOS files from https://github.com/Abdess/retrobios";
            } else {
                errorMsg = "Missing: " + status.missingFiles.toString() + "\n\nPlease add FMT_SYS.ROM and FMT_FNT.ROM from https://github.com/Abdess/retrobios";
            }
            runOnUiThread(() -> {
                new AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
                        .setTitle("BIOS Setup Incomplete")
                        .setMessage(errorMsg)
                        .setCancelable(false)
                        .setPositiveButton("OK", (dialog, which) -> {
                            finish();
                        })
                        .show();
            });
            return;
        }

        if (status.hasDeprecated) {
            runOnUiThread(() -> {
                Toast.makeText(this, "TOWNS.SYS is deprecated. Use FMT_SYS.ROM from retrobios.", Toast.LENGTH_LONG).show();
            });
        }

        int jniMode = 0; // PC/Auto
        if (status.configuredMode == BIOSFileMapper.Mode.MARTY ||
            (status.configuredMode == BIOSFileMapper.Mode.AUTO && status.detectedMode == BIOSFileMapper.Mode.MARTY)) {
            jniMode = 1; // Marty
        } else if (status.configuredMode == BIOSFileMapper.Mode.CUSTOM) {
            jniMode = 2; // Custom
        }

        EmulatorCore.nativeClearBIOSFileMappings();
        EmulatorCore.nativeSetBIOSMode(jniMode);

        // Load custom BIOS paths from configuration
        Config appConfig = ConfigManager.loadConfig(this);
        if (appConfig.biosSetupComplete && appConfig.biosPath != null && !appConfig.biosPath.isEmpty()) {
            BiosInfo biosInfo = BiosScanner.scanFolder(this, appConfig.biosPath);
            if (biosInfo.hasSystemBios && biosInfo.hasFontRom) {
                EmulatorCore.loadBIOS(biosInfo.systemBiosPath, biosInfo.fontRomPath);
            }
        }

        java.util.Map<String, String> mappings = BIOSFileMapper.getFileMappings(this);
        for (java.util.Map.Entry<String, String> entry : mappings.entrySet()) {
            EmulatorCore.nativeSetBIOSFileMapping(entry.getKey(), entry.getValue());
        }

        runOnUiThread(() -> {
            Toast.makeText(this, "Detected: " + status.statusMessage, Toast.LENGTH_SHORT).show();
        });

        boolean inited = EmulatorCore.nativeInit(biosDir.getAbsolutePath(), romsDir.getAbsolutePath());
        FileLogger.log("Java: EmulatorCore init finished with result: " + inited);
        if (!inited) {
            Log.e(TAG, "initCoreAndLoad: Emulator core initialization failed.");
            runOnUiThread(() -> {
                Toast.makeText(this, "Core Setup failed. Ensure you copied your FM Towns BIOS ROMs to the bios/ folder.", Toast.LENGTH_LONG).show();
            });
            return;
        }

        Log.i(TAG, "initCoreAndLoad: Core initialized successfully. Loading game: " + gamePath);
        FileLogger.log("Java: Loading game: " + gamePath);
        boolean loaded = false;
        String lowerGamePath = gamePath.toLowerCase();
        if (lowerGamePath.endsWith(".iso") || lowerGamePath.endsWith(".mds") ||
            lowerGamePath.endsWith(".cue") || lowerGamePath.endsWith(".chd") ||
            lowerGamePath.endsWith(".ccd")) {
            loaded = EmulatorCore.nativeLoadDisc(gamePath);
        } else if (lowerGamePath.endsWith(".d77") || lowerGamePath.endsWith(".img")) {
            loaded = EmulatorCore.nativeLoadDisc(gamePath); // Bridge handles floppy
        } else {
            loaded = EmulatorCore.nativeLoadROM(gamePath);
            if (!loaded) {
                loaded = EmulatorCore.nativeLoadDisc(gamePath);
            }
        }

        if (!loaded) {
            Log.e(TAG, "initCoreAndLoad: Failed to load game: " + gamePath);
            runOnUiThread(() -> {
                Toast.makeText(this, "Failed to load game image.", Toast.LENGTH_LONG).show();
            });
            return;
        }

        Log.i(TAG, "initCoreAndLoad: Game loaded successfully. Starting background emulator thread.");
        isCoreInitialized = true;
        if (isRunning && emuHandler != null) {
            emuHandler.post(emuRunnable);
        }
    }

    private void showExitConfirmation() {
        if (isMenuOpen) toggleQuickMenu();
        new AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
                .setTitle(getString(R.string.confirm_exit_title))
                .setMessage(getString(R.string.confirm_exit_message))
                .setPositiveButton(getString(R.string.yes), (dialog, which) -> {
                    exitEmulator();
                })
                .setNegativeButton(getString(R.string.no), null)
                .show();
    }

    private void exitEmulator() {
        stopEmuThread();
        EmulatorCore.nativeShutdown();
        Uri storageUri = StorageHelper.getPersistedUri(this);
        if (storageUri != null) {
            StorageHelper.syncLocalSavesToSAF(this, storageUri);
        }
        finish();
    }

    @Override
    public void onBackPressed() {
        showExitConfirmation();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopEmuThread();
        Uri storageUri = StorageHelper.getPersistedUri(this);
        if (storageUri != null) {
            StorageHelper.syncLocalSavesToSAF(this, storageUri);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSettings();
        if (surfaceView != null) {
            surfaceView.setScreenFilterBilinear(screenFilterBilinear);
        } else if (gpuView != null) {
            gpuView.setScreenFilterBilinear(screenFilterBilinear);
        }
        if (fpsCounterText != null) {
            fpsCounterText.setVisibility(showFps ? View.VISIBLE : View.GONE);
        }
        if (virtualPadContainer != null) {
            virtualPadContainer.setAlpha(virtualPadOpacity);
        }
        if (isCoreInitialized) {
            startEmuThread();
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        stopEmuThread(); // Stop BEFORE destroying views
        updateLayoutForOrientation(newConfig.orientation);
        if (isCoreInitialized) {
            startEmuThread(); // Restart AFTER layout is rebuilt
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopEmuThread();
        EmulatorCore.nativeShutdown();
        surfaceView = null;
        gpuView = null;
        if (audioBridge != null) {
            audioBridge.stop();
            audioBridge = null;
        }
    }

    private int dpToPx(float dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics()
        );
    }
}
