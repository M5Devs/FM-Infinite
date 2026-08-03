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
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "FMInfinite_Main";

    private EmulatorSurfaceView surfaceView;
    private FrameLayout mainLayout;
    private LinearLayout overlayLayout;
    private Button btnPickStorage;
    private Button btnLoadDisc;
    private Button btnReset;

    private boolean isRunning = false;
    private Uri storageUri = null;

    // SAF Directory Picker Launcher
    private final ActivityResultLauncher<Uri> openDocumentTreeLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(),
            uri -> {
                if (uri != null) {
                    storageUri = uri;
                    StorageHelper.persistUriPermission(this, uri);
                    StorageHelper.syncStorage(this, uri);
                    onStorageConfigured();
                } else {
                    Toast.makeText(this, "Storage folder selection cancelled.", Toast.LENGTH_LONG).show();
                }
            }
    );

    // File Picker for CD/FD Images
    private final ActivityResultLauncher<String[]> selectDiscLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri != null) {
                    try {
                        // Copy to local app storage and load
                        File localRoot = getExternalFilesDir(null);
                        if (localRoot == null) localRoot = getFilesDir();
                        File tempDir = new File(localRoot, "temp");
                        if (!tempDir.exists()) tempDir.mkdirs();

                        String filename = "loaded_disc.img";
                        File localDest = new File(tempDir, filename);

                        // Extract content from uri
                        java.io.InputStream in = getContentResolver().openInputStream(uri);
                        java.io.FileOutputStream out = new java.io.FileOutputStream(localDest);
                        byte[] buf = new byte[8192];
                        int len;
                        while ((in != null) && (len = in.read(buf)) > 0) {
                            out.write(buf, 0, len);
                        }
                        in.close();
                        out.close();

                        // Load the copied image into C++ emulator core
                        boolean loaded = EmulatorCore.nativeLoadDisc(localDest.getAbsolutePath());
                        if (loaded) {
                            Toast.makeText(this, "Disc loaded successfully", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, "Failed to mount disc image", Toast.LENGTH_LONG).show();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to copy and load disc", e);
                        Toast.makeText(this, "Error loading disc image", Toast.LENGTH_LONG).show();
                    }
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep screen on
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Setup layout programmatically
        mainLayout = new FrameLayout(this);
        mainLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // Emulator Surface
        surfaceView = new EmulatorSurfaceView(this);
        FrameLayout.LayoutParams surfaceParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        surfaceView.setLayoutParams(surfaceParams);
        mainLayout.addView(surfaceView);

        // Transparent buttons overlay
        overlayLayout = new LinearLayout(this);
        overlayLayout.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        overlayParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        overlayParams.topMargin = 20;
        overlayLayout.setLayoutParams(overlayParams);

        btnPickStorage = new Button(this);
        btnPickStorage.setText("Select SAF Folder");
        btnPickStorage.setOnClickListener(v -> pickStorageFolder());
        overlayLayout.addView(btnPickStorage);

        btnLoadDisc = new Button(this);
        btnLoadDisc.setText("Load Disc/FD");
        btnLoadDisc.setEnabled(false);
        btnLoadDisc.setOnClickListener(v -> selectDiscLauncher.launch(new String[]{"*/*"}));
        overlayLayout.addView(btnLoadDisc);

        btnReset = new Button(this);
        btnReset.setText("Reset");
        btnReset.setEnabled(false);
        btnReset.setOnClickListener(v -> {
            EmulatorCore.nativeShutdown();
            initCore();
            Toast.makeText(this, "Emulator Reset", Toast.LENGTH_SHORT).show();
        });
        overlayLayout.addView(btnReset);

        mainLayout.addView(overlayLayout);
        setContentView(mainLayout);

        // Set touch/mouse listener on the surface view to send touch coordinates to mouse emulator
        setupMouseInput();

        // Check storage permissions
        checkStorageFlow();
    }

    private void setupMouseInput() {
        surfaceView.setOnTouchListener((v, event) -> {
            int action = event.getAction();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) {
                // Map coordinates to 640x480 (Tsugaru default screen resolution)
                int viewWidth = surfaceView.getWidth();
                int viewHeight = surfaceView.getHeight();
                if (viewWidth > 0 && viewHeight > 0) {
                    float touchX = event.getX();
                    float touchY = event.getY();

                    // Aspect ratio fit adjustment
                    // Get same scaling coordinates as we did in drawing
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

                    // Clamp to 0-639, 0-479
                    mouseX = Math.max(0, Math.min(639, mouseX));
                    mouseY = Math.max(0, Math.min(479, mouseY));

                    int clickState = 0;
                    if (action != MotionEvent.ACTION_UP) {
                        clickState |= 1; // Left click on touch down / move
                    }

                    int keyOrButton = mouseX | (mouseY << 16);
                    EmulatorCore.nativeSendInput(2, keyOrButton, clickState);
                }
                return true;
            }
            return false;
        });
    }

    private void checkStorageFlow() {
        if (StorageHelper.isAndroid11OrHigher()) {
            if (StorageHelper.hasPersistedPermission(this)) {
                storageUri = StorageHelper.getPersistedUri(this);
                StorageHelper.syncStorage(this, storageUri);
                onStorageConfigured();
            } else {
                Toast.makeText(this, "Please select a root folder to save BIOS, ROMS, saves, and states.", Toast.LENGTH_LONG).show();
                pickStorageFolder();
            }
        } else {
            // Android < 11 doesn't require tree picking, we just use local files
            onStorageConfigured();
        }
    }

    private void pickStorageFolder() {
        openDocumentTreeLauncher.launch(null);
    }

    private void onStorageConfigured() {
        btnLoadDisc.setEnabled(true);
        btnReset.setEnabled(true);
        btnPickStorage.setText("Change Storage SAF Folder");

        initCore();

        // Start emulation frame loop
        if (!isRunning) {
            isRunning = true;
            Choreographer.getInstance().postFrameCallback(frameCallback);
        }
    }

    private void initCore() {
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
        } else {
            Toast.makeText(this, "Core Setup failed. Ensure you copied your FM Towns BIOS ROMs to the bios/ folder under your storage root.", Toast.LENGTH_LONG).show();
        }
    }

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (isRunning) {
                // Execute core frame step
                EmulatorCore.nativeRunFrame();

                // Request SurfaceView to render the cooked frame
                surfaceView.drawFrame();

                // Schedule next frame
                Choreographer.getInstance().postFrameCallback(this);
            }
        }
    };

    @Override
    protected void onPause() {
        super.onPause();
        isRunning = false;
        // Persist local saves & states back to the SAF directory
        if (storageUri != null) {
            StorageHelper.syncLocalSavesToSAF(this, storageUri);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (storageUri != null || !StorageHelper.isAndroid11OrHigher()) {
            isRunning = true;
            Choreographer.getInstance().postFrameCallback(frameCallback);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isRunning = false;
        EmulatorCore.nativeShutdown();
    }
}
