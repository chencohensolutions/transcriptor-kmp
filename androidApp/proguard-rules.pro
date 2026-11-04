# Ktor and kotlinx.serialization keep what they need through their own consumer rules.
-dontwarn org.slf4j.**
# sherpa-onnx's native code reads its config classes' fields by name (JNI GetFieldID), and its AAR ships no rules.
-keep class com.k2fsa.sherpa.onnx.** { *; }
