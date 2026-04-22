# sherpa-onnx integration continuation

Date: 2026-04-18

## What changed

- Switched `AppContainer` defaults from demo ASR / demo speaker engines to sherpa-first wrappers with automatic fallback.
- Added vendored Kotlin JNI wrapper classes under `app/src/main/java/com/k2fsa/sherpa/onnx/` so the app can bind to `sherpa-onnx-jni` without adding a separate Kotlin artifact.
- Implemented `SherpaSpeechRecognizer` as a real streaming pipeline shell:
  - reads 16 kHz PCM frames
  - feeds sherpa online recognizer
  - emits partial and final transcript segments
  - computes utterance-level anonymous speaker embeddings when the speaker model is present
- Added `SpeakerEmbeddingStore` and updated `SherpaSpeakerIdentificationEngine` to map anonymous speaker labels to enrolled profiles via cosine similarity over stored embeddings.
- Added `SpeakerEnrollmentRecorder` so speaker profile creation now records a short enrollment sample and stores a real embedding when the sherpa speaker model is available.
- Added `app/src/main/assets/sherpa/README.txt` documenting the expected asset layout for ASR models, speaker model, and native `.so` files.
- Tightened `MeetingSessionCoordinator` so speaker matching and live chunk summarization run on final transcript segments instead of provisional partial text.

## Runtime behavior

- If `sherpa-onnx-jni` or the expected model assets are missing, the app automatically falls back to the existing demo ASR / speaker behavior instead of crashing.
- When the speaker embedding model is missing during enrollment, profile creation stores a deterministic fallback embedding so the app remains usable while the real assets are being prepared.

## Expected assets

- ASR:
  - `app/src/main/assets/sherpa/asr/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/`
  - `encoder-epoch-99-avg-1.onnx`
  - `decoder-epoch-99-avg-1.onnx`
  - `joiner-epoch-99-avg-1.onnx`
  - `tokens.txt`
- Speaker embedding:
  - `app/src/main/assets/sherpa/speaker/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx`
- Native runtime:
  - `app/src/main/jniLibs/<abi>/libsherpa-onnx-jni.so`

## Validation status

- Code changes completed.
- Local Android compilation could not be fully verified in the current environment because Gradle is running on Java 8 (`1.8.0_471`) and this project now requires a newer JDK for Android Gradle Plugin / Kotlin configuration.
