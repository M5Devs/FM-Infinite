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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextMenu;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "FMInfinite_Main";
    private static final String PREFS_NAME = "fminfinite_prefs";
    private static final String KEY_FIRST_LAUNCH = "first_launch";
    private static final String KEY_RECENT_GAMES = "recent_games_v2";

    // Layout elements
    private LinearLayout rootLayout;
    private RelativeLayout toolbarLayout;
    private LinearLayout searchBarContainer;
    private EditText searchEditText;
    private LinearLayout tabsContainer;

    // Custom tab bar elements (PPSSPP indicator style)
    private LinearLayout tabLibraryLayout;
    private TextView tabLibraryText;
    private View tabLibraryIndicator;
    private LinearLayout tabRecentLayout;
    private TextView tabRecentText;
    private View tabRecentIndicator;

    private FrameLayout contentContainer;
    private GridView libraryGridView;
    private ListView recentListView;
    private LinearLayout emptyStateContainer;

    private RelativeLayout fabBtn;

    // Bitmap Cache to prevent memory leaks and OOM
    private final android.util.LruCache<String, Bitmap> coverCache = new android.util.LruCache<String, Bitmap>(20) {
        @Override
        protected void entryRemoved(boolean evicted, String key, Bitmap old, Bitmap newValue) {
            if (evicted && old != null && !old.isRecycled()) old.recycle();
        }
    };

    // Adapters & Data lists
    private LibraryAdapter libraryAdapter;
    private RecentAdapter recentAdapter;

    private List<File> allGames = new ArrayList<>();
    private List<File> filteredGames = new ArrayList<>();
    private List<String> recentGamePaths = new ArrayList<>();

    private int activeTab = 0; // 0 = Library, 1 = Recent
    private boolean isSearching = false;

    // SAF Directory Picker Launcher
    private final ActivityResultLauncher<Uri> openDocumentTreeLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(),
            uri -> {
                if (uri != null) {
                    StorageHelper.persistUriPermission(this, uri);
                    try {
                        StorageHelper.syncStorage(this, uri);
                    } catch (java.io.IOException e) {
                        Log.e(TAG, "Failed to sync storage", e);
                        Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                    refreshLibrary();
                } else {
                    Toast.makeText(this, "Storage folder selection cancelled.", Toast.LENGTH_LONG).show();
                }
            }
    );

    // File Picker for adding ROM/ISO via FAB (+)
    private final ActivityResultLauncher<String[]> addRomLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri != null) {
                    copyAndLaunchDirectly(uri);
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FileLogger.init(this);
        FileLogger.log("Java: MainActivity onCreate called");

        // Check if BIOS setup is complete, redirect if not
        Config config = ConfigManager.loadConfig(this);
        if (!config.biosSetupComplete) {
            Intent intent = new Intent(this, BiosSetupActivity.class);
            startActivity(intent);
            finish();
            return;
        }

        // Enable edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        // Main Programmatic Layout
        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(Color.parseColor("#0D0D14")); // Near-black with blue tint
        rootLayout.setFitsSystemWindows(true);
        rootLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // 1. Toolbar
        setupToolbar();
        rootLayout.addView(toolbarLayout);

        // 2. Tabs Bar
        setupTabs();
        rootLayout.addView(tabsContainer);

        // 3. Content Frame (Library, Recent, Empty State)
        setupContentFrame();
        rootLayout.addView(contentContainer);

        // Set layout content
        setContentView(rootLayout);

        // Setup Floating Action Button (+)
        setupFAB();

        // Register GridView and ListView for Context Menus
        registerForContextMenu(libraryGridView);
        registerForContextMenu(recentListView);

        // Load data and refresh
        refreshLibrary();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Fix 5: Sync storage content on background thread to prevent UI lock
        Uri storageUri = StorageHelper.getPersistedUri(this);
        if (storageUri != null) {
            final Uri uri = storageUri;
            new Thread(() -> {
                try {
                    StorageHelper.syncStorage(MainActivity.this, uri);
                } catch (java.io.IOException e) {
                    Log.e(TAG, "Failed to sync storage onResume", e);
                }
                runOnUiThread(this::refreshLibrary);
            }, "StorageSyncThread").start();
        } else {
            refreshLibrary();
        }
    }

    private void setupToolbar() {
        toolbarLayout = new RelativeLayout(this);
        toolbarLayout.setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12));
        toolbarLayout.setBackgroundColor(Color.parseColor("#13141F")); // Surface color
        LinearLayout.LayoutParams toolbarParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(56) // Height: 56dp
        );
        toolbarLayout.setLayoutParams(toolbarParams);

        // App Icon (small, 28dp width/height) + "FM Infinite" text (Left)
        LinearLayout logoContainer = new LinearLayout(this);
        logoContainer.setId(View.generateViewId());
        logoContainer.setOrientation(LinearLayout.HORIZONTAL);
        logoContainer.setGravity(Gravity.CENTER_VERTICAL);
        RelativeLayout.LayoutParams logoContainerParams = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        logoContainerParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
        logoContainerParams.addRule(RelativeLayout.CENTER_VERTICAL);
        logoContainer.setLayoutParams(logoContainerParams);

        TextView appIcon = new TextView(this);
        appIcon.setText("🖥️");
        appIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20); // ~28dp
        appIcon.setPadding(0, 0, dpToPx(8), 0);
        logoContainer.addView(appIcon);

        TextView logoText = new TextView(this);
        logoText.setText("FM Infinite");
        logoText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        logoText.setTextColor(Color.parseColor("#7B68EE")); // Primary color
        logoText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        logoContainer.addView(logoText);

        toolbarLayout.addView(logoContainer);

        // Search Bar Container (Initially Hidden)
        searchBarContainer = new LinearLayout(this);
        searchBarContainer.setOrientation(LinearLayout.HORIZONTAL);
        searchBarContainer.setGravity(Gravity.CENTER_VERTICAL);
        searchBarContainer.setVisibility(View.GONE);
        RelativeLayout.LayoutParams searchParams = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        searchParams.addRule(RelativeLayout.RIGHT_OF, logoContainer.getId());
        searchParams.addRule(RelativeLayout.LEFT_OF, View.generateViewId()); // will anchor to settings below
        searchParams.addRule(RelativeLayout.CENTER_VERTICAL);
        searchParams.leftMargin = dpToPx(16);
        searchParams.rightMargin = dpToPx(16);
        searchBarContainer.setLayoutParams(searchParams);

        searchEditText = new EditText(this);
        searchEditText.setHint("Search games...");
        searchEditText.setHintTextColor(Color.parseColor("#4A4A6A")); // Text Disabled
        searchEditText.setTextColor(Color.parseColor("#E8E8FF")); // Text Primary
        searchEditText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        searchEditText.setBackgroundColor(Color.TRANSPARENT);
        searchEditText.setSingleLine(true);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f
        );
        searchEditText.setLayoutParams(inputParams);
        searchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterLibrary(s.toString());
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });
        searchBarContainer.addView(searchEditText);

        toolbarLayout.addView(searchBarContainer);

        // Actions Container (Right)
        LinearLayout actionsLayout = new LinearLayout(this);
        actionsLayout.setId(View.generateViewId());
        actionsLayout.setOrientation(LinearLayout.HORIZONTAL);
        RelativeLayout.LayoutParams actionsParams = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        actionsParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        actionsParams.addRule(RelativeLayout.CENTER_VERTICAL);
        actionsLayout.setLayoutParams(actionsParams);

        // Search Action Icon
        TextView searchIcon = new TextView(this);
        searchIcon.setText("🔍");
        searchIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        searchIcon.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8));
        searchIcon.setOnClickListener(v -> toggleSearch());
        actionsLayout.addView(searchIcon);

        // Settings Action Icon
        TextView settingsIcon = new TextView(this);
        settingsIcon.setText("⚙️");
        settingsIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        settingsIcon.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8));
        settingsIcon.setOnClickListener(v -> {
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
        });
        actionsLayout.addView(settingsIcon);

        toolbarLayout.addView(actionsLayout);

        // Anchor search bar to actions container right boundary
        ((RelativeLayout.LayoutParams) searchBarContainer.getLayoutParams()).addRule(RelativeLayout.LEFT_OF, actionsLayout.getId());
    }

    private void setupTabs() {
        tabsContainer = new LinearLayout(this);
        tabsContainer.setOrientation(LinearLayout.HORIZONTAL);
        tabsContainer.setBackgroundColor(Color.parseColor("#13141F")); // Surface color
        LinearLayout.LayoutParams tabsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        tabsContainer.setLayoutParams(tabsParams);

        LinearLayout.LayoutParams tabCellParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f
        );

        // 1. Library Tab
        tabLibraryLayout = new LinearLayout(this);
        tabLibraryLayout.setOrientation(LinearLayout.VERTICAL);
        tabLibraryLayout.setGravity(Gravity.CENTER_HORIZONTAL);
        tabLibraryLayout.setLayoutParams(tabCellParams);
        tabLibraryLayout.setPadding(0, dpToPx(12), 0, 0);

        tabLibraryText = new TextView(this);
        tabLibraryText.setText(getString(R.string.tab_library));
        tabLibraryText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tabLibraryText.setGravity(Gravity.CENTER);
        tabLibraryLayout.addView(tabLibraryText);

        tabLibraryIndicator = new View(this);
        LinearLayout.LayoutParams ind1Params = new LinearLayout.LayoutParams(
                dpToPx(80), dpToPx(2)
        );
        ind1Params.topMargin = dpToPx(10);
        tabLibraryIndicator.setLayoutParams(ind1Params);
        tabLibraryIndicator.setBackgroundColor(Color.parseColor("#7B68EE"));
        tabLibraryLayout.addView(tabLibraryIndicator);

        tabLibraryLayout.setOnClickListener(v -> selectTab(0));
        tabsContainer.addView(tabLibraryLayout);

        // 2. Recent Tab
        tabRecentLayout = new LinearLayout(this);
        tabRecentLayout.setOrientation(LinearLayout.VERTICAL);
        tabRecentLayout.setGravity(Gravity.CENTER_HORIZONTAL);
        tabRecentLayout.setLayoutParams(tabCellParams);
        tabRecentLayout.setPadding(0, dpToPx(12), 0, 0);

        tabRecentText = new TextView(this);
        tabRecentText.setText(getString(R.string.tab_recent));
        tabRecentText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tabRecentText.setGravity(Gravity.CENTER);
        tabRecentLayout.addView(tabRecentText);

        tabRecentIndicator = new View(this);
        LinearLayout.LayoutParams ind2Params = new LinearLayout.LayoutParams(
                dpToPx(80), dpToPx(2)
        );
        ind2Params.topMargin = dpToPx(10);
        tabRecentIndicator.setLayoutParams(ind2Params);
        tabRecentIndicator.setBackgroundColor(Color.parseColor("#7B68EE"));
        tabRecentLayout.addView(tabRecentIndicator);

        tabRecentLayout.setOnClickListener(v -> selectTab(1));
        tabsContainer.addView(tabRecentLayout);

        updateTabStyles();
    }

    private void updateTabStyles() {
        if (activeTab == 0) {
            tabLibraryText.setTextColor(Color.parseColor("#7B68EE")); // Primary
            tabLibraryText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            tabLibraryIndicator.setVisibility(View.VISIBLE);

            tabRecentText.setTextColor(Color.parseColor("#9090B0")); // Text Secondary
            tabRecentText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            tabRecentIndicator.setVisibility(View.INVISIBLE);
        } else {
            tabLibraryText.setTextColor(Color.parseColor("#9090B0")); // Text Secondary
            tabLibraryText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            tabLibraryIndicator.setVisibility(View.INVISIBLE);

            tabRecentText.setTextColor(Color.parseColor("#7B68EE")); // Primary
            tabRecentText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            tabRecentIndicator.setVisibility(View.VISIBLE);
        }
    }

    private void selectTab(int index) {
        activeTab = index;
        updateTabStyles();

        // Show FAB only on Library tab when folder is already configured
        Uri storageUri = StorageHelper.getPersistedUri(this);
        if (activeTab == 0 && storageUri != null) {
            if (fabBtn != null) fabBtn.setVisibility(View.VISIBLE);
        } else {
            if (fabBtn != null) fabBtn.setVisibility(View.GONE);
        }

        if (activeTab == 0) {
            recentListView.setVisibility(View.GONE);
            if (filteredGames.isEmpty()) {
                emptyStateContainer.setVisibility(View.VISIBLE);
                libraryGridView.setVisibility(View.GONE);
            } else {
                emptyStateContainer.setVisibility(View.GONE);
                libraryGridView.setVisibility(View.VISIBLE);
            }
        } else {
            libraryGridView.setVisibility(View.GONE);
            if (recentGamePaths.isEmpty()) {
                emptyStateContainer.setVisibility(View.VISIBLE);
                recentListView.setVisibility(View.GONE);
            } else {
                emptyStateContainer.setVisibility(View.GONE);
                recentListView.setVisibility(View.VISIBLE);
            }
        }
    }

    private void setupContentFrame() {
        contentContainer = new FrameLayout(this);
        LinearLayout.LayoutParams containerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        contentContainer.setLayoutParams(containerParams);

        // A. Library GridView (PPSSPP Responsive layout)
        libraryGridView = new GridView(this);
        int columns = getResources().getConfiguration().smallestScreenWidthDp >= 600 ? 3 : 2;
        libraryGridView.setNumColumns(columns);
        libraryGridView.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        libraryGridView.setHorizontalSpacing(dpToPx(16));
        libraryGridView.setVerticalSpacing(dpToPx(16));
        libraryGridView.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16));
        libraryGridView.setClipToPadding(false);
        libraryGridView.setOnItemClickListener((parent, view, position, id) -> {
            File gameFile = filteredGames.get(position);
            launchGame(gameFile);
        });
        contentContainer.addView(libraryGridView);

        // B. Recent ListView
        recentListView = new ListView(this);
        recentListView.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16));
        recentListView.setClipToPadding(false);
        recentListView.setDividerHeight(dpToPx(8)); // 8dp gap between items
        recentListView.setDivider(new GradientDrawable()); // Transparent spacing
        recentListView.setOnItemClickListener((parent, view, position, id) -> {
            String path = recentGamePaths.get(position);
            launchGame(new File(path));
        });
        recentListView.setVisibility(View.GONE);
        contentContainer.addView(recentListView);

        // C. Empty State Container
        emptyStateContainer = new LinearLayout(this);
        emptyStateContainer.setOrientation(LinearLayout.VERTICAL);
        emptyStateContainer.setGravity(Gravity.CENTER);
        emptyStateContainer.setPadding(dpToPx(32), dpToPx(32), dpToPx(32), dpToPx(32));
        emptyStateContainer.setVisibility(View.GONE);

        TextView emptyIcon = new TextView(this);
        emptyIcon.setText("🖥️");
        emptyIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 64); // 64sp
        emptyIcon.setGravity(Gravity.CENTER);
        emptyStateContainer.addView(emptyIcon);

        TextView emptyText = new TextView(this);
        emptyText.setText("No Games Found"); // Title: No Games Found
        emptyText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20); // 20sp
        emptyText.setTextColor(Color.parseColor("#E8E8FF")); // Text Primary
        emptyText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        emptyText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams etParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        etParams.topMargin = dpToPx(16);
        emptyText.setLayoutParams(etParams);
        emptyStateContainer.addView(emptyText);

        TextView emptySubtext = new TextView(this);
        emptySubtext.setText("Add your FM Towns ROM/ISO files"); // Subtitle
        emptySubtext.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14); // 14sp
        emptySubtext.setTextColor(Color.parseColor("#9090B0")); // Text Secondary
        emptySubtext.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams estParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        estParams.topMargin = dpToPx(8);
        estParams.bottomMargin = dpToPx(24);
        emptySubtext.setLayoutParams(estParams);
        emptyStateContainer.addView(emptySubtext);

        // Select Folder Button (Primary bg, white text, 12dp rounded, 48dp height)
        Button selectFoldBtn = new Button(this);
        selectFoldBtn.setText(getString(R.string.select_folder_btn));
        selectFoldBtn.setTextColor(Color.WHITE);
        selectFoldBtn.setTypeface(Typeface.DEFAULT_BOLD);
        selectFoldBtn.setAllCaps(false);
        GradientDrawable foldBg = new GradientDrawable();
        foldBg.setColor(Color.parseColor("#7B68EE")); // Primary color
        foldBg.setCornerRadius(dpToPx(12)); // 12dp rounded
        selectFoldBtn.setBackground(foldBg);
        LinearLayout.LayoutParams fBtnParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(48)
        );
        fBtnParams.bottomMargin = dpToPx(16); // 16dp gap between buttons
        selectFoldBtn.setLayoutParams(fBtnParams);
        selectFoldBtn.setOnClickListener(v -> openDocumentTreeLauncher.launch(null));
        emptyStateContainer.addView(selectFoldBtn);

        // Load ROM Button (transparent bg, Primary border 1dp, Primary text, 12dp rounded, 48dp height)
        Button loadRomBtn = new Button(this);
        loadRomBtn.setText(getString(R.string.load_rom_btn));
        loadRomBtn.setTextColor(Color.parseColor("#7B68EE")); // Primary text
        loadRomBtn.setTypeface(Typeface.DEFAULT_BOLD);
        loadRomBtn.setAllCaps(false);
        GradientDrawable romBg = new GradientDrawable();
        romBg.setColor(Color.TRANSPARENT);
        romBg.setStroke(dpToPx(1), Color.parseColor("#7B68EE"));
        romBg.setCornerRadius(dpToPx(12)); // 12dp rounded
        loadRomBtn.setBackground(romBg);
        loadRomBtn.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(48)
        ));
        loadRomBtn.setOnClickListener(v -> addRomLauncher.launch(new String[]{"*/*"}));
        emptyStateContainer.addView(loadRomBtn);

        contentContainer.addView(emptyStateContainer);
    }

    private void setupFAB() {
        fabBtn = new RelativeLayout(this);
        FrameLayout.LayoutParams fabParams = new FrameLayout.LayoutParams(
                dpToPx(56), dpToPx(56)
        );
        fabParams.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        fabParams.bottomMargin = dpToPx(20);
        fabParams.rightMargin = dpToPx(20);
        fabBtn.setLayoutParams(fabParams);

        // 56dp circle, Primary bg
        GradientDrawable fabBg = new GradientDrawable();
        fabBg.setShape(GradientDrawable.OVAL);
        fabBg.setColor(Color.parseColor("#7B68EE"));
        fabBtn.setBackground(fabBg);

        // White + icon 28sp centered
        TextView plusText = new TextView(this);
        plusText.setText("+");
        plusText.setTextColor(Color.WHITE);
        plusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        RelativeLayout.LayoutParams plusParams = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        plusParams.addRule(RelativeLayout.CENTER_IN_PARENT);
        plusText.setLayoutParams(plusParams);
        fabBtn.addView(plusText);

        fabBtn.setOnClickListener(v -> addRomLauncher.launch(new String[]{"*/*"}));
        contentContainer.addView(fabBtn);
    }

    private void toggleSearch() {
        if (isSearching) {
            searchBarContainer.setVisibility(View.GONE);
            searchEditText.setText("");
            isSearching = false;
        } else {
            searchBarContainer.setVisibility(View.VISIBLE);
            searchEditText.requestFocus();
            isSearching = true;
        }
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics()
        );
    }

    private void refreshLibrary() {
        // Scan ROMs
        allGames = scanGames();

        // Load recent list
        loadRecentList();

        // Apply filters
        filterLibrary(searchEditText != null ? searchEditText.getText().toString() : "");
    }

    private void filterLibrary(String query) {
        filteredGames.clear();
        if (TextUtils.isEmpty(query)) {
            filteredGames.addAll(allGames);
        } else {
            String lowerQuery = query.toLowerCase();
            for (File f : allGames) {
                if (f.getName().toLowerCase().contains(lowerQuery)) {
                    filteredGames.add(f);
                }
            }
        }

        // Setup adaptors
        if (libraryAdapter == null) {
            libraryAdapter = new LibraryAdapter();
            libraryGridView.setAdapter(libraryAdapter);
        } else {
            libraryAdapter.notifyDataSetChanged();
        }

        if (recentAdapter == null) {
            recentAdapter = new RecentAdapter();
            recentListView.setAdapter(recentAdapter);
        } else {
            recentAdapter.notifyDataSetChanged();
        }

        // Set Tab visibility appropriately
        selectTab(activeTab);
    }

    private List<File> scanGames() {
        List<File> list = new ArrayList<>();
        File localRoot = getExternalFilesDir(null);
        if (localRoot == null) localRoot = getFilesDir();
        File romsDir = new File(localRoot, StorageHelper.SUBFOLDER_ROMS);
        if (romsDir.exists() && romsDir.isDirectory()) {
            File[] files = romsDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile()) {
                        String name = f.getName().toLowerCase();
                        // Support .iso, .mds, .cue, .chd, .d77, .img
                        if (name.endsWith(".iso") || name.endsWith(".mds") || name.endsWith(".cue") ||
                            name.endsWith(".chd") || name.endsWith(".d77") || name.endsWith(".img")) {
                            list.add(f);
                        }
                    }
                }
            }
        }
        return list;
    }

    private void copyAndLaunchDirectly(Uri uri) {
        // Get original filename first (quick, on UI thread)
        String[] filename = {"custom_game.iso"};
        try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (nameIndex != -1) {
                    filename[0] = cursor.getString(nameIndex);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Cursor error", e);
        }

        android.app.ProgressDialog progress = new android.app.ProgressDialog(this);
        progress.setMessage("Adding game to library...");
        progress.setIndeterminate(true);
        progress.setCancelable(false);
        progress.show();

        final String finalFilename = filename[0];
        new Thread(() -> {
            try {
                File localRoot = getExternalFilesDir(null);
                if (localRoot == null) localRoot = getFilesDir();
                File romsDir = new File(localRoot, StorageHelper.SUBFOLDER_ROMS);
                if (!romsDir.exists()) romsDir.mkdirs();
                File localDest = new File(romsDir, finalFilename);

                // Copy file to local roms directory in chunks
                try (InputStream in = getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(localDest)) {
                    byte[] buf = new byte[65536];
                    int len;
                    while (in != null && (len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                }

                // Sync saves to SAF if configured
                Uri storageUri = StorageHelper.getPersistedUri(this);
                if (storageUri != null) {
                    StorageHelper.syncLocalSavesToSAF(this, storageUri);
                }

                final File dest = localDest;
                runOnUiThread(() -> {
                    progress.dismiss();
                    Toast.makeText(this, "Game added to library", Toast.LENGTH_SHORT).show();
                    refreshLibrary();
                    launchGame(dest);
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to copy file", e);
                runOnUiThread(() -> {
                    progress.dismiss();
                    Toast.makeText(this, "Error adding file to library.", Toast.LENGTH_LONG).show();
                });
            }
        }, "FileCopyThread").start();
    }

    private void launchGame(File gameFile) {
        if (!gameFile.exists()) {
            Toast.makeText(this, "Game file not found.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Add to Recent
        addToRecent(gameFile.getAbsolutePath());

        // Launch EmulatorActivity
        Intent intent = new Intent(this, EmulatorActivity.class);
        intent.putExtra("game_path", gameFile.getAbsolutePath());
        intent.putExtra("game_name", getBaseName(gameFile.getName()));
        startActivity(intent);
    }

    private void addToRecent(String path) {
        recentGamePaths.remove(path);
        recentGamePaths.add(0, path); // Add to top
        if (recentGamePaths.size() > 10) {
            recentGamePaths.subList(10, recentGamePaths.size()).clear();
        }
        saveRecentList();
    }

    private void loadRecentList() {
        recentGamePaths.clear();
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String recentJson = prefs.getString(KEY_RECENT_GAMES, "[]");
        try {
            JSONArray arr = new JSONArray(recentJson);
            for (int i = 0; i < arr.length(); ++i) {
                String path = arr.getString(i);
                // Only include if file actually exists
                if (new File(path).exists()) {
                    recentGamePaths.add(path);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse recent list JSON", e);
        }
    }

    private void saveRecentList() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        JSONArray arr = new JSONArray();
        for (String path : recentGamePaths) {
            arr.put(path);
        }
        prefs.edit().putString(KEY_RECENT_GAMES, arr.toString()).apply();
    }

    private String getBaseName(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    // Context Menu Handling
    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        menu.add(0, 1, 0, getString(R.string.menu_game_info));
        menu.add(0, 2, 1, getString(R.string.menu_delete_from_list));
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
        if (info == null) return super.onContextItemSelected(item);

        int pos = info.position;
        if (activeTab == 0) {
            // Library Item
            File gameFile = filteredGames.get(pos);
            handleContextMenuAction(item.getItemId(), gameFile);
        } else {
            // Recent Item
            String path = recentGamePaths.get(pos);
            handleContextMenuAction(item.getItemId(), new File(path));
        }
        return true;
    }

    private void handleContextMenuAction(int itemId, File gameFile) {
        if (itemId == 1) {
            // Game Info
            String sizeText = String.format("%.2f MB", (double) gameFile.length() / (1024 * 1024));
            new AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
                    .setTitle(getString(R.string.game_info_title))
                    .setMessage(String.format("Name: %s\n\nPath: %s\n\nSize: %s",
                            getBaseName(gameFile.getName()), gameFile.getAbsolutePath(), sizeText))
                    .setPositiveButton(getString(R.string.game_info_launch), (dialog, which) -> launchGame(gameFile))
                    .setNegativeButton("OK", null)
                    .show();
        } else if (itemId == 2) {
            // Delete
            new AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
                    .setTitle("Delete Game")
                    .setMessage("Are you sure you want to delete this game ROM file from your storage?")
                    .setPositiveButton("Delete", (dialog, which) -> {
                        // Delete file
                        if (gameFile.delete()) {
                            Toast.makeText(this, "Game deleted successfully.", Toast.LENGTH_SHORT).show();
                            // Remove from recents
                            recentGamePaths.remove(gameFile.getAbsolutePath());
                            saveRecentList();
                            // Refresh
                            refreshLibrary();
                        } else {
                            Toast.makeText(this, "Failed to delete game file.", Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }
    }

    // Adapter for Library Grid
    private class LibraryAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return filteredGames.size();
        }

        @Override
        public Object getItem(int position) {
            return filteredGames.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout itemLayout;
            if (convertView == null) {
                itemLayout = new LinearLayout(MainActivity.this);
                itemLayout.setOrientation(LinearLayout.VERTICAL);
                itemLayout.setGravity(Gravity.CENTER_HORIZONTAL);

                // Item card params: Height 180dp
                GridView.LayoutParams cardParams = new GridView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(180)
                );
                itemLayout.setLayoutParams(cardParams);

                GradientDrawable cardBg = new GradientDrawable();
                cardBg.setColor(Color.parseColor("#13141F")); // Surface bg
                cardBg.setCornerRadius(dpToPx(10)); // 10dp rounded corners
                cardBg.setStroke(dpToPx(1), Color.parseColor("#252538")); // 1dp Divider color border
                itemLayout.setBackground(cardBg);
                itemLayout.setClipToOutline(true);
            } else {
                itemLayout = (LinearLayout) convertView;
                itemLayout.removeAllViews();
            }

            File gameFile = filteredGames.get(position);
            String displayName = getBaseName(gameFile.getName());

            // Cover Image Frame (Fills top 70% of card)
            FrameLayout coverFrame = new FrameLayout(MainActivity.this);
            LinearLayout.LayoutParams coverParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.7f
            );
            coverFrame.setLayoutParams(coverParams);

            Bitmap coverBitmap = loadCover(gameFile.getName());
            if (coverBitmap != null) {
                ImageView coverImg = new ImageView(MainActivity.this);
                coverImg.setImageBitmap(coverBitmap);
                coverImg.setScaleType(ImageView.ScaleType.CENTER_CROP);

                // Rounded top corners only
                GradientDrawable imgBg = new GradientDrawable();
                imgBg.setColor(Color.parseColor("#13141F"));
                imgBg.setCornerRadii(new float[]{
                        dpToPx(10), dpToPx(10), // top-left
                        dpToPx(10), dpToPx(10), // top-right
                        0, 0,
                        0, 0
                });
                coverImg.setBackground(imgBg);
                coverImg.setClipToOutline(true);

                coverFrame.addView(coverImg, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                ));
            } else {
                // Placeholder
                LinearLayout phLayout = new LinearLayout(MainActivity.this);
                phLayout.setOrientation(LinearLayout.VERTICAL);
                phLayout.setGravity(Gravity.CENTER);
                phLayout.setBackgroundColor(Color.parseColor("#13141F"));

                TextView phIcon = new TextView(MainActivity.this);
                phIcon.setText("🖥️"); // Platform icon
                phIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
                phLayout.addView(phIcon);

                TextView phText = new TextView(MainActivity.this);
                phText.setText(displayName);
                phText.setTextColor(Color.parseColor("#9090B0")); // Text Secondary
                phText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                phText.setGravity(Gravity.CENTER);
                phText.setPadding(dpToPx(8), dpToPx(4), dpToPx(8), 0);
                phText.setSingleLine(true);
                phText.setEllipsize(TextUtils.TruncateAt.END);
                phLayout.addView(phText);

                coverFrame.addView(phLayout, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                ));
            }

            itemLayout.addView(coverFrame);

            // Text Area (Fills bottom 30% of card)
            LinearLayout textLayout = new LinearLayout(MainActivity.this);
            textLayout.setOrientation(LinearLayout.VERTICAL);
            textLayout.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.3f
            );
            textLayout.setLayoutParams(textParams);

            TextView nameBelow = new TextView(MainActivity.this);
            nameBelow.setText(displayName);
            nameBelow.setTextColor(Color.parseColor("#E8E8FF")); // Text Primary
            nameBelow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            nameBelow.setGravity(Gravity.CENTER);
            nameBelow.setSingleLine(true);
            nameBelow.setEllipsize(TextUtils.TruncateAt.END);
            nameBelow.setPadding(dpToPx(8), 0, dpToPx(8), 0);
            textLayout.addView(nameBelow);

            itemLayout.addView(textLayout);

            return itemLayout;
        }
    }

    // Adapter for Recents List
    private class RecentAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return recentGamePaths.size();
        }

        @Override
        public Object getItem(int position) {
            return recentGamePaths.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout itemLayout;
            if (convertView == null) {
                itemLayout = new LinearLayout(MainActivity.this);
                itemLayout.setOrientation(LinearLayout.HORIZONTAL);
                itemLayout.setGravity(Gravity.CENTER_VERTICAL);
                itemLayout.setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12));

                GradientDrawable itemBg = new GradientDrawable();
                itemBg.setColor(Color.parseColor("#13141F")); // Surface bg
                itemBg.setCornerRadius(dpToPx(8)); // rounded 8dp
                itemLayout.setBackground(itemBg);
            } else {
                itemLayout = (LinearLayout) convertView;
                itemLayout.removeAllViews();
            }

            String path = recentGamePaths.get(position);
            File gameFile = new File(path);
            String displayName = getBaseName(gameFile.getName());

            // 1. Cover Art thumbnail on the left (50x50dp, rounded 8dp)
            ImageView coverThumb = new ImageView(MainActivity.this);
            LinearLayout.LayoutParams thumbParams = new LinearLayout.LayoutParams(
                    dpToPx(50), dpToPx(50)
            );
            thumbParams.rightMargin = dpToPx(16);
            coverThumb.setLayoutParams(thumbParams);

            Bitmap coverBitmap = loadCover(gameFile.getName());
            if (coverBitmap != null) {
                coverThumb.setImageBitmap(coverBitmap);
                coverThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);

                GradientDrawable imgBg = new GradientDrawable();
                imgBg.setColor(Color.parseColor("#13141F"));
                imgBg.setCornerRadius(dpToPx(8));
                coverThumb.setBackground(imgBg);
                coverThumb.setClipToOutline(true);
            } else {
                // Mini placeholder with 🎮 icon
                GradientDrawable thumbBg = new GradientDrawable();
                thumbBg.setColor(Color.parseColor("#0D0D14"));
                thumbBg.setCornerRadius(dpToPx(8));
                thumbBg.setStroke(dpToPx(1), Color.parseColor("#252538"));
                coverThumb.setBackground(thumbBg);
                coverThumb.setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));
                coverThumb.setImageResource(android.R.drawable.ic_media_play);
            }
            itemLayout.addView(coverThumb);

            // 2. Text layout on the right
            LinearLayout textLayout = new LinearLayout(MainActivity.this);
            textLayout.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tlParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f
            );
            textLayout.setLayoutParams(tlParams);

            TextView nameText = new TextView(MainActivity.this);
            nameText.setText(displayName);
            nameText.setTextColor(Color.parseColor("#E8E8FF")); // Text Primary
            nameText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            nameText.setTypeface(Typeface.DEFAULT_BOLD);
            textLayout.addView(nameText);

            TextView pathText = new TextView(MainActivity.this);
            pathText.setText(path);
            pathText.setTextColor(Color.parseColor("#9090B0")); // Text Secondary
            pathText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            pathText.setSingleLine(true);
            pathText.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            textLayout.addView(pathText);

            itemLayout.addView(textLayout);

            return itemLayout;
        }
    }

    private Bitmap loadCover(String gameFileName) {
        Bitmap cached = coverCache.get(gameFileName);
        if (cached != null) return cached;

        File localRoot = getExternalFilesDir(null);
        if (localRoot == null) localRoot = getFilesDir();
        File coversDir = new File(localRoot, StorageHelper.SUBFOLDER_COVERS);
        if (coversDir.exists() && coversDir.isDirectory()) {
            String baseName = getBaseName(gameFileName);
            File cover1 = new File(coversDir, baseName + ".png");
            File cover2 = new File(coversDir, gameFileName + ".png");
            if (cover1.exists()) {
                Bitmap bmp = BitmapFactory.decodeFile(cover1.getAbsolutePath());
                if (bmp != null) coverCache.put(gameFileName, bmp);
                return bmp;
            } else if (cover2.exists()) {
                Bitmap bmp = BitmapFactory.decodeFile(cover2.getAbsolutePath());
                if (bmp != null) coverCache.put(gameFileName, bmp);
                return bmp;
            }
        }
        return null;
    }
}
