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
import android.widget.GridLayout;
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

    // Top bar
    private LinearLayout topStatusBar;
    private TextView gameNameText;
    private TextView fpsCounterText;

    // Controls & Overlays
    private FrameLayout controlsArea;
    private RelativeLayout virtualPadContainer;
    private LinearLayout keyboardContainer;
    private FrameLayout quickMenuOverlay;

    // Running states
    private boolean isRunning = false;
    private int currentGamepadMask = 0;
    private long fpsLastTime = 0;
    private int fpsFrameCount = 0;
    private boolean isKeyboardOpen = false;
    private boolean isMenuOpen = false;

    // SharedPreferences settings
    private boolean showFps = true;
    private boolean screenFilterBilinear = false;
    private float virtualPadOpacity = 0.7f;
    private String virtualPadSize = "medium";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep screen on
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        // Get extras
        gamePath = getIntent().getStringExtra("game_path");
        gameName = getIntent().getStringExtra("game_name");
        if (gameName == null) gameName = "FM Towns Game";

        // Load Settings
        loadSettings();

        // Root Layout (Vertical)
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

        // 1. TOP Status Bar (Game name + FPS)
        setupTopStatusBar();
        mainVerticalLayout.addView(topStatusBar);

        // 2. MIDDLE Emulator Surface View (4:3 lock scaled)
        setupSurfaceView();

        // Wrap surface in a weight-based container so it takes maximum remaining vertical space
        FrameLayout surfaceWrapper = new FrameLayout(this);
        LinearLayout.LayoutParams wrapperParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f
        );
        surfaceWrapper.setLayoutParams(wrapperParams);
        surfaceWrapper.setBackgroundColor(Color.BLACK);
        surfaceWrapper.addView(surfaceView);
        mainVerticalLayout.addView(surfaceWrapper);

        // 3. BOTTOM Controls Area (Portrait)
        setupControlsArea();
        mainVerticalLayout.addView(controlsArea);

        rootLayout.addView(mainVerticalLayout);

        // 4. Quick Menu Overlay (Initially GONE)
        setupQuickMenuOverlay();
        rootLayout.addView(quickMenuOverlay);

        setContentView(rootLayout);

        // Start Core
        initCoreAndLoad();
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        showFps = prefs.getBoolean("show_fps", true);
        screenFilterBilinear = prefs.getBoolean("screen_filter_bilinear", false);
        virtualPadOpacity = (float) prefs.getInt("virtual_pad_opacity", 70) / 100.0f;
        virtualPadSize = prefs.getString("virtual_pad_size", "medium");
    }

    private void setupTopStatusBar() {
        topStatusBar = new LinearLayout(this);
        topStatusBar.setOrientation(LinearLayout.HORIZONTAL);
        topStatusBar.setBackgroundColor(Color.parseColor("#111318"));
        topStatusBar.setPadding(dpToPx(16), dpToPx(6), dpToPx(16), dpToPx(6));
        topStatusBar.setGravity(Gravity.CENTER_VERTICAL);
        topStatusBar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(36)
        ));

        gameNameText = new TextView(this);
        gameNameText.setText(gameName);
        gameNameText.setTextColor(Color.parseColor("#E0E0FF"));
        gameNameText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        gameNameText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        topStatusBar.addView(gameNameText, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f
        ));

        fpsCounterText = new TextView(this);
        fpsCounterText.setText("0 FPS");
        fpsCounterText.setTextColor(Color.parseColor("#7B6FFF"));
        fpsCounterText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        fpsCounterText.setTypeface(Typeface.MONOSPACE);
        fpsCounterText.setVisibility(showFps ? View.VISIBLE : View.GONE);
        topStatusBar.addView(fpsCounterText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
    }

    private void setupSurfaceView() {
        surfaceView = new EmulatorSurfaceView(this);
        surfaceView.setScreenFilterBilinear(screenFilterBilinear);

        // Standard 4:3 lock layout params centered inside wrapper
        FrameLayout.LayoutParams svParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        svParams.gravity = Gravity.CENTER;
        surfaceView.setLayoutParams(svParams);

        // Keep direct mouse touch input from MainActivity
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
                        clickState |= 1; // Left click on down/move
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
                dpToPx(240) // Standard height of virtual controller area
        );
        controlsArea.setLayoutParams(caParams);
        controlsArea.setBackgroundColor(Color.parseColor("#0D0D0F"));

        // A. Virtual Pad Overlay Container
        setupVirtualGamepad();
        controlsArea.addView(virtualPadContainer);

        // B. Custom Soft Keyboard Panel Overlay
        setupSoftKeyboard();
        keyboardContainer.setVisibility(View.GONE);
        controlsArea.addView(keyboardContainer);
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
            scale = 0.8f;
        } else if ("large".equals(virtualPadSize)) {
            scale = 1.15f;
        }

        int dpadSize = (int) (dpToPx(136) * scale);
        int actionSize = (int) (dpToPx(136) * scale);

        // 1. LEFT side: D-Pad (cross style)
        RelativeLayout dpadLayout = new RelativeLayout(this);
        RelativeLayout.LayoutParams dpadParams = new RelativeLayout.LayoutParams(
                dpadSize, dpadSize
        );
        dpadParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
        dpadParams.addRule(RelativeLayout.CENTER_VERTICAL);
        dpadParams.leftMargin = dpToPx(12);
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

        // 2. RIGHT side: 4 Action Buttons in Diamond layout
        RelativeLayout diamondLayout = new RelativeLayout(this);
        RelativeLayout.LayoutParams diamondParams = new RelativeLayout.LayoutParams(
                actionSize, actionSize
        );
        diamondParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        diamondParams.addRule(RelativeLayout.CENTER_VERTICAL);
        diamondParams.rightMargin = dpToPx(12);
        diamondLayout.setLayoutParams(diamondParams);

        int actBtnSize = actionSize / 3;

        // Y = yellow (top)
        View yBtn = createVirtualButton("Y", 1 << 8, Color.parseColor("#FFFF4D"));
        RelativeLayout.LayoutParams yParams = new RelativeLayout.LayoutParams(actBtnSize, actBtnSize);
        yParams.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        yParams.addRule(RelativeLayout.CENTER_HORIZONTAL);
        yBtn.setLayoutParams(yParams);
        diamondLayout.addView(yBtn);

        // A = red (bottom)
        View aBtn = createVirtualButton("A", 1 << 0, Color.parseColor("#FF4D4D"));
        RelativeLayout.LayoutParams aParams = new RelativeLayout.LayoutParams(actBtnSize, actBtnSize);
        aParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        aParams.addRule(RelativeLayout.CENTER_HORIZONTAL);
        aBtn.setLayoutParams(aParams);
        diamondLayout.addView(aBtn);

        // X = green (left) - Let's map X as an extra space button! Wait, we will also map Space (0x35) or Enter as alternate keyboard outputs
        View xBtn = createVirtualButton("X", 0, Color.parseColor("#4DFF4D"));
        // Custom touch for X that sends TOWNS_JISKEY_SPACE (0x35)
        xBtn.setOnTouchListener((v, event) -> {
            int action = event.getAction();
            if (action == MotionEvent.ACTION_DOWN) {
                v.setAlpha(0.4f);
                EmulatorCore.nativeSendInput(0, 0x35, 1); // Press Space
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                v.setAlpha(1.0f);
                EmulatorCore.nativeSendInput(0, 0x35, 0); // Release Space
            }
            return true;
        });
        RelativeLayout.LayoutParams xParams = new RelativeLayout.LayoutParams(actBtnSize, actBtnSize);
        xParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
        xParams.addRule(RelativeLayout.CENTER_VERTICAL);
        xBtn.setLayoutParams(xParams);
        diamondLayout.addView(xBtn);

        // B = blue (right)
        View bBtn = createVirtualButton("B", 1 << 1, Color.parseColor("#4D94FF"));
        RelativeLayout.LayoutParams bParams = new RelativeLayout.LayoutParams(actBtnSize, actBtnSize);
        bParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        bParams.addRule(RelativeLayout.CENTER_VERTICAL);
        bBtn.setLayoutParams(bParams);
        diamondLayout.addView(bBtn);

        virtualPadContainer.addView(diamondLayout);

        // 3. CENTER: SELECT, START, SAVE, LOAD
        LinearLayout centerBtns = new LinearLayout(this);
        centerBtns.setOrientation(LinearLayout.HORIZONTAL);
        centerBtns.setGravity(Gravity.CENTER);
        RelativeLayout.LayoutParams centerParams = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        centerParams.addRule(RelativeLayout.CENTER_IN_PARENT);
        centerBtns.setLayoutParams(centerParams);

        // SELECT button
        View selectBtn = createVirtualButton("SEL", 1 << 7, Color.parseColor("#808080"));
        LinearLayout.LayoutParams selParams = new LinearLayout.LayoutParams(dpToPx(44), dpToPx(34));
        selParams.rightMargin = dpToPx(6);
        selectBtn.setLayoutParams(selParams);
        centerBtns.addView(selectBtn);

        // START button
        View startBtn = createVirtualButton("STA", 1 << 6, Color.parseColor("#808080"));
        LinearLayout.LayoutParams staParams = new LinearLayout.LayoutParams(dpToPx(44), dpToPx(34));
        staParams.rightMargin = dpToPx(10);
        startBtn.setLayoutParams(staParams);
        centerBtns.addView(startBtn);

        // SAVE State button (Floppy)
        View saveBtn = createTextOnlyBtn("💾 SV", Color.parseColor("#7B6FFF"));
        LinearLayout.LayoutParams savParams = new LinearLayout.LayoutParams(dpToPx(56), dpToPx(34));
        savParams.rightMargin = dpToPx(6);
        saveBtn.setLayoutParams(savParams);
        saveBtn.setOnClickListener(v -> saveStatePrompt());
        centerBtns.addView(saveBtn);

        // LOAD State button (Floppy/Open)
        View loadBtn = createTextOnlyBtn("📂 LD", Color.parseColor("#7B6FFF"));
        LinearLayout.LayoutParams loaParams = new LinearLayout.LayoutParams(dpToPx(56), dpToPx(34));
        loadBtn.setLayoutParams(loaParams);
        loadBtn.setOnClickListener(v -> loadStatePrompt());
        centerBtns.addView(loadBtn);

        virtualPadContainer.addView(centerBtns);

        // 4. Auxiliary Buttons: Menu (Top Left), Keyboard (Top Right)
        TextView menuIcon = new TextView(this);
        menuIcon.setText("≡");
        menuIcon.setTextColor(Color.parseColor("#7B6FFF"));
        menuIcon.setGravity(Gravity.CENTER);
        menuIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        GradientDrawable miBg = new GradientDrawable();
        miBg.setColor(Color.parseColor("#33111318"));
        miBg.setStroke(dpToPx(1), Color.parseColor("#7B6FFF"));
        miBg.setCornerRadius(dpToPx(18));
        menuIcon.setBackground(miBg);
        RelativeLayout.LayoutParams miParams = new RelativeLayout.LayoutParams(dpToPx(36), dpToPx(36));
        miParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
        miParams.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        miParams.leftMargin = dpToPx(12);
        miParams.topMargin = dpToPx(6);
        menuIcon.setLayoutParams(miParams);
        menuIcon.setOnClickListener(v -> toggleQuickMenu());
        virtualPadContainer.addView(menuIcon);

        TextView kbIcon = new TextView(this);
        kbIcon.setText("⌨️");
        kbIcon.setGravity(Gravity.CENTER);
        kbIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        GradientDrawable kiBg = new GradientDrawable();
        kiBg.setColor(Color.parseColor("#33111318"));
        kiBg.setStroke(dpToPx(1), Color.parseColor("#7B6FFF"));
        kiBg.setCornerRadius(dpToPx(18));
        kbIcon.setBackground(kiBg);
        RelativeLayout.LayoutParams kiParams = new RelativeLayout.LayoutParams(dpToPx(36), dpToPx(36));
        kiParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        kiParams.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        kiParams.rightMargin = dpToPx(12);
        kiParams.topMargin = dpToPx(6);
        kbIcon.setLayoutParams(kiParams);
        kbIcon.setOnClickListener(v -> toggleKeyboard());
        virtualPadContainer.addView(kbIcon);
    }

    private View createVirtualButton(String label, int bitmask, int borderHex) {
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btn.setGravity(Gravity.CENTER);
        btn.setTypeface(Typeface.DEFAULT_BOLD);

        // Circular background with specific border color
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#D9111318"));
        bg.setCornerRadius(dpToPx(24));
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
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        btn.setGravity(Gravity.CENTER);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#E6111318"));
        bg.setCornerRadius(dpToPx(8));
        bg.setStroke(dpToPx(1), borderHex);
        btn.setBackground(bg);

        return btn;
    }

    private void setupSoftKeyboard() {
        keyboardContainer = new LinearLayout(this);
        keyboardContainer.setOrientation(LinearLayout.VERTICAL);
        keyboardContainer.setBackgroundColor(Color.parseColor("#15171F"));
        keyboardContainer.setPadding(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6));
        keyboardContainer.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // Add a clean top toggle bar inside keyboard overlay
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dpToPx(8), 0, dpToPx(8), dpToPx(4));

        TextView title = new TextView(this);
        title.setText("Virtual Keyboard");
        title.setTextColor(Color.parseColor("#7B6FFF"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        topBar.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f
        ));

        TextView closeKb = new TextView(this);
        closeKb.setText("Close ✕ ");
        closeKb.setTextColor(Color.WHITE);
        closeKb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        closeKb.setTypeface(Typeface.DEFAULT_BOLD);
        closeKb.setOnClickListener(v -> toggleKeyboard());
        topBar.addView(closeKb);

        keyboardContainer.addView(topBar);

        // 4 Rows of standard keyboards
        LinearLayout row1 = createKeyboardRow(new String[]{"Esc", "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "BS"},
                new int[]{0x01, 0x5D, 0x5E, 0x5F, 0x60, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x0F});
        keyboardContainer.addView(row1);

        LinearLayout row2 = createKeyboardRow(new String[]{"Tab", "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P", "Ent"},
                new int[]{0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x1D});
        keyboardContainer.addView(row2);

        LinearLayout row3 = createKeyboardRow(new String[]{"Ctrl", "A", "S", "D", "F", "G", "H", "J", "K", "L", ";", "Shft"},
                new int[]{0x52, 0x1E, 0x1F, 0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x53});
        keyboardContainer.addView(row3);

        LinearLayout row4 = createKeyboardRow(new String[]{"Z", "X", "C", "V", "B", "N", "M", ",", ".", "/", "Space"},
                new int[]{0x2A, 0x2B, 0x2C, 0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x32, 0x33, 0x35});
        keyboardContainer.addView(row4);
    }

    private LinearLayout createKeyboardRow(String[] labels, int[] keycodes) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f
        );
        rp.topMargin = dpToPx(3);
        row.setLayoutParams(rp);

        for (int i = 0; i < labels.length; ++i) {
            final int code = keycodes[i];
            TextView key = new TextView(this);
            key.setText(labels[i]);
            key.setTextColor(Color.WHITE);
            key.setGravity(Gravity.CENTER);
            key.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            key.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));

            GradientDrawable kBg = new GradientDrawable();
            kBg.setColor(Color.parseColor("#252834"));
            kBg.setCornerRadius(dpToPx(4));
            kBg.setStroke(dpToPx(1), Color.parseColor("#3B3E4F"));
            key.setBackground(kBg);

            LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f
            );
            kp.leftMargin = dpToPx(3);
            kp.rightMargin = dpToPx(3);
            key.setLayoutParams(kp);

            key.setOnTouchListener((v, event) -> {
                int action = event.getAction();
                if (action == MotionEvent.ACTION_DOWN) {
                    v.setAlpha(0.4f);
                    EmulatorCore.nativeSendInput(0, code, 1); // Press key
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    v.setAlpha(1.0f);
                    EmulatorCore.nativeSendInput(0, code, 0); // Release key
                }
                return true;
            });

            row.addView(key);
        }
        return row;
    }

    private void toggleKeyboard() {
        if (isKeyboardOpen) {
            keyboardContainer.setVisibility(View.GONE);
            virtualPadContainer.setVisibility(View.VISIBLE);
            isKeyboardOpen = false;
        } else {
            virtualPadContainer.setVisibility(View.GONE);
            keyboardContainer.setVisibility(View.VISIBLE);
            isKeyboardOpen = true;
        }
    }

    private void setupQuickMenuOverlay() {
        quickMenuOverlay = new FrameLayout(this);
        FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        quickMenuOverlay.setLayoutParams(overlayParams);
        quickMenuOverlay.setBackgroundColor(Color.parseColor("#AA000000")); // semi-transparent dark tint
        quickMenuOverlay.setVisibility(View.GONE);

        // Slide panel
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

        // Slide-down aesthetics: neon border at bottom
        GradientDrawable pbBg = new GradientDrawable();
        pbBg.setColor(Color.parseColor("#111318"));
        pbBg.setStroke(dpToPx(1), Color.parseColor("#7B6FFF"));
        panel.setBackground(pbBg);

        // Menu Title
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

        // Buttons
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

        // Cancel/Dismiss area below
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

        // Dismiss menu if touching outside of the slide down panel
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
            // Sync to SAF
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
        File localRoot = getExternalFilesDir(null);
        if (localRoot == null) localRoot = getFilesDir();

        File biosDir = new File(localRoot, StorageHelper.SUBFOLDER_BIOS);
        File romsDir = new File(localRoot, StorageHelper.SUBFOLDER_ROMS);

        if (!biosDir.exists()) biosDir.mkdirs();
        if (!romsDir.exists()) romsDir.mkdirs();

        // Initialize core
        boolean inited = EmulatorCore.nativeInit(biosDir.getAbsolutePath(), romsDir.getAbsolutePath());
        if (inited) {
            Log.i(TAG, "Core initialized successfully.");
            // Load ROM/Disc
            boolean loaded = EmulatorCore.nativeLoadROM(gamePath);
            if (!loaded) {
                loaded = EmulatorCore.nativeLoadDisc(gamePath);
            }
            if (loaded) {
                Log.i(TAG, "Game loaded successfully: " + gamePath);
            } else {
                Toast.makeText(this, "Failed to load game image.", Toast.LENGTH_LONG).show();
            }
        } else {
            Toast.makeText(this, "Core Setup failed. Ensure you copied your FM Towns BIOS ROMs to the bios/ folder.", Toast.LENGTH_LONG).show();
        }

        // Start frame loop
        if (!isRunning) {
            isRunning = true;
            fpsLastTime = SystemClock.elapsedRealtime();
            fpsFrameCount = 0;
            Choreographer.getInstance().postFrameCallback(frameCallback);
        }
    }

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (isRunning) {
                // Execute core frame step
                EmulatorCore.nativeRunFrame();

                // Request SurfaceView to render
                surfaceView.drawFrame();

                // Calculate FPS
                fpsFrameCount++;
                long now = SystemClock.elapsedRealtime();
                long diff = now - fpsLastTime;
                if (diff >= 1000) {
                    final int calculatedFps = Math.round((float) fpsFrameCount * 1000.0f / diff);
                    fpsCounterText.setText(calculatedFps + " FPS");
                    fpsLastTime = now;
                    fpsFrameCount = 0;
                }

                // Schedule next
                Choreographer.getInstance().postFrameCallback(this);
            }
        }
    };

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
        isRunning = false;
        EmulatorCore.nativeShutdown();

        // Sync local saves and states to SAF
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
        isRunning = false;
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
        isRunning = true;
        fpsLastTime = SystemClock.elapsedRealtime();
        fpsFrameCount = 0;
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isRunning = false;
        EmulatorCore.nativeShutdown();
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics()
        );
    }
}
