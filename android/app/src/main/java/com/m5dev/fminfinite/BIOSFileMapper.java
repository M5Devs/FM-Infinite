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
import android.content.SharedPreferences;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BIOSFileMapper {
    private static final String PREFS_NAME = "fminfinite_prefs";
    public static final String KEY_BIOS_TYPE = "bios_type"; // auto, pc, marty, custom

    // Custom mappings keys (logical names to paths)
    public static final String KEY_CUSTOM_SYS = "custom_bios_sys";
    public static final String KEY_CUSTOM_FNT = "custom_bios_fnt";
    public static final String KEY_CUSTOM_DIC = "custom_bios_dic";
    public static final String KEY_CUSTOM_DOS = "custom_bios_dos";
    public static final String KEY_CUSTOM_MAR0 = "custom_bios_mar0";
    public static final String KEY_CUSTOM_MAR1 = "custom_bios_mar1";
    public static final String KEY_CUSTOM_MAR2 = "custom_bios_mar2";
    public static final String KEY_CUSTOM_MAR3 = "custom_bios_mar3";

    public enum Mode {
        AUTO("auto"),
        PC("pc"),
        MARTY("marty"),
        CUSTOM("custom"),
        UNKNOWN("unknown");

        private final String val;
        Mode(String val) {
            this.val = val;
        }

        public String getValue() {
            return val;
        }

        public static Mode fromString(String val) {
            for (Mode m : Mode.values()) {
                if (m.val.equalsIgnoreCase(val)) {
                    return m;
                }
            }
            return AUTO;
        }
    }

    public static class BIOSStatus {
        public Mode configuredMode;
        public Mode detectedMode; // PC, MARTY, CUSTOM/COMBINED, UNKNOWN
        public int fileCount = 0;
        public boolean hasAllRom = false;
        public boolean hasPcRequired = false;
        public boolean hasMartyRequired = false;
        public boolean hasDeprecated = false;
        public List<String> missingFiles = new ArrayList<>();
        public String statusMessage = "";
        public boolean isOK = false;
    }

    public static BIOSStatus getStatus(Context context) {
        BIOSStatus status = new BIOSStatus();
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        status.configuredMode = Mode.fromString(prefs.getString(KEY_BIOS_TYPE, "auto"));

        File localRoot = context.getExternalFilesDir(null);
        if (localRoot == null) localRoot = context.getFilesDir();
        File biosDir = new File(localRoot, StorageHelper.SUBFOLDER_BIOS);

        boolean hasFmtSys = new File(biosDir, "FMT_SYS.ROM").exists();
        boolean hasFmtFnt = new File(biosDir, "FMT_FNT.ROM").exists();
        boolean hasFmtAll = new File(biosDir, "FMT_ALL.ROM").exists();
        boolean hasMar0 = new File(biosDir, "MAR_EX0.ROM").exists();
        boolean hasMar1 = new File(biosDir, "MAR_EX1.ROM").exists();
        boolean hasMar2 = new File(biosDir, "MAR_EX2.ROM").exists();
        boolean hasMar3 = new File(biosDir, "MAR_EX3.ROM").exists();

        boolean hasTownsSys = new File(biosDir, "TOWNS.SYS").exists() || new File(biosDir, "TOWNSCRD.SYS").exists();
        status.hasDeprecated = hasTownsSys;

        // File count
        int count = 0;
        File[] files = biosDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().toUpperCase().endsWith(".ROM")) {
                    count++;
                }
            }
        }
        status.fileCount = count;
        status.hasAllRom = hasFmtAll;
        status.hasPcRequired = hasFmtSys && hasFmtFnt;
        status.hasMartyRequired = hasMar0 && hasMar1 && hasMar2 && hasMar3;

        // Auto detection logic
        if (hasFmtAll) {
            status.detectedMode = Mode.CUSTOM; // Extract/Combined mode
        } else if (status.hasMartyRequired) {
            status.detectedMode = Mode.MARTY;
        } else if (status.hasPcRequired) {
            status.detectedMode = Mode.PC;
        } else {
            status.detectedMode = Mode.UNKNOWN;
        }

        Mode activeMode = (status.configuredMode == Mode.AUTO) ? status.detectedMode : status.configuredMode;

        if (activeMode == Mode.PC) {
            status.isOK = status.hasPcRequired || status.hasAllRom;
            if (!hasFmtSys && !status.hasAllRom) status.missingFiles.add("FMT_SYS.ROM");
            if (!hasFmtFnt && !status.hasAllRom) status.missingFiles.add("FMT_FNT.ROM");
            status.statusMessage = "FM Towns PC BIOS";
        } else if (activeMode == Mode.MARTY) {
            status.isOK = status.hasMartyRequired || status.hasAllRom;
            if (!hasMar0 && !status.hasAllRom) status.missingFiles.add("MAR_EX0.ROM");
            if (!hasMar1 && !status.hasAllRom) status.missingFiles.add("MAR_EX1.ROM");
            if (!hasMar2 && !status.hasAllRom) status.missingFiles.add("MAR_EX2.ROM");
            if (!hasMar3 && !status.hasAllRom) status.missingFiles.add("MAR_EX3.ROM");
            status.statusMessage = "FM Towns Marty BIOS";
        } else if (activeMode == Mode.CUSTOM) {
            // In custom mode, check either user's custom file paths or default ones
            boolean customSysOk = isCustomFileOk(prefs.getString(KEY_CUSTOM_SYS, ""), biosDir, "FMT_SYS.ROM");
            boolean customFntOk = isCustomFileOk(prefs.getString(KEY_CUSTOM_FNT, ""), biosDir, "FMT_FNT.ROM");
            status.isOK = (customSysOk && customFntOk) || status.hasAllRom;
            if (!customSysOk && !status.hasAllRom) status.missingFiles.add("FMT_SYS.ROM");
            if (!customFntOk && !status.hasAllRom) status.missingFiles.add("FMT_FNT.ROM");
            status.statusMessage = "Custom BIOS Mode";
        } else {
            status.isOK = status.hasAllRom;
            if (!status.hasAllRom) {
                status.missingFiles.add("FMT_SYS.ROM");
                status.missingFiles.add("FMT_FNT.ROM");
            }
            status.statusMessage = "Unknown BIOS";
        }

        return status;
    }

    private static boolean isCustomFileOk(String customPath, File biosDir, String defaultName) {
        if (customPath != null && !customPath.isEmpty()) {
            return new File(customPath).exists();
        }
        return new File(biosDir, defaultName).exists();
    }

    public static Map<String, String> getFileMappings(Context context) {
        Map<String, String> mappings = new HashMap<>();
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Mode mode = Mode.fromString(prefs.getString(KEY_BIOS_TYPE, "auto"));

        File localRoot = context.getExternalFilesDir(null);
        if (localRoot == null) localRoot = context.getFilesDir();
        File biosDir = new File(localRoot, StorageHelper.SUBFOLDER_BIOS);

        if (mode == Mode.AUTO) {
            BIOSStatus status = getStatus(context);
            mode = status.detectedMode;
        }

        if (mode == Mode.CUSTOM) {
            mappings.put("FMT_SYS.ROM", getMappedPath(prefs.getString(KEY_CUSTOM_SYS, ""), biosDir, "FMT_SYS.ROM"));
            mappings.put("FMT_FNT.ROM", getMappedPath(prefs.getString(KEY_CUSTOM_FNT, ""), biosDir, "FMT_FNT.ROM"));
            mappings.put("FMT_DIC.ROM", getMappedPath(prefs.getString(KEY_CUSTOM_DIC, ""), biosDir, "FMT_DIC.ROM"));
            mappings.put("FMT_DOS.ROM", getMappedPath(prefs.getString(KEY_CUSTOM_DOS, ""), biosDir, "FMT_DOS.ROM"));
            mappings.put("MAR_EX0.ROM", getMappedPath(prefs.getString(KEY_CUSTOM_MAR0, ""), biosDir, "MAR_EX0.ROM"));
            mappings.put("MAR_EX1.ROM", getMappedPath(prefs.getString(KEY_CUSTOM_MAR1, ""), biosDir, "MAR_EX1.ROM"));
            mappings.put("MAR_EX2.ROM", getMappedPath(prefs.getString(KEY_CUSTOM_MAR2, ""), biosDir, "MAR_EX2.ROM"));
            mappings.put("MAR_EX3.ROM", getMappedPath(prefs.getString(KEY_CUSTOM_MAR3, ""), biosDir, "MAR_EX3.ROM"));
        } else {
            // Default mappings
            mappings.put("FMT_SYS.ROM", new File(biosDir, "FMT_SYS.ROM").getAbsolutePath());
            mappings.put("FMT_FNT.ROM", new File(biosDir, "FMT_FNT.ROM").getAbsolutePath());
            mappings.put("FMT_DIC.ROM", new File(biosDir, "FMT_DIC.ROM").getAbsolutePath());
            mappings.put("FMT_DOS.ROM", new File(biosDir, "FMT_DOS.ROM").getAbsolutePath());
            mappings.put("MAR_EX0.ROM", new File(biosDir, "MAR_EX0.ROM").getAbsolutePath());
            mappings.put("MAR_EX1.ROM", new File(biosDir, "MAR_EX1.ROM").getAbsolutePath());
            mappings.put("MAR_EX2.ROM", new File(biosDir, "MAR_EX2.ROM").getAbsolutePath());
            mappings.put("MAR_EX3.ROM", new File(biosDir, "MAR_EX3.ROM").getAbsolutePath());
        }

        return mappings;
    }

    private static String getMappedPath(String customPath, File biosDir, String defaultName) {
        if (customPath != null && !customPath.isEmpty() && new File(customPath).exists()) {
            return customPath;
        }
        return new File(biosDir, defaultName).getAbsolutePath();
    }
}
