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

    private EmulatorSurfaceView surfaceView;
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

    // Running states
    private volatile boolean isRunning = false;
    private boolean isCoreInitialized = false;
    private int currentGamepadMask = 0;
    private long fpsLastTime = 0;
    private int fpsFrameCount = 0;

    private HandlerThread emuThread;
    private Handler emuHandler;
    private final Runnable emuRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRunning) {
                long startTime = SystemClock.elapsedRealtime();

                EmulatorCore.nativeRunFrame();
                if (surfaceView != null) {
                    surfaceView.drawFrame();
                }

                fpsFrameCount++;
                long now = SystemClock.elapsedRealtime();
                long diff = now - fpsLastTime;
                if (diff >= 1000) {
                    final int calculatedFps = Math.round((float) fpsFrameCount * 1000.0f / diff);
                    runOnUiThread(() -> {
                        if (fpsCounterText != null) {
                            fpsCounterText.setText(calculatedFps + " FPS");
                        }
                    });
                    fpsLastTime = now;
                    fpsFrameCount = 0;
                }

                long elapsed = SystemClock.elapsedRealtime() - startTime;
                long delay = Math.max(0, 16 - elapsed);
                if (emuHandler != null) {
                    emuHandler.postDelayed(this, delay);
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
        emuHandler.post(emuRunnable);
    }

    private synchronized void stopEmuThread() {
        isRunning = false;
        if (emuHandler != null) {
            emuHandler.removeCallbacks(emuRunnable);
        }
        if (emuThread != null) {
            emuThread.quit();
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FileLogger.init(this);
        FileLogger.log("Java: EmulatorActivity onCreate called");

        // Keep screen on
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        // Get extras
        gamePath = getIntent().getStringExtra("game_path");
        gameName = getIntent().getStringExtra("game_name");
        if (gameName == null) gameName = "FM Towns Game";

        // Load Settings
        loadSettings();

        // Root Layout (FrameLayout for overlaying Quick Menu)
        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.parseColor("#0D0D0F"));
        rootLayout.setFitsSystemWindows(true);
        rootLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        LinearLayout mainVerticalLayout = new LinearLayout(this);
        mainVerticalLayout.setOrientation(LinearLayout.VERTICAL);
        mainVerticalLayout.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // 1. TOP Emulator Surface View (4:3 lock scaled centered in weight-based container)
        setupSurfaceView();
        FrameLayout surfaceWrapper = new FrameLayout(this);
        LinearLayout.LayoutParams wrapperParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f
        );
        surfaceWrapper.setLayoutParams(wrapperParams);
        surfaceWrapper.setBackgroundColor(Color.BLACK);
        surfaceWrapper.addView(surfaceView);
        mainVerticalLayout.addView(surfaceWrapper);

        // 2. MIDDLE Keyboard Overlay (slides/appears ABOVE gamepad area, fits between wrapper and status bar)
        setupSoftKeyboardContainer();
        mainVerticalLayout.addView(keyboardContainer);

        // 3. MIDDLE Status Bar above controls (⌨️ on left, ☰ on right, name/FPS in center)
        setupStatusMenuBar();
        mainVerticalLayout.addView(statusMenuBar);

        // 4. BOTTOM Virtual Gamepad (Portrait, standard 240dp height)
        setupControlsArea();
        mainVerticalLayout.addView(controlsArea);

        rootLayout.addView(mainVerticalLayout);

        // 5. Quick Menu Overlay (Initially GONE)
        setupQuickMenuOverlay();
        rootLayout.addView(quickMenuOverlay);

        setContentView(rootLayout);

        // Initialize core and start emulation loop
        initCoreAndLoad();
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        showFps = prefs.getBoolean("show_fps", true);
        screenFilterBilinear = prefs.getBoolean("screen_filter_bilinear", false);
        virtualPadOpacity = (float) prefs.getInt("virtual_pad_opacity", 70) / 100.0f;
        virtualPadSize = prefs.getString("virtual_pad_size", "medium");
    }

    private void setupStatusMenuBar() {
        statusMenuBar = new LinearLayout(this);
        statusMenuBar.setOrientation(LinearLayout.HORIZONTAL);
        statusMenuBar.setBackgroundColor(Color.parseColor("#111318"));
        statusMenuBar.setGravity(Gravity.CENTER_VERTICAL);
        statusMenuBar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(48) // 48dp high for comfortable touch targets
        ));

        // Left ⌨️ button
        kbToggleBtn = new TextView(this);
        kbToggleBtn.setText("⌨️");
        kbToggleBtn.setGravity(Gravity.CENTER);
        kbToggleBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        kbToggleBtn.setPadding(dpToPx(16), 0, dpToPx(16), 0);
        LinearLayout.LayoutParams kbParams = new LinearLayout.LayoutParams(
                dpToPx(56), ViewGroup.LayoutParams.MATCH_PARENT
        );
        kbToggleBtn.setLayoutParams(kbParams);
        kbToggleBtn.setOnClickListener(v -> cycleKeyboardState());
        statusMenuBar.addView(kbToggleBtn);

        // Centered info layout (Name & FPS)
        LinearLayout infoCenter = new LinearLayout(this);
        infoCenter.setOrientation(LinearLayout.VERTICAL);
        infoCenter.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f
        );
        infoCenter.setLayoutParams(infoParams);

        gameNameText = new TextView(this);
        gameNameText.setText(gameName);
        gameNameText.setTextColor(Color.parseColor("#E0E0FF"));
        gameNameText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        gameNameText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        gameNameText.setSingleLine(true);
        infoCenter.addView(gameNameText);

        fpsCounterText = new TextView(this);
        fpsCounterText.setText("0 FPS");
        fpsCounterText.setTextColor(Color.parseColor("#7B6FFF"));
        fpsCounterText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        fpsCounterText.setTypeface(Typeface.MONOSPACE);
        fpsCounterText.setVisibility(showFps ? View.VISIBLE : View.GONE);
        infoCenter.addView(fpsCounterText);

        statusMenuBar.addView(infoCenter);

        // Right ☰ menu button
        menuOpenBtn = new TextView(this);
        menuOpenBtn.setText("☰");
        menuOpenBtn.setTextColor(Color.parseColor("#7B6FFF"));
        menuOpenBtn.setGravity(Gravity.CENTER);
        menuOpenBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        menuOpenBtn.setPadding(dpToPx(16), 0, dpToPx(16), 0);
        LinearLayout.LayoutParams menuParams = new LinearLayout.LayoutParams(
                dpToPx(56), ViewGroup.LayoutParams.MATCH_PARENT
        );
        menuOpenBtn.setLayoutParams(menuParams);
        menuOpenBtn.setOnClickListener(v -> toggleQuickMenu());
        statusMenuBar.addView(menuOpenBtn);
    }

    private void setupSurfaceView() {
        surfaceView = new EmulatorSurfaceView(this);
        surfaceView.setScreenFilterBilinear(screenFilterBilinear);

        FrameLayout.LayoutParams svParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        svParams.gravity = Gravity.CENTER;
        surfaceView.setLayoutParams(svParams);

        setupMouseInput();
    }

    private void setupMouseInput() {
        surfaceView.setOnTouchListener((v, event) -> {
            int action = event.getAction();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) {
                int viewWidth = surfaceView.getWidth();
                int viewHeight = surfaceView.getHeight();
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

                    int mouseX = Math.round((relativeX / scaledWidth) * width);
                    int mouseY = Math.round((relativeY / scaledHeight) * height);

                    mouseX = Math.max(0, Math.min(639, mouseX));
                    mouseY = Math.max(0, Math.min(479, mouseY));

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

    private void setupControlsArea() {
        controlsArea = new FrameLayout(this);
        LinearLayout.LayoutParams caParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(240) // 240dp high for gamepad area
        );
        controlsArea.setLayoutParams(caParams);
        controlsArea.setBackgroundColor(Color.parseColor("#0D0D0F"));

        // Authentic FM Towns Marty layout gamepad area
        setupVirtualGamepad();
        controlsArea.addView(virtualPadContainer);
    }

    private void setupVirtualGamepad() {
        virtualPadContainer = new RelativeLayout(this);
        virtualPadContainer.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        virtualPadContainer.setAlpha(virtualPadOpacity);

        // Adjust dimensions based on SharedPreferences size setting
        float scale = 1.0f;
        if ("small".equals(virtualPadSize)) {
            scale = 0.82f;
        } else if ("large".equals(virtualPadSize)) {
            scale = 1.15f;
        }

        int dpadSize = (int) (dpToPx(140) * scale);

        // 1. LEFT SIDE: D-Pad (cross style)
        RelativeLayout dpadLayout = new RelativeLayout(this);
        RelativeLayout.LayoutParams dpadParams = new RelativeLayout.LayoutParams(
                dpadSize, dpadSize
        );
        dpadParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
        dpadParams.addRule(RelativeLayout.CENTER_VERTICAL);
        dpadParams.leftMargin = dpToPx(16);
        dpadLayout.setLayoutParams(dpadParams);

        int btnSize = dpadSize / 3;

        View upBtn = createVirtualButton("▲", 1 << 4, Color.parseColor("#7B6FFF"));
        RelativeLayout.LayoutParams upParams = new RelativeLayout.LayoutParams(btnSize, btnSize);
        upParams.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        upParams.addRule(RelativeLayout.CENTER_HORIZONTAL);
        upBtn.setLayoutParams(upParams);
        dpadLayout.addView(upBtn);

        View downBtn = createVirtualButton("▼", 1 << 5, Color.parseColor("#7B6FFF"));
        RelativeLayout.LayoutParams downParams = new RelativeLayout.LayoutParams(btnSize, btnSize);
        downParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        downParams.addRule(RelativeLayout.CENTER_HORIZONTAL);
        downBtn.setLayoutParams(downParams);
        dpadLayout.addView(downBtn);

        View leftBtn = createVirtualButton("◀", 1 << 2, Color.parseColor("#7B6FFF"));
        RelativeLayout.LayoutParams leftParams = new RelativeLayout.LayoutParams(btnSize, btnSize);
        leftParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
        leftParams.addRule(RelativeLayout.CENTER_VERTICAL);
        leftBtn.setLayoutParams(leftParams);
        dpadLayout.addView(leftBtn);

        View rightBtn = createVirtualButton("▶", 1 << 3, Color.parseColor("#7B6FFF"));
        RelativeLayout.LayoutParams rightParams = new RelativeLayout.LayoutParams(btnSize, btnSize);
        rightParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        rightParams.addRule(RelativeLayout.CENTER_VERTICAL);
        rightBtn.setLayoutParams(rightParams);
        dpadLayout.addView(rightBtn);

        virtualPadContainer.addView(dpadLayout);

        // 2. RIGHT SIDE: Only 2 buttons in vertical layout (A: Red Top, B: Blue Bottom)
        LinearLayout rightBtnsLayout = new LinearLayout(this);
        rightBtnsLayout.setOrientation(LinearLayout.VERTICAL);
        rightBtnsLayout.setGravity(Gravity.CENTER_HORIZONTAL);
        RelativeLayout.LayoutParams rightBtnsParams = new RelativeLayout.LayoutParams(
                (int) (dpToPx(72) * scale), ViewGroup.LayoutParams.WRAP_CONTENT
        );
        rightBtnsParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        rightBtnsParams.addRule(RelativeLayout.CENTER_VERTICAL);
        rightBtnsParams.rightMargin = dpToPx(24);
        rightBtnsLayout.setLayoutParams(rightBtnsParams);

        int actBtnSize = (int) (dpToPx(52) * scale);

        // A Button (Red, Top) -> bitmask 1 << 0
        View aBtn = createCircularGamepadBtn("A", 1 << 0, Color.parseColor("#FF4D4D"));
        LinearLayout.LayoutParams aParams = new LinearLayout.LayoutParams(actBtnSize, actBtnSize);
        aParams.bottomMargin = dpToPx(16);
        aBtn.setLayoutParams(aParams);
        rightBtnsLayout.addView(aBtn);

        // B Button (Blue, Bottom) -> bitmask 1 << 1
        View bBtn = createCircularGamepadBtn("B", 1 << 1, Color.parseColor("#4D94FF"));
        LinearLayout.LayoutParams bParams = new LinearLayout.LayoutParams(actBtnSize, actBtnSize);
        bBtn.setLayoutParams(bParams);
        rightBtnsLayout.addView(bBtn);

        virtualPadContainer.addView(rightBtnsLayout);

        // 3. CENTER: SELECT, RUN (start), SAVE (💾), LOAD (📂)
        LinearLayout centerBtns = new LinearLayout(this);
        centerBtns.setOrientation(LinearLayout.HORIZONTAL);
        centerBtns.setGravity(Gravity.CENTER);
        RelativeLayout.LayoutParams centerParams = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        centerParams.addRule(RelativeLayout.CENTER_IN_PARENT);
        centerBtns.setLayoutParams(centerParams);

        int centW = (int) (dpToPx(48) * scale);
        int centH = (int) (dpToPx(36) * scale);

        // SELECT button
        View selectBtn = createVirtualButton("SEL", 1 << 7, Color.parseColor("#808080"));
        LinearLayout.LayoutParams selParams = new LinearLayout.LayoutParams(centW, centH);
        selParams.rightMargin = dpToPx(6);
        selectBtn.setLayoutParams(selParams);
        centerBtns.addView(selectBtn);

        // RUN button (Start on FM Towns Marty)
        View runBtn = createVirtualButton("RUN", 1 << 6, Color.parseColor("#808080"));
        LinearLayout.LayoutParams runParams = new LinearLayout.LayoutParams(centW, centH);
        runParams.rightMargin = dpToPx(10);
        runBtn.setLayoutParams(runParams);
        centerBtns.addView(runBtn);

        // SAVE State button (Floppy 💾)
        View saveBtn = createTextOnlyBtn("💾 SV", Color.parseColor("#7B6FFF"));
        LinearLayout.LayoutParams savParams = new LinearLayout.LayoutParams((int)(dpToPx(56)*scale), centH);
        savParams.rightMargin = dpToPx(6);
        saveBtn.setLayoutParams(savParams);
        saveBtn.setOnClickListener(v -> saveStatePrompt());
        centerBtns.addView(saveBtn);

        // LOAD State button (Floppy/Open 📂)
        View loadBtn = createTextOnlyBtn("📂 LD", Color.parseColor("#7B6FFF"));
        LinearLayout.LayoutParams loaParams = new LinearLayout.LayoutParams((int)(dpToPx(56)*scale), centH);
        loadBtn.setLayoutParams(loaParams);
        loadBtn.setOnClickListener(v -> loadStatePrompt());
        centerBtns.addView(loadBtn);

        virtualPadContainer.addView(centerBtns);
    }

    private View createCircularGamepadBtn(String label, int bitmask, int borderHex) {
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        btn.setGravity(Gravity.CENTER);
        btn.setTypeface(Typeface.DEFAULT_BOLD);

        // Circular background with border
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#D9111318"));
        bg.setCornerRadius(dpToPx(28));
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

    private View createVirtualButton(String label, int bitmask, int borderHex) {
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        btn.setGravity(Gravity.CENTER);
        btn.setTypeface(Typeface.DEFAULT_BOLD);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#D9111318"));
        bg.setCornerRadius(dpToPx(20));
        bg.setStroke(dpToPx(2), borderHex);
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

    private View createTextOnlyBtn(String label, int borderHex) {
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        btn.setGravity(Gravity.CENTER);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#E6111318"));
        bg.setCornerRadius(dpToPx(8));
        bg.setStroke(dpToPx(1), borderHex);
        btn.setBackground(bg);

        return btn;
    }

    private void setupSoftKeyboardContainer() {
        keyboardContainer = new LinearLayout(this);
        keyboardContainer.setOrientation(LinearLayout.VERTICAL);
        keyboardContainer.setBackgroundColor(Color.parseColor("#CC0D0D0F")); // Semi-transparent dark background
        keyboardContainer.setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4));
        keyboardContainer.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
    }

    private void cycleKeyboardState() {
        keyboardState = (keyboardState + 1) % 3; // Cycle: 0 -> 1 -> 2 -> 0
        updateKeyboardOverlay();
    }

    private void updateKeyboardOverlay() {
        keyboardContainer.removeAllViews();
        if (keyboardState == 0) {
            keyboardContainer.setVisibility(View.GONE);
            kbToggleBtn.setTextColor(Color.WHITE);
        } else {
            keyboardContainer.setVisibility(View.VISIBLE);
            kbToggleBtn.setTextColor(Color.parseColor("#7B6FFF")); // Highlight keyboard icon

            if (keyboardState == 1) {
                // Basic mode
                buildBasicKeyboard();
            } else {
                // Full mode
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
        kBg.setColor(Color.parseColor(isSpecial ? "#1F1F35" : "#1A1A28"));
        kBg.setStroke(dpToPx(1), Color.parseColor("#2A2A3A"));
        kBg.setCornerRadius(dpToPx(6));
        key.setBackground(kBg);

        key.setTextColor(Color.parseColor(isSpecial ? "#7B6FFF" : "#E0E0FF"));

        // Height & Width constraints (min height 36dp)
        int keyHeight = dpToPx(38);
        int keyWidth = "SPACE".equals(label) ? dpToPx(160) : dpToPx(42);
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

    private void setupQuickMenuOverlay() {
        quickMenuOverlay = new FrameLayout(this);
        FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        quickMenuOverlay.setLayoutParams(overlayParams);
        quickMenuOverlay.setBackgroundColor(Color.parseColor("#AA000000"));
        quickMenuOverlay.setVisibility(View.GONE);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.parseColor("#111318"));
        panel.setPadding(dpToPx(24), dpToPx(32), dpToPx(24), dpToPx(32));
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        panelParams.gravity = Gravity.TOP;
        panel.setLayoutParams(panelParams);

        GradientDrawable pbBg = new GradientDrawable();
        pbBg.setColor(Color.parseColor("#111318"));
        pbBg.setStroke(dpToPx(1), Color.parseColor("#7B6FFF"));
        panel.setBackground(pbBg);

        TextView menuTitle = new TextView(this);
        menuTitle.setText("FM Infinite — Quick Menu");
        menuTitle.setTextColor(Color.parseColor("#7B6FFF"));
        menuTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        menuTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        menuTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams mtParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        mtParams.bottomMargin = dpToPx(24);
        menuTitle.setLayoutParams(mtParams);
        panel.addView(menuTitle);

        addButtonToPanel(panel, getString(R.string.quick_save_state), v -> saveStatePrompt());
        addButtonToPanel(panel, getString(R.string.quick_load_state), v -> loadStatePrompt());
        addButtonToPanel(panel, getString(R.string.quick_reset), v -> {
            EmulatorCore.nativeShutdown();
            initCoreAndLoad();
            toggleQuickMenu();
            Toast.makeText(this, "Emulator Reset Complete", Toast.LENGTH_SHORT).show();
        });
        addButtonToPanel(panel, getString(R.string.quick_settings), v -> {
            toggleQuickMenu();
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
        });
        addButtonToPanel(panel, getString(R.string.quick_exit), v -> showExitConfirmation());

        TextView closeBtn = new TextView(this);
        closeBtn.setText("Dismiss Menu ✕");
        closeBtn.setTextColor(Color.parseColor("#666680"));
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12));
        closeBtn.setOnClickListener(v -> toggleQuickMenu());
        LinearLayout.LayoutParams cbParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cbParams.topMargin = dpToPx(16);
        closeBtn.setLayoutParams(cbParams);
        panel.addView(closeBtn);

        quickMenuOverlay.addView(panel);

        quickMenuOverlay.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                toggleQuickMenu();
                return true;
            }
            return false;
        });
    }

    private void addButtonToPanel(LinearLayout panel, String label, View.OnClickListener listener) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        btn.setAllCaps(false);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1C1E24"));
        bg.setCornerRadius(dpToPx(8));
        btn.setBackground(bg);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(48)
        );
        params.bottomMargin = dpToPx(10);
        btn.setLayoutParams(params);
        btn.setOnClickListener(listener);

        panel.addView(btn);
    }

    private void toggleQuickMenu() {
        if (isMenuOpen) {
            quickMenuOverlay.setVisibility(View.GONE);
            isMenuOpen = false;
        } else {
            quickMenuOverlay.setVisibility(View.VISIBLE);
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
            Toast.makeText(this, "Game path is invalid.", Toast.LENGTH_LONG).show();
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

        String activeBiosMode = BIOSFileMapper.getActiveBIOSMode(this);
        EmulatorCore.nativeSetBIOSMode(activeBiosMode);
        EmulatorCore.nativeSetBIOSFileMapping(
                BIOSFileMapper.getMappedSys(this),
                BIOSFileMapper.getMappedFnt(this),
                BIOSFileMapper.getMappedDos(this),
                BIOSFileMapper.getMappedDic(this)
        );

        boolean inited = EmulatorCore.nativeInit(biosDir.getAbsolutePath(), romsDir.getAbsolutePath());
        FileLogger.log("Java: EmulatorCore init finished with result: " + inited);
        if (!inited) {
            Log.e(TAG, "initCoreAndLoad: Emulator core initialization failed.");
            Toast.makeText(this, "Core Setup failed. Ensure you copied your FM Towns BIOS ROMs to the bios/ folder.", Toast.LENGTH_LONG).show();
            return;
        }

        Log.i(TAG, "initCoreAndLoad: Core initialized successfully. Loading game: " + gamePath);
        FileLogger.log("Java: Loading game: " + gamePath);
        boolean loaded = false;
        if (gamePath.toLowerCase().endsWith(".iso") || gamePath.toLowerCase().endsWith(".mds") || gamePath.toLowerCase().endsWith(".cue") || gamePath.toLowerCase().endsWith(".chd")) {
            loaded = EmulatorCore.nativeLoadDisc(gamePath);
        } else {
            loaded = EmulatorCore.nativeLoadROM(gamePath);
            if (!loaded) {
                loaded = EmulatorCore.nativeLoadDisc(gamePath);
            }
        }

        if (!loaded) {
            Log.e(TAG, "initCoreAndLoad: Failed to load game: " + gamePath);
            Toast.makeText(this, "Failed to load game image.", Toast.LENGTH_LONG).show();
            return;
        }

        Log.i(TAG, "initCoreAndLoad: Game loaded successfully. Starting background emulator thread.");
        isCoreInitialized = true;
        startEmuThread();
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
        // Do nothing to prevent activity restart on rotation
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopEmuThread();
        EmulatorCore.nativeShutdown();
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics()
        );
    }
}
