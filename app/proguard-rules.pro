# The engine's JNI functions are found by name.
-keep class art.cappuccino.paint.NativeEngine { *; }

# Saved preferences and files store enum names, such as the chosen companion.
-keepclassmembers enum art.cappuccino.paint.** { *; }
