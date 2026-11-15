# ShallowDrop Android 计划

## 1. 目标

原生 Android App：填入 DeepSeek API Key，导入官方导出的 `deepseek_data-*.zip`，即可继续之前的对话，体验接近官方 App。

必须：

- API Key 录入、校验（`GET /models`）、加密保存
- 导入官方导出（格式 v3，含 `user.json` 跳过）；导出为同格式 ZIP
- 会话列表（按时间分组）、新建、重命名、删除、全部删除
- 流式回复，深度思考、智能搜索开关；停止、重新生成、编辑提问、分支切换、续写
- 思考过程折叠、搜索 / 阅读链接步骤、来源卡片与正文引用角标
- Markdown（GFM 表格、代码高亮与复制、KaTeX 公式）
- 图片与文件附件（Files API 上传）
- 中英文、浅色 / 深色 / 跟随系统

后续（P2）：语音输入（ASR）、朗读（TTS）、Mermaid、位置设置、分享。

不做：登录、通话、账号、云同步等官方私有服务端功能。

## 2. 技术栈

| 项 | 选择 |
|---|---|
| 语言 / UI | Kotlin 2.4、Jetpack Compose（BOM 2026.09.00）、Material 3 |
| 构建 | Gradle 9.5、AGP 9.3.1（内置 Kotlin）、KSP 2.3.14 |
| SDK | minSdk 26、targetSdk 36、compileSdk 37 |
| 架构 | 单 Activity、Navigation Compose 类型安全路由、ViewModel + StateFlow、手动 DI（`AppContainer`） |
| 网络 | OkHttp 5 + 自写 SSE 解析、kotlinx.serialization |
| 存储 | Room（会话、消息、附件元数据）、`filesDir/attachments/` 存原件、DataStore（设置）、Keystore AES-GCM 加密 API Key |
| Markdown | commonmark-java（GFM 表格、删除线、自动链接）→ 自写 Compose 渲染；公式离线 KaTeX（WebView）或纯原生方案，择一由 W3 决定 |
| 测试 | JUnit4 + kotlinx-coroutines-test + MockWebServer；Room 用 Robolectric 或 instrumented |

包名 `io.github.smalltailqwq.shallowdrop`（暂定），应用名 ShallowDrop。

## 3. 包结构与工作流划分

```text
android/app/src/main/java/io/github/smalltailqwq/shallowdrop/
  App.kt, MainActivity.kt, AppContainer.kt        骨架（W0）
  core/model/        领域模型：Session、Message、ReplyBlock、FileAttachment 等（W0，共享契约）
  core/contract/     跨层接口：ChatApi、SessionStore、HistoryBackup、SettingsRepository、AttachmentStore（W0）
  data/api/          W1：OkHttp 客户端、SSE、事件解析、错误、请求 DTO、Files API
  data/db/           W2：Room 实体 / DAO、SessionStore 实现、AttachmentStore 实现
  data/history/      W2：ZIP 读写、v3 schema 校验、toLocal / toArchive、导入事务
  data/settings/     W4：DataStore + Keystore 实现 SettingsRepository
  domain/chat/       W5：分支树、当前路径、上下文构建、生成流程（ChatEngine）、标题
  ui/markdown/       W3：解析与 Compose 渲染、代码块、公式、引用角标
  ui/theme/          W4：配色、字体、深浅色
  ui/…               W4：导航、侧栏会话列表、对话页、输入栏、消息组件、设置页、引导页
```

### W0 骨架（主会话完成后再分发）

Gradle 工程、版本目录（预先登记所有预计依赖）、`core/model`、`core/contract`、`AppContainer` 占位实现、空白主题与首页，确保 `assembleDebug` 通过。

### W1 API 层 — 对照 `src/services/api/*`

- `DeepSeekApi : ChatApi`：`verifyApiKey`、`streamMessage`（`Flow<MessageStreamEvent>`）、`uploadFile`、`deleteFile`
- SSE 解析：event / data 帧、注释保活、无名 error 帧；`message_stop` 前断流报 `interrupted`
- 错误映射 `ApiError(kind, message, status)`，HTTP 错误体解析；对照 `errors.ts` 给出可本地化的错误码
- 请求体 DTO 序列化与 TS 类型字段名逐一对应（`latest_reminder` 角色、`safety_check`、`title`）
- 单测：MockWebServer 回放 SSE 样例（分块、半行、保活、错误帧）

### W2 存储与历史导入导出 — 对照 `src/services/chat/db.ts`、`history/*`

- Room：`sessions`、`messages`（blocks 以 JSON 列存）、`files`；附件原件落盘
- `SessionStore` 实现：列表 Flow、按会话加载、增量写消息、删除、全部删除
- 导入：SAF Uri → 流式读取 ZIP（STORE / DEFLATE），首条目 manifest 版本校验，逐会话转换写入暂存表，校验通过后单事务替换；失败保留原数据
- 导出：生成同格式 ZIP 写入用户选择的 Uri
- 移植 `schema.ts` 校验与 `toLocal.ts` / `toArchive.ts` 映射规则（状态映射、片段丢弃、prefix=false 等）
- 单测：用合成归档覆盖消息树、分支、图片、坏数据

### W3 Markdown — 对照 `src/components/markdown/*`、`services/markdown/*`

- 解析：commonmark + GFM；LLM 风格公式 `\(...\)`、`\[...\]`、`$$...$$`
- Compose 渲染：标题、列表、引用、表格（横向滚动）、代码块（语言标签、高亮、复制）、链接、行内代码、公式
- 流式渲染节流，长文性能（增量解析或分块缓存）
- 引用角标：接收 `TextCitation` 列表，渲染可点击角标
- 公开 API：`@Composable fun MarkdownText(markdown: String, citations: List<TextCitation>, modifier)`

### W4 UI — 对照 `src/components/*`，交互参考官方 App 结构（只参考，不复制资源）

- 导航：引导（填 Key）→ 对话页；设置页嵌套（通用、数据、关于）
- 侧栏抽屉：会话分组（今天 / 7 天内 / 30 天内 / 按月），长按重命名 / 删除
- 对话页：顶栏（菜单、标题、新对话）、消息列表、输入栏（附件、深度思考、智能搜索、发送 / 停止）
- 消息组件：用户气泡（编辑、复制）、助手回复（思考折叠与用时、搜索步骤、来源面板、操作栏：复制 / 重新生成 / 分支切换 `< 2/3 >`）、错误态
- 设置：API Key、语言、主题、导入 / 导出 / 全部删除、关于（MIT 许可）
- `SettingsRepository` 实现（DataStore + Keystore）
- 中英文 `strings.xml`（文案对照 `src/i18n/zh.ts`、`en.ts`）
- 开发期使用 `FakeChatRepository` 演示数据，不依赖 W1/W2/W5

### W5 对话领域 — 对照 `branches.ts`、`view.ts`、`reply.ts`、`latestReminder.ts`、`store.ts`

- 消息树：当前路径、分支切换、编辑生成兄弟节点、重新生成
- 上下文构建：哪些回复进入上下文、`read_link` 签名回传、续写条件、附件补传
- `ChatEngine`：发送 / 停止 / 重试 / 续写，流事件累积为 `ReplyBlock`，节流写库，标题生成（`title.enabled`）
- 进程被杀时进行中的回复标记 `stopped`
- 实现 `ChatRepository` 供 UI 使用
- 单测：纯 Kotlin 测试分支与上下文规则

## 4. 集成顺序

1. W0 完成并提交 → 并行分发 W1–W5（各自独立 worktree，只改自己的包；新增依赖只追加到版本目录对应段落）
2. 合并：W1 + W2 + W5 接线到 `AppContainer`，UI 从 Fake 切到真实实现
3. 端到端：导入真实导出 → 继续对话 → 导出再导入一致性
4. 打包：release 签名配置（密钥不入库）、R8、`LICENSE` 随包、README 增加 Android 章节

## 5. 验收

- `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` 全部通过
- 真机 / 模拟器：导入官方导出后会话、分支、思考、搜索来源正确显示；继续对话流式正常；停止、重新生成、编辑、分支切换正常
- 导出 ZIP 可被 Web 版导入
