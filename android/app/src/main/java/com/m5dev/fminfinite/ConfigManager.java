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
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class ConfigManager {
    private static final String TAG = "FMInfinite_Config";
    private static final String CONFIG_FILE = "fminfinite.json";
    private static Context appContext;

    public static void init(Context context) {
        if (context != null) {
            appContext = context.getApplicationContext();
        }
    }

    public static Context getAppContext() {
        return appContext;
    }

    public static void saveConfig(Context context, Config config) {
        if (context == null) return;
        init(context);
        try {
            JSONObject obj = new JSONObject();
            obj.put("biosPath", config.biosPath);
            obj.put("biosType", config.biosType);
            obj.put("firstRun", config.firstRun);
            obj.put("lastGamePath", config.lastGamePath);
            obj.put("biosSetupComplete", config.biosSetupComplete);

            File file = new File(context.getFilesDir(), CONFIG_FILE);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(obj.toString(4).getBytes(StandardCharsets.UTF_8));
            }
            Log.i(TAG, "Config saved to " + file.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to save config", e);
        }
    }

    public static Config loadConfig(Context context) {
        Config config = new Config();
        if (context == null) return config;
        init(context);
        File file = new File(context.getFilesDir(), CONFIG_FILE);
        if (!file.exists()) {
            Log.i(TAG, "Config file not found, returning default Config");
            return config;
        }

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            fis.read(data);
            String jsonStr = new String(data, StandardCharsets.UTF_8);
            JSONObject obj = new JSONObject(jsonStr);

            config.biosPath = obj.optString("biosPath", "");
            config.biosType = obj.optString("biosType", "auto");
            config.firstRun = obj.optBoolean("firstRun", true);
            config.lastGamePath = obj.optString("lastGamePath", "");
            config.biosSetupComplete = obj.optBoolean("biosSetupComplete", false);

            Log.i(TAG, "Config loaded from " + file.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to load config, returning default Config", e);
        }
        return config;
    }
}
