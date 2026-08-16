/* LICENSE>>
Copyright 2025 M5_Development (FM Infinite Authors)

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

<< LICENSE */

#include <jni.h>
#include <android/log.h>
#include <mutex>
#include <string>
#include <vector>
#include <chrono>
#include <algorithm>
#include <cstring>
#include <stdarg.h>
#include <cstdio>
#include <map>
#include <cstdint>

#include "towns.h"
#include "townsthread.h"
#include "outside_world.h"
#include "render.h"
#include "townsdef.h"

#define LOG_TAG "FMInfinite_Bridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Forward declaration
void write_to_log(const char *format, ...);

// Custom Window Interface for Android
class AndroidWindowInterface : public Outside_World::WindowInterface
{
public:
    void Start(void) override {}
    void Stop(void) override {}
    
    void Interval(void) override
    {
        BaseInterval();
        {
            std::lock_guard<std::mutex> lock(deviceStateLock);
            winThr.VMClosed = shared.VMClosedFromVMThread;
        }
    }
    
    void Render(bool swapBuffers) override {}
    
    void UpdateImage(TownsRender::ImageCopy &img) override
    {
        std::lock_guard<std::mutex> lock(imageMutex);
        static int frameCount = 0;
        frameCount++;
        if (frameCount == 1) {
            write_to_log("C++: First frame received from core! size=%dx%d alpha_sample=%u",
                img.wid, img.hei,
                img.rgba.empty() ? 0 : (unsigned char)img.rgba[3]);
        }
        if (frameCount % 300 == 0) {
            write_to_log("C++: Frame #%d received. size=%dx%d", frameCount, img.wid, img.hei);
        }
        latestImage = img;
    }
    
    void Communicate(Outside_World *) override {}

    TownsRender::ImageCopy GetLatestImage()
    {
        std::lock_guard<std::mutex> lock(imageMutex);
        return latestImage;
    }

private:
    std::mutex imageMutex;
    TownsRender::ImageCopy latestImage;
};

// Globals for JNI Audio Integration
static JavaVM* g_jvm = nullptr;
static jobject g_audio_bridge = nullptr;
static jmethodID g_writePCM_mid = nullptr;

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

// Custom Sound Interface for Android with real mixing and streaming
class AndroidSound : public Outside_World::Sound
{
public:
    std::chrono::time_point<std::chrono::high_resolution_clock> FMPCMReadyTime, BeepReadyTime;

    // CDDA state
    std::vector<unsigned char> cdda_wave;
    uint64_t cdda_play_pointer = 0;
    bool cdda_playing = false;
    bool cdda_repeat = false;
    float cdda_left_vol = 1.0f;
    float cdda_right_vol = 1.0f;

    // Beep state
    std::vector<unsigned char> beep_wave;
    uint64_t beep_play_pointer = 0;
    bool beep_playing = false;

    AndroidSound()
    {
        FMPCMReadyTime = std::chrono::high_resolution_clock::now();
        BeepReadyTime = std::chrono::high_resolution_clock::now();
    }

    void Start(void) override {}
    void Stop(void) override {}
    void Polling(void) override {}

    void CDDAPlay(const DiscImage &discImg, DiscImage::MinSecFrm from, DiscImage::MinSecFrm to, bool repeat, unsigned int, unsigned int) override
    {
        cdda_wave = discImg.GetWave(from, to);
        cdda_play_pointer = 0;
        cdda_playing = true;
        cdda_repeat = repeat;
    }
    void CDDASetVolume(float leftVol, float rightVol) override
    {
        cdda_left_vol = leftVol;
        cdda_right_vol = rightVol;
    }
    void CDDAStop(void) override
    {
        cdda_playing = false;
    }
    void CDDAPause(void) override
    {
        cdda_playing = false;
    }
    void CDDAResume(void) override
    {
        cdda_playing = true;
    }
    bool CDDAIsPlaying(void) override
    {
        return cdda_playing && (cdda_play_pointer < cdda_wave.size() || cdda_repeat);
    }
    DiscImage::MinSecFrm CDDACurrentPosition(void) override
    {
        DiscImage::MinSecFrm msf;
        uint32_t sector_offset = cdda_play_pointer / 2352;
        msf.FromHSG(sector_offset);
        return msf;
    }

    void MixStereoPCM(std::vector<unsigned char> &dst, const std::vector<unsigned char> &src, uint64_t &src_ptr, bool repeat, float left_vol = 1.0f, float right_vol = 1.0f)
    {
        if (src.empty()) return;
        int16_t *dst16 = reinterpret_cast<int16_t*>(dst.data());
        const int16_t *src16 = reinterpret_cast<const int16_t*>(src.data());
        size_t num_samples = dst.size() / 4; // 1 sample = 4 bytes (L + R)
        size_t src_num_samples = src.size() / 4;
        uint64_t src_sample_ptr = src_ptr / 4;

        for (size_t i = 0; i < num_samples; ++i) {
            if (src_sample_ptr >= src_num_samples) {
                if (repeat) {
                    src_sample_ptr = 0;
                } else {
                    break;
                }
            }

            int16_t src_l = src16[src_sample_ptr * 2];
            int16_t src_r = src16[src_sample_ptr * 2 + 1];

            // Apply volume
            src_l = static_cast<int16_t>(src_l * left_vol);
            src_r = static_cast<int16_t>(src_r * right_vol);

            // Mix Left channel
            int32_t mixed_l = dst16[i * 2] + src_l;
            if (mixed_l > 32767) mixed_l = 32767;
            else if (mixed_l < -32768) mixed_l = -32768;
            dst16[i * 2] = static_cast<int16_t>(mixed_l);

            // Mix Right channel
            int32_t mixed_r = dst16[i * 2 + 1] + src_r;
            if (mixed_r > 32767) mixed_r = 32767;
            else if (mixed_r < -32768) mixed_r = -32768;
            dst16[i * 2 + 1] = static_cast<int16_t>(mixed_r);

            src_sample_ptr++;
        }
        src_ptr = src_sample_ptr * 4;
    }

    void FMPCMPlay(std::vector<unsigned char> &wave) override
    {
        // Mix CDDA if playing
        if (cdda_playing && !cdda_wave.empty()) {
            MixStereoPCM(wave, cdda_wave, cdda_play_pointer, cdda_repeat, cdda_left_vol, cdda_right_vol);
        }

        // Mix Beep if playing
        if (beep_playing && !beep_wave.empty()) {
            MixStereoPCM(wave, beep_wave, beep_play_pointer, false);
            if (beep_play_pointer >= beep_wave.size()) {
                beep_playing = false;
            }
        }

        // Send to Java AudioBridge
        if (g_jvm && g_audio_bridge && g_writePCM_mid && !wave.empty()) {
            JNIEnv* env = nullptr;
            bool attached = false;
            jint res = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
            if (res == JNI_EDETACHED) {
                res = g_jvm->AttachCurrentThread(&env, nullptr);
                if (res == JNI_OK) {
                    attached = true;
                }
            }
            if (env != nullptr) {
                jbyteArray arr = env->NewByteArray(wave.size());
                if (arr != nullptr) {
                    env->SetByteArrayRegion(arr, 0, wave.size(), reinterpret_cast<const jbyte*>(wave.data()));
                    env->CallVoidMethod(g_audio_bridge, g_writePCM_mid, arr);
                    env->DeleteLocalRef(arr);
                }
                if (attached) {
                    g_jvm->DetachCurrentThread();
                }
            }
        }

        auto num_samples = wave.size() / 4;
        auto microsec = 10000 * num_samples / 441;
        FMPCMReadyTime = std::chrono::high_resolution_clock::now() + std::chrono::microseconds(microsec);
    }
    void FMPCMPlayStop(void) override {}
    bool FMPCMChannelPlaying(void) override
    {
        return std::chrono::high_resolution_clock::now() < FMPCMReadyTime;
    }

    void BeepPlay(int samplingRate, std::vector<unsigned char> &wave) override
    {
        beep_wave = wave;
        beep_play_pointer = 0;
        beep_playing = true;

        auto num_samples = wave.size() / 4;
        auto microsec = 10000 * num_samples / 441;
        BeepReadyTime = std::chrono::high_resolution_clock::now() + std::chrono::microseconds(microsec);
    }
    void BeepPlayStop() override
    {
        beep_playing = false;
    }
    bool BeepChannelPlaying() const override
    {
        return std::chrono::high_resolution_clock::now() < BeepReadyTime;
    }
};

// Custom Outside_World Interface for Android
class AndroidOutsideWorld : public Outside_World
{
public:
    std::string resourceDir;

    AndroidOutsideWorld(std::string rDir) : resourceDir(rDir)
    {
        SetKeyboardMode(TOWNS_KEYBOARD_MODE_DIRECT);
        SetKeyboardLayout(KEYBOARD_LAYOUT_US);
    }

    std::string GetProgramResourceDirectory(void) const override
    {
        return resourceDir;
    }

    void Start(void) override {}
    void Stop(void) override {}
    void DevicePolling(class FMTownsCommon &towns) override {}
    bool ImageNeedsFlip(void) override { return false; }
    void SetKeyboardLayout(unsigned int layout) override {}

    Outside_World::WindowInterface *CreateWindowInterface(void) const override
    {
        return new AndroidWindowInterface();
    }
    void DeleteWindowInterface(WindowInterface *itfc) const override
    {
        delete itfc;
    }

    Outside_World::Sound *CreateSound(void) const override
    {
        return new AndroidSound();
    }
    void DeleteSound(Sound *itfc) const override
    {
        delete itfc;
    }
};

// Global Emulator State
static FMTownsWithMediumFidelityCPU *g_towns = nullptr;
static AndroidOutsideWorld *g_outside_world = nullptr;
static AndroidWindowInterface *g_window = nullptr;
static AndroidSound *g_sound = nullptr;
static std::mutex g_vm_mutex;
static std::string g_rom_dir;
static std::string g_log_file_path;

static int g_bios_mode = 0; // 0 = PC, 1 = Marty, 2 = Custom
static std::map<std::string, std::string> g_bios_mappings;

void write_to_log(const char *format, ...)
{
    if (g_log_file_path.empty()) return;
    FILE *f = fopen(g_log_file_path.c_str(), "a");
    if (f) {
        va_list args;
        va_start(args, format);
        vfprintf(f, format, args);
        fprintf(f, "\n");
        va_end(args);
        fclose(f);
    }
}

std::string GetBIOSFileMapping(const std::string &dirName, const std::string &defaultFileName)
{
    auto it = g_bios_mappings.find(defaultFileName);
    if (it != g_bios_mappings.end() && !it->second.empty()) {
        write_to_log("BIOS mode: %d, loading file: %s", g_bios_mode, it->second.c_str());
        return it->second;
    }
    std::string fullPath = cpputil::MakeFullPathName(dirName, defaultFileName);
    write_to_log("BIOS mode: %d, loading file: %s", g_bios_mode, fullPath.c_str());
    return fullPath;
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_m5dev_fminfinite_EmulatorCore_nativeInitAudio(JNIEnv *env, jclass clazz, jobject audioBridgeObj)
{
    if (g_audio_bridge != nullptr) {
        env->DeleteGlobalRef(g_audio_bridge);
        g_audio_bridge = nullptr;
    }
    if (audioBridgeObj != nullptr) {
        g_audio_bridge = env->NewGlobalRef(audioBridgeObj);
        jclass cls = env->GetObjectClass(g_audio_bridge);
        g_writePCM_mid = env->GetMethodID(cls, "writePCM", "([B)V");
    } else {
        g_writePCM_mid = nullptr;
    }
    write_to_log("C++: nativeInitAudio initialized successfully");
}

JNIEXPORT void JNICALL
Java_com_m5dev_fminfinite_EmulatorCore_nativeSetBIOSMode(JNIEnv *env, jclass clazz, jint mode)
{
    g_bios_mode = mode;
    write_to_log("C++: BIOS mode set to %d", mode);
}

JNIEXPORT void JNICALL
Java_com_m5dev_fminfinite_EmulatorCore_loadBIOS(JNIEnv *env, jclass clazz, jstring sysPath, jstring fntPath)
{
    if (sysPath == nullptr || fntPath == nullptr) return;
    const char *sys = env->GetStringUTFChars(sysPath, nullptr);
    const char *fnt = env->GetStringUTFChars(fntPath, nullptr);

    g_bios_mappings["FMT_SYS.ROM"] = sys;
    g_bios_mappings["FMT_FNT.ROM"] = fnt;

    __android_log_print(ANDROID_LOG_DEBUG, "FMInfinite_Bridge", "Loading BIOS: %s", sys);
    __android_log_print(ANDROID_LOG_DEBUG, "FMInfinite_Bridge", "Loading Font: %s", fnt);
    write_to_log("C++: loadBIOS registered: FMT_SYS.ROM -> %s, FMT_FNT.ROM -> %s", sys, fnt);

    env->ReleaseStringUTFChars(sysPath, sys);
    env->ReleaseStringUTFChars(fntPath, fnt);
}

JNIEXPORT void JNICALL
Java_com_m5dev_fminfinite_EmulatorCore_nativeSetBIOSFileMapping(JNIEnv *env, jclass clazz, jstring jLogicName, jstring jActualPath)
{
    if (jLogicName == nullptr || jActualPath == nullptr) return;
    const char *c_logic = env->GetStringUTFChars(jLogicName, nullptr);
    const char *c_actual = env->GetStringUTFChars(jActualPath, nullptr);
    g_bios_mappings[c_logic] = c_actual;
    write_to_log("C++: BIOS mapping registered: %s -> %s", c_logic, c_actual);
    env->ReleaseStringUTFChars(jLogicName, c_logic);
    env->ReleaseStringUTFChars(jActualPath, c_actual);
}

JNIEXPORT void JNICALL
Java_com_m5dev_fminfinite_EmulatorCore_nativeClearBIOSFileMappings(JNIEnv *env, jclass clazz)
{
    g_bios_mappings.clear();
    write_to_log("C++: Cleared all BIOS file mappings");
}

JNIEXPORT void JNICALL
Java_com_m5dev_fminfinite_EmulatorCore_nativeSetLogFilePath(JNIEnv *env, jclass clazz, jstring logFilePath)
{
    if (logFilePath == nullptr) return;
    const char *c_path = env->GetStringUTFChars(logFilePath, nullptr);
    g_log_file_path = c_path;
    env->ReleaseStringUTFChars(logFilePath, c_path);
    write_to_log("C++: Logger initialized on C++ side successfully.");
}

JNIEXPORT jboolean JNICALL
Java_com_m5dev_fminfinite_EmulatorCore_nativeInit(JNIEnv *env, jobject thiz, jstring romDir, jstring sharedDir)
{
    std::lock_guard<std::mutex> lock(g_vm_mutex);
    LOGI("nativeInit called");
    write_to_log("C++: nativeInit called.");

    if (romDir == nullptr) {
        LOGE("nativeInit: romDir is null!");
        return JNI_FALSE;
    }

    if (g_towns != nullptr) {
        LOGI("Emulator already initialized. Reusing instance.");
        return JNI_TRUE;
    }

    const char *c_rom_dir = env->GetStringUTFChars(romDir, nullptr);
    const char *c_shared_dir = sharedDir ? env->GetStringUTFChars(sharedDir, nullptr) : nullptr;

    g_rom_dir = c_rom_dir;

    g_outside_world = new AndroidOutsideWorld(g_rom_dir);
    g_window = static_cast<AndroidWindowInterface*>(g_outside_world->CreateWindowInterface());
    g_sound = static_cast<AndroidSound*>(g_outside_world->CreateSound());

    g_towns = new FMTownsWithMediumFidelityCPU();

    write_to_log("C++: Configuring memory map. RAM size: 16MB, VRAM size: 2MB");
    TownsStartParameters params;
    params.ROMPath = g_rom_dir;
    if (g_bios_mode == 1) {
        params.townsType = TOWNSTYPE_MARTY;
        write_to_log("C++: Setting townsType to TOWNSTYPE_MARTY");
    } else {
        params.townsType = TOWNSTYPE_2_MX;
        write_to_log("C++: Setting townsType to TOWNSTYPE_2_MX");
    }
    params.memSizeInMB = 16; // Standard 16MB RAM
    params.highResAvailable = true;
    params.highResPCM = true;

    if (c_shared_dir && strlen(c_shared_dir) > 0) {
        params.sharedDir.push_back(c_shared_dir);
    }

    write_to_log("C++: BIOS loading started from directory: %s", c_rom_dir);
    bool setupResult = FMTownsCommon::Setup(*g_towns, g_outside_world, g_window, params);
    if (setupResult) {
        write_to_log("C++: BIOS loaded and core set up successfully.");
    } else {
        write_to_log("C++: BIOS load and core set up failed!");
    }

    env->ReleaseStringUTFChars(romDir, c_rom_dir);
    if (sharedDir && c_shared_dir) {
        env->ReleaseStringUTFChars(sharedDir, c_shared_dir);
    }

    if (!setupResult) {
        LOGE("Failed to set up FMTownsCommon core!");
        delete g_towns;
        g_towns = nullptr;
        delete g_window;
        g_window = nullptr;
        delete g_sound;
        g_sound = nullptr;
        delete g_outside_world;
        g_outside_world = nullptr;
        return JNI_FALSE;
    }

    // PowerOn called AFTER disc/ROM is loaded
    g_window->ClearVMClosedFlag();
    LOGI("Emulator core initialized successfully");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_m5dev_fminfinite_EmulatorCore_nativeLoadROM(JNIEnv *env, jobject thiz, jstring romPath)
{
    std::lock_guard<std::mutex> lock(g_vm_mutex);
    LOGI("nativeLoadROM called");

    if (g_towns == nullptr) {
        LOGE("nativeLoadROM: Core not initialized!");
        return JNI_FALSE;
    }

    if (romPath == nullptr) {
        LOGE("nativeLoadROM: romPath is null!");
        return JNI_FALSE;
    }

    const char *c_rom_path = env->GetStringUTFChars(romPath, nullptr);
    write_to_log("C++: BIOS load / nativeLoadROM called with path: %s", c_rom_path ? c_rom_path : "null");
    bool result = g_towns->LoadROMImages(c_rom_path, true);
    write_to_log("C++: BIOS load / nativeLoadROM result: %s", result ? "success" : "failed");
    env->ReleaseStringUTFChars(romPath, c_rom_path);

    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_m5dev_fminfinite_EmulatorCore_nativeLoadDisc(JNIEnv *env, jobject thiz, jstring discPath)
{
    std::lock_guard<std::mutex> lock(g_vm_mutex);
    LOGI("nativeLoadDisc called");

    if (g_towns == nullptr) {
        LOGE("nativeLoadDisc: Core not initialized!");
        return JNI_FALSE;
    }

    if (discPath == nullptr) {
        LOGE("nativeLoadDisc: discPath is null!");
        return JNI_FALSE;
    }

    const char *c_disc_path = env->GetStringUTFChars(discPath, nullptr);
    std::string path_str(c_disc_path);
    env->ReleaseStringUTFChars(discPath, c_disc_path);

    std::string lower_path = path_str;
    std::transform(lower_path.begin(), lower_path.end(), lower_path.begin(), ::tolower);

    // If extension corresponds to CD image
    if (lower_path.find(".iso") != std::string::npos ||
        lower_path.find(".cue") != std::string::npos ||
        lower_path.find(".mds") != std::string::npos) {
        LOGI("Mounting CD-ROM Image: %s", path_str.c_str());
        auto errCode = g_towns->cdrom.state.GetDisc().Open(path_str);
        if (DiscImage::ERROR_NOERROR != errCode) {
            LOGE("Failed to open CD image: %s", DiscImage::ErrorCodeToText(errCode));
            write_to_log("C++: CD-ROM open FAILED: %s", DiscImage::ErrorCodeToText(errCode));
            return JNI_FALSE;
        }
        write_to_log("C++: CD-ROM loaded OK. Calling PowerOn to boot from disc.");
        g_towns->PowerOn();
        write_to_log("C++: PowerOn complete after CD load.");
    } else {
        // Assume floppy disk
        LOGI("Mounting Floppy Disk Image: %s", path_str.c_str());
        g_towns->fdc.LoadD77orRDDorRAW(0, path_str.c_str(), g_towns->state.townsTime);
        g_towns->fdc.CancelDiskChanged(0);
        write_to_log("C++: Floppy loaded. Calling PowerOn.");
        g_towns->PowerOn();
        write_to_log("C++: PowerOn complete after floppy load.");
    }

    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_m5dev_fminfinite_EmulatorCore_nativeRunFrame(JNIEnv *env, jobject thiz)
{
    std::lock_guard<std::mutex> lock(g_vm_mutex);
    if (g_towns == nullptr) {
        return;
    }

    static int runFrameCount = 0;
    runFrameCount++;
    if (runFrameCount == 1) {
        write_to_log("C++: nativeRunFrame called for FIRST TIME — emulation starting!");
    }
    if (runFrameCount % 600 == 0) {
        write_to_log("C++: nativeRunFrame call #%d — emulator running", runFrameCount);
    }

    // Run for roughly 1/60th of a second worth of Towns time (16666667 nanoseconds)
    long long nanosecondsToRun = 16666667LL;
    long long targetTime = g_towns->state.townsTime + nanosecondsToRun;

    while (g_towns->state.townsTime < targetTime) {
        g_towns->var.nextTimeSync = g_towns->state.townsTime + TownsThread::NANOSECONDS_PER_TIME_SYNC;
        g_towns->debugger.ClearStopFlag();

        if (g_towns->CheckAbort()) {
            break;
        }

        while (g_towns->state.townsTime < g_towns->var.nextTimeSync) {
            while (g_towns->state.townsTime <= g_towns->state.nextFastDevicePollingTime && 
                   0 == g_towns->GetStopFlags()) {
                g_towns->RunOneInstruction();
                g_towns->pic.ProcessIRQ(g_towns->CPU(), g_towns->mem);
            }
            g_towns->RunScheduledTasks();
            g_towns->RunFastDevicePolling();
        }

        g_towns->ProcessSound(g_outside_world);
        g_towns->cdrom.UpdateCDDAState(g_towns->state.townsTime);
        g_outside_world->ProcessAppSpecific(*g_towns);

        if (g_towns->state.nextDevicePollingTime < g_towns->state.townsTime) {
            g_outside_world->UpdateStatusBarInfo(*g_towns);
            g_window->Communicate(g_outside_world);
            g_outside_world->DevicePolling(*g_towns);
            g_sound->Polling();
            g_towns->rex3586.Polling();
            g_towns->state.nextDevicePollingTime = g_towns->state.townsTime + FMTownsCommon::DEVICE_POLLING_INTERVAL;
        }

        // Trigger rendering — build directly to avoid try_lock race in SendNewImage
        if (g_towns->state.nextRenderingTime <= g_towns->state.townsTime) {
            g_towns->state.nextRenderingTime += FMTownsCommon::DEVICE_POLLING_INTERVAL;
            g_towns->RenderQuiet(g_window->shared.renderer, true, true);
            g_window->shared.renderer.MakeOpaque();
            TownsRender::ImageCopy img = g_window->shared.renderer.MoveImage();
            g_window->UpdateImage(img);
            static int imgCount = 0;
            imgCount++;
            if (imgCount == 1) {
                write_to_log("C++: First image built and sent directly! size=%dx%d", img.wid, img.hei);
            }
        }
    }
    g_window->Interval();
}

JNIEXPORT jboolean JNICALL
Java_com_m5dev_fminfinite_EmulatorCore_nativeGetFrameBuffer(JNIEnv *env, jobject thiz, jintArray outPixels, jintArray outSize)
{
    std::lock_guard<std::mutex> lock(g_vm_mutex);
    if (g_window == nullptr) {
        return JNI_FALSE;
    }

    if (outPixels == nullptr) {
        LOGE("nativeGetFrameBuffer: outPixels is null!");
        return JNI_FALSE;
    }

    if (outSize == nullptr) {
        LOGE("nativeGetFrameBuffer: outSize is null!");
        return JNI_FALSE;
    }

    TownsRender::ImageCopy img = g_window->GetLatestImage();
    if (img.wid == 0 || img.hei == 0 || img.rgba.empty()) {
        static int emptyCount = 0;
        emptyCount++;
        if (emptyCount == 1 || emptyCount % 600 == 0) {
            write_to_log("C++: nativeGetFrameBuffer: no frame yet (call #%d) wid=%d hei=%d empty=%d",
                emptyCount, img.wid, img.hei, (int)img.rgba.empty());
        }
        return JNI_FALSE;
    }

    static int fetchCount = 0;
    fetchCount++;
    if (fetchCount == 1) {
        write_to_log("C++: nativeGetFrameBuffer: FIRST frame sent to Java! size=%dx%d", img.wid, img.hei);
    }

    // Set output size
    jint size_data[2] = { static_cast<jint>(img.wid), static_cast<jint>(img.hei) };
    env->SetIntArrayRegion(outSize, 0, 2, size_data);

    // Convert RGBA memory buffer directly to standard Android ARGB format on the fly if needed,
    // or just copy the pixels as-is.
    // In TownsRender, buildImage gives RGBA: [R, G, B, A] bytes in memory.
    // For android IntBuffer, we want 0xAARRGGBB.
    // In little-endian, memory representation [B, G, R, A] is loaded as 0xAARRGGBB.
    // Since TownsRender provides [R, G, B, A] bytes in memory, that represents 0xAABBGGRR.
    // Therefore, we swap R and B on the fly to get perfect 0xAARRGGBB.
    jint *pixels_ptr = env->GetIntArrayElements(outPixels, nullptr);
    jsize dest_len = env->GetArrayLength(outPixels);
    jsize copy_len = std::min<jsize>(dest_len, img.wid * img.hei);

    const uint32_t *src_pixels = reinterpret_cast<const uint32_t*>(img.rgba.data());
    for (jsize i = 0; i < copy_len; ++i) {
        uint32_t rgba_pixel = src_pixels[i];
        uint32_t r = rgba_pixel & 0xFF;
        uint32_t g = (rgba_pixel >> 8) & 0xFF;
        uint32_t b = (rgba_pixel >> 16) & 0xFF;
        uint32_t a = (rgba_pixel >> 24) & 0xFF;
        pixels_ptr[i] = (0xFF << 24) | (r << 16) | (g << 8) | b;
    }

    env->ReleaseIntArrayElements(outPixels, pixels_ptr, 0);

    return JNI_TRUE;
}

JNIEXPORT jintArray JNICALL
Java_com_m5dev_fminfinite_EmulatorCore_getFrameBuffer(JNIEnv *env, jclass clazz)
{
    std::lock_guard<std::mutex> lock(g_vm_mutex);
    if (g_window == nullptr) {
        return nullptr;
    }

    TownsRender::ImageCopy img = g_window->GetLatestImage();
    if (img.wid == 0 || img.hei == 0 || img.rgba.empty()) {
        return nullptr;
    }

    jintArray result = env->NewIntArray(img.wid * img.hei);
    if (result == nullptr) {
        return nullptr;
    }

    jint *pixels_ptr = env->GetIntArrayElements(result, nullptr);
    const uint32_t *src_pixels = reinterpret_cast<const uint32_t*>(img.rgba.data());
    for (int i = 0; i < img.wid * img.hei; ++i) {
        uint32_t rgba_pixel = src_pixels[i];
        uint32_t r = rgba_pixel & 0xFF;
        uint32_t g = (rgba_pixel >> 8) & 0xFF;
        uint32_t b = (rgba_pixel >> 16) & 0xFF;
        uint32_t a = (rgba_pixel >> 24) & 0xFF;
        pixels_ptr[i] = (0xFF << 24) | (r << 16) | (g << 8) | b;
    }

    env->ReleaseIntArrayElements(result, pixels_ptr, 0);
    return result;
}

JNIEXPORT void JNICALL
Java_com_m5dev_fminfinite_EmulatorCore_updateFrameSoftware(JNIEnv* env, jclass clazz, jintArray pixelArray)
{
    std::lock_guard<std::mutex> lock(g_vm_mutex);
    if (g_window == nullptr || pixelArray == nullptr) {
        return;
    }

    TownsRender::ImageCopy img = g_window->GetLatestImage();
    if (img.wid == 0 || img.hei == 0 || img.rgba.empty()) {
        return;
    }

    jint *pixels_ptr = env->GetIntArrayElements(pixelArray, nullptr);
    jsize dest_len = env->GetArrayLength(pixelArray);
    jsize copy_len = std::min<jsize>(dest_len, img.wid * img.hei);

    const uint32_t *src_pixels = reinterpret_cast<const uint32_t*>(img.rgba.data());
    for (jsize i = 0; i < copy_len; ++i) {
        uint32_t rgba_pixel = src_pixels[i];
        uint32_t r = rgba_pixel & 0xFF;
        uint32_t g = (rgba_pixel >> 8) & 0xFF;
        uint32_t b = (rgba_pixel >> 16) & 0xFF;
        uint32_t a = (rgba_pixel >> 24) & 0xFF;
        pixels_ptr[i] = (0xFF << 24) | (r << 16) | (g << 8) | b;
    }

    env->ReleaseIntArrayElements(pixelArray, pixels_ptr, 0);
}

JNIEXPORT void JNICALL
Java_com_m5dev_fminfinite_EmulatorCore_nativeSendInput(JNIEnv *env, jobject thiz, jint type, jint keyOrButton, jint extra)
{
    std::lock_guard<std::mutex> lock(g_vm_mutex);
    if (g_towns == nullptr) return;

    if (type == 0) {
        // Keyboard: extra = 1 for press, 0 for release
        if (extra == 1) {
            g_towns->keyboard.PushFifo(TOWNS_KEYFLAG_JIS_PRESS, keyOrButton);
        } else {
            g_towns->keyboard.PushFifo(TOWNS_KEYFLAG_JIS_RELEASE, keyOrButton);
        }
    } else if (type == 1) {
        // Gamepad: extra = port (0 or 1), keyOrButton = bits mask
        bool A = (keyOrButton & (1 << 0)) != 0;
        bool B = (keyOrButton & (1 << 1)) != 0;
        bool left = (keyOrButton & (1 << 2)) != 0;
        bool right = (keyOrButton & (1 << 3)) != 0;
        bool up = (keyOrButton & (1 << 4)) != 0;
        bool down = (keyOrButton & (1 << 5)) != 0;
        bool run = (keyOrButton & (1 << 6)) != 0;
        bool pause = (keyOrButton & (1 << 7)) != 0;
        bool zoom = (keyOrButton & (1 << 8)) != 0;
        
        g_towns->SetGamePadState(extra, A, B, left, right, up, down, run, pause, zoom);
    } else if (type == 2) {
        // Mouse/Touch: keyOrButton: lower 16 bits = mouse_x, upper 16 = mouse_y
        // extra: bit 0 = Left click, bit 1 = Right click
        int mx = keyOrButton & 0xFFFF;
        int my = (keyOrButton >> 16) & 0xFFFF;
        bool leftClick = (extra & 1) != 0;
        bool rightClick = (extra & 2) != 0;

        g_outside_world->ProcessMouse(*g_towns, leftClick, false, rightClick, mx, my);
    }
}

JNIEXPORT void JNICALL
Java_com_m5dev_fminfinite_EmulatorCore_nativeShutdown(JNIEnv *env, jobject thiz)
{
    std::lock_guard<std::mutex> lock(g_vm_mutex);
    LOGI("nativeShutdown called");

    if (g_audio_bridge != nullptr) {
        env->DeleteGlobalRef(g_audio_bridge);
        g_audio_bridge = nullptr;
        g_writePCM_mid = nullptr;
    }

    if (g_towns == nullptr) return;

    delete g_towns;
    g_towns = nullptr;

    delete g_window;
    g_window = nullptr;

    delete g_sound;
    g_sound = nullptr;

    delete g_outside_world;
    g_outside_world = nullptr;

    LOGI("Emulator core shutdown completed");
}

JNIEXPORT jboolean JNICALL
Java_com_m5dev_fminfinite_EmulatorCore_nativeSaveState(JNIEnv *env, jobject thiz, jstring statePath)
{
    std::lock_guard<std::mutex> lock(g_vm_mutex);
    if (g_towns == nullptr) {
        LOGE("nativeSaveState: Core is not initialized!");
        return JNI_FALSE;
    }

    if (statePath == nullptr) {
        LOGE("nativeSaveState: statePath is null!");
        return JNI_FALSE;
    }

    const char *c_state_path = env->GetStringUTFChars(statePath, nullptr);
    std::string path_str(c_state_path);
    env->ReleaseStringUTFChars(statePath, c_state_path);

    bool result = g_towns->SaveState(path_str);
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_m5dev_fminfinite_EmulatorCore_nativeLoadState(JNIEnv *env, jobject thiz, jstring statePath)
{
    std::lock_guard<std::mutex> lock(g_vm_mutex);
    if (g_towns == nullptr) {
        LOGE("nativeLoadState: Core is not initialized!");
        return JNI_FALSE;
    }

    if (statePath == nullptr) {
        LOGE("nativeLoadState: statePath is null!");
        return JNI_FALSE;
    }

    const char *c_state_path = env->GetStringUTFChars(statePath, nullptr);
    std::string path_str(c_state_path);
    env->ReleaseStringUTFChars(statePath, c_state_path);

    bool result = g_towns->LoadState(path_str);
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_m5dev_fminfinite_EmulatorCore_nativeSendKey(JNIEnv *env, jclass clazz, jint keyCode, jboolean pressed)
{
    std::lock_guard<std::mutex> lock(g_vm_mutex);
    if (g_towns == nullptr) return;

    int townsKey = -1;
    switch (keyCode) {
        case 111: townsKey = TOWNS_JISKEY_ESC; break;
        case 131: townsKey = TOWNS_JISKEY_PF01; break;
        case 132: townsKey = TOWNS_JISKEY_PF02; break;
        case 133: townsKey = TOWNS_JISKEY_PF03; break;
        case 134: townsKey = TOWNS_JISKEY_PF04; break;
        case 135: townsKey = TOWNS_JISKEY_PF05; break;
        case 136: townsKey = TOWNS_JISKEY_PF06; break;
        case 137: townsKey = TOWNS_JISKEY_PF07; break;
        case 138: townsKey = TOWNS_JISKEY_PF08; break;
        case 139: townsKey = TOWNS_JISKEY_PF09; break;
        case 140: townsKey = TOWNS_JISKEY_PF10; break;
        case 61:  townsKey = TOWNS_JISKEY_TAB; break;
        case 66:  townsKey = TOWNS_JISKEY_RETURN; break;
        case 67:  townsKey = TOWNS_JISKEY_BACKSPACE; break;
        case 112: townsKey = TOWNS_JISKEY_DELETE; break;
        case 113: townsKey = TOWNS_JISKEY_CTRL; break;
        case 114: townsKey = TOWNS_JISKEY_CTRL; break;
        case 59:  townsKey = TOWNS_JISKEY_SHIFT; break;
        case 60:  townsKey = TOWNS_JISKEY_SHIFT; break;
        case 62:  townsKey = TOWNS_JISKEY_SPACE; break;
        case 121: townsKey = TOWNS_JISKEY_BREAK; break;
        case 124: townsKey = TOWNS_JISKEY_INSERT; break;
        case 122: townsKey = TOWNS_JISKEY_HOME; break;
        case 123: townsKey = TOWNS_JISKEY_CANCEL; break;
        case 92:  townsKey = TOWNS_JISKEY_PREV; break;
        case 93:  townsKey = TOWNS_JISKEY_NEXT; break;
        case 278: townsKey = TOWNS_JISKEY_EXECUTE; break;
        case 68:  townsKey = TOWNS_JISKEY_HAT; break;
        case 7:   townsKey = TOWNS_JISKEY_0; break;
        case 8:   townsKey = TOWNS_JISKEY_1; break;
        case 9:   townsKey = TOWNS_JISKEY_2; break;
        case 10:  townsKey = TOWNS_JISKEY_3; break;
        case 11:  townsKey = TOWNS_JISKEY_4; break;
        case 12:  townsKey = TOWNS_JISKEY_5; break;
        case 13:  townsKey = TOWNS_JISKEY_6; break;
        case 14:  townsKey = TOWNS_JISKEY_7; break;
        case 15:  townsKey = TOWNS_JISKEY_8; break;
        case 16:  townsKey = TOWNS_JISKEY_9; break;
        case 69:  townsKey = TOWNS_JISKEY_MINUS; break;
        case 70:  townsKey = TOWNS_JISKEY_HAT; break;
        case 252: townsKey = TOWNS_JISKEY_BACKSLASH; break;
        case 73:  townsKey = TOWNS_JISKEY_BACKSLASH; break;
        case 29:  townsKey = TOWNS_JISKEY_A; break;
        case 30:  townsKey = TOWNS_JISKEY_B; break;
        case 31:  townsKey = TOWNS_JISKEY_C; break;
        case 32:  townsKey = TOWNS_JISKEY_D; break;
        case 33:  townsKey = TOWNS_JISKEY_E; break;
        case 34:  townsKey = TOWNS_JISKEY_F; break;
        case 35:  townsKey = TOWNS_JISKEY_G; break;
        case 36:  townsKey = TOWNS_JISKEY_H; break;
        case 37:  townsKey = TOWNS_JISKEY_I; break;
        case 38:  townsKey = TOWNS_JISKEY_J; break;
        case 39:  townsKey = TOWNS_JISKEY_K; break;
        case 40:  townsKey = TOWNS_JISKEY_L; break;
        case 41:  townsKey = TOWNS_JISKEY_M; break;
        case 42:  townsKey = TOWNS_JISKEY_N; break;
        case 43:  townsKey = TOWNS_JISKEY_O; break;
        case 44:  townsKey = TOWNS_JISKEY_P; break;
        case 45:  townsKey = TOWNS_JISKEY_Q; break;
        case 46:  townsKey = TOWNS_JISKEY_R; break;
        case 47:  townsKey = TOWNS_JISKEY_S; break;
        case 48:  townsKey = TOWNS_JISKEY_T; break;
        case 49:  townsKey = TOWNS_JISKEY_U; break;
        case 50:  townsKey = TOWNS_JISKEY_V; break;
        case 51:  townsKey = TOWNS_JISKEY_W; break;
        case 52:  townsKey = TOWNS_JISKEY_X; break;
        case 53:  townsKey = TOWNS_JISKEY_Y; break;
        case 54:  townsKey = TOWNS_JISKEY_Z; break;
        case 71:  townsKey = TOWNS_JISKEY_LEFT_SQ_BRACKET; break;
        case 72:  townsKey = TOWNS_JISKEY_RIGHT_SQ_BRACKET; break;
        case 74:  townsKey = TOWNS_JISKEY_SEMICOLON; break;
        case 75:  townsKey = TOWNS_JISKEY_COLON; break;
        case 55:  townsKey = TOWNS_JISKEY_COMMA; break;
        case 56:  townsKey = TOWNS_JISKEY_DOT; break;
        case 76:  townsKey = TOWNS_JISKEY_SLASH; break;
        case 19:  townsKey = TOWNS_JISKEY_UP; break;
        case 20:  townsKey = TOWNS_JISKEY_DOWN; break;
        case 21:  townsKey = TOWNS_JISKEY_LEFT; break;
        case 22:  townsKey = TOWNS_JISKEY_RIGHT; break;
    }

    if (townsKey != -1) {
        if (pressed) {
            g_towns->keyboard.PushFifo(TOWNS_KEYFLAG_JIS_PRESS, townsKey);
        } else {
            g_towns->keyboard.PushFifo(TOWNS_KEYFLAG_JIS_RELEASE, townsKey);
        }
    } else {
        LOGE("nativeSendKey: Unmapped keyCode %d", keyCode);
    }
}

}
