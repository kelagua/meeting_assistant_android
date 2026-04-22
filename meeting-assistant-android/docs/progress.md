# Meeting Assistant Android 开发进展

## 进展快照

- 记录日期：2026-04-19
- 当前分支：`codex/meeting-assistant-android-mvp`
- 最新提交：`9397d2b` `Check audio permission before capture`
- CI 状态：成功
- Kotlin 版本：2.3.0
- LiteRT SDK：0.9.0（已集成）
- i18n：中文/英文已完成

## 当前总体状态

项目已完成 MVP 工程闭环并进入真实 AI 模型集成阶段：

- Android 工程骨架与基础模块划分
- 本地数据层、会话编排、导出能力
- 会议主流程 UI（支持中英文）
- **LiteRT SDK 已集成**，真实端侧 LLM 摘要生成就绪
- **模型市场功能已实现**，支持下载/管理 LiteRT 模型
- 可用的 GitHub Actions CI

当前阶段状态：

“LiteRT 推理链路和模型市场已就绪，接下来重点是模型下载后的真实 LLM 摘要生成验证，以及 sherpa ASR 替换。”

## 已完成内容

### 1. 工程与架构

- 创建了 Android 项目基础结构
- 建立了 `data / runtime / service / ui` 分层
- 增加了 Gradle Wrapper 与 CI workflow
- 完成了 Compose 基础页面与 ViewModel 状态流
- Kotlin 升级到 2.3.0（含 `compilerOptions` DSL 迁移）
- Room 升级到 2.8.4（支持 KSP2）

### 2. 数据与业务闭环

- 完成 `Room + DataStore` 数据层
- 完成会议、音频、转写、说话人、摘要等模型与仓储接口
- 完成会议开始、进行中、结束后的状态流转
- 完成 Markdown / TXT / JSON 导出能力

### 3. 运行时链路

- 完成 `AudioRecord` 音频采集
- 完成实时音频帧流与 ring buffer 指标
- 完成 transcript 事件入库与 chunk summary 触发流程
- 完成最终 summary 生成与结果保存流程
- 完成基于负载指标的降级逻辑
- **LiteRT SDK 集成**：完整 `Engine` / `EngineConfig` / `Conversation` 推理链路

### 4. 模型市场（Model Market）

- 模型白名单（`assets/gallery/allowlist.json`）
- 模型卡片 UI（ModelMarketScreen）
- 下载管理（ModelDownloadManager + ModelDownloadService）
- 前台下载服务 + 通知栏进度
- 模型存储到 `filesDir/models/`

### 5. UI 与交互

- 完成 `Setup / Speakers / Live / Review / Models` 五个主页面
- 支持录音权限申请
- 支持 speaker label 重命名与身份锁定
- 支持回顾纪要与导出操作
- **完整 i18n 支持**：130+ 字符串中英文，系统语言自动切换

## 最近一轮关键修复 / 开发

- `753246d` 启用了手动触发和分支触发的 GitHub Actions
- `e78b0aa` 将工作流移动到仓库根目录，修复 Actions 识别问题
- `02a828d` 增加 Kotlin Compose compiler plugin，修复 Compose 编译配置
- `3d57129` 修复主题资源与 Android 配置问题
- `b0c2086` 修复 `Flow.combine` 类型推断错误
- `04af599` 避免 vararg `combine` 和 `Modifier.weight` 引发的问题
- `9397d2b` 修复 `AudioRecord` 权限检查与 lint `MissingPermission` 问题
- **Kotlin 2.3.0 升级**：compilerOptions DSL + Room 2.8.4 + KSP 2.3.0
- **LiteRT SDK 集成**：`com.google.ai.edge.litertlm:litertlm-android:0.9.0`
- **i18n 实现**：130+ 字符串资源 + Compose `stringResource()` 全量改造

## 当前确认可用的结果

- 最新分支代码已经推送到远端
- 最新 CI 已通过
- `lintDebug`、`testDebugUnitTest`、`assembleDebug` 这条基础校验链路已恢复为绿色
- 当前权限问题已经通过”显式权限检查 + 运行时异常兜底”的方式闭环
- APK 构建成功（约 110MB），已安装到真机（192.168.0.219）

## 仍未完成的核心事项

以下部分仍然是后续开发重点：

- 将 `DemoSpeechRecognizer` 替换为真实 sherpa ASR 实现
- 将 `DemoSpeakerIdentificationEngine` 替换为真实说话人识别实现
- **真机验证 LLM 推理**：下载模型后发起会议，验证 AI 摘要生成效果
- 为 Repository、ViewModel、会话编排补充更多自动化测试
- 下载前检查设备存储空间和可用内存
- 支持同时下载多个模型

## 下一步建议

1. 真机测试：下载 Qwen2.5-1.5B 模型，发起会议验证 LLM 摘要生成
2. 接入真实 sherpa ASR，拿到可用的实时转写结果
3. 接入真实 speaker embedding / verification
4. 在真实设备上跑一轮完整会议录制与导出验收
