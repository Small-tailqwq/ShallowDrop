# ShallowDrop

[简体中文](README.zh-CN.md) · [Android releases](https://github.com/Small-tailqwq/ShallowDrop/releases) · [Web branch](https://github.com/Small-tailqwq/ShallowDrop/tree/main)

A lightweight native Android client for DeepSeek, built with Kotlin and Jetpack Compose. Bring your own DeepSeek API key. This is an independent community project based on DeepSeek's chat-demo.

## Install

Version 0.1.0 is being prepared for release. Published packages will appear on the [releases page](https://github.com/Small-tailqwq/ShallowDrop/releases); to try the current source, follow the build instructions below. Android 8.0 or later is required; the universal APK supports arm64-v8a, armeabi-v7a, x86 and x86_64.

**Moving from a development build:** export your data in Settings before uninstalling it, then install 0.1.0 and import the backup. The official release uses a new signing certificate and cannot update an installation signed with the development key.

## Features

- Streaming conversations, thinking, web search and references.
- Image and file attachments, speech input and text-to-speech.
- Markdown, code blocks, tables, math and chemistry formulas.
- Retry history and conversation branches. Fork a completed assistant message into a separate conversation; branches use titles such as #1, #2 and #1-1.
- Conversation search, local history, backup import/export and context compaction.
- Theme, language, font-size and navigation preferences.
- Settings → About: app information, disclaimer, source code and component licenses.

Long conversations load the selected conversation path, while retry history is read as needed. Storage, retry, package-size and cold-start measurements are documented in the [Android performance report](docs/android/performance-2026-11-17.md).

## Data

Conversations and attachments are stored on the device. The API key is encrypted with AES-256-GCM using Android Keystore. Requests send the relevant conversation content and attachments to the API provider; provider terms, privacy policy and usage charges apply. ShallowDrop does not require a separate application backend.

AI output can be inaccurate. Keep backups before uninstalling or replacing the app.

## Build

Use JDK 21 and Android SDK 37. Set ANDROID_HOME or configure android/local.properties for your SDK.

Windows:

~~~powershell
cd android
.\gradlew.bat :app:assembleDebug
~~~

macOS / Linux:

~~~sh
cd android
bash gradlew :app:assembleDebug
~~~

The debug APK is written to android/app/build/outputs/apk/debug/. Release builds enable R8 and resource shrinking.

The **Android release** GitHub Actions workflow builds and signs an APK using repository secrets: ANDROID_KEYSTORE_BASE64, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS and ANDROID_KEY_PASSWORD. The private key is not stored in the repository. The [public release certificate](android/signing/release-cert.pem) has SHA-256 fingerprint:

~~~text
C6:87:A3:EE:7E:87:B8:B6:65:51:B4:99:0D:A1:D4:2F:92:B6:1A:35:AD:30:FB:E1:1A:56:C5:B3:C1:BC:D7:60
~~~

## License

[MIT](LICENSE) is the default license for ShallowDrop code, with the original DeepSeek copyright retained. Individually licensed files and third-party components retain their own terms, including Apache-2.0, BSD and OFL-1.1. See the [Android license inventory](docs/android/licenses.md) or Settings → About → Open-source licenses.

The original web application remains on the [main branch](https://github.com/Small-tailqwq/ShallowDrop/tree/main).
