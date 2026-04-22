# 开发环境配置

## 概览

本项目为 Android 12+ 应用，开发环境基于 Windows 11，使用 Android Studio 自带 JDK，所有软件均安装在 D 盘。

---

## 硬件信息

| 项目 | 值 |
|------|-----|
| 设备 | Realme RMX5010（真机，IP 192.168.0.219） |
| Android 版本 | 待确认（minSdk 31 = Android 12+） |
| ADB 连接 | 无线调试，已配对 |

---

## 工具链

### JDK

| 项目 | 值 |
|------|-----|
| 路径 | `D:\AndroidStudio\jbr` |
| 版本 | OpenJDK 21.0.8 (2025-07-15) |
| 用途 | Android Gradle 构建（必须） |
| 系统默认 JDK | Java 1.8.0_471（**不用于构建**） |

**重要**：系统默认 Java 为 1.8，不兼容 AGP 8.5.2（需要 Java 11+）。所有 Gradle 构建必须通过设置 `JAVA_HOME=D:/AndroidStudio/jbr` 显式指定 JDK 21。

### Android SDK

| 项目 | 值 |
|------|-----|
| 路径 | `C:\Users\FNULNU\AppData\Local\Android\Sdk` |
| compileSdk | 35 |
| targetSdk | 35 |
| minSdk | 31 |
| 构建工具 | 默认 |

### Gradle

| 项目 | 值 |
|------|-----|
| 版本 | 8.7 |
| 内置 Kotlin | 1.9.22（仅 Gradle 自身使用） |
| 项目 Kotlin | 2.3.0（独立管理） |

---

## 项目依赖版本

### 核心语言和插件

| 依赖 | 版本 | 说明 |
|------|------|------|
| Kotlin | 2.3.0 | Android 项目语言 |
| Kotlin Compose Plugin | 2.3.0 | Jetpack Compose 编译器 |
| KSP | 2.3.0 | 注解处理器（KSP2 引擎） |
| Kotlin Serialization | 2.3.0 | Kotlinx Serialization |
| Android Gradle Plugin | 8.5.2 | 构建系统 |
| Gradle | 8.7 | Gradle Wrapper |

### Android 库

| 依赖 | 版本 | 说明 |
|------|------|------|
| Room | 2.8.4 | 数据库（支持 KSP2） |
| Compose BOM | 2024.10.01 | Compose UI |
| Navigation Compose | 2.8.5 | 导航 |
| Lifecycle | 2.8.7 | 生命周期 |
| DataStore | 1.1.1 | 本地偏好 |
| Coroutines | 1.9.0 | 协程 |
| OkHttp | 4.12.0 | HTTP 下载 |

### AI / LLM

| 依赖 | 版本 | 说明 |
|------|------|------|
| LiteRT-LM Android SDK | 0.9.0 | 端侧 LLM 推理 |

---

## 编译命令

### Debug 构建（带 JDK 指定）

```bash
cd D:/codex/meeting-assistant-android
JAVA_HOME="D:/AndroidStudio/jbr" ./gradlew.bat assembleDebug
```

输出：`app/build/outputs/apk/debug/app-debug.apk`（~110MB）

### 清理构建

```bash
JAVA_HOME="D:/AndroidStudio/jbr" ./gradlew.bat clean assembleDebug
```

### Kotlin 编译检查

```bash
JAVA_HOME="D:/AndroidStudio/jbr" ./gradlew.bat compileDebugKotlin
```

---

## ADB 真机调试

### 已配对设备

```
IP: 192.168.0.219
端口: 33369（每次无线调试重新开启后会变化，以设备上显示为准）
```

### 常用 ADB 命令

```bash
# 查看已连接设备
adb devices -l

# 重新连接（如设备重新开启无线调试）
adb pair 192.168.0.219:45085 837366
adb connect 192.168.0.219:45085

# 安装/更新 APK
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 卸载应用
adb uninstall com.codex.meetingassistant

# 查看日志
adb logcat | grep com.codex.meetingassistant

# 推送文件到设备
adb push local/path /sdcard/destination
```

### 配对新设备

1. 设备：设置 → 开发者选项 → 无线调试 → 开启
2. 电脑：运行 `adb pair <设备IP:端口> <配对码>`
3. 连接：`adb connect <设备IP:端口>`
4. 安装：`adb install -r app/build/outputs/apk/debug/app-debug.apk`

---

## 踩过的坑

### 1. Java 版本不匹配

**问题**：系统默认 Java 1.8，AGP 8.5.2 需要 Java 11+，构建报错：
```
No matching variant of com.android.tools.build:gradle:8.5.2 was found
```

**解决**：构建时指定 `JAVA_HOME="D:/AndroidStudio/jbr"`

### 2. Kotlin 2.3.0 废弃旧 DSL

**问题**：升级 Kotlin 到 2.3.0 后报错：
```
Using 'jvmTarget: String' is an error. Please migrate to the compilerOptions DSL.
```

**解决**：迁移 `kotlinOptions` → `compilerOptions`：
```kotlin
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}
```

### 3. KSP 2.3.0 + Room 2.6.1 不兼容

**问题**：KSP 2.3.0 默认启用 KSP2 引擎，Room 2.6.1 只支持 KSP1，报错：
```
java.lang.IllegalStateException: unexpected jvm signature V
```

**解决**：升级 Room 到 2.8.4

### 4. LiteRT SDK Kotlin 版本要求

**问题**：LiteRT SDK (`com.google.ai.edge.litertlm:litertlm-android`) 依赖 Kotlin 2.3+ 运行时，Kotlin 2.0.21 不兼容

**解决**：升级 Kotlin 到 2.3.0+（连带升级 Room 和 KSP）

### 5. Java 关键字作为 Android 资源名

**问题**：Android 资源名不能使用 Java 关键字，报错：
```
final is not a valid resource name (reserved Java keyword)
```

**解决**：避免使用 `final`、`class`、`void` 等关键字作为字符串名：
- `final` → `transcript_confirmed`
- `live` → `live_tag`

### 6. `context.getString()` 在 child composable 中未解析

**问题**：`MeetingAssistantRoot` 中 `context` 变量在子 composable 作用域不可达，报错：
```
Function invocation 'context(...)' expected
```

**解决**：在 child composable 中使用 `LocalContext.current.getString(resId)`，而非捕获的 `context` 变量。

### 7. 非空安全调用在确定非空的分支内

**问题**：在 `when (status)` 分支内，`downloadTask` 已被 Kotlin 智能类型转换确定非空，但代码中仍有 `?.` 安全调用，IDE 报 warning。

**解决**：移除 `?.` 安全调用符，直接访问属性。

### 8. compose-resources 依赖不存在

**问题**：`implementation("androidx.compose.resources:resources:...")` 各版本均找不到。

**解决**：不依赖该库，使用标准 Android `LocalContext.current.getString()` API。

---

## 文件路径速查

| 内容 | 路径 |
|------|------|
| 项目根目录 | `D:\codex\meeting-assistant-android` |
| Gradle 构建 | `D:\codex\meeting-assistant-android\build.gradle.kts` |
| App 模块构建 | `D:\codex\meeting-assistant-android\app\build.gradle.kts` |
| 英文字符串 | `app/src/main/res/values/strings.xml` |
| 中文字符串 | `app/src/main/res/values-zh/strings.xml` |
| 入口 Activity | `app/src/main/java/.../MainActivity.kt` |
| 数据库实体 | `app/src/main/java/.../data/local/Entities.kt` |
| 模型市场 | `app/src/main/java/.../runtime/llm/` |
| AI 推理 | `app/src/main/java/.../runtime/llm/ModelRuntime.kt` |
| 模型白名单 | `app/src/main/assets/gallery/allowlist.json` |
| APK 输出 | `app/build/outputs/apk/debug/app-debug.apk` |
| 开发日志 | `D:\codex\meeting-assistant-android\docs\DEVELOPMENT_LOG.md` |
| 环境文档 | `D:\codex\meeting-assistant-android\docs\ENVIRONMENT.md` |
| Android SDK | `C:\Users\FNULNU\AppData\Local\Android\Sdk` |
| JDK（构建用） | `D:\AndroidStudio\jbr` |
