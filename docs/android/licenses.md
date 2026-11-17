# Android 许可证与版权

ShallowDrop 默认采用 [MIT](../../LICENSE)，保留 `Copyright (c) 2026 DeepSeek`，并注明 ShallowDrop 贡献者的修改。文件单独标注的许可和第三方资源继续适用各自条款。

## 随包组件

本清单依据 0.1.0 的 `releaseRuntimeClasspath`（91 个产物、90 个不同模块）、随包 JavaScript、字体元数据和改编代码整理。设置 → 关于 → 开源许可按组件展示来源、版本、版权与完整许可。AndroidX 等组件组展开后列出实际使用的模块及版本。

| 组件 | 许可 | 声明位置 |
| --- | --- | --- |
| ShallowDrop、上游 DeepSeek chat-demo 代码与转换图标 | MIT | [项目许可证](../../LICENSE)、`res/raw/license.txt` |
| Codex 异步压缩实现的 Kotlin 改编 | Apache-2.0 | [原始版权与改动说明](../../android/app/src/main/assets/third-party/codex/NOTICE)；`AsyncContextCompaction.kt` 保留来源头 |
| AndroidX、Kotlin / kotlinx、OkHttp / Okio、Guava ListenableFuture、JetBrains annotations、JSpecify | Apache-2.0 | `assets/third-party/` 中各组件的 `NOTICE`；完整 Apache 许可证共用 `codex/LICENSE` |
| CommonMark Java 0.32.0 及扩展 | BSD-2-Clause | [版权和许可原文](../../android/app/src/main/assets/third-party/commonmark/LICENSE) |
| Autolink Java 0.12.0 | MIT | [版权和许可原文](../../android/app/src/main/assets/third-party/autolink/LICENSE) |
| AndroidX DataStore 1.3.0 重新打包的 Protobuf | BSD-3-Clause | [版权和许可原文](../../android/app/src/main/assets/third-party/protobuf/LICENSE) |
| KaTeX 0.16.22 JavaScript / CSS | MIT | [KaTeX 许可证](../../android/app/src/main/assets/markdown/katex/LICENSE) |
| mhchem 3.3.0 的 KaTeX 适配 | MIT，保留 MathJax 原始 Apache-2.0 声明 | [来源和版权](../../android/app/src/main/assets/third-party/mhchem/NOTICE) |
| 20 个 KaTeX WOFF2 字体 | OFL-1.1 | [字体版权、保留名称和完整 OFL](../../android/app/src/main/assets/third-party/katex-fonts/LICENSE) |

应用标志及自行绘制的图标见[图标来源](icons.md)。构建和测试工具未作为 Android 运行时组件列入本表。

## MIT 的适用范围

[MIT 条款](https://opensource.org/license/mit)允许修改和分发，并要求保留版权和许可文本。本项目继续以 MIT 作为默认许可，同时保留单独标注的第三方条件。

[Apache-2.0 第 4 条](https://www.apache.org/licenses/LICENSE-2.0)要求提供许可副本、保留适用的版权与 NOTICE，并说明改动。Codex 改编文件继续标注 Apache-2.0；它的原始版权、来源提交和修改说明随 APK 提供。

KaTeX 的 JavaScript 许可不覆盖其字体。字体内部的版权字段指定 Design Science、Khan Academy、OFL-1.1 以及 12 个保留字体名称，随包文本保留这些声明。[OFL 官方说明](https://openfontlicense.org/ofl-faq/#13)允许字体与采用其他许可证的软件一起分发，字体本身继续采用 OFL。

本次核对的生产依赖未出现 GPL / AGPL 等要求调整本项目整体许可的组件。该结论针对当前源码和打包内容；新增或升级依赖时应核对对应版本的声明。
