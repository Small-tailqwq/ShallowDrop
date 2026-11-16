package io.github.smalltailqwq.shallowdrop.ui.voice

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import io.github.smalltailqwq.shallowdrop.core.model.TtsVoice
import io.github.smalltailqwq.shallowdrop.ui.motion.MotionEaseOut

/**
 * 光球的外观：三色流体配色，加上流动的「性格」。每个音色一套，配色为本项目原创。
 *
 * @param light 底色，占大部分球面
 * @param mid 主色调，也是光晕的颜色
 * @param deep 少量深色点缀
 * @param seed 纹理相位，让不同音色的纹路走向不同
 * @param detail 纹理疏密，越大纹路越细碎
 * @param warp 卷曲强度
 * @param speed 流动快慢
 */
@Immutable
data class OrbLook(
    val light: Color,
    val mid: Color,
    val deep: Color,
    val seed: Float,
    val detail: Float,
    val warp: Float,
    val speed: Float,
)

/** 海星：暖色珠光，纹路细、流动轻快；暗潮：青蓝深海，纹路宽、流动沉缓 */
val TtsVoice.orbLook: OrbLook
    get() = when (this) {
        TtsVoice.Stella -> OrbLook(
            light = Color(0xFFFFF4EF),
            mid = Color(0xFFFFB7A8),
            deep = Color(0xFFD774A6),
            seed = 1.7f,
            detail = 2.3f,
            warp = 0.95f,
            speed = 1.15f,
        )
        TtsVoice.Tide -> OrbLook(
            light = Color(0xFFE9F7F6),
            mid = Color(0xFF84CCD4),
            deep = Color(0xFF3565AE),
            seed = 5.2f,
            detail = 1.65f,
            warp = 1.2f,
            speed = 0.8f,
        )
    }

/** 深色背景上把底色压暗一些，避免整颗球亮成一片白 */
fun OrbLook.onDark(): OrbLook = copy(light = lerp(light, mid, 0.22f))

/** 切换音色时颜色与纹理平滑过渡 */
@Composable
fun animateOrbLook(target: OrbLook): OrbLook {
    val spec = tween<Color>(LOOK_MS, easing = MotionEaseOut)
    val floats = tween<Float>(LOOK_MS, easing = MotionEaseOut)
    val light by animateColorAsState(target.light, spec, label = "orbLight")
    val mid by animateColorAsState(target.mid, spec, label = "orbMid")
    val deep by animateColorAsState(target.deep, spec, label = "orbDeep")
    val seed by animateFloatAsState(target.seed, floats, label = "orbSeed")
    val detail by animateFloatAsState(target.detail, floats, label = "orbDetail")
    val warp by animateFloatAsState(target.warp, floats, label = "orbWarp")
    return OrbLook(light, mid, deep, seed, detail, warp, target.speed)
}

private const val LOOK_MS = 700
