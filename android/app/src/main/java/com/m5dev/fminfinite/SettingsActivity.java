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
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.view.WindowCompat;

import java.io.File;

public class SettingsActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "fminfinite_prefs";

    private ScrollView scrollView;
    private LinearLayout rootContainer;

    // Preference values
    private SharedPreferences prefs;

    // UI elements to update dynamically
    private TextView gamePathSubtext;
    private TextView biosPathSubtext;
    private TextView opacityValueText;
    private TextView sizeValueText;

    // SAF Directory Launcher
    private final ActivityResultLauncher<Uri> selectFolderLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(),
            uri -> {
                if (uri != null) {
                    StorageHelper.persistUriPermission(this, uri);
                    StorageHelper.syncStorage(this, uri);
                    updateFolderPaths();
                    Toast.makeText(this, "Storage folder updated and synchronized.", Toast.LENGTH_SHORT).show();
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // Build main layout
        scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        scrollView.setBackgroundColor(Color.parseColor("#0D0D0F"));
        scrollView.setFitsSystemWindows(true);
        scrollView.setFillViewport(true);

        rootContainer = new LinearLayout(this);
        rootContainer.setOrientation(LinearLayout.VERTICAL);
        rootContainer.setPadding(dpToPx(16), dpToPx(24), dpToPx(16), dpToPx(24));
        rootContainer.setLayoutParams(new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        // 1. Settings Activity Header
        TextView titleText = new TextView(this);
        titleText.setText(getString(R.string.settings_title));
        titleText.setTextColor(Color.parseColor("#E0E0FF"));
        titleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        titleText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        titleText.setPadding(0, 0, 0, dpToPx(24));
        rootContainer.addView(titleText);

        // --- SECTION 1: STORAGE ---
        addSectionHeader(getString(R.string.section_storage));

        // Game Folder Card
        LinearLayout gameFolderCard = createSettingCard();
        gameFolderCard.setOnClickListener(v -> selectFolderLauncher.launch(null));
        TextView gameTitle = createSettingTitle(getString(R.string.setting_game_folder));
        gamePathSubtext = createSettingSubtext("Not configured");
        gameFolderCard.addView(gameTitle);
        gameFolderCard.addView(gamePathSubtext);
        rootContainer.addView(gameFolderCard);

        // BIOS Folder Card
        LinearLayout biosFolderCard = createSettingCard();
        biosFolderCard.setOnClickListener(v -> selectFolderLauncher.launch(null));
        TextView biosTitle = createSettingTitle(getString(R.string.setting_bios_folder));
        biosPathSubtext = createSettingSubtext("Not configured");
        biosFolderCard.addView(biosTitle);
        biosFolderCard.addView(biosPathSubtext);
        rootContainer.addView(biosFolderCard);

        // --- SECTION 2: DISPLAY ---
        addSectionHeader(getString(R.string.section_display));

        // Screen Filter card (Nearest vs Bilinear)
        LinearLayout filterCard = createSettingCard();
        RelativeLayout filterRow = new RelativeLayout(this);
        filterRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView filterTitle = createSettingTitle(getString(R.string.setting_screen_filter));
        RelativeLayout.LayoutParams ftParams = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        ftParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
        ftParams.addRule(RelativeLayout.CENTER_VERTICAL);
        filterTitle.setLayoutParams(ftParams);
        filterRow.addView(filterTitle);

        SwitchCompat filterSwitch = new SwitchCompat(this);
        filterSwitch.setChecked(prefs.getBoolean("screen_filter_bilinear", false));
        filterSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("screen_filter_bilinear", isChecked).apply();
            Toast.makeText(this, isChecked ? "Bilinear Filter Enabled" : "Nearest Filter Enabled", Toast.LENGTH_SHORT).show();
        });
        RelativeLayout.LayoutParams fsParams = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        fsParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        fsParams.addRule(RelativeLayout.CENTER_VERTICAL);
        filterSwitch.setLayoutParams(fsParams);
        filterRow.addView(filterSwitch);
        filterCard.addView(filterRow);

        TextView filterDesc = createSettingSubtext("Toggle graphics upscale filtering quality");
        filterDesc.setPadding(0, dpToPx(4), 0, 0);
        filterCard.addView(filterDesc);
        rootContainer.addView(filterCard);

        // Show FPS Card
        LinearLayout fpsCard = createSettingCard();
        RelativeLayout fpsRow = new RelativeLayout(this);
        fpsRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView fpsTitle = createSettingTitle(getString(R.string.setting_show_fps));
        RelativeLayout.LayoutParams fpsTitleParams = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        fpsTitleParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
        fpsTitleParams.addRule(RelativeLayout.CENTER_VERTICAL);
        fpsTitle.setLayoutParams(fpsTitleParams);
        fpsRow.addView(fpsTitle);

        SwitchCompat fpsSwitch = new SwitchCompat(this);
        fpsSwitch.setChecked(prefs.getBoolean("show_fps", true));
        fpsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("show_fps", isChecked).apply();
        });
        RelativeLayout.LayoutParams fpsSwitchParams = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        fpsSwitchParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        fpsSwitchParams.addRule(RelativeLayout.CENTER_VERTICAL);
        fpsSwitch.setLayoutParams(fpsSwitchParams);
        fpsRow.addView(fpsSwitch);
        fpsCard.addView(fpsRow);
        rootContainer.addView(fpsCard);

        // --- SECTION 3: CONTROLS ---
        addSectionHeader(getString(R.string.section_controls));

        // Virtual Pad Opacity Card
        LinearLayout opacityCard = createSettingCard();
        RelativeLayout opacityRow = new RelativeLayout(this);
        opacityRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView opacityTitle = createSettingTitle(getString(R.string.setting_pad_opacity));
        RelativeLayout.LayoutParams otParams = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        otParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
        opacityTitle.setLayoutParams(otParams);
        opacityRow.addView(opacityTitle);

        opacityValueText = new TextView(this);
        int currentOpacity = prefs.getInt("virtual_pad_opacity", 70);
        opacityValueText.setText(currentOpacity + "%");
        opacityValueText.setTextColor(Color.parseColor("#7B6FFF"));
        opacityValueText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        opacityValueText.setTypeface(Typeface.DEFAULT_BOLD);
        RelativeLayout.LayoutParams ovParams = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        ovParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        opacityValueText.setLayoutParams(ovParams);
        opacityRow.addView(opacityValueText);
        opacityCard.addView(opacityRow);

        SeekBar opacitySeekBar = new SeekBar(this);
        opacitySeekBar.setMax(100 - 30); // 30% to 100%
        opacitySeekBar.setProgress(currentOpacity - 30);
        opacitySeekBar.setPadding(0, dpToPx(12), 0, dpToPx(6));
        opacitySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int opacity = progress + 30;
                opacityValueText.setText(opacity + "%");
                prefs.edit().putInt("virtual_pad_opacity", opacity).apply();
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        opacityCard.addView(opacitySeekBar);
        rootContainer.addView(opacityCard);

        // Virtual Pad Size Card
        LinearLayout sizeCard = createSettingCard();
        RelativeLayout sizeRow = new RelativeLayout(this);
        sizeRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView sizeTitle = createSettingTitle(getString(R.string.setting_pad_size));
        RelativeLayout.LayoutParams stParams = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        stParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
        sizeTitle.setLayoutParams(stParams);
        sizeRow.addView(sizeTitle);

        sizeValueText = new TextView(this);
        String currentSize = prefs.getString("virtual_pad_size", "medium");
        sizeValueText.setText(capitalize(currentSize));
        sizeValueText.setTextColor(Color.parseColor("#7B6FFF"));
        sizeValueText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        sizeValueText.setTypeface(Typeface.DEFAULT_BOLD);
        RelativeLayout.LayoutParams svParams = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        svParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        sizeValueText.setLayoutParams(svParams);
        sizeRow.addView(sizeValueText);
        sizeCard.addView(sizeRow);

        SeekBar sizeSeekBar = new SeekBar(this);
        sizeSeekBar.setMax(2); // 0 = small, 1 = medium, 2 = large
        int sizeIndex = 1;
        if ("small".equals(currentSize)) sizeIndex = 0;
        else if ("large".equals(currentSize)) sizeIndex = 2;
        sizeSeekBar.setProgress(sizeIndex);
        sizeSeekBar.setPadding(0, dpToPx(12), 0, dpToPx(6));
        sizeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                String sizeStr = "medium";
                if (progress == 0) sizeStr = "small";
                else if (progress == 2) sizeStr = "large";

                sizeValueText.setText(capitalize(sizeStr));
                prefs.edit().putString("virtual_pad_size", sizeStr).apply();
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        sizeCard.addView(sizeSeekBar);
        rootContainer.addView(sizeCard);

        // --- SECTION 4: ABOUT ---
        addSectionHeader(getString(R.string.section_about));

        // About details Card
        LinearLayout aboutCard = createSettingCard();

        TextView appNameLabel = new TextView(this);
        appNameLabel.setText("FM Infinite");
        appNameLabel.setTextColor(Color.WHITE);
        appNameLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        appNameLabel.setTypeface(Typeface.DEFAULT_BOLD);
        aboutCard.addView(appNameLabel);

        TextView appVerLabel = new TextView(this);
        appVerLabel.setText("Version 1.0.0");
        appVerLabel.setTextColor(Color.parseColor("#666680"));
        appVerLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        appVerLabel.setPadding(0, dpToPx(2), 0, dpToPx(10));
        aboutCard.addView(appVerLabel);

        TextView creditsLabel = new TextView(this);
        creditsLabel.setText("Based on Tsugaru by CaptainYS");
        creditsLabel.setTextColor(Color.parseColor("#E0E0FF"));
        creditsLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        creditsLabel.setPadding(0, 0, 0, dpToPx(10));
        aboutCard.addView(creditsLabel);

        // Clickable GitHub Link
        TextView gitHubLink = new TextView(this);
        gitHubLink.setText("🔗 https://github.com/M5Devs/FM-Infinite");
        gitHubLink.setTextColor(Color.parseColor("#7B6FFF"));
        gitHubLink.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        gitHubLink.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        gitHubLink.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/M5Devs/FM-Infinite"));
            startActivity(intent);
        });
        aboutCard.addView(gitHubLink);
        rootContainer.addView(aboutCard);

        scrollView.addView(rootContainer);
        setContentView(scrollView);

        // Load folder configurations
        updateFolderPaths();
    }

    private void addSectionHeader(String sectionTitle) {
        TextView header = new TextView(this);
        header.setText(sectionTitle);
        header.setTextColor(Color.parseColor("#7B6FFF"));
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        header.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        header.setAllCaps(true);
        header.setPadding(0, dpToPx(16), 0, dpToPx(8));
        rootContainer.addView(header);
    }

    private LinearLayout createSettingCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#111318"));
        bg.setCornerRadius(dpToPx(8));
        card.setBackground(bg);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dpToPx(12);
        card.setLayoutParams(params);

        return card;
    }

    private TextView createSettingTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#E0E0FF"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        return tv;
    }

    private TextView createSettingSubtext(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#666680"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        return tv;
    }

    private void updateFolderPaths() {
        Uri rootUri = StorageHelper.getPersistedUri(this);
        File localRoot = getExternalFilesDir(null);
        if (localRoot == null) localRoot = getFilesDir();

        if (rootUri != null) {
            String displayedPath = rootUri.getPath();
            if (displayedPath != null && displayedPath.contains(":")) {
                displayedPath = displayedPath.substring(displayedPath.lastIndexOf(':') + 1);
            }
            gamePathSubtext.setText("SAF folder: " + displayedPath + "\nLocal synced: " + new File(localRoot, StorageHelper.SUBFOLDER_ROMS).getAbsolutePath());
            biosPathSubtext.setText("SAF folder: " + displayedPath + "\nLocal synced: " + new File(localRoot, StorageHelper.SUBFOLDER_BIOS).getAbsolutePath());
        } else {
            gamePathSubtext.setText("Tap to choose storage folder");
            biosPathSubtext.setText("Tap to choose storage folder");
        }
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return "";
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics()
        );
    }
}
