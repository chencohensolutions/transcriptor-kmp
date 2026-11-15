# Ktor and kotlinx.serialization keep what they need through their own consumer rules.
-dontwarn org.slf4j.**
# sherpa-onnx's native code reads its config classes' fields by name (JNI GetFieldID), and its AAR ships no rules.
-keep class com.k2fsa.sherpa.onnx.** { *; }
# whisper's JNI calls these back by name (whisper_jni.cpp: the NPU encoder's time on the NPU meter).
-keepclassmembers class com.chencohensolution.transcriptor.speech.WhisperEngine$Companion { *** npuEncoder*(...); }
-keepclassmembers class com.chencohensolution.transcriptor.speech.WhisperEngine { static *** npuEncoder*(...); }
