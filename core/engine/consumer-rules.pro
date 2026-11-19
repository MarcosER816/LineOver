# LoEngine's native methods are bound by name from liblineover_engine.so.
-keepclasseswithmembernames,includedescriptorclasses class dev.mark.lineover.core.engine.** {
    native <methods>;
}

# The render thread calls these back by name (jni_bridge.cpp, GetMethodID in nativeCreate).
# Nothing in Kotlin calls them, so without this R8 removes them from release builds and
# opening any photo fails with NoSuchMethodError. tools/ci/check_jni_keep.py guards it.
-keepclassmembers class dev.mark.lineover.core.engine.NativeEngine {
    private void onLoadingChanged(boolean);
    private void onHistogram(int[]);
}
