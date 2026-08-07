/* LICENSE>>
Copyright 2025 M5_Development (FM Infinite Authors)

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

<< LICENSE */

package com.m5dev.fminfinite;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;

public class BiosSetupActivity extends AppCompatActivity {
    private static final String TAG = "FMInfinite_BiosSetup";

    private TextView statusSysText;
    private TextView statusFntText;
    private ImageView iconSysView;
    private ImageView iconFntView;

    private Button btnAutoDetect;
    private Button btnBrowse;
    private Button btnConfirm;

    private Config config;

    private final ActivityResultLauncher<Uri> openDocumentTreeLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(),
            uri -> {
                if (uri != null) {
                    onFolderSelected(uri);
                } else {
                    Toast.makeText(this, "Folder selection cancelled.", Toast.LENGTH_LONG).show();
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FileLogger.init(this);
        FileLogger.log("Java: BiosSetupActivity onCreate called");

        setContentView(R.layout.activity_bios_setup);

        // Load configuration
        config = ConfigManager.loadConfig(this);

        // Bind Views
        statusSysText = findViewById(R.id.status_sys);
        statusFntText = findViewById(R.id.status_fnt);
        iconSysView = findViewById(R.id.icon_sys);
        iconFntView = findViewById(R.id.icon_fnt);

        btnAutoDetect = findViewById(R.id.btn_auto_detect);
        btnBrowse = findViewById(R.id.btn_browse);
        btnConfirm = findViewById(R.id.btn_confirm);

        // Style the buttons dynamically for high quality retro feel (similar to FirstLaunchActivity)
        styleButton(btnAutoDetect, "#1C1E24", "#7B6FFF");
        styleButton(btnBrowse, "#1C1E24", "#7B6FFF");
        styleButton(btnConfirm, "#7B6FFF", null);

        // Set up Listeners
        btnAutoDetect.setOnClickListener(v -> performAutoDetect());
        btnBrowse.setOnClickListener(v -> openFolderPicker());
        btnConfirm.setOnClickListener(v -> confirmAndLaunch());

        // Perform an initial scan on start if we already have a path saved
        if (config.biosPath != null && !config.biosPath.isEmpty()) {
            performScan(config.biosPath);
        } else {
            // Also try scanning the local sync folder as an initial auto-detect
            performAutoDetectSilent();
        }
    }

    private void styleButton(Button button, String bgHexColor, String strokeHexColor) {
        button.setTextColor(Color.WHITE);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        button.setAllCaps(false);

        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(Color.parseColor(bgHexColor));
        btnBg.setCornerRadius(dpToPx(8));
        if (strokeHexColor != null) {
            btnBg.setStroke(dpToPx(1), Color.parseColor(strokeHexColor));
        }
        button.setBackground(btnBg);
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics()
        );
    }

    private void openFolderPicker() {
        openDocumentTreeLauncher.launch(null);
    }

    private void onFolderSelected(Uri uri) {
        // Persist permissions and sync/create directories
        StorageHelper.persistUriPermission(this, uri);
        StorageHelper.syncStorage(this, uri);

        String path = uri.toString();

        // Save path to config
        config.biosPath = path;
        ConfigManager.saveConfig(this, config);

        // Scan and show results
        performScan(path);
    }

    private void performScan(String path) {
        BiosInfo info = BiosScanner.scanFolder(this, path);
        updateUI(info);

        // Check for deprecated TOWNS.SYS or TOWNSCRD.SYS
        checkDeprecated();
    }

    private void performAutoDetect() {
        // Run storage sync on the persisted URI if any, or scan local
        Uri storageUri = StorageHelper.getPersistedUri(this);
        if (storageUri != null) {
            StorageHelper.syncStorage(this, storageUri);
            config.biosPath = storageUri.toString();
            ConfigManager.saveConfig(this, config);
            performScan(config.biosPath);
            Toast.makeText(this, "Auto-Detect complete.", Toast.LENGTH_SHORT).show();
        } else {
            // Try scanning local synced files directly
            BiosInfo info = BiosScanner.scanFolder(this, "");
            updateUI(info);
            checkDeprecated();
            if (info.hasSystemBios && info.hasFontRom) {
                Toast.makeText(this, "Auto-Detected local files successfully.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Auto-Detect found no local files. Please use Browse Folder.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void performAutoDetectSilent() {
        Uri storageUri = StorageHelper.getPersistedUri(this);
        if (storageUri != null) {
            StorageHelper.syncStorage(this, storageUri);
            config.biosPath = storageUri.toString();
            ConfigManager.saveConfig(this, config);
            BiosInfo info = BiosScanner.scanFolder(this, config.biosPath);
            updateUI(info);
        } else {
            BiosInfo info = BiosScanner.scanFolder(this, "");
            updateUI(info);
        }
        checkDeprecated();
    }

    private void updateUI(BiosInfo info) {
        if (info.hasSystemBios) {
            statusSysText.setText("Found");
            statusSysText.setTextColor(Color.parseColor("#4DFF4D")); // Neon Green
            iconSysView.setImageResource(android.R.drawable.presence_online); // green dot or similar online icon
            iconSysView.setVisibility(View.VISIBLE);
        } else {
            statusSysText.setText("Not found");
            statusSysText.setTextColor(Color.parseColor("#FF4D4D")); // Neon Red
            iconSysView.setImageResource(android.R.drawable.presence_busy); // red dot/cross style
            iconSysView.setVisibility(View.VISIBLE);
        }

        if (info.hasFontRom) {
            statusFntText.setText("Found");
            statusFntText.setTextColor(Color.parseColor("#4DFF4D"));
            iconFntView.setImageResource(android.R.drawable.presence_online);
            iconFntView.setVisibility(View.VISIBLE);
        } else {
            statusFntText.setText("Not found");
            statusFntText.setTextColor(Color.parseColor("#FF4D4D"));
            iconFntView.setImageResource(android.R.drawable.presence_busy);
            iconFntView.setVisibility(View.VISIBLE);
        }

        // Enable confirm button only if both required files are found
        boolean ready = info.hasSystemBios && info.hasFontRom;
        btnConfirm.setEnabled(ready);
        if (ready) {
            styleButton(btnConfirm, "#7B6FFF", null);
            btnConfirm.setAlpha(1.0f);
        } else {
            styleButton(btnConfirm, "#1F1F24", null);
            btnConfirm.setAlpha(0.5f);
        }

        // Save auto detected type if PC or Marty
        if (!"unknown".equals(info.detectedType)) {
            config.biosType = info.detectedType;
            ConfigManager.saveConfig(this, config);
        }
    }

    private void checkDeprecated() {
        File localRoot = getExternalFilesDir(null);
        if (localRoot == null) localRoot = getFilesDir();
        File biosDir = new File(localRoot, "bios");
        boolean hasTownsSys = new File(biosDir, "TOWNS.SYS").exists() || new File(biosDir, "TOWNSCRD.SYS").exists();

        if (hasTownsSys) {
            new AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
                    .setTitle("Deprecated BIOS Found")
                    .setMessage("TOWNS.SYS is deprecated.\nPlease use FMT_SYS.ROM from:\ngithub.com/Abdess/retrobios")
                    .setCancelable(true)
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    private void confirmAndLaunch() {
        config.biosSetupComplete = true;
        ConfigManager.saveConfig(this, config);

        // Transition back to MainActivity
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        finish();
    }
}
