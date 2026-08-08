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
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class FileLogger {
    private static final String TAG = "FMInfinite_FileLogger";
    private static File logFile;
    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    public static synchronized void init(Context context) {
        if (logFile != null) {
            return;
        }
        try {
            Context appContext = context.getApplicationContext();
            File externalFilesDir = appContext.getExternalFilesDir(null);
            if (externalFilesDir == null) {
                externalFilesDir = appContext.getFilesDir();
            }
            File logsDir = new File(externalFilesDir, "logs");
            boolean created = false;
            try {
                if (!logsDir.exists()) {
                    created = logsDir.mkdirs();
                } else {
                    created = true;
                }
            } catch (Exception ex) {
                Log.e(TAG, "Exception creating logs directory in external files dir", ex);
            }

            if (!created) {
                // Fallback to internal app storage
                externalFilesDir = appContext.getFilesDir();
                logsDir = new File(externalFilesDir, "logs");
                if (!logsDir.exists()) {
                    logsDir.mkdirs();
                }
            }
            logFile = new File(logsDir, "fminfinite.log");
            Log.i(TAG, "FileLogger initialized. Log file path: " + logFile.getAbsolutePath());
            log("Java: FileLogger initialized.");
        } catch (Exception e) {
            Log.e(TAG, "Error initializing FileLogger", e);
        }
    }

    public static String getLogFilePath() {
        return logFile != null ? logFile.getAbsolutePath() : "";
    }

    public static synchronized void log(String message) {
        if (logFile == null) {
            Log.w(TAG, "FileLogger not initialized. Message not logged: " + message);
            return;
        }
        try (PrintWriter out = new PrintWriter(new FileWriter(logFile, true))) {
            String timeStamp = dateFormat.format(new Date());
            out.println(timeStamp + " - " + message);
        } catch (IOException e) {
            Log.e(TAG, "Error writing to log file", e);
        }
    }
}
