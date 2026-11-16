package io.github.smalltailqwq.shallowdrop.ui.voice

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 语音对话的光球：玻璃球里流动的三色流体，外圈光晕；聆听时向外扩散波纹，思考时一颗带拖尾的光点绕球环行。
 *
 * 布局大小是光球的标称直径，光晕与波纹画在布局范围之外（[spread] 倍），父级不要裁剪。
 * Android 13 起用 AGSL 着色器绘制，更早的系统用渐变叠出近似效果。
 *
 * @param level 当前音量 0..1，逐帧读取（聆听为麦克风，说话为朗读）
 * @param spread 绘制范围相对布局大小的倍数；空间有限处（如音色选择）调小，光晕在边界前淡出
 */
@Composable
fun VoiceOrb(
    look: OrbLook,
    mood: OrbMood,
    modifier: Modifier = Modifier,
    level: () -> Float = { 0f },
    pressed: Boolean = false,
    spread: Float = 2.4f,
    initialScale: Float = OrbDynamics.ENTER_SCALE,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val shown = if (dark) look.onDark() else look
    val dynamics = remember { OrbDynamics(initialScale) }
    val painter = remember { if (Build.VERSION.SDK_INT >= 33) ShaderOrbPainter() else GradientOrbPainter() }
    val currentMood by rememberUpdatedState(mood)
    val currentLevel by rememberUpdatedState(level)
    val currentSpeed by rememberUpdatedState(look.speed)
    val currentPressed by rememberUpdatedState(pressed)
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(dynamics) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) dynamics.step((now - last) / 1e9f, currentMood, currentLevel(), currentSpeed, currentPressed)
                last = now
                frame = now
            }
        }
    }
    Canvas(modifier) {
        // 读取 frame 让绘制逐帧失效；第一帧之前没有可画的状态
        if (frame == 0L) return@Canvas
        with(painter) { draw(dynamics, shown, glow = if (dark) 0.8f else 1f, spread = spread) }
    }
}

private interface OrbPainter {
    fun DrawScope.draw(dynamics: OrbDynamics, look: OrbLook, glow: Float, spread: Float)
}

/** 波纹强度：聆听时常驻一点，随音量加强 */
private fun OrbDynamics.rippleAmount() = listening * (0.3f + 0.7f * level)

@RequiresApi(33)
private class ShaderOrbPainter : OrbPainter {
    private val shader = RuntimeShader(ORB_SHADER)
    private val brush = ShaderBrush(shader)

    override fun DrawScope.draw(dynamics: OrbDynamics, look: OrbLook, glow: Float, spread: Float) {
        val box = size.minDimension
        val extent = box * spread / 2f
        shader.setFloatUniform("uCenter", center.x, center.y)
        shader.setFloatUniform("uRadius", box / 2f * dynamics.scale)
        shader.setFloatUniform("uExtent", extent)
        shader.setFloatUniform("uFlow", dynamics.flow)
        shader.setFloatUniform("uSeed", look.seed)
        shader.setFloatUniform("uDetail", look.detail)
        shader.setFloatUniform("uWarp", look.warp)
        shader.setFloatUniform("uLight", look.light.red, look.light.green, look.light.blue)
        shader.setFloatUniform("uMid", look.mid.red, look.mid.green, look.mid.blue)
        shader.setFloatUniform("uDeep", look.deep.red, look.deep.green, look.deep.blue)
        shader.setFloatUniform("uPulse", dynamics.level)
        shader.setFloatUniform("uRipple", dynamics.ripple)
        shader.setFloatUniform("uRippleAmt", dynamics.rippleAmount())
        shader.setFloatUniform("uSpin", dynamics.spin)
        shader.setFloatUniform("uThink", dynamics.thinking)
        shader.setFloatUniform("uDim", dynamics.dim)
        shader.setFloatUniform("uGlow", glow)
        drawRect(brush, topLeft = Offset(center.x - extent, center.y - extent), size = Size(extent * 2f, extent * 2f))
    }
}

/** Android 12 及更早：没有 AGSL，用几团漂移的径向渐变近似流体 */
private class GradientOrbPainter : OrbPainter {
    private val clip = Path()

    override fun DrawScope.draw(dynamics: OrbDynamics, look: OrbLook, glow: Float, spread: Float) {
        val radius = size.minDimension / 2f * dynamics.scale
        val live = 1f - dynamics.dim
        val light = look.light.faded(dynamics.dim)
        val mid = look.mid.faded(dynamics.dim)
        val deep = look.deep.faded(dynamics.dim)

        val haloRadius = minOf(radius * 1.7f, size.minDimension * spread / 2f)
        val haloAlpha = ((0.26f + 0.4f * dynamics.level) * glow * (0.25f + 0.75f * live)).coerceIn(0f, 1f)
        drawCircle(
            Brush.radialGradient(
                0f to mid.copy(alpha = haloAlpha),
                radius / haloRadius to mid.copy(alpha = haloAlpha),
                1f to mid.copy(alpha = 0f),
                center = center,
                radius = haloRadius,
            ),
            haloRadius,
        )
        val ripple = dynamics.rippleAmount()
        if (ripple > 0.01f) {
            repeat(RINGS) { i ->
                val phase = (dynamics.ripple / TAU + i.toFloat() / RINGS) % 1f
                drawCircle(
                    mid.copy(alpha = (1f - phase) * ripple * 0.22f * glow),
                    radius * (1.06f + phase * 0.55f),
                    style = Stroke(radius * 0.02f),
                )
            }
        }

        clip.rewind()
        clip.addOval(Rect(center, radius))
        clipPath(clip) {
            drawCircle(light, radius)
            val t = dynamics.flow
            blob(mid, radius, 0.42f, 0.95f, t * 0.9f + look.seed, t * 0.7f + look.seed * 1.7f)
            blob(deep.copy(alpha = 0.72f), radius, 0.55f, 0.62f, -t * 1.1f + 1.3f, t * 0.85f + 4.1f)
            blob(light.copy(alpha = 0.85f), radius, 0.38f, 0.58f, t * 1.3f + 2.6f, -t * 1.25f + 0.4f)
            val highlight = center + Offset(-0.36f, -0.44f) * radius
            drawCircle(
                Brush.radialGradient(listOf(Color.White.copy(alpha = 0.6f), Color.Transparent), highlight, radius * 0.6f),
                radius * 0.6f,
                highlight,
            )
        }
        drawCircle(
            Brush.radialGradient(
                0.84f to Color.Transparent,
                0.97f to Color.White.copy(alpha = 0.88f),
                1f to Color.White.copy(alpha = 0.88f),
                center = center,
                radius = radius,
            ),
            radius,
        )
        if (dynamics.thinking > 0.01f) {
            // 光点在前，拖尾落在后面
            val outset = radius * 0.05f
            drawArc(
                mid.copy(alpha = 0.75f * dynamics.thinking * glow),
                startAngle = dynamics.spin * 180f / PI.toFloat() - ARC_SWEEP,
                sweepAngle = ARC_SWEEP,
                useCenter = false,
                topLeft = Offset(center.x - radius - outset, center.y - radius - outset),
                size = Size((radius + outset) * 2f, (radius + outset) * 2f),
                style = Stroke(radius * 0.07f, cap = StrokeCap.Round),
            )
        }
    }

    /** 以 (ax, ay) 为相位绕球心漂移的一团颜色 */
    private fun DrawScope.blob(color: Color, radius: Float, orbit: Float, extent: Float, ax: Float, ay: Float) {
        val at = center + Offset(cos(ax), sin(ay)) * (radius * orbit)
        drawCircle(Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), at, radius * extent), radius * extent, at)
    }

    private fun Color.faded(dim: Float): Color {
        // 褪色并压暗，与着色器一致
        val gray = (red * 0.299f + green * 0.587f + blue * 0.114f) * 0.82f + 0.03f
        return lerp(this, Color(gray, gray, gray, alpha), dim * 0.82f)
    }

    private companion object {
        const val RINGS = 3
        const val ARC_SWEEP = 70f
        const val TAU = (2 * PI).toFloat()
    }
}

/*
 * 本项目自写的光球着色器。流体不用噪声纹理：对坐标做三层正弦扭曲（swirl），再取正弦得到丝绸状的色带，
 * 没有哈希函数，不存在因浮点精度产生的接缝。输出为预乘 alpha。
 */
internal const val ORB_SHADER = """
uniform vec2 uCenter;
uniform float uRadius;
uniform float uExtent;
uniform float uFlow;
uniform float uSeed;
uniform float uDetail;
uniform float uWarp;
uniform vec3 uLight;
uniform vec3 uMid;
uniform vec3 uDeep;
uniform float uPulse;
uniform float uRipple;
uniform float uRippleAmt;
uniform float uSpin;
uniform float uThink;
uniform float uDim;
uniform float uGlow;

vec2 swirl(vec2 q, float t) {
    q += uWarp * 0.62 * vec2(sin(q.y * 1.35 + t * 0.90 + uSeed), cos(q.x * 1.20 - t * 0.70 + uSeed * 1.7));
    q += uWarp * 0.44 * vec2(sin(q.y * 2.10 - t * 1.10 + 1.3), cos(q.x * 1.90 + t * 0.85 + 4.1));
    q += uWarp * 0.24 * vec2(sin(q.y * 3.40 + t * 1.30 + 2.6), cos(q.x * 3.10 - t * 1.25 + 0.4));
    return q;
}

vec4 main(vec2 fragCoord) {
    vec2 p = (fragCoord - uCenter) / uRadius;
    float r = length(p);
    float px = 1.5 / uRadius;
    float live = 1.0 - uDim;

    // 球外：光晕与聆听波纹，到绘制范围边界前淡出
    float d = max(r - 1.0, 0.0);
    // 光晕随音量变亮，也向外铺开：声音像是从球里漫出来
    float halo = exp(-d * (6.0 - 3.2 * uPulse)) * (0.26 + 0.40 * uPulse) + exp(-d * 1.7) * 0.08;
    // 思考：一颗带拖尾的光点绕球环行。turn 为落后光点的圈数，0 在光点处，沿拖尾增大
    float turn = fract((uSpin - atan(p.y, p.x)) / 6.2831853);
    float comet = (exp(-turn * 5.0) + smoothstep(0.95, 1.0, turn)) * uThink;
    halo += comet * exp(-d * 7.0) * 0.8;
    float wave = 0.5 + 0.5 * sin(d * 20.0 - uRipple);
    wave *= wave;
    wave *= wave;
    float rings = wave * exp(-d * 2.6) * smoothstep(0.03, 0.22, d) * uRippleAmt;
    float outside = clamp((halo + rings * 0.42) * uGlow * (0.25 + 0.75 * live), 0.0, 1.0);
    outside *= 1.0 - smoothstep(0.72, 1.0, length(fragCoord - uCenter) / uExtent);
    vec3 haloColor = mix(uMid, uLight, 0.3);
    // 绘制范围的大半在球外：到此为止，不做球内的流体计算（r >= 1 时下面的 inside 恒为 0，结果相同）
    if (r >= 1.0) {
        return vec4(haloColor * outside, outside);
    }

    // 球内：越靠边缘采样越远，模拟玻璃球的折射
    float z = sqrt(max(1.0 - r * r, 0.0));
    vec2 uv = p * (1.0 + 0.5 * (1.0 - z));
    vec2 q = swirl(uv * uDetail, uFlow);
    float a = 0.5 + 0.5 * sin(q.x * 1.1 + q.y * 0.7);
    float b = 0.5 + 0.5 * sin(q.y * 1.3 - q.x * 0.5 + 2.0 + uFlow * 0.3);
    float c = 0.5 + 0.5 * sin(q.x * 0.8 - q.y * 1.5 + 1.0 - uFlow * 0.2);
    vec3 col = mix(uLight, uMid, smoothstep(0.18, 0.88, a));
    col = mix(col, uDeep, smoothstep(0.42, 1.0, a * b + 0.16 * b) * 0.74);
    col = mix(col, uLight, smoothstep(0.62, 1.0, c) * 0.42);
    col = mix(col, uLight, 0.16 * uPulse);

    // 磨砂边缘、右下暗面、左上高光、白色玻璃边
    float edge = r * r;
    edge *= edge * edge;
    col = mix(col, uLight, edge * 0.55);
    col *= 1.0 - 0.10 * smoothstep(-0.2, 1.0, dot(p, vec2(0.55, 0.75)));
    vec2 h = p - vec2(-0.36, -0.44);
    col += (1.0 - col) * 0.62 * exp(-dot(h, h) * 7.0);
    float rim = smoothstep(0.86, 0.975, r);
    col = mix(col, vec3(1.0), rim * 0.88);

    // 思考的光点在球内侧也照亮贴近玻璃边的一圈
    col = mix(col, mix(uMid, vec3(1.0), 0.7), comet * smoothstep(0.66, 0.94, r) * 0.8);

    // 静音、停止：褪色并压暗
    float gray = dot(col, vec3(0.299, 0.587, 0.114));
    col = mix(col, vec3(gray * 0.82 + 0.03), uDim * 0.82);

    float inside = 1.0 - smoothstep(1.0 - px, 1.0, r);
    return vec4(mix(haloColor * outside, col, inside), mix(outside, 1.0, inside));
}
"""
