# Keep all classes and methods in the emulator package that are accessed via JNI
-keep class com.m5dev.fminfinite.EmulatorCore {
    native <methods>;
}

# Keep custom views that are declared in XML layouts
-keep class com.m5dev.fminfinite.EmulatorSurfaceView { *; }
-keep class com.m5dev.fminfinite.EmulatorGLSurfaceView { *; }

# Keep native method names from being obfuscated
-keepclasseswithmembernames class * {
    native <methods>;
}
