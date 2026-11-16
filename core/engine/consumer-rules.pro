# LoEngine's native methods are bound by name from liblineover_engine.so.
-keepclasseswithmembernames,includedescriptorclasses class dev.mark.lineover.core.engine.** {
    native <methods>;
}
