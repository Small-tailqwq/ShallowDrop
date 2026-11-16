package io.github.smalltailqwq.shallowdrop.ui.easteregg

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.smalltailqwq.shallowdrop.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

internal data class RidePalette(
    val paper: Color,
    val sky: Color,
    val far: Color,
    val near: Color,
    val road: Color,
    val line: Color,
    val accent: Color,
    val feather: Color,
    val wing: Color,
    val beak: Color,
)

internal fun RideStyle.palette() = when (this) {
    RideStyle.Ink -> RidePalette(
        Color(0xFFF3EFE3), Color(0xFFE4E8DE), Color(0xFFABB8AE), Color(0xFF526C63),
        Color(0xFFD2CDBD), Color(0xFF263F39), Color(0xFFAF4838), Color(0xFFFFFCF0),
        Color(0xFF9CAFA4), Color(0xFFC69550),
    )
    RideStyle.Coast -> RidePalette(
        Color(0xFFF9E8CA), Color(0xFFF7C888), Color(0xFFDF7B57), Color(0xFF327F81),
        Color(0xFFCD684A), Color(0xFF493B42), Color(0xFFF4BE62), Color(0xFFFFF4D9),
        Color(0xFFE6A487), Color(0xFFF2AA47),
    )
    RideStyle.Night -> RidePalette(
        Color(0xFF101329), Color(0xFF30234B), Color(0xFF3D3B68), Color(0xFF245365),
        Color(0xFF171C36), Color(0xFFA8EEEA), Color(0xFFFA819C), Color(0xFFE1F9F2),
        Color(0xFF65ABBC), Color(0xFFFFBA88),
    )
    RideStyle.Reef -> RidePalette(
        Color(0xFFD9F4EC), Color(0xFF4DBAC6), Color(0xFF3AA3B5), Color(0xFF2788A0),
        Color(0xFFF1E2B4), Color(0xFF0B3147), Color(0xFFFF7E6B), Color(0xFFF7FFFB),
        Color(0xFF8FD3CF), Color(0xFFFFB85C),
    )
    RideStyle.Orbit -> RidePalette(
        Color(0xFF171C48), Color(0xFF141A45), Color(0xFFB7A6E8), Color(0xFF2E8792),
        Color(0xFF63C2A8), Color(0xFFFFEBC8), Color(0xFFFF8A7A), Color(0xFFFFF8E7),
        Color(0xFFB7A6E8), Color(0xFFFFC56B),
    )
    RideStyle.Cloud -> RidePalette(
        Color(0xFFFFF4E4), Color(0xFF7CC4EE), Color(0xFFFFFFFF), Color(0xFF7BC47F),
        Color(0xFFFFFFFF), Color(0xFF3A3560), Color(0xFFEF5B5B), Color(0xFFFFFDF6),
        Color(0xFFA9C4E8), Color(0xFFFFB347),
    )
}

internal fun progress(time: Float, from: Float, to: Float): Float {
    val t = ((time - from) / (to - from)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

@Composable
internal fun PelicanScene(style: RideStyle, elapsed: () -> Float, modifier: Modifier = Modifier) {
    val head = rememberPelicanHead()
    val description = stringResource(R.string.easter_egg_description)
    val palette = remember(style) { style.palette() }
    val grain = remember {
        val random = Random(73)
        List(900) { Offset(random.nextFloat(), random.nextFloat()) }
    }
    Canvas(modifier.semantics { contentDescription = description }) {
        val time = elapsed()
        val unit = size.width / 1000f
        val height = size.height / unit
        val road = height * 0.73f
        val rideScale = min(1f, height / 1050f)
        val pull = progress(time, 1.6f, 6.8f)
        val moving = max(0f, time - 5.8f)
        val camera = 1f + (1f - pull) * when (style) {
            RideStyle.Ink -> 1.5f
            RideStyle.Coast -> 1.15f
            RideStyle.Night -> 1.8f
            RideStyle.Reef -> 1.4f
            RideStyle.Orbit -> 1.7f
            RideStyle.Cloud -> 1.3f
        }
        val stage = Stage(road, height, rideScale, time, moving)
        val riderY = road - 253f * rideScale
        val focus = Offset(500f - PelicanHeadCenter.x * rideScale, riderY + PelicanHeadCenter.y * rideScale)
        drawRect(lerp(Color(0xFF10171A), palette.paper, progress(time, 0f, 1.25f)))
        scale(unit, Offset.Zero) {
            withTransform({
                translate((500f - focus.x) * (1f - pull), (height * 0.46f - focus.y) * (1f - pull))
                scale(camera, camera, focus)
            }) {
                reveal(style, progress(time, 1.05f, 3.4f), height, 0) {
                    if (!drawWorld(style, 0, palette, stage)) {
                        drawRect(Brush.verticalGradient(listOf(palette.sky, palette.paper), endY = road), size = Size(1000f, height))
                        drawSky(style, palette, road, -moving)
                    }
                }
                reveal(style, progress(time, 1.9f, 4.6f), height, 1) {
                    if (!drawWorld(style, 1, palette, stage)) drawLandscape(style, palette, road, -moving, distant = true)
                }
                reveal(style, progress(time, 2.65f, 5.4f), height, 2) {
                    if (!drawWorld(style, 2, palette, stage)) drawLandscape(style, palette, road, -moving, distant = false)
                }
                reveal(style, progress(time, 3.2f, 5.9f), height, 3) {
                    if (!drawWorld(style, 3, palette, stage)) drawRoad(style, palette, road, height, -moving)
                }
                val logoAlpha = progress(time, 0.15f, 1.1f)
                if (logoAlpha > 0f) {
                    opacity(logoAlpha) {
                        translate(500f, riderY) {
                            scale(rideScale, Offset.Zero) {
                                scale(-1f, 1f, Offset.Zero) {
                                    drawPelican(style, palette, head, moving,
                                        pigment = progress(time, 5.15f, 6.3f),
                                        bodyReveal = progress(time, 4.3f, 6f),
                                        morph = progress(time, 3.6f, 5.2f))
                                }
                            }
                        }
                    }
                }
                if (style != RideStyle.Night) {
                    val grainColor = palette.line.copy(alpha = 0.055f * progress(time, 1f, 3f))
                    grain.forEachIndexed { index, dot ->
                        drawCircle(grainColor, if (index % 3 == 0) 1.5f else 0.7f, Offset(dot.x * 1000f, dot.y * height))
                    }
                }
                reveal(style, progress(time, 4.8f, 6.8f), height, 4) {
                    if (!drawWorld(style, 4, palette, stage)) drawForeground(style, palette, road, height, -moving)
                }
            }
            // 窄幅遮幅随镜头后拉收起，给循环段留出完整画面。
            val bars = height * (0.065f * (1f - pull) + 0.018f)
            drawRect(Color(0xFF0E151A), size = Size(1000f, bars))
            drawRect(Color(0xFF0E151A), topLeft = Offset(0f, height - bars), size = Size(1000f, bars))
        }
    }
}

private fun DrawScope.opacity(alpha: Float, block: DrawScope.() -> Unit) {
    if (alpha >= 1f) { block(); return }
    drawContext.canvas.nativeCanvas.saveLayerAlpha(null, (alpha * 255).toInt())
    try { block() } finally { drawContext.canvas.restore() }
}

private fun DrawScope.reveal(style: RideStyle, amount: Float, height: Float, seed: Int, block: DrawScope.() -> Unit) {
    if (amount <= 0f) return
    if (amount >= 1f) { block(); return }
    val mask = Path()
    repeat(18) { index ->
        val x = ((index * 317 + seed * 151) % 1100).toFloat() - 50f
        val y = ((index * 239 + seed * 113) % 1000) / 1000f * height
        val local = ((amount - (index % 5) * 0.055f) / 0.78f).coerceIn(0f, 1f)
        val radius = local * max(850f, height * 0.65f)
        when (style) {
            RideStyle.Night -> {
                val stepped = (radius / 28f).toInt() * 28f
                mask.addRect(Rect(x - stepped, y - stepped * 0.38f, x + stepped, y + stepped * 0.38f))
            }
            RideStyle.Coast -> mask.addOval(Rect(x - radius * 1.4f, y - radius * 0.6f, x + radius * 1.4f, y + radius * 0.6f))
            RideStyle.Ink -> {
                repeat(40) { point ->
                    val angle = point * 2f * PI.toFloat() / 40f
                    val edge = radius * (1f + 0.13f * sin(angle * 7f + index) + 0.06f * cos(angle * 11f))
                    val px = x + cos(angle) * edge
                    val py = y + sin(angle) * edge * 0.8f
                    if (point == 0) mask.moveTo(px, py) else mask.lineTo(px, py)
                }
                mask.close()
            }
            // 浅海冒泡、星球迸出星芒、云端长出云朵。
            RideStyle.Reef -> mask.addOval(Rect(Offset(x, y), radius))
            RideStyle.Orbit -> mask.addStar(Offset(x, y), radius * 1.7f, radius * 0.8f, 4)
            RideStyle.Cloud -> {
                mask.addOval(Rect(x - radius * 1.3f, y - radius * 0.5f, x + radius * 1.3f, y + radius * 0.5f))
                mask.addOval(Rect(Offset(x - radius * 0.4f, y - radius * 0.45f), radius * 0.6f))
                mask.addOval(Rect(Offset(x + radius * 0.45f, y - radius * 0.3f), radius * 0.45f))
            }
        }
    }
    opacity(min(1f, amount * 3f)) { clipPath(mask) { block() } }
}

private fun DrawScope.drawSky(style: RideStyle, p: RidePalette, road: Float, time: Float) {
    val sun = Offset(if (style == RideStyle.Coast) 730f else 720f, road * 0.35f)
    when (style) {
        RideStyle.Ink -> {
            drawCircle(p.accent.copy(alpha = 0.8f), 65f, sun)
            repeat(3) { index ->
                val x = 230f + index * 42f + sin(time * 0.17f) * 14f
                val y = road * 0.34f - index % 2 * 22f
                val path = Path().apply {
                    moveTo(x - 12f, y - 3f); quadraticTo(x - 4f, y - 9f, x, y)
                    quadraticTo(x + 5f, y - 7f, x + 14f, y - 5f)
                }
                drawPath(path, p.line.copy(alpha = 0.5f), style = Stroke(2f, cap = StrokeCap.Round))
            }
        }
        RideStyle.Coast -> {
            drawCircle(p.accent.copy(alpha = 0.4f), 150f, sun)
            drawCircle(Color(0xFFE96E46), 122f, sun)
            repeat(3) { index ->
                val x = ((index * 410f - time * 8f) % 1400f + 1400f) % 1400f - 180f
                val y = road * (0.25f + index * 0.12f)
                drawLine(p.paper.copy(alpha = 0.65f), Offset(x, y), Offset(x + 170f, y), 15f, StrokeCap.Round)
                drawLine(p.paper.copy(alpha = 0.4f), Offset(x + 45f, y + 22f), Offset(x + 240f, y + 22f), 7f, StrokeCap.Round)
            }
        }
        RideStyle.Night -> {
            drawCircle(Brush.radialGradient(listOf(p.accent.copy(alpha = 0.15f), Color.Transparent), sun, 220f), 220f, sun)
            val disc = Path().apply { addOval(Rect(sun - Offset(112f, 112f), Size(224f, 224f))) }
            clipPath(disc) {
                repeat(13) { i ->
                    drawRect(lerp(p.accent, p.beak, i / 12f), Offset(sun.x - 120f, sun.y - 112f + i * 19f), Size(240f, 15f - i * 0.65f))
                }
            }
            repeat(42) { i ->
                val point = Offset(((i * 137) % 1000).toFloat(), ((i * 83) % 700) / 1000f * road)
                drawCircle(p.feather.copy(alpha = 0.25f + 0.25f * (1f + sin(time * 0.6f + i))), if (i % 7 == 0) 2.3f else 1.1f, point)
            }
        }
        else -> Unit
    }
}

private fun DrawScope.drawLandscape(style: RideStyle, p: RidePalette, road: Float, time: Float, distant: Boolean) {
    val speed = if (distant) 9f else 24f
    val drift = time * speed % 1400f
    val baseline = road * if (distant) 0.78f else 0.96f
    val amplitude = road * if (distant) 0.23f else 0.18f
    val color = if (distant) p.far else p.near
    if (style == RideStyle.Coast) {
        drawRect(color, Offset(0f, baseline), Size(1000f, road - baseline + 20f))
    }
    for (tile in -1..1) {
        translate(tile * 1400f - drift, 0f) {
            when (style) {
                RideStyle.Ink -> {
                    val mountain = Path().apply {
                        moveTo(0f, baseline)
                        cubicTo(110f, baseline - 20f, 150f, baseline - amplitude * 0.6f, 225f, baseline - amplitude * 0.8f)
                        lineTo(260f, baseline - amplitude * 0.72f); lineTo(310f, baseline - amplitude)
                        cubicTo(380f, baseline - amplitude * 0.94f, 390f, baseline - 30f, 530f, baseline - 12f)
                        cubicTo(680f, baseline - 20f, 700f, baseline - amplitude * 0.8f, 790f, baseline - amplitude * 0.92f)
                        lineTo(840f, baseline - amplitude * 0.73f); lineTo(880f, baseline - amplitude * 0.79f)
                        cubicTo(1050f, baseline - 30f, 1120f, baseline - amplitude * 0.4f, 1220f, baseline - amplitude * 0.32f)
                        cubicTo(1300f, baseline - 10f, 1370f, baseline, 1400f, baseline)
                        lineTo(1400f, road + 40f); lineTo(0f, road + 40f); close()
                    }
                    drawPath(mountain, Brush.verticalGradient(listOf(color.copy(alpha = 0.9f), p.paper.copy(alpha = 0.25f)), baseline - amplitude, road))
                    if (!distant) {
                        repeat(10) { i ->
                            val x = 120f + i * 122f
                            val y = baseline - 20f - (i % 3) * 23f
                            drawLine(p.line.copy(alpha = 0.3f), Offset(x, y), Offset(x + 7f, y - 45f), 3f)
                            repeat(4) { branch ->
                                val by = y - branch * 10f - 15f
                                drawLine(p.line.copy(alpha = 0.28f), Offset(x - 16f + branch * 3f, by + 8f), Offset(x + 17f - branch * 3f, by), 5f, StrokeCap.Round)
                            }
                        }
                    }
                }
                RideStyle.Coast -> {
                    val hill = Path().apply {
                        moveTo(0f, baseline)
                        cubicTo(180f, baseline, 150f, baseline - amplitude, 410f, baseline - amplitude)
                        cubicTo(700f, baseline - amplitude, 680f, baseline - 15f, 920f, baseline - 15f)
                        cubicTo(1090f, baseline - 15f, 1170f, baseline - amplitude * 0.35f, 1400f, baseline)
                        lineTo(1400f, road + 20f); lineTo(0f, road + 20f); close()
                    }
                    drawPath(hill, color)
                    if (!distant) {
                        repeat(12) { i ->
                            val x = i * 127f
                            val y = baseline + 10f + i % 3 * 17f
                            drawLine(p.paper.copy(alpha = 0.32f), Offset(x, y), Offset(x + 65f, y), 3f, StrokeCap.Round)
                        }
                    } else {
                        repeat(7) { i ->
                            drawLine(p.paper.copy(alpha = 0.12f), Offset(280f + i * 16f, baseline - amplitude + 18f), Offset(230f + i * 16f, baseline - 10f), 6f)
                        }
                    }
                }
                RideStyle.Night -> {
                    repeat(14) { i ->
                        val top = baseline - amplitude * (0.22f + ((i * 7) % 11) / 13f)
                        val x = i * 100f
                        drawRect(color, Offset(x, top), Size(86f, road - top + 20f))
                        drawLine(p.wing.copy(alpha = 0.45f), Offset(x, top), Offset(x + 86f, top), 2f)
                        if (!distant) {
                            repeat(4) { window ->
                                drawLine(p.accent.copy(alpha = 0.35f), Offset(x + 16f, top + 20f + window * 18f), Offset(x + 35f, top + 20f + window * 18f), 3f)
                            }
                        }
                    }
                }
                else -> Unit
            }
        }
    }
    if (style == RideStyle.Ink) {
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, p.paper.copy(alpha = 0.7f), Color.Transparent), baseline - 25f, baseline + 75f), Offset(0f, baseline - 25f), Size(1000f, 100f))
    }
}

private fun DrawScope.drawRoad(style: RideStyle, p: RidePalette, road: Float, height: Float, time: Float) {
    drawRect(p.road, Offset(0f, road - 10f), Size(1000f, height - road + 10f))
    when (style) {
        RideStyle.Ink -> {
            repeat(4) { row ->
                val y = road + row * 48f
                drawLine(p.line.copy(alpha = 0.12f), Offset(0f, y), Offset(1000f, y - 4f), 1.6f)
                repeat(11) { i ->
                    val x = ((i * 112f + row * 43f - time * 155f) % 1232f + 1232f) % 1232f - 112f
                    drawLine(p.line.copy(alpha = 0.15f), Offset(x, y), Offset(x - 24f, y + 42f), 1.5f)
                }
            }
        }
        RideStyle.Coast -> {
            drawLine(p.paper.copy(alpha = 0.65f), Offset(0f, road + 8f), Offset(1000f, road + 8f), 5f)
            repeat(9) { i ->
                val x = ((i * 160f - time * 155f) % 1440f + 1440f) % 1440f - 160f
                drawLine(p.accent, Offset(x, road + 98f), Offset(x + 84f, road + 98f), 8f)
            }
        }
        RideStyle.Night -> {
            drawLine(p.wing, Offset(0f, road), Offset(1000f, road), 3f)
            repeat(6) { row ->
                val y = road + row * row * 14f + 18f
                drawLine(p.wing.copy(alpha = 0.18f), Offset(0f, y), Offset(1000f, y), 2f)
            }
            repeat(12) { i ->
                val x = ((i * 140f - time * 155f) % 1680f + 1680f) % 1680f - 280f
                drawLine(p.wing.copy(alpha = 0.18f), Offset(x, road), Offset(x - 240f, height), 2f)
                drawLine(p.accent.copy(alpha = 0.7f), Offset(x, road + 80f), Offset(x + 65f, road + 80f), 4f)
            }
        }
        else -> Unit
    }
}

private fun DrawScope.drawForeground(style: RideStyle, p: RidePalette, road: Float, height: Float, time: Float) {
    val y = road + (height - road) * 0.74f
    val drift = time * 240f % 1300f
    for (tile in -1..1) {
        translate(tile * 1300f - drift, 0f) {
            when (style) {
                RideStyle.Ink -> {
                    repeat(5) { i ->
                        val x = 90f + i * 27f
                        val grass = Path().apply { moveTo(x, height); quadraticTo(x - 30f, y, x + 15f, y - 50f + i * 13f) }
                        drawPath(grass, p.line.copy(alpha = 0.4f), style = Stroke(4f, cap = StrokeCap.Round))
                    }
                    drawOval(p.near, Offset(660f, y + 26f), Size(75f, 25f))
                }
                RideStyle.Coast -> {
                    drawLine(p.line.copy(alpha = 0.25f), Offset(150f, y + 40f), Offset(150f, y - 34f), 9f, StrokeCap.Round)
                    repeat(5) { i ->
                        val angle = i * 0.55f + 3.7f
                        drawLine(p.near, Offset(150f, y + 10f), Offset(150f + cos(angle) * 60f, y + sin(angle) * 65f), 19f, StrokeCap.Round)
                    }
                    drawOval(p.accent.copy(alpha = 0.5f), Offset(780f, y + 35f), Size(60f, 18f))
                }
                RideStyle.Night -> {
                    drawLine(p.wing.copy(alpha = 0.3f), Offset(100f, height), Offset(100f, y - 50f), 6f)
                    drawLine(p.accent, Offset(100f, y - 50f), Offset(100f, y - 20f), 7f, StrokeCap.Round)
                    drawOval(p.accent.copy(alpha = 0.08f), Offset(55f, y + 45f), Size(90f, 14f))
                }
                else -> Unit
            }
        }
    }
}
