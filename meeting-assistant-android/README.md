# Meeting Assistant Android

本工程是一个面向 Android 12+ 旗舰设备的纯离线会议助手 MVP，覆盖：

- 本地实时录音与前台服务
- 实时字幕与会后 final transcript 双阶段转写协议
- 匿名说话人标签 + 已注册声纹绑定
- 会中分片提纲与会后结构化纪要
- Markdown / TXT / JSON 导出

## 当前状态

这版代码已经把 MVP 的主干搭好：

- `Room + DataStore` 数据层与本地加密封装
- `Foreground Service + AudioRecord` 录音与持续会话编排
- `ModelRuntime / ModelPackManager / BenchmarkSelector` 端侧总结运行时边界
- `Setup / Speakers / Live / Review` 四个主页面

为了保证当前仓库可以先落工程结构，本版默认使用：

- `DemoSpeechRecognizer` 作为流式字幕 fallback
- `DemoSpeakerIdentificationEngine` 作为声纹映射 fallback
- `LiteRtModelRuntime` 内部先回退到 `HeuristicModelRuntime`

## 接入真实能力

### 1. sherpa-onnx

把 `runtime/asr/SpeechRecognizer.kt` 中的 `SherpaSpeechRecognizer` 接到真实 sherpa Android runtime，并将 `AppContainer` 中默认实例从 `DemoSpeechRecognizer` 切到 `SherpaSpeechRecognizer`。

同时把 `runtime/speaker/SpeakerIdentificationEngine.kt` 中的 `SherpaSpeakerIdentificationEngine` 接到 speaker embedding / verification 流程，用注册档案替换 demo 映射逻辑。

### 2. LiteRT / AI Edge Gallery 风格 runtime

把 `runtime/llm/ModelRuntime.kt` 中的 `LiteRtModelRuntime` 替换为真实 LiteRT 推理：

- `generateChunkSummary()` 对应会中轻量提纲
- `generateMeetingSummary()` 对应会后 4B 级结构化纪要

`ModelPackManager` 已经把 pack 选择和设备基准独立出来，接真实推理时不需要改 UI 与数据层。

## 目录说明

- `app/src/main/java/com/codex/meetingassistant/data`：Room、仓库、加密与导出协议
- `app/src/main/java/com/codex/meetingassistant/runtime`：音频、ASR、speaker、LLM 运行时接口与 fallback
- `app/src/main/java/com/codex/meetingassistant/service`：前台服务与会话编排
- `app/src/main/java/com/codex/meetingassistant/ui`：Compose UI 与 ViewModel

## 构建说明

当前工程已经带了 Gradle wrapper，但我这次所在环境没有 JDK 17 和 Android SDK，因此没有实际跑编译。导入 Android Studio 或在 CI 中构建前请准备：

- JDK 17
- Android SDK / Build Tools
- Android Studio 自动补齐 Gradle wrapper，或手动运行 `gradle wrapper`

## GitHub Actions

仓库现在已经包含：

- `gradlew`
- `gradlew.bat`
- `gradle/wrapper/gradle-wrapper.jar`
- `gradle/wrapper/gradle-wrapper.properties`
- `.github/workflows/android-ci.yml`

推到 GitHub 后，`main` 分支和 Pull Request 会自动执行：

- `lintDebug`
- `testDebugUnitTest`
- `assembleDebug`

构建成功后会上传 `app-debug.apk` 作为 Actions artifact。

如果要在本地先跑同一套检查：

- Windows：`gradlew.bat lintDebug testDebugUnitTest assembleDebug`
- macOS / Linux：`./gradlew lintDebug testDebugUnitTest assembleDebug`

## MVP 已实现的关键约束

- `TranscriptSegment` 按 `start_ms/end_ms/text/speaker_label/speaker_confidence/asr_confidence/is_final` 落库
- `MeetingSummary` 固定包含 `overview/decisions/action_items/risks/open_questions/speaker_notes`
- 性能降级顺序固定为：保录音与 ASR -> 降会中提纲 -> 暂停 speaker identification
- 手动重命名 speaker label 与锁定身份映射已经在 UI 和仓库层打通
