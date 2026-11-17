package io.github.smalltailqwq.shallowdrop.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp

/*
 * 文字与图标、标签、开关的水平对齐。
 * 中文字形的视觉中心比行框中心低约 0.1em，按行框居中时文字会比旁边的图标低一两个像素。
 */

/** [style] 的字形视觉中心相对行框中心的下移量 */
@Composable
fun opticalOffset(style: TextStyle): Dp = with(LocalDensity.current) { (style.fontSize * 0.1f).toDp() }

/** 单行文字与同行的图标、标签、开关居中对齐时加在文字上：把字形上移到行框中心。 */
fun Modifier.opticalCenter(style: TextStyle): Modifier = composed { offset(y = -opticalOffset(style)) }

/** 图标与旁边可能换行的文字对齐时用：高度等于 [style] 的行高，内容对准首行字形的视觉中心。 */
@Composable
fun FirstLineAligned(style: TextStyle, content: @Composable () -> Unit) {
    val lineHeight = with(LocalDensity.current) { style.lineHeight.toDp() }
    Box(Modifier.height(lineHeight).offset(y = opticalOffset(style)), contentAlignment = Alignment.Center) { content() }
}
