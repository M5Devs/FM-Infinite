/* LICENSE>>
Copyright 2025 M5_Development (FM Infinite Authors)

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

<< LICENSE */

package com.m5dev.fminfinite;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

public class StorageHelper {
    private static final String TAG = "FMInfinite_Storage";

    public static final String SUBFOLDER_BIOS = "bios";
    public static final String SUBFOLDER_ROMS = "roms";
    public static final String SUBFOLDER_SAVES = "saves";
    public static final String SUBFOLDER_STATES = "states";
    public static final String SUBFOLDER_COVERS = "covers";

    public static boolean isAndroid11OrHigher() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

    public static void persistUriPermission(Context context, Uri uri) {
        if (uri == null) return;
        final ContentResolver resolver = context.getContentResolver();
        final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        try {
            resolver.takePersistableUriPermission(uri, takeFlags);
            Log.i(TAG, "Persisted URI permission for: " + uri);
        } catch (Exception e) {
            Log.e(TAG, "Failed to persist URI permission", e);
        }
    }

    public static boolean hasPersistedPermission(Context context) {
        List<UriPermission> permissions = context.getContentResolver().getPersistedUriPermissions();
        for (UriPermission permission : permissions) {
            if (permission.isReadPermission() && permission.isWritePermission()) {
                return true;
            }
        }
        return false;
    }

    public static Uri getPersistedUri(Context context) {
        List<UriPermission> permissions = context.getContentResolver().getPersistedUriPermissions();
        for (UriPermission permission : permissions) {
            if (permission.isReadPermission() && permission.isWritePermission()) {
                return permission.getUri();
            }
        }
        return null;
    }

    public static void syncStorage(Context context, Uri rootUri) {
        if (rootUri == null) return;

        DocumentFile rootDir = DocumentFile.fromTreeUri(context, rootUri);
        if (rootDir == null || !rootDir.exists()) {
            Log.e(TAG, "Root Document directory does not exist.");
            return;
        }

        // Auto-create subfolders in the tree directly using fromTreeUri and createDirectory
        DocumentFile biosDoc = rootDir.findFile(SUBFOLDER_BIOS);
        if (biosDoc == null || !biosDoc.isDirectory()) {
            rootDir.createDirectory(SUBFOLDER_BIOS);
            Log.i(TAG, "Created SAF subfolder: " + SUBFOLDER_BIOS);
        }

        DocumentFile romsDoc = rootDir.findFile(SUBFOLDER_ROMS);
        if (romsDoc == null || !romsDoc.isDirectory()) {
            rootDir.createDirectory(SUBFOLDER_ROMS);
            Log.i(TAG, "Created SAF subfolder: " + SUBFOLDER_ROMS);
        }

        DocumentFile savesDoc = rootDir.findFile(SUBFOLDER_SAVES);
        if (savesDoc == null || !savesDoc.isDirectory()) {
            rootDir.createDirectory(SUBFOLDER_SAVES);
            Log.i(TAG, "Created SAF subfolder: " + SUBFOLDER_SAVES);
        }

        DocumentFile statesDoc = rootDir.findFile(SUBFOLDER_STATES);
        if (statesDoc == null || !statesDoc.isDirectory()) {
            rootDir.createDirectory(SUBFOLDER_STATES);
            Log.i(TAG, "Created SAF subfolder: " + SUBFOLDER_STATES);
        }

        DocumentFile coversDoc = rootDir.findFile(SUBFOLDER_COVERS);
        if (coversDoc == null || !coversDoc.isDirectory()) {
            rootDir.createDirectory(SUBFOLDER_COVERS);
            Log.i(TAG, "Created SAF subfolder: " + SUBFOLDER_COVERS);
        }

        // Synchronize files from DocumentTree to local app private storage for C++ core usage
        File localRoot = context.getExternalFilesDir(null);
        if (localRoot == null) {
            localRoot = context.getFilesDir();
        }

        // Copy SAF bios/roms to local folder
        syncDocToLocal(context, rootDir, localRoot, SUBFOLDER_BIOS);
        syncDocToLocal(context, rootDir, localRoot, SUBFOLDER_ROMS);
        syncDocToLocal(context, rootDir, localRoot, SUBFOLDER_SAVES);
        syncDocToLocal(context, rootDir, localRoot, SUBFOLDER_STATES);
        syncDocToLocal(context, rootDir, localRoot, SUBFOLDER_COVERS);
    }

    private static void syncDocToLocal(Context context, DocumentFile rootDoc, File localParent, String folderName) {
        DocumentFile sourceDocDir = rootDoc.findFile(folderName);
        if (sourceDocDir == null) return;

        File localDestDir = new File(localParent, folderName);
        if (!localDestDir.exists()) {
            localDestDir.mkdirs();
        }

        DocumentFile[] files = sourceDocDir.listFiles();
        for (DocumentFile docFile : files) {
            if (docFile.isFile()) {
                File destFile = new File(localDestDir, docFile.getName());
                // Only copy if size/time mismatch or doesn't exist
                if (!destFile.exists() || destFile.length() != docFile.length()) {
                    copyDocToLocalFile(context, docFile.getUri(), destFile);
                }
            }
        }
    }

    private static void copyDocToLocalFile(Context context, Uri srcUri, File destFile) {
        try (InputStream in = context.getContentResolver().openInputStream(srcUri);
             OutputStream out = new FileOutputStream(destFile)) {
            byte[] buf = new byte[8192];
            int len;
            while ((in != null) && (len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            Log.i(TAG, "Copied file to local storage: " + destFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to copy doc file to local: " + srcUri, e);
        }
    }

    public static void syncLocalSavesToSAF(Context context, Uri rootUri) {
        if (rootUri == null) return;

        DocumentFile rootDoc = DocumentFile.fromTreeUri(context, rootUri);
        if (rootDoc == null || !rootDoc.exists()) return;

        File localRoot = context.getExternalFilesDir(null);
        if (localRoot == null) {
            localRoot = context.getFilesDir();
        }

        // Push local saves & states back to SAF so they persist across uninstall/cleanups
        DocumentFile savesDoc = rootDoc.findFile(SUBFOLDER_SAVES);
        if (savesDoc == null || !savesDoc.isDirectory()) {
            savesDoc = rootDoc.createDirectory(SUBFOLDER_SAVES);
        }

        DocumentFile statesDoc = rootDoc.findFile(SUBFOLDER_STATES);
        if (statesDoc == null || !statesDoc.isDirectory()) {
            statesDoc = rootDoc.createDirectory(SUBFOLDER_STATES);
        }

        syncLocalToDoc(context, new File(localRoot, SUBFOLDER_SAVES), savesDoc);
        syncLocalToDoc(context, new File(localRoot, SUBFOLDER_STATES), statesDoc);
    }

    private static void syncLocalToDoc(Context context, File localDir, DocumentFile destDocDir) {
        if (!localDir.exists() || destDocDir == null) return;

        File[] files = localDir.listFiles();
        if (files == null) return;

        for (File localFile : files) {
            if (localFile.isFile()) {
                DocumentFile destFile = destDocDir.findFile(localFile.getName());
                if (destFile == null) {
                    destFile = destDocDir.createFile("*/*", localFile.getName());
                }
                if (destFile != null) {
                    copyLocalFileToDoc(context, localFile, destFile.getUri());
                }
            }
        }
    }

    private static void copyLocalFileToDoc(Context context, File srcFile, Uri destUri) {
        try (InputStream in = new java.io.FileInputStream(srcFile);
             OutputStream out = context.getContentResolver().openOutputStream(destUri)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                if (out != null) {
                    out.write(buf, 0, len);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to copy local file to doc: " + srcFile.getName(), e);
        }
    }
}
