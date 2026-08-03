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

#include "towns.h"
#include "townsthread.h"
#include "outside_world.h"
#include "render.h"
#include "townsdef.h"

#define LOG_TAG "FMInfinite_Bridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

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

// Custom Sound Interface for Android (using template NoSoundConnection structure)
class AndroidSound : public Outside_World::Sound
{
public:
    std::chrono::time_point<std::chrono::high_resolution_clock> FMPCMReadyTime, BeepReadyTime;

    AndroidSound()
    {
        FMPCMReadyTime = std::chrono::high_resolution_clock::now();
        BeepReadyTime = std::chrono::high_resolution_clock::now();
    }

    void Start(void) override {}
    void Stop(void) override {}
    void Polling(void) override {}

    void CDDAPlay(const DiscImage &discImg, DiscImage::MinSecFrm from, DiscImage::MinSecFrm to, bool repeat, unsigned int, unsigned int) override {}
    void CDDASetVolume(float leftVol, float rightVol) override {}
    void CDDAStop(void) override {}
    void CDDAPause(void) override {}
    void CDDAResume(void) override {}
    bool CDDAIsPlaying(void) override { return false; }
    DiscImage::MinSecFrm CDDACurrentPosition(void) override
    {
        DiscImage::MinSecFrm msf;
        msf.FromHSG(0);
        return msf;
    }

    void FMPCMPlay(std::vector<unsigned char> &wave) override
    {
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
        auto num_samples = wave.size() / 4;
        auto microsec = 10000 * num_samples / 441;
        BeepReadyTime = std::chrono::high_resolution_clock::now() + std::chrono::microseconds(microsec);
    }
    void BeepPlayStop() override {}
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

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_m5dev_fminfinite_EmulatorCore_nativeInit(JNIEnv *env, jobject thiz, jstring romDir, jstring sharedDir)
{
    std::lock_guard<std::mutex> lock(g_vm_mutex);
    LOGI("nativeInit called");

    if (g_towns != nullptr) {
        LOGI("Emulator already initialized. Reusing instance.");
        return JNI_TRUE;
    }

    const char *c_rom_dir = env->GetStringUTFChars(romDir, nullptr);
    const char *c_shared_dir = env->GetStringUTFChars(sharedDir, nullptr);

    g_rom_dir = c_rom_dir;

    g_outside_world = new AndroidOutsideWorld(g_rom_dir);
    g_window = static_cast<AndroidWindowInterface*>(g_outside_world->CreateWindowInterface());
    g_sound = static_cast<AndroidSound*>(g_outside_world->CreateSound());

    g_towns = new FMTownsWithMediumFidelityCPU();

    TownsStartParameters params;
    params.ROMPath = g_rom_dir;
    params.townsType = TOWNSTYPE_2_MX;
    params.memSizeInMB = 16; // Standard 16MB RAM
    params.highResAvailable = true;
    params.highResPCM = true;

    if (c_shared_dir && strlen(c_shared_dir) > 0) {
        params.sharedDir.push_back(c_shared_dir);
    }

    bool setupResult = FMTownsCommon::Setup(*g_towns, g_outside_world, g_window, params);

    env->ReleaseStringUTFChars(romDir, c_rom_dir);
    env->ReleaseStringUTFChars(sharedDir, c_shared_dir);

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

    g_towns->PowerOn();
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
        LOGE("Core not initialized!");
        return JNI_FALSE;
    }

    const char *c_rom_path = env->GetStringUTFChars(romPath, nullptr);
    bool result = g_towns->LoadROMImages(c_rom_path, true);
    env->ReleaseStringUTFChars(romPath, c_rom_path);

    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_m5dev_fminfinite_EmulatorCore_nativeLoadDisc(JNIEnv *env, jobject thiz, jstring discPath)
{
    std::lock_guard<std::mutex> lock(g_vm_mutex);
    LOGI("nativeLoadDisc called");

    if (g_towns == nullptr) {
        LOGE("Core not initialized!");
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
            return JNI_FALSE;
        }
    } else {
        // Assume floppy disk
        LOGI("Mounting Floppy Disk Image: %s", path_str.c_str());
        g_towns->fdc.LoadD77orRDDorRAW(0, path_str.c_str(), g_towns->state.townsTime);
        g_towns->fdc.CancelDiskChanged(0);
    }

    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_m5dev_fminfinite_EmulatorCore_nativeRunFrame(JNIEnv *env, jobject thiz)
{
    std::lock_guard<std::mutex> lock(g_vm_mutex);
    if (g_towns == nullptr) return;

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

        // Trigger rendering check
        if (g_towns->state.nextRenderingTime <= g_towns->state.townsTime) {
            g_towns->state.nextRenderingTime += FMTownsCommon::DEVICE_POLLING_INTERVAL;
            g_window->SendNewImage(*g_towns, g_outside_world->ImageNeedsFlip());
        }
    }

    g_window->Interval();
}

JNIEXPORT jboolean JNICALL
Java_com_m5dev_fminfinite_EmulatorCore_nativeGetFrameBuffer(JNIEnv *env, jobject thiz, jintArray outPixels, jintArray outSize)
{
    std::lock_guard<std::mutex> lock(g_vm_mutex);
    if (g_window == nullptr) return JNI_FALSE;

    TownsRender::ImageCopy img = g_window->GetLatestImage();
    if (img.wid == 0 || img.hei == 0 || img.rgba.empty()) {
        return JNI_FALSE;
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
        pixels_ptr[i] = (a << 24) | (r << 16) | (g << 8) | b;
    }

    env->ReleaseIntArrayElements(outPixels, pixels_ptr, 0);

    return JNI_TRUE;
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
    if (g_towns == nullptr) return JNI_FALSE;

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
    if (g_towns == nullptr) return JNI_FALSE;

    const char *c_state_path = env->GetStringUTFChars(statePath, nullptr);
    std::string path_str(c_state_path);
    env->ReleaseStringUTFChars(statePath, c_state_path);

    bool result = g_towns->LoadState(path_str);
    return result ? JNI_TRUE : JNI_FALSE;
}

}
