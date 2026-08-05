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
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;

public class FirstLaunchActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "fminfinite_prefs";
    private static final String KEY_FIRST_LAUNCH = "first_launch";

    private final ActivityResultLauncher<Uri> openDocumentTreeLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(),
            uri -> {
                if (uri != null) {
                    onFolderSelected(uri);
                } else {
                    Toast.makeText(this, "Storage folder selection cancelled.", Toast.LENGTH_LONG).show();
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FileLogger.init(this);
        FileLogger.log("Java: FirstLaunchActivity onCreate called");

        // Check if first launch flag is already false
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_FIRST_LAUNCH, true)) {
            launchMainActivity();
            return;
        }

        // Build premium retro UI programmatically
        ScrollView scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        scrollView.setBackgroundColor(Color.parseColor("#0D0D0F"));
        scrollView.setFillViewport(true);

        LinearLayout rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setGravity(Gravity.CENTER_HORIZONTAL);
        rootLayout.setPadding(dpToPx(24), dpToPx(32), dpToPx(24), dpToPx(32));
        ScrollView.LayoutParams rootParams = new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        rootLayout.setLayoutParams(rootParams);

        // Retro Monitor Emoji/Logo Watermark
        TextView logoText = new TextView(this);
        logoText.setText("🖥️");
        logoText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 64);
        logoText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        logoParams.bottomMargin = dpToPx(16);
        logoText.setLayoutParams(logoParams);
        rootLayout.addView(logoText);

        // Title
        TextView titleText = new TextView(this);
        titleText.setText(getString(R.string.welcome_title));
        titleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        titleText.setTextColor(Color.parseColor("#E0E0FF"));
        titleText.setGravity(Gravity.CENTER);
        titleText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        titleParams.bottomMargin = dpToPx(8);
        titleText.setLayoutParams(titleParams);
        rootLayout.addView(titleText);

        // Subtitle
        TextView subtitleText = new TextView(this);
        subtitleText.setText(getString(R.string.welcome_subtitle));
        subtitleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        subtitleText.setTextColor(Color.parseColor("#666680"));
        subtitleText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        subtitleParams.bottomMargin = dpToPx(24);
        subtitleText.setLayoutParams(subtitleParams);
        rootLayout.addView(subtitleText);

        // Folder structure view box
        LinearLayout structureBox = new LinearLayout(this);
        structureBox.setOrientation(LinearLayout.VERTICAL);
        structureBox.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16));

        // Dark surface background with neon border
        GradientDrawable boxBg = new GradientDrawable();
        boxBg.setColor(Color.parseColor("#111318"));
        boxBg.setCornerRadius(dpToPx(8));
        boxBg.setStroke(dpToPx(1), Color.parseColor("#7B6FFF"));
        structureBox.setBackground(boxBg);

        LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        boxParams.bottomMargin = dpToPx(32);
        structureBox.setLayoutParams(boxParams);

        TextView structureText = new TextView(this);
        structureText.setText("FMInfinite/\n├── bios/\n├── roms/\n├── saves/\n└── states/");
        structureText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        structureText.setTextColor(Color.parseColor("#E0E0FF"));
        structureText.setTypeface(Typeface.MONOSPACE);
        structureText.setLineSpacing(dpToPx(4), 1.0f);
        structureBox.addView(structureText);
        rootLayout.addView(structureBox);

        // Choose Folder Button
        Button chooseBtn = new Button(this);
        chooseBtn.setText(getString(R.string.choose_folder));
        chooseBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        chooseBtn.setTextColor(Color.WHITE);
        chooseBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        chooseBtn.setAllCaps(false);

        // Flat neon button style with rounded corners
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(Color.parseColor("#7B6FFF"));
        btnBg.setCornerRadius(dpToPx(8));
        chooseBtn.setBackground(btnBg);

        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(54)
        );
        btnParams.bottomMargin = dpToPx(12);
        chooseBtn.setLayoutParams(btnParams);
        chooseBtn.setOnClickListener(v -> openDocumentTreeLauncher.launch(null));
        rootLayout.addView(chooseBtn);

        // Footer small hint text
        TextView footerText = new TextView(this);
        footerText.setText(getString(R.string.change_later));
        footerText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        footerText.setTextColor(Color.parseColor("#666680"));
        footerText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        footerText.setLayoutParams(footerParams);
        rootLayout.addView(footerText);

        scrollView.addView(rootLayout);
        setContentView(scrollView);
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics()
        );
    }

    private void onFolderSelected(Uri uri) {
        // Persist permissions and sync/create directories
        StorageHelper.persistUriPermission(this, uri);
        StorageHelper.syncStorage(this, uri);

        // Check if BIOS folder is empty in local files
        File localRoot = getExternalFilesDir(null);
        if (localRoot == null) localRoot = getFilesDir();
        File biosDir = new File(localRoot, StorageHelper.SUBFOLDER_BIOS);
        File[] biosFiles = biosDir.listFiles();

        boolean isBiosEmpty = (biosFiles == null || biosFiles.length == 0);

        if (isBiosEmpty) {
            // Show warning dialog as required
            new AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
                    .setTitle("BIOS Files Missing")
                    .setMessage(getString(R.string.bios_warning))
                    .setCancelable(false)
                    .setPositiveButton("OK", (dialog, which) -> {
                        finalizeSetup();
                    })
                    .show();
        } else {
            finalizeSetup();
        }
    }

    private void finalizeSetup() {
        // Mark first launch as false
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_FIRST_LAUNCH, false).apply();

        launchMainActivity();
    }

    private void launchMainActivity() {
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        finish();
    }
}
