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
import java.util.List;

public class BIOSFileMapper {
    public static final String MODE_AUTO = "auto";
    public static final String MODE_PC = "pc";
    public static final String MODE_MARTY = "marty";
    public static final String MODE_CUSTOM = "custom";

    private static final String PREFS_NAME = "fminfinite_prefs";

    public static String getBIOSSettingMode(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString("bios_type", MODE_AUTO);
    }

    public static void setBIOSSettingMode(Context context, String mode) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString("bios_type", mode).apply();
    }

    public static String getActiveBIOSMode(Context context) {
        String mode = getBIOSSettingMode(context);
        if (MODE_AUTO.equals(mode)) {
            File biosDir = getLocalBIOSDir(context);
            if (hasFileIgnoreCase(biosDir, "TOWNS.SYS") && hasFileIgnoreCase(biosDir, "TOWNSCRD.SYS")) {
                return MODE_PC;
            } else if (hasFileIgnoreCase(biosDir, "fmt_sys.rom") && hasFileIgnoreCase(biosDir, "fmt_fnt.rom")) {
                return MODE_MARTY;
            } else {
                return MODE_PC; // Fallback default
            }
        }
        return mode;
    }

    public static File getLocalBIOSDir(Context context) {
        File localRoot = context.getExternalFilesDir(null);
        if (localRoot == null) localRoot = context.getFilesDir();
        return new File(localRoot, StorageHelper.SUBFOLDER_BIOS);
    }

    public static boolean hasFileIgnoreCase(File dir, String filename) {
        if (filename == null || filename.isEmpty()) return false;
        if (!dir.exists() || !dir.isDirectory()) return false;
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File f : files) {
            if (f.getName().equalsIgnoreCase(filename)) {
                return true;
            }
        }
        return false;
    }

    public static String findActualFilename(File dir, String filename) {
        if (filename == null || filename.isEmpty()) return filename;
        if (!dir.exists() || !dir.isDirectory()) return filename;
        File[] files = dir.listFiles();
        if (files == null) return filename;
        for (File f : files) {
            if (f.getName().equalsIgnoreCase(filename)) {
                return f.getName();
            }
        }
        return filename;
    }

    public static String getMappedSys(Context context) {
        String activeMode = getActiveBIOSMode(context);
        File biosDir = getLocalBIOSDir(context);
        if (MODE_PC.equals(activeMode)) {
            return findActualFilename(biosDir, "TOWNS.SYS");
        } else if (MODE_MARTY.equals(activeMode)) {
            return findActualFilename(biosDir, "fmt_sys.rom");
        } else {
            // Custom
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            return prefs.getString("custom_bios_sys", "TOWNS.SYS");
        }
    }

    public static String getMappedFnt(Context context) {
        String activeMode = getActiveBIOSMode(context);
        File biosDir = getLocalBIOSDir(context);
        if (MODE_PC.equals(activeMode)) {
            return findActualFilename(biosDir, "TOWNSCRD.SYS");
        } else if (MODE_MARTY.equals(activeMode)) {
            return findActualFilename(biosDir, "fmt_fnt.rom");
        } else {
            // Custom
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            return prefs.getString("custom_bios_fnt", "TOWNSCRD.SYS");
        }
    }

    public static String getMappedDos(Context context) {
        String activeMode = getActiveBIOSMode(context);
        File biosDir = getLocalBIOSDir(context);
        if (MODE_PC.equals(activeMode)) {
            return findActualFilename(biosDir, "FMT_DOS.ROM");
        } else if (MODE_MARTY.equals(activeMode)) {
            return findActualFilename(biosDir, "fmt_dos.rom");
        } else {
            // Custom
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            return prefs.getString("custom_bios_dos", "");
        }
    }

    public static String getMappedDic(Context context) {
        String activeMode = getActiveBIOSMode(context);
        File biosDir = getLocalBIOSDir(context);
        if (MODE_PC.equals(activeMode)) {
            return findActualFilename(biosDir, "FMT_DIC.ROM");
        } else if (MODE_MARTY.equals(activeMode)) {
            return findActualFilename(biosDir, "fmt_dic.rom");
        } else {
            // Custom
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            return prefs.getString("custom_bios_dic", "");
        }
    }

    public static String getCustomSys(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString("custom_bios_sys", "");
    }

    public static void setCustomSys(Context context, String val) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString("custom_bios_sys", val).apply();
    }

    public static String getCustomFnt(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString("custom_bios_fnt", "");
    }

    public static void setCustomFnt(Context context, String val) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString("custom_bios_fnt", val).apply();
    }

    public static String getCustomDos(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString("custom_bios_dos", "");
    }

    public static void setCustomDos(Context context, String val) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString("custom_bios_dos", val).apply();
    }

    public static String getCustomDic(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString("custom_bios_dic", "");
    }

    public static void setCustomDic(Context context, String val) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString("custom_bios_dic", val).apply();
    }

    // Returns a list of missing critical filenames for the selected mode
    public static List<String> getMissingBIOSFiles(Context context) {
        String mode = getBIOSSettingMode(context);
        File biosDir = getLocalBIOSDir(context);
        List<String> missing = new ArrayList<>();

        if (MODE_AUTO.equals(mode)) {
            boolean hasPc = hasFileIgnoreCase(biosDir, "TOWNS.SYS") && hasFileIgnoreCase(biosDir, "TOWNSCRD.SYS");
            boolean hasMarty = hasFileIgnoreCase(biosDir, "fmt_sys.rom") && hasFileIgnoreCase(biosDir, "fmt_fnt.rom");
            if (!hasPc && !hasMarty) {
                if (hasFileIgnoreCase(biosDir, "fmt_sys.rom") || hasFileIgnoreCase(biosDir, "fmt_fnt.rom")) {
                    if (!hasFileIgnoreCase(biosDir, "fmt_sys.rom")) missing.add("fmt_sys.rom");
                    if (!hasFileIgnoreCase(biosDir, "fmt_fnt.rom")) missing.add("fmt_fnt.rom");
                } else {
                    if (!hasFileIgnoreCase(biosDir, "TOWNS.SYS")) missing.add("TOWNS.SYS");
                    if (!hasFileIgnoreCase(biosDir, "TOWNSCRD.SYS")) missing.add("TOWNSCRD.SYS");
                }
            }
        } else if (MODE_PC.equals(mode)) {
            if (!hasFileIgnoreCase(biosDir, "TOWNS.SYS")) missing.add("TOWNS.SYS");
            if (!hasFileIgnoreCase(biosDir, "TOWNSCRD.SYS")) missing.add("TOWNSCRD.SYS");
        } else if (MODE_MARTY.equals(mode)) {
            if (!hasFileIgnoreCase(biosDir, "fmt_sys.rom")) missing.add("fmt_sys.rom");
            if (!hasFileIgnoreCase(biosDir, "fmt_fnt.rom")) missing.add("fmt_fnt.rom");
        } else if (MODE_CUSTOM.equals(mode)) {
            String sys = getCustomSys(context);
            String fnt = getCustomFnt(context);
            if (sys.isEmpty()) {
                missing.add("Custom System BIOS");
            } else if (!hasFileIgnoreCase(biosDir, sys)) {
                missing.add(sys);
            }
            if (fnt.isEmpty()) {
                missing.add("Custom Character ROM");
            } else if (!hasFileIgnoreCase(biosDir, fnt)) {
                missing.add(fnt);
            }
        }
        return missing;
    }
}
