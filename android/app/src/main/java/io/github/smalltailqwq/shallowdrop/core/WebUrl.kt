package io.github.smalltailqwq.shallowdrop.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 来自服务端或归档的链接会展示成可点击的来源，只接受 HTTP / HTTPS（对照 src/utils/url.ts；
 * OkHttp 的解析器与 WHATWG URL 基本一致）。
 */
fun isWebUrl(value: String): Boolean = value.toHttpUrlOrNull() != null
