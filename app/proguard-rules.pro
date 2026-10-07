# Release shrinking rules.
#
# ONNX Runtime's native code looks its Java classes up by name over JNI
# (OrtSession, OnnxTensor, the value and result types). R8 renames or drops
# them, and the first inference aborts the whole process with
# "JNI DETECTED ERROR ... java_class == null" — measured on the first release
# build, in manga mode. Keep the package whole.
-keep class ai.onnxruntime.** { *; }
