# ShallowDrop

[English](README.md) · [Android 发布](https://github.com/Small-tailqwq/ShallowDrop/releases) · [Web 分支](https://github.com/Small-tailqwq/ShallowDrop/tree/main)

轻量的 DeepSeek 原生 Android 客户端，使用 Kotlin 和 Jetpack Compose 构建，需要自备 DeepSeek API Key。本项目基于 DeepSeek chat-demo 开发，是独立的社区项目。

## 安装

从 [0.1.0 发布页面](https://github.com/Small-tailqwq/ShallowDrop/releases/tag/v0.1.0) 下载并安装 APK。最低支持 Android 8.0；通用 APK 包含 arm64-v8a、armeabi-v7a、x86 和 x86_64。

## 功能

- 流式对话、思考、联网搜索与引用。
- 图片和文件附件、语音输入与朗读。
- Markdown、代码块、表格、数学与化学公式。
- 重试历史和对话分支：从已完成的 AI 消息创建独立对话，分支标题使用 #1、#2、#1-1 等编号。
- 对话搜索、本地历史、备份导入导出和上下文压缩。
- 主题、语言、字号和导航偏好。
- 设置 → 关于：应用介绍、免责声明、开源代码和逐项开源许可。

长对话按当前对话路径加载，按需读取重试历史。存储、重试、安装包体积和冷启动的测量方法与结果见 [Android 性能报告](docs/android/performance-2026-11-17.md)。

## 数据

对话和附件保存在设备上，API Key 使用 Android Keystore 和 AES-256-GCM 加密存储。请求会将相关对话内容和附件发送给 API 提供方，适用其服务条款、隐私政策和用量计费。ShallowDrop 无需额外部署应用后端。

AI 回答可能有误；卸载或更换安装前请备份数据。

## 构建

需要 JDK 21 和 Android SDK 37，通过 ANDROID_HOME 或 android/local.properties 配置 SDK 路径。

Windows：

~~~powershell
cd android
.\gradlew.bat :app:assembleDebug
~~~

macOS / Linux：

~~~sh
cd android
bash gradlew :app:assembleDebug
~~~

调试 APK 输出到 android/app/build/outputs/apk/debug/。Release 构建启用 R8 和资源裁剪。

GitHub Actions 中的 **Android release** 工作流使用仓库 Secrets 构建并签名 APK：ANDROID_KEYSTORE_BASE64、ANDROID_KEYSTORE_PASSWORD、ANDROID_KEY_ALIAS 和 ANDROID_KEY_PASSWORD。私钥不写入代码仓库。[正式版公开证书](android/signing/release-cert.pem)的 SHA-256 指纹为：

~~~text
C6:87:A3:EE:7E:87:B8:B6:65:51:B4:99:0D:A1:D4:2F:92:B6:1A:35:AD:30:FB:E1:1A:56:C5:B3:C1:BC:D7:60
~~~

## 许可

ShallowDrop 自有代码默认采用 [MIT](LICENSE)，保留 DeepSeek 原始版权。单独标注许可的文件与第三方组件继续采用各自条款，包括 Apache-2.0、BSD 和 OFL-1.1。详细清单见 [Android 许可证与版权](docs/android/licenses.md)，也可在设置 → 关于 → 开源许可中查看。

原有 Web 应用保留在 [main 分支](https://github.com/Small-tailqwq/ShallowDrop/tree/main)。
