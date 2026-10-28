# ONNX Runtime ruft Java-Klassen aus nativem Code auf (JNI) – nicht umbenennen/entfernen
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
