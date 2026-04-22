# Meeting Assistant Android 开发计划

## 项目目标

面向 Android 12+ 设备，交付一个本地优先、离线可用的会议助手 MVP，覆盖以下核心能力：

- 实时录音与会议会话管理
- 实时字幕与会后最终转写
- 说话人识别与人工修正
- 会中分段摘要与会后结构化纪要
- 本地数据存储、基础加密与多格式导出

## MVP 范围

当前版本聚焦“先跑通完整闭环，再逐步替换真实模型能力”，MVP 交付范围包括：

- 会议创建、开始、停止、回顾、导出
- `AudioRecord` 音频采集与前台服务会话编排
- `Room + DataStore` 本地数据层
- 实时 transcript 流与 final transcript 落库
- 说话人标签映射、重命名、锁定身份
- 分段摘要与最终会议纪要的数据结构与展示
- Markdown / TXT / JSON 导出
- GitHub Actions 基础 CI

## 分阶段计划

### 阶段 1：基础工程与数据闭环
状态：已完成

- 完成 Android 工程骨架、Gradle Wrapper、基础依赖配置
- 建立 Compose UI 结构与 `MeetingViewModel`
- 建立 `Room` 实体、DAO、Repository、导出协议
- 建立 `MeetingSessionCoordinator` 作为录音、转写、摘要的会话编排中心
- 建立基础 CI，确保 `lintDebug`、`testDebugUnitTest`、`assembleDebug` 可自动执行

### 阶段 2：MVP 运行时闭环
状态：已完成

- 接通 `AudioRecord` 音频采集链路
- 实现 ASR、说话人识别、摘要运行时接口与 demo/fallback 实现
- 打通 `Setup / Speakers / Live / Review` 四个主要页面
- 完成会中转写、分段摘要、会后总结、导出等端到端流程
- 修复 Kotlin Flow combine、Compose 配置、主题资源、权限 lint 等工程问题

### 阶段 3：真实模型接入
状态：下一优先级

- 接入真实 Android 端 ASR 方案，优先考虑 `sherpa-onnx`
- 接入说话人 embedding / verification，替换 demo 映射逻辑
- 接入 LiteRT 或等价端侧推理运行时，替换启发式摘要 fallback
- 将模型包选择、设备能力分级与真实运行时打通

### 阶段 4：质量与体验增强
状态：进行中

- 增加单元测试，覆盖 Repository、ViewModel、摘要拼装等关键路径
- 增强录音权限、错误提示、降级策略与异常恢复
- 优化实时字幕刷新、长会话稳定性与资源占用
- 完善 speaker enrollment 体验与会后校正体验

### 阶段 5：发布准备
状态：待开始

- 完善 README、架构说明、模型接入说明
- 增加内部演示样例与验收脚本
- 评估 APK 体积、冷启动、长录音稳定性
- 准备 Beta 包、版本号策略与发布清单

## 当前优先级

截至 2026-04-17，建议按以下顺序推进：

1. 接入真实 ASR 引擎，替换 `DemoSpeechRecognizer`
2. 接入真实说话人识别能力，替换 `DemoSpeakerIdentificationEngine`
3. 接入真实摘要模型运行时，替换 `LiteRtModelRuntime` 的 fallback 路径
4. 为核心数据流补齐单元测试与稳定性验证
5. 整理演示文档与可复现的本地运行说明

## 阶段验收标准

### MVP 可验收标准

- 可以在 Android 12+ 设备上创建并完成一场会议
- 会议过程中可看到实时字幕与说话人标签
- 会议结束后可生成结构化纪要并导出
- 主要数据存储在本地，核心流程不依赖联网
- CI 持续保持绿色

### 下一阶段验收标准

- demo 组件替换为真实模型能力后，接口层和 UI 层不需要大改
- 真实模型路径可在中低端设备上稳定运行
- 具备最小可演示版本，适合继续做性能和精度迭代
