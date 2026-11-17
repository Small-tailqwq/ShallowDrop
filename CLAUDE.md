# ShallowDrop

基于 DeepSeek 官方 `chat-demo`（MIT）的分支。仓库包含两部分：

- **Web 版**（`src/`，React 18 + rspack + zustand + IndexedDB）：上游原样代码，作为行为规格的权威来源。
- **Android 原生版**（`android/`，Kotlin + Jetpack Compose）：目标是用户填入 DeepSeek API Key、导入 DeepSeek 官方导出的聊天记录（`deepseek_data-*.zip`）后，像用官方 App 一样继续聊天。计划见 [docs/android/PLAN.md](docs/android/PLAN.md)。

## 许可与参考边界

- 本仓库沿用上游 MIT，`LICENSE` 不改；Android 版同样 MIT，APK/源码分发需带 `LICENSE`。
- 官方 DeepSeek App 的逆向分析资料（`C:\Users\Ko_teiru\Documents\code\reverse\projects\deepseek-android-app`）**只能参考** UI 结构、交互和架构思路。禁止复制其反编译代码、资源文件（图标、动画、字体、字符串表、配色 XML）或混淆类结构进本仓库。界面元素自行用 Compose / Material 3 实现。
- 不实现登录、通话、账号、风控等官方服务端私有功能；只对接公开的 DeepSeek API（`https://api.deepseek.com`）。

## Web 版（`src/`）

命令：`pnpm dev` / `pnpm build` / `pnpm type` / `pnpm lint` / `pnpm format`（Node 22.12+、pnpm）。

关键位置——Android 移植时以这些文件为准：

| 主题 | 文件 |
|---|---|
| API 协议类型（Anthropic 兼容 Messages / Files） | `src/services/api/types.ts` |
| 请求、SSE、事件解析、错误 | `src/services/api/client.ts`、`sse.ts`、`parseEvent.ts`、`errors.ts` |
| 本地消息模型（消息树、回复块、状态） | `src/services/chat/types.ts` |
| 分支树、当前路径 | `src/services/chat/branches.ts`、`view.ts` |
| 请求上下文构建、续写规则 | `src/services/chat/reply.ts`、`latestReminder.ts` |
| 会话状态与生成流程 | `src/services/chat/store.ts` |
| 历史导入导出（格式 v3） | `src/services/chat/history/`，说明见其 `README.md` |
| 附件扩展名与 purpose | `src/services/uploadExts.ts`、`attachments.ts` |
| 设置项 | `src/services/settings.ts` |
| 文案（中/英） | `src/i18n/zh.ts`、`en.ts` |

约定：代码注释与文档用简体中文；oxfmt 格式化（单引号、无分号风格见 `.oxfmtrc.json`）。

## Android 版（`android/`）

- 工具链：JDK 21、Android SDK（`C:\Users\Ko_teiru\AppData\Local\Android\Sdk`，compileSdk 37）、Gradle Wrapper 9.5、AGP 9.3.1、Kotlin 2.4、KSP 2.3.14。
- 本机 Gradle 需走代理：命令追加 `-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897`（不要写进仓库的 gradle.properties）。`local.properties` 不入库。
- 构建：在 `android/` 下运行 `./gradlew :app:assembleDebug`；单元测试 `./gradlew :app:testDebugUnitTest`；静态检查 `./gradlew :app:lintDebug`。
- 单 Activity + Compose + Navigation（类型安全路由），MVVM（ViewModel + StateFlow），手动依赖注入（`AppContainer`），不引入 Hilt。
- 网络 OkHttp + kotlinx.serialization；存储 Room（会话/消息/附件）+ DataStore（设置）；API Key 经 Android Keystore 加密后存储。
- 包结构与模块边界见 PLAN.md §3。`core/model` 中的领域模型是各模块的共享契约，改动需同步所有使用方。
- 行为与 Web 版保持一致：协议细节、消息树规则、导入校验规则直接对照上面表格中的 TS 文件移植，并移植相应注释中的约束。
- UI 遵循全局偏好：不加多余副标题、说明文字和 slogan，让布局说明用途。
- 同一行里的文字、图标、标签和开关要在视觉上水平对齐。中文字形的视觉中心比行框中心低约 0.1em，只按行框居中会差一两个像素：单行文字加 `Modifier.opticalCenter(style)`，与可能换行的文字首行对齐的图标放进 `FirstLineAligned(style)`（均在 `ui/components/OpticalAlign.kt`），同一行不同字号的文字用 `alignByBaseline()`。改动界面后在模拟器截图上量各元素的垂直中心，偏差不超过 1 像素。
- 跨对话记忆（实验性，默认关闭）：逻辑在 `domain/memory`，说明见 [docs/android/memory.md](docs/android/memory.md)。发给模型的信息分记忆、当前情况（每条提问开头的时间、时区、位置，`domain/chat/CurrentState.kt`）和对话三层，提示词只说明各层的性质，不为单个场景加规则。改动 `MemoryPrompts.kt` 或当前情况的格式后要重跑设备实测 `MemoryReliabilityTest` 和 `MemoryScenarioTest`。
