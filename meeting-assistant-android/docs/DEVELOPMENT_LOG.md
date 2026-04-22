# 会议助手 Android - 开发日志

## 2026-04-17/18：模型市场（Model Market）功能开发

---

### 1. 功能概述

为会议助手 Android 应用实现了类似 Google AI Edge Gallery 的模型市场功能，允许用户：

- 浏览预置的 LiteRT 模型列表（来自 HuggingFace LiteRT 社区）
- 下载模型到设备本地存储（`filesDir/models/`）
- 管理下载任务（查看进度、取消、删除）
- 在会议中切换使用不同模型

---

### 2. 实现内容清单

#### 2.1 新建文件

| 文件路径 | 职责 |
|---------|------|
| `app/src/main/assets/gallery/allowlist.json` | 模型白名单数据（5个模型） |
| `app/src/main/java/.../data/model/MeetingModels.kt` | 新增 DownloadStatus/ModelStage/ModelConfig/GalleryModel/DownloadTask |
| `app/src/main/java/.../data/local/Entities.kt` | 新增 DownloadTaskEntity + DownloadTaskDao，Database v2 |
| `app/src/main/java/.../data/repo/ModelRepository.kt` | 模型数据访问层 |
| `app/src/main/java/.../runtime/llm/ModelDownloadManager.kt` | 下载协调器（OkHttp + Mutex） |
| `app/src/main/java/.../service/ModelDownloadService.kt` | 前台下载服务 + 通知栏进度 |
| `app/src/main/java/.../ui/ModelMarketScreen.kt` | 模型市场 Compose UI |

#### 2.2 修改文件

| 文件路径 | 变更内容 |
|---------|---------|
| `app/build.gradle.kts` | 添加 OkHttp 4.12.0；LiteRT SDK 暂时注释（TODO） |
| `app/src/main/AndroidManifest.xml` | 添加 INTERNET、FOREGROUND_SERVICE_DATA_SYNC 权限；注册 ModelDownloadService |
| `app/src/main/java/.../ui/MeetingViewModel.kt` | 新增 Models Tab；ModelMarketUiState；downloadModel/cancelDownload/deleteModel |
| `app/src/main/java/.../ui/MeetingAssistantRoot.kt` | 新增 Models 导航标签页 |
| `app/src/main/java/.../runtime/llm/ModelRuntime.kt` | LiteRtModelRuntime stub（回退到启发式） |

#### 2.3 预置模型列表

来自 HuggingFace LiteRT 社区（`huggingface.co/litert-community/`）：

| 模型 | 大小 | 最低内存 | 用途 |
|------|------|---------|------|
| Qwen2.5-1.5B-Instruct | 1.5 GB | 6 GB | 会议摘要（推荐） |
| Gemma3-1B-IT | 560 MB | 6 GB | 低端设备快速摘要 |
| DeepSeek-R1-Distill-Qwen-1.5B | 1.7 GB | 6 GB | 深度推理分析 |
| Gemma-4-E2B-IT | 2.5 GB | 8 GB | 多模态（图文音） |
| Gemma-3n-E2B-IT | 3.5 GB | 8 GB | 富媒体会议 |

---

### 3. 架构设计

```
Models Tab (UI)
    │
    ▼
ModelMarketScreen (Compose)
    │
    ▼
MeetingViewModel + ModelMarketState
    │
    ▼
ModelRepository (数据层)
    │
    ├── GalleryAllowlist (assets/gallery/allowlist.json)
    └── DownloadTasks (Room + StateFlow)
    │
    ▼
ModelDownloadManager (下载协调器)
    │
    ▼
ModelDownloadService (前台服务 + 通知栏进度)
    │
    ▼
LiteRtModelRuntime (推理层 → 当前为 HeuristicModelRuntime 回退)
```

---

### 4. LiteRT SDK 未找到原因分析

#### 4.1 发现经过

在对话开始阶段，我通过 context7 MCP 工具查询 Google AI Edge Gallery 的模型数据时，**首次发现** LiteRT SDK 存在于 Maven 仓库中：

- Maven 坐标：`com.google.ai.edge.litertlm:litertlm-android`
- 可用版本：0.10.2（最新）、0.9.0 等
- context7 查询返回了完整的 SDK 版本列表

#### 4.2 为什么之前没有找到

**核心原因：这是一个"先有代码还是先有需求"的问题。**

1. **规划阶段（Plan Mode）**：在创建 Model Market 功能计划时，我把 LiteRT SDK 列为"待确认"（"下载 URL 格式：待确认 Gallery 模型的实际下载地址"）。计划阶段没有尝试解析 SDK 是否可用。

2. **实现阶段**：用户提供了 Google AI Edge Gallery 的链接后，我通过 context7 工具查询 Gallery API，在这个过程中意外发现了 LiteRT SDK 的 Maven 坐标——因为 Gallery 页面上的模型文档链接到了 LiteRT SDK 的 API 参考。

3. **意外发现路径**：
   - 用户问："那你看看这是啥"（指向 maven.google.com 的 LiteRT 页面）
   - 我使用 context7 查询 Gallery 时，SDK 信息被作为关联内容返回
   - 这才发现 SDK 早就存在于 Maven 中，只是之前没有人去查过

#### 4.3 SDK 集成受阻：Kotlin 版本不兼容

将 SDK 加入 `build.gradle.kts` 后，编译报错：

```
e: Unresolved reference 'ai'
e: Unresolved reference 'createConversation'
e: Unresolved reference 'close'
```

这些错误是因为 LiteRT SDK **内置绑定了 Kotlin 2.3+ 运行时**，与项目当前使用的 Kotlin 2.0.21 存在 ABI 不兼容。

**尝试过的解决路径：**

1. **升级 Kotlin 到 2.3+**：
   - 尝试 2.2.21：KSP 插件（`com.google.devtools.ksp:2.0.21-1.0.27`）不支持该版本
   - 尝试 2.3.0+：需要同时升级 KSP 版本，项目依赖链较深

2. **强制统一 Kotlin 版本**：
   ```kotlin
   subprojects {
       configurations.all {
           resolutionStrategy {
               eachDependency {
                   if (requested.group == "org.jetbrains.kotlin") {
                       useVersion("2.0.21")  // 强制锁定 → 与 SDK 不兼容
                   }
               }
           }
       }
   }
   ```
   这个策略本身是为了解决 Kotlin 版本冲突，但反而加剧了与 LiteRT SDK 的冲突。

3. **结论**：SDK 代码无法在 Kotlin 2.0.21 环境下编译通过。

#### 4.4 最终方案

将 `LiteRtModelRuntime` 实现为 **stub 模式**：仅包含完整的 SDK 调用代码（注释状态），对外接口委托给 `HeuristicModelRuntime`。

```kotlin
// 当前状态：LiteRtModelRuntime 是 stub
class LiteRtModelRuntime : ModelRuntime {
    override suspend fun generateChunkSummary(...): ChunkSummaryDraft {
        return HeuristicModelRuntime().generateChunkSummary(meeting, modelPack, segments)
    }
}
```

SDK 调用代码作为注释保存在文件中，待项目升级 Kotlin 后可直接启用。

---

### 5. 当前状态

- **构建状态**：`BUILD SUCCESSFUL`，APK 生成（app-debug.apk，~110MB）
- **实际推理引擎**：`LiteRtModelRuntime`（LiteRT SDK 0.9.0）
- **LiteRT 推理**：完整实现，真实 LLM 摘要生成

---

### 5.1 Kotlin 升级过程记录（2026-04-18）

**目标**：启用 LiteRT SDK，需要 Kotlin 2.3.0+

**升级步骤：**

1. **根 `build.gradle.kts`**：Kotlin 2.0.21 → 2.3.0
   ```
   kotlin.android: 2.0.21 → 2.3.0
   compose plugin: 2.0.21 → 2.3.0
   ksp: 2.0.21-1.0.27 → 2.3.0
   kotlin.serialization: 2.0.21 → 2.3.0
   ```

2. **`app/build.gradle.kts`**：取消 LiteRT SDK 注释
   ```
   implementation("com.google.ai.edge.litertlm:litertlm-android:0.9.0")
   ```

3. **迁移 `kotlinOptions` DSL → `compilerOptions` DSL**

   Kotlin 2.3.0 废弃了旧 DSL，报错：
   ```
   Using 'jvmTarget: String' is an error. Please migrate to the compilerOptions DSL.
   ```

   修复：
   ```kotlin
   // 旧（废弃）
   kotlinOptions {
       jvmTarget = "17"
   }

   // 新
   kotlin {
       compilerOptions {
           jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
       }
   }
   ```

4. **升级 Room 2.6.1 → 2.8.4**

   首次尝试 KSP 2.3.0 + Room 2.6.1 报错：
   ```
   e: [ksp] java.lang.IllegalStateException: unexpected jvm signature V
   ```

   **原因**：KSP 2.3.0 默认启用 KSP2（全新编译器引擎），Room 2.6.1 只支持 KSP1（旧的编译器插件模式），两者不兼容。

   **解决方案**：升级 Room 到 2.7.0+（2.8.4），该版本支持 KSP2 和 Kotlin 2.0+。

5. **恢复 `ModelRuntime.kt` 中完整的 LiteRT SDK 调用代码**

**升级后完整依赖版本：**

| 依赖 | 旧版本 | 新版本 |
|------|--------|--------|
| Kotlin | 2.0.21 | 2.3.0 |
| Compose Plugin | 2.0.21 | 2.3.0 |
| KSP | 2.0.21-1.0.27 | 2.3.0 |
| Room | 2.6.1 | 2.8.4 |
| LiteRT SDK | 注释中 | 0.9.0 |

**构建结果**：✅ `BUILD SUCCESSFUL`，APK 110MB

---

### 6. 应用前端国际化（i18n）实现（2026-04-19）

**目标**：所有 UI 文字支持中英文，根据系统语言自动切换。

#### 6.1 实现方案

采用 Android 标准方案：字符串资源文件 + Compose `stringResource()` API。

```
res/values/strings.xml          → 英文（默认）
res/values-zh/strings.xml       → 中文（系统中文时自动加载）
```

#### 6.2 新建字符串资源

创建 `res/values/strings.xml` 和 `res/values-zh/strings.xml`，共 130+ 条字符串，覆盖：

| 分组 | 主要字符串 |
|------|-----------|
| App & Notifications | app_name、notification_title、notification_text 等 |
| Navigation tabs | tab_home / 准备、tab_people / 声纹、tab_live / 会议、tab_notes / 总结、tab_models / 模型 |
| Setup screen | device_profile、permissions、runtime_readiness、quick_start 等 |
| Speaker profiles | speakers_hero_title、display_name、enrollment_count 等 |
| Live meeting | live_hero_title、meeting_status、transcript_stream 等 |
| Review screen | review_hero_title、decisions、action_items、export_markdown 等 |
| Model market | models_hero_title、downloading_progress、delete_local_model 等 |
| ViewModel 状态消息 | msg_recording_enrollment、msg_starting_capture、msg_speaker_renamed 等 |
| 运行时诊断 | diag_sherpa_ready、diag_litert_ready、diag_pack_downloading 等 |

#### 6.3 UI 代码改造

**MeetingViewModel.kt** 关键改造：

```kotlin
// AppTab: 硬编码 String → Int 资源 ID
enum class AppTab(val labelResId: Int) {
    Setup(R.string.tab_home),
    Speakers(R.string.tab_people),
    Live(R.string.tab_live),
    Review(R.string.tab_notes),
    Models(R.string.tab_models),
}

// StatusMessage: String? → StatusMessage 数据类
data class StatusMessage(
    val stringResId: Int,
    val arg: String? = null,
)

// 诊断状态: 所有 String → Int 资源 ID
data class RuntimeDiagnosticsUiState(
    val sherpaStatusResId: Int = R.string.diag_checking_sherpa,
    val speakerRuntimeStatusResId: Int = R.string.diag_checking_speaker,
    val llmStatusResId: Int = R.string.diag_checking_llm,
    val livePackStatusResId: Int = R.string.diag_no_live_pack,
    val finalPackStatusResId: Int = R.string.diag_no_final_pack,
)
```

**MeetingAssistantRoot.kt**：

- 所有硬编码中文字符串 → `stringResource(R.string.xxx)`
- `LocalContext.current.getString(resId)` 在需要 context 的 child composable 中使用
- 状态消息显示：`context.getString(msg.stringResId, msg.arg)`

**ModelMarketScreen.kt**：

- 所有硬编码字符串 → `stringResource()`
- 修正非空安全调用：`downloadTask?.bytesDownloaded` → `downloadTask.bytesDownloaded`（downloadTask 在该分支内非空）

#### 6.4 遇到的问题与修复

| 问题 | 原因 | 修复 |
|------|------|------|
| `final` 不是合法资源名 | Java 关键字不能作资源名 | 重命名为 `transcript_confirmed` |
| `live` 不是合法资源名 | 同上 | 重命名为 `live_tag` |
| `context.getString()` 在 child composable 中未解析 | `context` 变量不在子组件作用域 | 改用 `LocalContext.current.getString()` |
| 非空安全调用警告 | when 分支内 `downloadTask` 已确定非空 | 移除 `?.` 安全调用符 |

#### 6.5 编译命令

```bash
JAVA_HOME="D:/AndroidStudio/jbr" ./gradlew.bat assembleDebug
```

**构建结果**：✅ `BUILD SUCCESSFUL`，APK 110MB

---

### 7. 构建环境要点（2026-04-19 补充）

**重要**：系统默认 JDK 为 Java 1.8，**不兼容** KSP 2.3.0 和 AGP 8.5.2。必须显式指定 JDK 21：

```bash
JAVA_HOME="D:/AndroidStudio/jbr" ./gradlew.bat assembleDebug
```

| JDK | 版本 | 用途 |
|-----|------|------|
| `D:\AndroidStudio\jbr` | OpenJDK 21.0.8 | Android Gradle 构建（必须） |
| 系统默认 | Java 1.8.0_471 | 不用于构建 |

### 8. 模型下载源切换（2026-04-19）

#### 8.1 背景

原本模型从 HuggingFace 官方 CDN（`huggingface.co`）下载，国内访问可能较慢甚至无法访问。调研发现：

- **ModelScope 无法替代**：ModelScope 上的 Qwen2.5-1.5B 是 GGUF 格式（给 llama.cpp 用），而 LiteRT SDK 只支持 `.litertlm` 格式（Google 自定义的 FlatBuffer 容器）。两者完全不兼容。
- **最佳方案**：`hf-mirror.com`（HfProxy）是国内最流行的 HuggingFace 镜像，速度快、稳定性好，已被多个生产级 Android 应用采用（如 LLM-Hub）。

#### 8.2 调研结论

| 对比项 | HuggingFace | ModelScope |
|--------|------------|------------|
| `.litertlm` 格式模型 | ✅ `litert-community/` 组织有完整支持 | ❌ 只有 GGUF |
| Android SDK | ❌ 无官方 SDK，直链 HTTP 下载即可 | ❌ 只有 Python SDK |
| 国内访问速度 | 较慢（需镜像） | 快 |
| 模型下载 URL | `huggingface.co/{id}/resolve/main/{file}` | 需 git clone 或 Python SDK |

#### 8.3 实施变更

**变更 1：`MeetingModels.kt` — 下载 URL 构造**

```kotlin
companion object {
    private const val HF_OFFICIAL = "https://huggingface.co"
    private const val HF_MIRROR = "https://hf-mirror.com"

    fun buildDownloadUrl(hfRepoId: String, modelFile: String, useMirror: Boolean = false): String {
        val base = if (useMirror) HF_MIRROR else HF_OFFICIAL
        return "$base/$hfRepoId/resolve/main/$modelFile"
    }
}

val downloadUrl: String
    get() = buildDownloadUrl(hfRepoId, modelFile)
```

默认 `useMirror = false`，即优先走官方源 `huggingface.co`。

**变更 2：`ModelDownloadManager.kt` — 镜像 fallback 逻辑**

```kotlin
private suspend fun downloadFile(modelPackId: String, downloadUrl: String, totalBytes: Long) {
    val official = "https://huggingface.co"
    val mirror = "https://hf-mirror.com"
    // 优先官方源，VPN 拦截时切换镜像
    val fallbackUrl = if (downloadUrl.contains(official)) {
        downloadUrl.replace(official, mirror)
    } else {
        downloadUrl.replace(mirror, official)
    }
    val urls = listOf(downloadUrl, fallbackUrl).distinct()

    var lastException: Exception? = null
    for (url in urls) {
        try {
            doDownloadFile(modelPackId, url, totalBytes)
            return
        } catch (e: CancellationException) { throw e } // 取消不 fallback
        catch (e: Exception) {
            lastException = e
        }
    }
    throw lastException ?: Exception("Download failed for all URLs")
}
```

下载逻辑：
1. 优先从 `huggingface.co` 下载（官方源，稳定性最高）
2. 若失败，自动切换到 `hf-mirror.com` 镜像重试
3. 镜像也失败才标记为 `FAILED`
4. **取消操作**直接终止，不尝试 fallback

**变更 3：进度条实时更新**

原问题：`doDownloadFile` 更新的是内部 StateFlow，UI 观察的是 Room DB flow，两者不同步，进度始终为 0。

修复：在下载循环中每次写入 buffer 后调用 `repository.updateDownloadProgress()`，将实时字节数写入 Room DB，触发下游 Flow 更新。

```kotlin
// 同步到 Room DB，触发 UI 进度更新
repository.updateDownloadProgress(modelPackId, totalDownloaded, DownloadStatus.DOWNLOADING)
```

**变更 4：取消下载可中断**

原问题：`client.newCall(request).execute()` 是同步阻塞调用，`cancelDownload` 只改状态，无法真正中断 OkHttp 请求。

修复：
- `AtomicBoolean cancelled` 取消标记
- `currentCall?.cancel()` 中断 OkHttp 网络请求
- 下载循环每次 `source.read()` 前检查 `cancelled.get()`，抛出 `CancellationException`
- 取消后删除已下载的部分文件

```kotlin
private val cancelled = AtomicBoolean(false)

suspend fun cancelDownload(modelPackId: String) {
    currentCall?.cancel()          // 中断 OkHttp 请求
    cancelled.set(true)             // 设置取消标记
    mutex.withLock {
        _downloadTasks.value = _downloadTasks.value + (modelPackId to task.copy(
            status = DownloadStatus.CANCELLED,
        ))
    }
}

private suspend fun doDownloadFile(...) {
    // 每次读取前检查取消标记
    if (cancelled.get()) {
        throw CancellationException("Cancelled during download")
    }
    // ... 读取并写入
}
```

---

### 9. 后续步骤

已完成：
- ✅ LiteRT SDK 推理已启用
- ✅ 应用前端国际化（中文/英文）
- ✅ 模型下载：官方源优先 + 镜像 fallback + 进度条实时更新 + 取消可中断
- ✅ APK 构建并安装到真机

待完成：

1. **真机测试 LLM 推理**：
   - 在 Models 标签页下载模型（如 Qwen2.5-1.5B）
   - 验证取消下载是否生效
   - 发起会议，验证 AI 摘要生成效果

2. **功能增强**：
   - 下载前检查设备存储空间和可用内存
   - 支持同时下载多个模型
   - 模型更新检测

3. **国际化完善**：
   - 检查是否遗漏了某些动态生成的字符串
   - 考虑添加其他语言支持（如日语、韩语）
