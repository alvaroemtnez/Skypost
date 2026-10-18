# R8 rules for release builds. AndroidX and Compose ship their own rules.

# Phase 2: UniFFI bindings call into the Rust core through JNA, which finds
# classes and methods by reflection.
# -keep class com.sun.jna.** { *; }
# -keep class uniffi.** { *; }
# -dontwarn java.awt.**
