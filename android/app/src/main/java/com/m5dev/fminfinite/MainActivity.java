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
    private Button tabLibraryBtn;
    private Button tabRecentBtn;

    private FrameLayout contentContainer;
    private GridView libraryGridView;
    private ListView recentListView;
    private LinearLayout emptyStateContainer;

    private RelativeLayout fabBtn;

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
        rootLayout.setBackgroundColor(Color.parseColor("#0D0D0F"));
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
        // Sync storage content to pick up any changes
        Uri storageUri = StorageHelper.getPersistedUri(this);
        if (storageUri != null) {
            try {
                StorageHelper.syncStorage(this, storageUri);
            } catch (java.io.IOException e) {
                Log.e(TAG, "Failed to sync storage onResume", e);
            }
        }
        refreshLibrary();
    }

    private void setupToolbar() {
        toolbarLayout = new RelativeLayout(this);
        toolbarLayout.setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12));
        toolbarLayout.setBackgroundColor(Color.parseColor("#111318"));
        LinearLayout.LayoutParams toolbarParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        toolbarLayout.setLayoutParams(toolbarParams);

        // FM Infinite Title Logo (Left)
        TextView logoText = new TextView(this);
        logoText.setId(View.generateViewId());
        logoText.setText("FM Infinite");
        logoText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        logoText.setTextColor(Color.parseColor("#7B6FFF"));
        logoText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        RelativeLayout.LayoutParams logoParams = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        logoParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
        logoParams.addRule(RelativeLayout.CENTER_VERTICAL);
        logoText.setLayoutParams(logoParams);
        toolbarLayout.addView(logoText);

        // Search Bar Container (Initially Hidden)
        searchBarContainer = new LinearLayout(this);
        searchBarContainer.setOrientation(LinearLayout.HORIZONTAL);
        searchBarContainer.setGravity(Gravity.CENTER_VERTICAL);
        searchBarContainer.setVisibility(View.GONE);
        RelativeLayout.LayoutParams searchParams = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        searchParams.addRule(RelativeLayout.RIGHT_OF, logoText.getId());
        searchParams.addRule(RelativeLayout.LEFT_OF, View.generateViewId()); // will anchor to settings below
        searchParams.addRule(RelativeLayout.CENTER_VERTICAL);
        searchParams.leftMargin = dpToPx(16);
        searchParams.rightMargin = dpToPx(16);
        searchBarContainer.setLayoutParams(searchParams);

        searchEditText = new EditText(this);
        searchEditText.setHint("Search games...");
        searchEditText.setHintTextColor(Color.parseColor("#666680"));
        searchEditText.setTextColor(Color.parseColor("#E0E0FF"));
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
        tabsContainer.setBackgroundColor(Color.parseColor("#111318"));
        LinearLayout.LayoutParams tabsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        tabsContainer.setLayoutParams(tabsParams);

        LinearLayout.LayoutParams tabBtnParams = new LinearLayout.LayoutParams(
                0, dpToPx(48), 1.0f
        );

        tabLibraryBtn = new Button(this);
        tabLibraryBtn.setText(getString(R.string.tab_library));
        tabLibraryBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tabLibraryBtn.setBackgroundColor(Color.TRANSPARENT);
        tabLibraryBtn.setLayoutParams(tabBtnParams);
        tabLibraryBtn.setOnClickListener(v -> selectTab(0));
        tabsContainer.addView(tabLibraryBtn);

        tabRecentBtn = new Button(this);
        tabRecentBtn.setText(getString(R.string.tab_recent));
        tabRecentBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tabRecentBtn.setBackgroundColor(Color.TRANSPARENT);
        tabRecentBtn.setLayoutParams(tabBtnParams);
        tabRecentBtn.setOnClickListener(v -> selectTab(1));
        tabsContainer.addView(tabRecentBtn);

        updateTabStyles();
    }

    private void updateTabStyles() {
        if (activeTab == 0) {
            tabLibraryBtn.setTextColor(Color.parseColor("#7B6FFF"));
            tabLibraryBtn.setTypeface(Typeface.DEFAULT_BOLD);
            tabRecentBtn.setTextColor(Color.parseColor("#666680"));
            tabRecentBtn.setTypeface(Typeface.DEFAULT);
        } else {
            tabLibraryBtn.setTextColor(Color.parseColor("#666680"));
            tabLibraryBtn.setTypeface(Typeface.DEFAULT);
            tabRecentBtn.setTextColor(Color.parseColor("#7B6FFF"));
            tabRecentBtn.setTypeface(Typeface.DEFAULT_BOLD);
        }
    }

    private void selectTab(int index) {
        activeTab = index;
        updateTabStyles();
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

        // A. Library GridView (2 Columns)
        libraryGridView = new GridView(this);
        libraryGridView.setNumColumns(2);
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
        recentListView.setDividerHeight(dpToPx(12));
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
        emptyIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 54);
        emptyIcon.setGravity(Gravity.CENTER);
        emptyStateContainer.addView(emptyIcon);

        TextView emptyText = new TextView(this);
        emptyText.setText(getString(R.string.empty_games));
        emptyText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        emptyText.setTextColor(Color.parseColor("#E0E0FF"));
        emptyText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams etParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        etParams.topMargin = dpToPx(12);
        etParams.bottomMargin = dpToPx(24);
        emptyText.setLayoutParams(etParams);
        emptyStateContainer.addView(emptyText);

        // Select Folder Button
        Button selectFoldBtn = new Button(this);
        selectFoldBtn.setText(getString(R.string.select_folder_btn));
        selectFoldBtn.setTextColor(Color.WHITE);
        selectFoldBtn.setTypeface(Typeface.DEFAULT_BOLD);
        selectFoldBtn.setAllCaps(false);
        GradientDrawable foldBg = new GradientDrawable();
        foldBg.setColor(Color.parseColor("#7B6FFF"));
        foldBg.setCornerRadius(dpToPx(8));
        selectFoldBtn.setBackground(foldBg);
        LinearLayout.LayoutParams fBtnParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(48)
        );
        fBtnParams.bottomMargin = dpToPx(12);
        selectFoldBtn.setLayoutParams(fBtnParams);
        selectFoldBtn.setOnClickListener(v -> openDocumentTreeLauncher.launch(null));
        emptyStateContainer.addView(selectFoldBtn);

        // Load ROM Button
        Button loadRomBtn = new Button(this);
        loadRomBtn.setText(getString(R.string.load_rom_btn));
        loadRomBtn.setTextColor(Color.parseColor("#7B6FFF"));
        loadRomBtn.setTypeface(Typeface.DEFAULT_BOLD);
        loadRomBtn.setAllCaps(false);
        GradientDrawable romBg = new GradientDrawable();
        romBg.setColor(Color.TRANSPARENT);
        romBg.setStroke(dpToPx(1), Color.parseColor("#7B6FFF"));
        romBg.setCornerRadius(dpToPx(8));
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
        fabParams.bottomMargin = dpToPx(24);
        fabParams.rightMargin = dpToPx(24);
        fabBtn.setLayoutParams(fabParams);

        GradientDrawable fabBg = new GradientDrawable();
        fabBg.setShape(GradientDrawable.OVAL);
        fabBg.setColor(Color.parseColor("#7B6FFF"));
        fabBtn.setBackground(fabBg);

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
                        if (name.endsWith(".iso") || name.endsWith(".mds") || name.endsWith(".cue") || name.endsWith(".chd")) {
                            list.add(f);
                        }
                    }
                }
            }
        }
        return list;
    }

    private void copyAndLaunchDirectly(Uri uri) {
        try {
            // Get original filename
            String filename = "custom_game.iso";
            try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (nameIndex != -1) {
                        filename = cursor.getString(nameIndex);
                    }
                }
            }

            File localRoot = getExternalFilesDir(null);
            if (localRoot == null) localRoot = getFilesDir();
            File romsDir = new File(localRoot, StorageHelper.SUBFOLDER_ROMS);
            if (!romsDir.exists()) romsDir.mkdirs();

            File localDest = new File(romsDir, filename);

            // Copy to local
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(localDest)) {
                byte[] buf = new byte[8192];
                int len;
                while ((in != null) && (len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
            }

            // Sync back to SAF if permission is active
            Uri storageUri = StorageHelper.getPersistedUri(this);
            if (storageUri != null) {
                StorageHelper.syncLocalSavesToSAF(this, storageUri);
            }

            Toast.makeText(this, "Game added to library", Toast.LENGTH_SHORT).show();
            refreshLibrary();

            // Launch the added game!
            launchGame(localDest);

        } catch (Exception e) {
            Log.e(TAG, "Failed to copy custom file", e);
            Toast.makeText(this, "Error adding file to library.", Toast.LENGTH_LONG).show();
        }
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
                itemLayout.setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));
            } else {
                itemLayout = (LinearLayout) convertView;
                itemLayout.removeAllViews();
            }

            File gameFile = filteredGames.get(position);
            String displayName = getBaseName(gameFile.getName());

            // 1. Cover Art Frame
            FrameLayout coverFrame = new FrameLayout(MainActivity.this);
            LinearLayout.LayoutParams frameParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(160)
            );
            coverFrame.setLayoutParams(frameParams);

            Bitmap coverBitmap = loadCover(gameFile.getName());
            if (coverBitmap != null) {
                // Show cover image
                ImageView coverImg = new ImageView(MainActivity.this);
                coverImg.setImageBitmap(coverBitmap);
                coverImg.setScaleType(ImageView.ScaleType.CENTER_CROP);

                GradientDrawable imageBg = new GradientDrawable();
                imageBg.setColor(Color.parseColor("#111318"));
                imageBg.setCornerRadius(dpToPx(8));
                coverImg.setBackground(imageBg);
                coverImg.setClipToOutline(true);

                coverFrame.addView(coverImg, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                ));
            } else {
                // Custom dark placeholder card with centered white text + watermark
                GradientDrawable phBg = new GradientDrawable();
                phBg.setColor(Color.parseColor("#111318"));
                phBg.setCornerRadius(dpToPx(8));
                phBg.setStroke(dpToPx(1), Color.parseColor("#7B6FFF"));
                coverFrame.setBackground(phBg);

                // Watermark in bottom right
                TextView watermark = new TextView(MainActivity.this);
                watermark.setText("FM Infinite");
                watermark.setTextColor(Color.parseColor("#222432")); // Low contrast
                watermark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                watermark.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                FrameLayout.LayoutParams wmParams = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                );
                wmParams.gravity = Gravity.BOTTOM | Gravity.RIGHT;
                wmParams.rightMargin = dpToPx(8);
                wmParams.bottomMargin = dpToPx(8);
                watermark.setLayoutParams(wmParams);
                coverFrame.addView(watermark);

                // Centered bold text
                TextView nameCenter = new TextView(MainActivity.this);
                nameCenter.setText(displayName);
                nameCenter.setTextColor(Color.WHITE);
                nameCenter.setGravity(Gravity.CENTER);
                nameCenter.setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12));
                nameCenter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                nameCenter.setTypeface(Typeface.DEFAULT_BOLD);
                FrameLayout.LayoutParams textParams = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                );
                textParams.gravity = Gravity.CENTER;
                nameCenter.setLayoutParams(textParams);
                coverFrame.addView(nameCenter);
            }

            itemLayout.addView(coverFrame);

            // 2. Game name text below card
            TextView nameBelow = new TextView(MainActivity.this);
            nameBelow.setText(displayName);
            nameBelow.setTextColor(Color.parseColor("#E0E0FF"));
            nameBelow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            nameBelow.setGravity(Gravity.CENTER);
            nameBelow.setSingleLine(true);
            nameBelow.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nbParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            nbParams.topMargin = dpToPx(6);
            nameBelow.setLayoutParams(nbParams);
            itemLayout.addView(nameBelow);

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
                itemBg.setColor(Color.parseColor("#111318"));
                itemBg.setCornerRadius(dpToPx(8));
                itemLayout.setBackground(itemBg);
            } else {
                itemLayout = (LinearLayout) convertView;
            }

            String path = recentGamePaths.get(position);
            File gameFile = new File(path);
            String displayName = getBaseName(gameFile.getName());

            // 1. Cover Art thumbnail on the left
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
            } else {
                // Mini placeholder circle/square
                GradientDrawable thumbBg = new GradientDrawable();
                thumbBg.setColor(Color.parseColor("#0D0D0F"));
                thumbBg.setCornerRadius(dpToPx(6));
                thumbBg.setStroke(dpToPx(1), Color.parseColor("#7B6FFF"));
                coverThumb.setBackground(thumbBg);
                coverThumb.setImageResource(android.R.drawable.ic_menu_gallery);
            }
            // Clear prior views before drawing to prevent duplicates
            itemLayout.removeAllViews();
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
            nameText.setTextColor(Color.parseColor("#E0E0FF"));
            nameText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            nameText.setTypeface(Typeface.DEFAULT_BOLD);
            textLayout.addView(nameText);

            TextView pathText = new TextView(MainActivity.this);
            pathText.setText(path);
            pathText.setTextColor(Color.parseColor("#666680"));
            pathText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            pathText.setSingleLine(true);
            pathText.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            textLayout.addView(pathText);

            itemLayout.addView(textLayout);

            return itemLayout;
        }
    }

    private Bitmap loadCover(String gameFileName) {
        File localRoot = getExternalFilesDir(null);
        if (localRoot == null) localRoot = getFilesDir();
        File coversDir = new File(localRoot, StorageHelper.SUBFOLDER_COVERS);
        if (coversDir.exists() && coversDir.isDirectory()) {
            String baseName = getBaseName(gameFileName);
            File cover1 = new File(coversDir, baseName + ".png");
            File cover2 = new File(coversDir, gameFileName + ".png");
            if (cover1.exists()) {
                return BitmapFactory.decodeFile(cover1.getAbsolutePath());
            } else if (cover2.exists()) {
                return BitmapFactory.decodeFile(cover2.getAbsolutePath());
            }
        }
        return null;
    }
}
