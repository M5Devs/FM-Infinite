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
import android.util.Log;

public class BiosScanner {
    private static final String TAG = "FMInfinite_BiosScanner";

    public static BiosInfo scanFolder(String folderPath) {
        return scanFolder(ConfigManager.getAppContext(), folderPath);
    }

    public static BiosInfo scanFolder(Context context, String folderPath) {
        BiosInfo info = new BiosInfo();
        if (folderPath == null || folderPath.isEmpty()) {
            return info;
        }

        boolean hasMar0 = false;
        boolean hasMar1 = false;
        boolean hasMar2 = false;
        boolean hasMar3 = false;

        if (folderPath.startsWith("content://") && context != null) {
            try {
                android.net.Uri uri = android.net.Uri.parse(folderPath);
                androidx.documentfile.provider.DocumentFile rootDir = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri);
                if (rootDir != null && rootDir.exists()) {
                    androidx.documentfile.provider.DocumentFile biosDir = rootDir.findFile("bios");
                    androidx.documentfile.provider.DocumentFile targetDir = (biosDir != null && biosDir.isDirectory()) ? biosDir : rootDir;

                    androidx.documentfile.provider.DocumentFile[] files = targetDir.listFiles();
                    if (files != null) {
                        for (androidx.documentfile.provider.DocumentFile file : files) {
                            if (file.isFile()) {
                                String name = file.getName();
                                if (name == null) continue;
                                String nameUpper = name.toUpperCase();
                                String fileUriStr = file.getUri().toString();
                                if (nameUpper.equals("FMT_SYS.ROM")) {
                                    info.hasSystemBios = true;
                                    info.systemBiosPath = fileUriStr;
                                } else if (nameUpper.equals("FMT_FNT.ROM")) {
                                    info.hasFontRom = true;
                                    info.fontRomPath = fileUriStr;
                                } else if (nameUpper.equals("MAR_EX0.ROM")) {
                                    hasMar0 = true;
                                } else if (nameUpper.equals("MAR_EX1.ROM")) {
                                    hasMar1 = true;
                                } else if (nameUpper.equals("MAR_EX2.ROM")) {
                                    hasMar2 = true;
                                } else if (nameUpper.equals("MAR_EX3.ROM")) {
                                    hasMar3 = true;
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to scan SAF folder: " + folderPath, e);
            }
        } else {
            // Treat as local file system path
            try {
                java.io.File rootDir = new java.io.File(folderPath);
                if (rootDir.exists()) {
                    java.io.File biosDir = new java.io.File(rootDir, "bios");
                    java.io.File targetDir = (biosDir.exists() && biosDir.isDirectory()) ? biosDir : rootDir;

                    java.io.File[] files = targetDir.listFiles();
                    if (files != null) {
                        for (java.io.File file : files) {
                            if (file.isFile()) {
                                String name = file.getName();
                                if (name == null) continue;
                                String nameUpper = name.toUpperCase();
                                String filePath = file.getAbsolutePath();
                                if (nameUpper.equals("FMT_SYS.ROM")) {
                                    info.hasSystemBios = true;
                                    info.systemBiosPath = filePath;
                                } else if (nameUpper.equals("FMT_FNT.ROM")) {
                                    info.hasFontRom = true;
                                    info.fontRomPath = filePath;
                                } else if (nameUpper.equals("MAR_EX0.ROM")) {
                                    hasMar0 = true;
                                } else if (nameUpper.equals("MAR_EX1.ROM")) {
                                    hasMar1 = true;
                                } else if (nameUpper.equals("MAR_EX2.ROM")) {
                                    hasMar2 = true;
                                } else if (nameUpper.equals("MAR_EX3.ROM")) {
                                    hasMar3 = true;
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to scan local folder: " + folderPath, e);
            }
        }

        // Parallel fallback: check local synced folder inside FM Infinite files/bios/
        if (context != null && (!info.hasSystemBios || !info.hasFontRom)) {
            try {
                java.io.File localRoot = context.getExternalFilesDir(null);
                if (localRoot == null) localRoot = context.getFilesDir();
                java.io.File biosDir = new java.io.File(localRoot, "bios");
                if (biosDir.exists() && biosDir.isDirectory()) {
                    java.io.File[] files = biosDir.listFiles();
                    if (files != null) {
                        for (java.io.File file : files) {
                            if (file.isFile()) {
                                String name = file.getName();
                                if (name == null) continue;
                                String nameUpper = name.toUpperCase();
                                String filePath = file.getAbsolutePath();
                                if (nameUpper.equals("FMT_SYS.ROM")) {
                                    info.hasSystemBios = true;
                                    info.systemBiosPath = filePath;
                                } else if (nameUpper.equals("FMT_FNT.ROM")) {
                                    info.hasFontRom = true;
                                    info.fontRomPath = filePath;
                                } else if (nameUpper.equals("MAR_EX0.ROM")) {
                                    hasMar0 = true;
                                } else if (nameUpper.equals("MAR_EX1.ROM")) {
                                    hasMar1 = true;
                                } else if (nameUpper.equals("MAR_EX2.ROM")) {
                                    hasMar2 = true;
                                } else if (nameUpper.equals("MAR_EX3.ROM")) {
                                    hasMar3 = true;
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to scan fallback local folder", e);
            }
        }

        // Detect type: PC vs Marty vs Unknown
        if (hasMar0 && hasMar1 && hasMar2 && hasMar3) {
            info.detectedType = "marty";
        } else if (info.hasSystemBios && info.hasFontRom) {
            info.detectedType = "pc";
        } else {
            info.detectedType = "unknown";
        }

        return info;
    }
}
