Place sherpa-onnx runtime assets in this folder before testing on device.

Expected ASR layout:
  sherpa/asr/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/
    encoder-epoch-99-avg-1.onnx
    decoder-epoch-99-avg-1.onnx
    joiner-epoch-99-avg-1.onnx
    tokens.txt

Expected speaker embedding model layout:
  sherpa/speaker/
    3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx

Native library note:
  The vendored Kotlin JNI wrappers expect the sherpa native library to be loadable as:
    sherpa-onnx-jni
  Add the corresponding .so files under app/src/main/jniLibs/<abi>/ before running on a device.
