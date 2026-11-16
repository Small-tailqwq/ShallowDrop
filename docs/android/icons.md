# Android 图标来源

应用标志为蓝色鹈鹕剪影，矢量源图见 [`android/branding/shallowdrop-mark.svg`](../../android/branding/shallowdrop-mark.svg)，白底启动图标见 [`android/branding/shallowdrop.svg`](../../android/branding/shallowdrop.svg)。`ic_app_mark.xml` 用于新对话问候语上方和首次使用页面，保持品牌蓝色。

启动图标遵循 [Android 自适应图标规范](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)：`mipmap-anydpi/ic_launcher.xml` 组合纯白背景和透明矢量前景，108dp 画布中的鹈鹕位于中央 66dp 安全区内，由系统裁切外形；`monochrome` 共用前景的透明轮廓，支持系统主题图标。应用最低支持 Android 8.0（API 26），直接使用自适应图标资源。

朗读相关的 `ic_speaker_outline_20.xml`、`ic_speaker_off_outline_20.xml`、`ic_stop_circle_outline_20.xml` 为自行绘制的线条图标。除这些图标、应用标志和启动图标外，`android/app/src/main/res/drawable/ic_*.xml` 来自本仓库 Web 版，即上游 DeepSeek [chat-demo](../../LICENSE)（MIT）中的内联 SVG，由 [`android/scripts/svg2vd.py`](../../android/scripts/svg2vd.py) 逐元素转换为 VectorDrawable，图形未改动。每个转换文件头部的注释写明了对应的源文件与组件名。

- `src/components/base/icons.tsx`：单色图标，`fill="currentColor"` 转为黑色填充，在 Compose 中通过 `Icon` 的 `tint` 着色（封装见 `ui/components/DsIcon.kt`）。
- `src/components/files/FileIcon.tsx`：彩色文件类型图标（`ic_file_*`），自带配色，使用时不着色。

重新生成：

```bash
python android/scripts/svg2vd.py . build/vd
```

然后只把 Compose 里用到的文件复制进 `res/drawable/`。

## 对照表

| Android 资源 | chat-demo 组件 | 用在 |
|---|---|---|
| `ic_menu_outline_20` | `IconMenuOutline20` | 对话页顶栏：打开侧栏 |
| `ic_new_chat_outline_20` | `IconNewChatOutline20` | 对话页顶栏：开启新对话 |
| `ic_new_chat` | `NewChatIcon` | 侧栏顶部：开启新对话 |
| `ic_settings_outline_16` | `IconSettingsOutline16` | 侧栏底部、引导页：设置 |
| `ic_edit_outline_16` | `IconEditOutline16` | 会话重命名、编辑提问 |
| `ic_trash_outline_16` | `IconTrashOutline16` | 删除会话、删除全部对话 |
| `ic_think_outline_14` / `_16` | `IconThinkOutline14` / `16` | 深度思考开关、思考区标题 |
| `ic_globe_language_outline_14` | `IconGlobeLanguageOutline14` | 智能搜索开关、语言设置 |
| `ic_paperclip_outline_16` | `IconPaperclipOutline16` | 输入栏：上传附件 |
| `ic_send_outline_16` / `ic_stop_fill_16` | `IconSendOutline16` / `IconStopFill16` | 输入栏：发送 / 停止 |
| `ic_close_fill_14` | `IconCloseFill14` | 移除草稿附件、查找失败 |
| `ic_refresh_outline_16` | `IconRefreshOutline16` | 重新生成、附件重试 |
| `ic_copy_outline_16` / `ic_check_outline_16` | `IconCopyOutline16` / `IconCheckOutline16` | 复制回复、代码块复制 |
| `ic_chevron_right_outline_14` | `IconChevronRightOutline14` | 设置行箭头、版本切换、返回按钮（水平翻转） |
| `ic_chevron_down_outline_14` | `IconChevronDownOutline14` | 思考区展开、回到底部 |
| `ic_warning_outline_16` | `IconWarningOutline16` | 回复错误提示 |
| `ic_browse_outline_16` / `ic_browse_warning_outline_16` | `IconBrowseOutline16` / `IconBrowseWarningOutline16` | 搜索来源、浏览 / 查找步骤 |
| `ic_search_outline_16` / `ic_search_warning_outline_16` | `IconSearchOutline16` / `IconSearchWarningOutline16` | 搜索步骤 |
| `ic_link_outline_16` | `IconLinkOutline16` | 读取链接步骤 |
| `ic_right_up_outline_14` | `IconRightUpOutline14` | 外部链接（网页标签、源代码） |
| `ic_checked_outline_14` | `IconCheckedOutline14` | 页内查找成功 |
| `ic_dark_outline_16` | `IconDarkOutline16` | 主题设置 |
| `ic_upload_outline_14` / `ic_download_outline_16` | `IconUploadOutline14` / `IconDownloadOutline16` | 导入 / 导出历史 |
| `ic_file_image` / `_pdf` / `_table` / `_ppt` / `_other` | `FileIcon.tsx` 各类型 | 附件卡片、上传菜单 |

Web 版没有对应图标的地方仍用 Material 图标：API Key（`Key`）、模型（`SmartToy`）、版本（`Info`）、开源许可（`Gavel`）、源代码（`Code`）、API Key 显隐（`Visibility` / `VisibilityOff`）。
