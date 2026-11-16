package io.github.smalltailqwq.shallowdrop.ui.easteregg

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// 浅海、小星球、云端三款世界。坐标沿用 PelicanScene 的 1000 宽画布；
// clock 是动画总时长，ride 是开始骑行后的时长（入场阶段为 0，景物静止）。
internal class Stage(val road: Float, val height: Float, val rider: Float, val clock: Float, val ride: Float)

private const val TAU = 2f * PI.toFloat()
private val White = Color.White

/** 按层绘制新世界；返回 false 表示该风格仍走 PelicanScene 的原有分支。 */
internal fun DrawScope.drawWorld(style: RideStyle, layer: Int, p: RidePalette, s: Stage): Boolean {
    when (style) {
        RideStyle.Reef -> when (layer) {
            0 -> reefWater(p, s)
            1 -> reefFar(p, s)
            2 -> reefNear(p, s)
            3 -> reefSand(p, s)
            else -> reefFront(s)
        }
        RideStyle.Orbit -> when (layer) {
            0 -> orbitSky(p, s)
            1 -> orbitFar(p, s)
            2 -> orbitBack(p, s)
            3 -> orbitGround(p, s)
            else -> orbitFront(p, s)
        }
        RideStyle.Cloud -> when (layer) {
            0 -> cloudSky(p, s)
            1 -> cloudFar(p, s)
            2 -> cloudNear(p, s)
            3 -> cloudRoad(s)
            else -> cloudFront(s)
        }
        else -> return false
    }
    return true
}

private fun wrap(value: Float, period: Float) = ((value % period) + period) % period

/** 把 [0, period) 内的内容向右循环滚动，period 需不小于画布宽度。 */
private fun DrawScope.scroll(distance: Float, period: Float, block: DrawScope.() -> Unit) {
    val drift = wrap(distance, period)
    translate(drift - period, 0f) { block() }
    translate(drift, 0f) { block() }
}

internal fun Path.addStar(center: Offset, outer: Float, inner: Float, points: Int) {
    repeat(points * 2) { i ->
        val radius = if (i % 2 == 0) outer else inner
        val angle = i * PI.toFloat() / points - PI.toFloat() / 2f
        val x = center.x + cos(angle) * radius
        val y = center.y + sin(angle) * radius
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

private fun star(center: Offset, outer: Float, inner: Float, points: Int) =
    Path().apply { addStar(center, outer, inner, points) }

// ───────────────────────── 浅海 ─────────────────────────

private fun DrawScope.fish(at: Offset, size: Float, color: Color, wag: Float) {
    val tail = Path().apply {
        moveTo(at.x - size * 0.7f, at.y)
        lineTo(at.x - size * 1.7f, at.y - size * 0.6f + wag)
        lineTo(at.x - size * 1.7f, at.y + size * 0.6f + wag)
        close()
    }
    drawPath(tail, color)
    drawOval(color, Offset(at.x - size, at.y - size * 0.45f), Size(size * 2f, size * 0.9f))
}

private fun DrawScope.kelp(x: Float, y: Float, height: Float, phase: Float, color: Color, width: Float) {
    val segment = height / 4f
    val blade = Path().apply {
        moveTo(x, y)
        var from = x
        repeat(4) { k ->
            val to = x + sin(phase + k * 0.9f) * 7f * (k + 1)
            val side = if (k % 2 == 0) 24f else -24f
            quadraticTo((from + to) / 2f + side, y - segment * (k + 0.5f), to, y - segment * (k + 1))
            from = to
        }
    }
    drawPath(blade, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.reefWater(p: RidePalette, s: Stage) {
    drawRect(
        Brush.verticalGradient(0f to Color(0xFFA6ECDD), 0.5f to p.sky, 1f to Color(0xFF2B95AF), endY = s.road),
        size = Size(1000f, s.height),
    )
    val depth = s.road * 0.94f
    repeat(5) { i ->
        val x = 110f + i * 215f + sin(s.clock * 0.35f + i * 1.9f) * 26f
        val width = 46f + (i % 3) * 24f
        val ray = Path().apply {
            moveTo(x, 0f); lineTo(x + width, 0f)
            lineTo(x + width - 150f, depth); lineTo(x - 230f, depth); close()
        }
        drawPath(ray, Brush.verticalGradient(listOf(White.copy(alpha = 0.22f), Color.Transparent), 0f, depth))
    }
    val school = Offset(wrap(520f + s.clock * 18f + s.ride * 20f, 1500f) - 250f, s.road * 0.24f)
    repeat(15) { i ->
        val at = school + Offset((i % 5) * 30f - (i / 5) * 17f, (i / 5) * 21f + sin(i * 2.3f) * 9f + sin(s.clock * 1.1f + i) * 4f)
        fish(at, 8f, Color(0xFF1B7C96).copy(alpha = 0.6f), sin(s.clock * 9f + i) * 2f)
    }
}

private fun DrawScope.reefFar(p: RidePalette, s: Stage) {
    val base = s.road * 0.9f
    val rise = s.road * 0.2f
    scroll(s.ride * 9f, 1400f) {
        val ridge = Path().apply {
            moveTo(0f, base)
            cubicTo(120f, base, 160f, base - rise, 300f, base - rise)
            cubicTo(420f, base - rise, 430f, base - rise * 0.35f, 560f, base - rise * 0.4f)
            cubicTo(700f, base - rise * 0.45f, 720f, base - rise * 0.8f, 860f, base - rise * 0.75f)
            cubicTo(1010f, base - rise * 0.7f, 1080f, base - rise * 0.1f, 1200f, base - rise * 0.2f)
            cubicTo(1300f, base - rise * 0.28f, 1340f, base, 1400f, base)
            lineTo(1400f, s.road + 20f); lineTo(0f, s.road + 20f); close()
        }
        drawPath(ridge, Brush.verticalGradient(listOf(p.far, p.far.copy(alpha = 0.55f)), base - rise, s.road))
    }
    // 远处滑翔的蝠鲼
    val flap = sin(s.clock * 2.2f) * 22f
    translate(1150f - wrap(s.clock * 34f - s.ride * 6f + 420f, 1500f), s.road * 0.4f + sin(s.clock * 0.5f) * 18f) {
        val ray = Path().apply {
            moveTo(-58f, 0f)
            cubicTo(-34f, -22f, 6f, -44f, 74f, -66f + flap)
            cubicTo(44f, -34f, 42f, -14f, 62f, -3f)
            lineTo(190f, 0f); lineTo(62f, 3f)
            cubicTo(42f, 12f, 44f, 26f, 74f, 50f - flap * 0.7f)
            cubicTo(6f, 32f, -34f, 16f, -58f, 0f)
            close()
            moveTo(-52f, -5f); lineTo(-74f, -11f); lineTo(-56f, 0f); close()
            moveTo(-52f, 5f); lineTo(-74f, 9f); lineTo(-56f, 0f); close()
        }
        drawPath(ray, Color(0xFF2487A0).copy(alpha = 0.75f))
    }
}

private fun reefGround(x: Float, base: Float, rise: Float) =
    base - rise * (0.55f + 0.3f * sin(x / 1400f * TAU * 2f + 0.6f) + 0.15f * sin(x / 1400f * TAU * 5f + 2f))

private fun DrawScope.reefNear(p: RidePalette, s: Stage) {
    val base = s.road
    val rise = s.road * 0.11f
    scroll(s.ride * 24f, 1400f) {
        // 沉在水底、仍亮着灯的灯塔
        withTransform({ translate(1010f, reefGround(1010f, base, rise) + 14f); rotate(-10f, Offset.Zero) }) {
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF2A8).copy(alpha = 0.55f), Color.Transparent), Offset(0f, -196f), 110f), 110f, Offset(0f, -196f))
            val tower = Path().apply { moveTo(-26f, 0f); lineTo(26f, 0f); lineTo(16f, -180f); lineTo(-16f, -180f); close() }
            drawPath(tower, Color(0xFFC9EFE4))
            clipPath(tower) {
                drawRect(p.accent.copy(alpha = 0.75f), Offset(-30f, -150f), Size(60f, 32f))
                drawRect(p.accent.copy(alpha = 0.75f), Offset(-30f, -78f), Size(60f, 32f))
            }
            drawRect(Color(0xFF1B6C88), Offset(-20f, -186f), Size(40f, 8f))
            drawRect(Color(0xFFFFF2A8), Offset(-13f, -212f), Size(26f, 26f))
            val roof = Path().apply { moveTo(-19f, -212f); lineTo(0f, -236f); lineTo(19f, -212f); close() }
            drawPath(roof, Color(0xFF1B6C88))
        }
        val mound = Path().apply {
            moveTo(0f, reefGround(0f, base, rise))
            for (i in 1..56) lineTo(i * 25f, reefGround(i * 25f, base, rise))
            lineTo(1400f, s.road + 20f); lineTo(0f, s.road + 20f); close()
        }
        drawPath(mound, p.near)
        repeat(9) { i ->
            val x = 70f + i * 152f
            val y = reefGround(x, base, rise) + 6f
            when (i % 3) {
                0 -> repeat(3) { k -> kelp(x + k * 22f, y, 150f + k * 55f + (i % 2) * 40f, s.clock * 1.1f + i + k, Color(0xFF167F86), 9f) }
                1 -> {
                    val coral = if (i % 2 == 0) p.accent else Color(0xFFFFC2A0)
                    repeat(5) { k ->
                        val lean = (k - 2) * 15f
                        drawLine(coral.copy(alpha = 0.9f), Offset(x + lean * 0.3f, y), Offset(x + lean, y - 46f - (k % 2) * 22f), 10f, StrokeCap.Round)
                        drawCircle(coral, 8f, Offset(x + lean, y - 50f - (k % 2) * 22f))
                    }
                }
                else -> repeat(3) { k ->
                    drawLine(Color(0xFFFFE08A).copy(alpha = 0.85f), Offset(x + k * 20f, y), Offset(x + k * 20f, y - 34f - k * 14f), 15f, StrokeCap.Round)
                    drawCircle(Color(0xFF1B6C88), 4f, Offset(x + k * 20f, y - 36f - k * 14f))
                }
            }
        }
    }
}

private fun DrawScope.reefSand(p: RidePalette, s: Stage) {
    drawRect(
        Brush.verticalGradient(listOf(p.road, Color(0xFFC8B27F)), s.road - 10f, s.height),
        Offset(0f, s.road - 10f), Size(1000f, s.height - s.road + 10f),
    )
    drawLine(White.copy(alpha = 0.55f), Offset(0f, s.road - 9f), Offset(1000f, s.road - 9f), 3f)
    val depth = s.height - s.road
    scroll(s.ride * 200f, 1200f) {
        repeat(4) { row ->
            val y = s.road + depth * (0.1f + row * 0.2f)
            repeat(4) { i ->
                val x = i * 300f + row * 95f
                val ripple = Path().apply {
                    moveTo(x, y); quadraticTo(x + 35f, y - 9f, x + 70f, y); quadraticTo(x + 105f, y + 9f, x + 140f, y)
                }
                drawPath(ripple, Color(0xFFB69C66).copy(alpha = 0.55f), style = Stroke(3f, cap = StrokeCap.Round))
            }
        }
        drawPath(star(Offset(250f, s.road + depth * 0.18f), 22f, 9f, 5), p.accent)
        drawPath(star(Offset(880f, s.road + depth * 0.42f), 17f, 7f, 5), Color(0xFFFFA36B))
        drawArc(Color(0xFFFFF7E2), 180f, 180f, true, Offset(590f, s.road + depth * 0.25f), Size(44f, 40f))
        repeat(3) { k ->
            drawLine(Color(0xFFD8B98A), Offset(612f, s.road + depth * 0.25f + 20f), Offset(598f + k * 14f, s.road + depth * 0.25f + 4f), 2f)
        }
        repeat(7) { i -> drawOval(Color(0xFFA98F5E).copy(alpha = 0.6f), Offset(60f + i * 171f, s.road + depth * (0.08f + (i * 3 % 5) * 0.11f)), Size(16f, 7f)) }
    }
}

private fun DrawScope.reefFront(s: Stage) {
    val depth = s.height - s.road
    scroll(s.ride * 240f, 1500f) {
        repeat(4) { k -> kelp(150f + k * 34f, s.height + 10f, depth * (0.75f + (k % 2) * 0.3f), s.clock * 1.3f + k, Color(0xFF0E5B6D), 17f) }
        repeat(3) { k -> kelp(1020f + k * 36f, s.height + 10f, depth * (0.6f + (k % 2) * 0.25f), s.clock * 1.3f + k + 2f, Color(0xFF0E5B6D), 17f) }
    }
    repeat(3) { i ->
        val center = Offset(
            wrap(i * 430f + 240f + s.ride * 70f + s.clock * 9f, 1300f) - 150f,
            s.road * (0.2f + i * 0.13f) + sin(s.clock * 0.9f + i * 2f) * 26f,
        )
        val pulse = sin(s.clock * 2.4f + i * 1.7f)
        val width = 34f + pulse * 4f
        val tint = if (i == 1) Color(0xFFFFC4D6) else White
        repeat(4) { k ->
            val x = center.x - width * 0.6f + k * width * 0.4f
            val tentacle = Path().apply {
                moveTo(x, center.y)
                quadraticTo(x + 8f * pulse, center.y + 22f, x, center.y + 40f)
                quadraticTo(x - 8f * pulse, center.y + 58f, x + 3f, center.y + 72f - pulse * 6f)
            }
            drawPath(tentacle, tint.copy(alpha = 0.5f), style = Stroke(2.5f, cap = StrokeCap.Round))
        }
        drawArc(tint.copy(alpha = 0.62f), 180f, 180f, true, Offset(center.x - width, center.y - 28f + pulse * 2f), Size(width * 2f, 56f - pulse * 4f))
        drawArc(White.copy(alpha = 0.5f), 205f, 60f, false, Offset(center.x - width * 0.7f, center.y - 20f), Size(width * 1.4f, 40f), style = Stroke(3f, cap = StrokeCap.Round))
    }
    repeat(14) { i ->
        val x = wrap(i * 97f + s.ride * 150f, 1100f) - 50f + sin(s.clock * 2f + i) * 6f
        val y = s.height - wrap(s.clock * (70f + (i % 4) * 18f) + i * 173f, s.height)
        val radius = 3f + (i % 4) * 2f
        drawCircle(White.copy(alpha = 0.6f), radius, Offset(x, y), style = Stroke(1.8f))
        drawCircle(White.copy(alpha = 0.7f), radius * 0.22f, Offset(x - radius * 0.35f, y - radius * 0.35f))
    }
}

// ───────────────────────── 小星球 ─────────────────────────

private class Globe(val center: Offset, val radius: Float, val top: Float, val turn: Float)

// 两只车轮同时与球面相切：球顶比轮底高 150² / 2(R + 91) ≈ 11.35，景物转速与轮缘线速度一致。
private fun Stage.globe(): Globe {
    val top = road - 16.35f * rider
    return Globe(Offset(500f, top + 900f * rider), 900f * rider, top, ride * 246f / 900f * 180f / PI.toFloat())
}

private fun Stage.ridge(): Globe {
    val top = road + (height - road) * 0.6f
    return Globe(Offset(500f, top + 1500f * rider), 1500f * rider, top, ride * 369f / 1500f * 180f / PI.toFloat())
}

/** 把景物立在球面的等分槽位上，槽内原点是地面、-y 朝外。 */
private fun DrawScope.onGlobe(g: Globe, slots: Int, unit: Float, block: DrawScope.(Int) -> Unit) {
    val step = 360f / slots
    repeat(slots) { i ->
        val angle = wrap(i * step + g.turn + 180f, 360f) - 180f
        if (abs(angle) < 100f) {
            withTransform({ rotate(angle, g.center); translate(500f, g.top); scale(unit, unit, Offset.Zero) }) { block(i) }
        }
    }
}

private fun DrawScope.orbitSky(p: RidePalette, s: Stage) {
    drawRect(
        Brush.verticalGradient(0f to p.sky, 0.65f to Color(0xFF3B3580), 1f to Color(0xFF7D5CA8), endY = s.road + 40f),
        size = Size(1000f, s.height),
    )
    repeat(70) { i ->
        val at = Offset(wrap(i * 137.5f + s.ride * 5f, 1000f), (i * 83 % 997) / 997f * s.height)
        val glow = 0.3f + 0.3f * (1f + sin(s.clock * 1.4f + i * 2.1f))
        if (i % 9 == 0) drawPath(star(at, 10f, 2.6f, 4), p.line.copy(alpha = glow))
        else drawCircle(p.line.copy(alpha = glow), if (i % 4 == 0) 2.3f else 1.3f, at)
    }
    val size = 0.55f + 0.45f * s.rider
    val giant = Offset(235f, s.road * 0.3f)
    val radius = 96f * size
    val ring = Color(0xFFFFE9C4).copy(alpha = 0.85f)
    rotate(-20f, giant) {
        val bounds = Rect(giant.x - radius * 1.9f, giant.y - radius * 0.45f, giant.x + radius * 1.9f, giant.y + radius * 0.45f)
        drawArc(ring, 180f, 180f, false, bounds.topLeft, bounds.size, style = Stroke(radius * 0.17f))
        drawCircle(Brush.linearGradient(listOf(Color(0xFFFFDB8E), Color(0xFFEE8468)), giant - Offset(radius, radius), giant + Offset(radius, radius)), radius, giant)
        clipPath(Path().apply { addOval(Rect(giant, radius)) }) {
            drawLine(Color(0xFFE9795F).copy(alpha = 0.5f), giant + Offset(-radius, -radius * 0.3f), giant + Offset(radius, -radius * 0.3f), radius * 0.16f)
            drawLine(Color(0xFFFFF0C2).copy(alpha = 0.5f), giant + Offset(-radius, radius * 0.38f), giant + Offset(radius, radius * 0.38f), radius * 0.24f)
        }
        drawArc(ring, 0f, 180f, false, bounds.topLeft, bounds.size, style = Stroke(radius * 0.17f))
        drawArc(Color(0xFFFFB8A0).copy(alpha = 0.7f), 0f, 180f, false, bounds.topLeft + Offset(radius * 0.3f, radius * 0.07f), Size(bounds.width - radius * 0.6f, bounds.height - radius * 0.14f), style = Stroke(radius * 0.05f))
    }
    val moon = Offset(815f, s.road * 0.15f)
    drawPath(
        Path.combine(
            PathOperation.Difference,
            Path().apply { addOval(Rect(moon, 36f * size)) },
            Path().apply { addOval(Rect(moon + Offset(15f, -9f) * size, 32f * size)) },
        ),
        p.line,
    )
    val streak = s.clock % 7f / 0.9f
    if (streak < 1f) {
        val head = Offset(930f - streak * 430f, s.road * 0.06f + streak * 200f)
        val tail = head + Offset(120f, -56f)
        drawLine(Brush.linearGradient(listOf(p.line, Color.Transparent), head, tail), head, tail, 3.5f, StrokeCap.Round)
    }
}

private fun DrawScope.orbitFar(p: RidePalette, s: Stage) {
    val size = 0.55f + 0.45f * s.rider
    repeat(3) { i ->
        val radius = (if (i == 2) 46f else 30f - i * 6f) * size
        val at = Offset(
            wrap(180f + i * 450f + s.ride * 9f, 1300f) - 150f,
            s.road * (0.56f - i * 0.045f) + sin(s.clock * 0.7f + i * 2f) * 7f,
        )
        val color = when (i) { 0 -> Color(0xFFF09A92); 1 -> Color(0xFF8FD6C1); else -> p.far }
        if (i == 0) drawArc(p.line.copy(alpha = 0.7f), 180f, 180f, false, at - Offset(radius * 1.7f, radius * 0.35f), Size(radius * 3.4f, radius * 0.7f), style = Stroke(4f))
        drawCircle(color, radius, at)
        clipPath(Path().apply { addOval(Rect(at, radius)) }) {
            drawCircle(Color(0xFF1B1F52).copy(alpha = 0.28f), radius, at + Offset(radius * 0.5f, radius * 0.45f))
        }
        when (i) {
            0 -> drawArc(p.line.copy(alpha = 0.7f), 0f, 180f, false, at - Offset(radius * 1.7f, radius * 0.35f), Size(radius * 3.4f, radius * 0.7f), style = Stroke(4f))
            1 -> {
                drawLine(p.line, at + Offset(0f, -radius), at + Offset(0f, -radius - 26f * size), 2.5f)
                val flag = Path().apply {
                    moveTo(at.x, at.y - radius - 26f * size); lineTo(at.x + 18f * size, at.y - radius - 20f * size)
                    lineTo(at.x, at.y - radius - 13f * size); close()
                }
                drawPath(flag, p.accent)
            }
            else -> translate(at.x, at.y - radius + 3f) { scale(0.42f * size, Offset.Zero) { orbitHouse(p); translate(52f, 0f) { orbitTree(1) } } }
        }
    }
}

private fun DrawScope.orbitTree(i: Int) {
    drawLine(Color(0xFF8A5A7A), Offset(0f, 8f), Offset(0f, -72f), 9f, StrokeCap.Round)
    drawCircle(if (i % 2 == 0) Color(0xFF9BE0B5) else Color(0xFFFFB2A1), 36f, Offset(0f, -96f))
    drawCircle(White.copy(alpha = 0.28f), 13f, Offset(-11f, -107f))
}

private fun DrawScope.orbitHouse(p: RidePalette) {
    drawRect(Color(0xFFFFE9C9), Offset(-32f, -48f), Size(64f, 58f))
    val roof = Path().apply { moveTo(-42f, -48f); lineTo(0f, -88f); lineTo(42f, -48f); close() }
    drawPath(roof, p.accent)
    drawRect(Color(0xFFFFD45E), Offset(-22f, -36f), Size(18f, 18f))
    drawRect(Color(0xFF8A5A7A), Offset(6f, -30f), Size(16f, 34f))
}

private fun DrawScope.orbitMill(p: RidePalette, clock: Float) {
    val tower = Path().apply { moveTo(-20f, 8f); lineTo(20f, 8f); lineTo(11f, -96f); lineTo(-11f, -96f); close() }
    drawPath(tower, Color(0xFFEADCF5))
    drawRect(Color(0xFF8A5A7A), Offset(-7f, -22f), Size(14f, 30f))
    val hub = Offset(0f, -96f)
    rotate(clock * 70f, hub) {
        repeat(4) { blade ->
            rotate(blade * 90f, hub) {
                drawLine(p.line, hub, hub + Offset(0f, -62f), 4f, StrokeCap.Round)
                drawRect(p.accent, hub + Offset(3f, -62f), Size(17f, 42f))
            }
        }
    }
    drawCircle(p.line, 6f, hub)
}

private fun DrawScope.orbitMushroom(i: Int) {
    drawLine(Color(0xFFFFF1DA), Offset(0f, 8f), Offset(0f, -40f), 18f, StrokeCap.Round)
    val cap = if (i % 2 == 0) Color(0xFFFF8A7A) else Color(0xFFFFC56B)
    drawArc(cap, 180f, 180f, true, Offset(-44f, -84f), Size(88f, 76f))
    drawCircle(White.copy(alpha = 0.85f), 7f, Offset(-16f, -62f))
    drawCircle(White.copy(alpha = 0.85f), 5f, Offset(12f, -70f))
    drawCircle(White.copy(alpha = 0.85f), 4f, Offset(24f, -54f))
}

private fun DrawScope.orbitLighthouse(p: RidePalette, clock: Float) {
    val lamp = Offset(0f, -144f)
    drawCircle(Color(0xFFFFE08A).copy(alpha = 0.2f + 0.1f * sin(clock * 3f)), 62f, lamp)
    val tower = Path().apply { moveTo(-18f, 8f); lineTo(18f, 8f); lineTo(11f, -130f); lineTo(-11f, -130f); close() }
    drawPath(tower, Color(0xFFFFF1DA))
    clipPath(tower) {
        drawRect(p.accent, Offset(-20f, -104f), Size(40f, 26f))
        drawRect(p.accent, Offset(-20f, -48f), Size(40f, 26f))
    }
    drawRect(Color(0xFFFFE08A), Offset(-13f, -158f), Size(26f, 28f))
    val roof = Path().apply { moveTo(-18f, -158f); lineTo(0f, -180f); lineTo(18f, -158f); close() }
    drawPath(roof, p.accent)
}

private fun DrawScope.orbitBack(p: RidePalette, s: Stage) {
    val g = s.globe()
    drawCircle(Color(0xFF9DE5D0).copy(alpha = 0.08f), g.radius + 52f * s.rider, g.center)
    drawCircle(Color(0xFF9DE5D0).copy(alpha = 0.13f), g.radius + 24f * s.rider, g.center)
    onGlobe(g, 20, s.rider) { i ->
        when (i * 3 % 5) {
            0 -> orbitTree(i)
            1 -> orbitHouse(p)
            2 -> orbitMill(p, s.clock)
            3 -> orbitMushroom(i)
            else -> orbitLighthouse(p, s.clock)
        }
    }
}

private fun DrawScope.orbitGround(p: RidePalette, s: Stage) {
    val g = s.globe()
    drawCircle(
        Brush.verticalGradient(listOf(p.road, p.near, Color(0xFF235A7C)), g.top, g.top + g.radius * 0.55f),
        g.radius, g.center,
    )
    drawCircle(White.copy(alpha = 0.4f), g.radius - 2f * s.rider, g.center, style = Stroke(4f * s.rider))
    onGlobe(g, 30, s.rider) { i ->
        drawLine(p.line.copy(alpha = 0.6f), Offset(-24f, 12f), Offset(24f, 12f), 4f, StrokeCap.Round)
        if (i % 3 == 0) {
            val deep = 48f + (i % 4) * 34f
            drawOval(Color(0xFF1E5A78).copy(alpha = 0.4f), Offset(-56f, deep), Size(112f, 32f))
            drawArc(White.copy(alpha = 0.22f), 20f, 140f, false, Offset(-56f, deep), Size(112f, 32f), style = Stroke(3f))
        }
        if (i % 2 == 1) {
            drawLine(Color(0xFF2B7F86), Offset(62f, 2f), Offset(62f, -17f), 3f)
            drawCircle(if (i % 4 == 1) Color(0xFFFFE08A) else p.accent, 7f, Offset(62f, -19f))
        }
    }
}

private fun DrawScope.orbitFront(p: RidePalette, s: Stage) {
    val g = s.ridge()
    drawCircle(Color(0xFF1B3F69), g.radius, g.center)
    drawCircle(Color(0xFF5FA6B8).copy(alpha = 0.55f), g.radius - 2f * s.rider, g.center, style = Stroke(4f * s.rider))
    onGlobe(g, 24, s.rider) { i ->
        when {
            i == 0 -> {
                // 停在路边的小火箭
                val fins = Path().apply {
                    moveTo(-24f, -34f); lineTo(-52f, 6f); lineTo(-18f, -4f); close()
                    moveTo(24f, -34f); lineTo(52f, 6f); lineTo(18f, -4f); close()
                }
                drawPath(fins, p.accent)
                val body = Path().apply {
                    moveTo(0f, -150f); cubicTo(34f, -112f, 34f, -40f, 22f, 0f)
                    lineTo(-22f, 0f); cubicTo(-34f, -40f, -34f, -112f, 0f, -150f); close()
                }
                drawPath(body, Color(0xFFFFF1DA))
                clipPath(body) { drawRect(p.accent, Offset(-40f, -150f), Size(80f, 44f)) }
                drawCircle(Color(0xFF7FD3F0), 13f, Offset(0f, -76f))
                drawCircle(Color(0xFF1B3F69), 13f, Offset(0f, -76f), style = Stroke(4f))
            }
            i % 3 == 1 -> {
                drawCircle(Color(0xFF2A5F7D), 34f, Offset(-22f, -8f))
                drawCircle(Color(0xFF2A5F7D), 46f, Offset(18f, -18f))
                drawCircle(Color(0xFF34708C), 26f, Offset(52f, -4f))
            }
            i % 3 == 2 -> {
                val bloom = Offset(sin(s.clock * 1.6f + i) * 5f, -64f)
                val stem = Path().apply { moveTo(0f, 6f); quadraticTo(-10f, -30f, bloom.x, bloom.y) }
                drawPath(stem, Color(0xFF5FA6B8), style = Stroke(4f, cap = StrokeCap.Round))
                drawCircle(Color(0xFFFFE08A).copy(alpha = 0.22f), 30f, bloom)
                drawCircle(Color(0xFFFFE08A), 11f, bloom)
            }
            else -> drawArc(Color(0xFF34708C), 180f, 180f, true, Offset(-40f, -30f), Size(80f, 60f))
        }
    }
}

// ───────────────────────── 云端 ─────────────────────────

/** 平底积云，y 是底边。 */
private fun DrawScope.puff(x: Float, y: Float, width: Float, color: Color) {
    val height = width * 0.22f
    drawRoundRect(color, Offset(x, y - height), Size(width, height), CornerRadius(height / 2f))
    drawCircle(color, width * 0.2f, Offset(x + width * 0.3f, y - height))
    drawCircle(color, width * 0.26f, Offset(x + width * 0.55f, y - height * 1.35f))
    drawCircle(color, width * 0.16f, Offset(x + width * 0.78f, y - height * 0.95f))
}

private fun DrawScope.balloon(c: Offset, r: Float, body: Color, stripe: Color, line: Color) {
    val envelope = Path().apply {
        moveTo(c.x - r, c.y)
        cubicTo(c.x - r, c.y - r * 1.35f, c.x + r, c.y - r * 1.35f, c.x + r, c.y)
        cubicTo(c.x + r, c.y + r * 0.7f, c.x + r * 0.3f, c.y + r * 1.1f, c.x + r * 0.22f, c.y + r * 1.45f)
        lineTo(c.x - r * 0.22f, c.y + r * 1.45f)
        cubicTo(c.x - r * 0.3f, c.y + r * 1.1f, c.x - r, c.y + r * 0.7f, c.x - r, c.y)
        close()
    }
    drawPath(envelope, body)
    clipPath(envelope) {
        drawOval(stripe, Offset(c.x - r * 0.42f, c.y - r * 1.3f), Size(r * 0.84f, r * 3.2f))
        drawOval(White.copy(alpha = 0.25f), Offset(c.x - r * 0.95f, c.y - r * 1.1f), Size(r * 0.5f, r * 1.5f))
    }
    drawLine(line.copy(alpha = 0.6f), Offset(c.x - r * 0.2f, c.y + r * 1.45f), Offset(c.x - r * 0.15f, c.y + r * 1.82f), 1.5f)
    drawLine(line.copy(alpha = 0.6f), Offset(c.x + r * 0.2f, c.y + r * 1.45f), Offset(c.x + r * 0.15f, c.y + r * 1.82f), 1.5f)
    drawRoundRect(Color(0xFF9C6B4A), Offset(c.x - r * 0.2f, c.y + r * 1.8f), Size(r * 0.4f, r * 0.3f), CornerRadius(3f))
}

private fun DrawScope.cloudSky(p: RidePalette, s: Stage) {
    drawRect(
        Brush.verticalGradient(0f to p.sky, 0.6f to Color(0xFFCBE9F6), 1f to Color(0xFFFFE2C4), endY = s.road),
        size = Size(1000f, s.height),
    )
    val sun = Offset(735f, s.road * 0.4f)
    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF6C9), Color(0x00FFF6C9)), sun, 280f), 280f, sun)
    repeat(12) { i ->
        rotate(s.clock * 4f + i * 30f, sun) {
            val ray = Path().apply { moveTo(sun.x, sun.y); lineTo(sun.x - 16f, sun.y - 360f); lineTo(sun.x + 16f, sun.y - 360f); close() }
            drawPath(ray, Brush.verticalGradient(listOf(Color.Transparent, White.copy(alpha = 0.2f)), sun.y - 360f, sun.y))
        }
    }
    drawCircle(Color(0xFFFFE38A), 82f, sun)
    drawCircle(Color(0xFFFFF3C4), 64f, sun)
    repeat(3) { i ->
        val x = wrap(i * 430f + s.ride * 5f, 1300f) - 200f
        val y = s.road * (0.1f + i * 0.07f)
        drawLine(White.copy(alpha = 0.6f), Offset(x, y), Offset(x + 190f, y), 6f, StrokeCap.Round)
        drawLine(White.copy(alpha = 0.4f), Offset(x + 60f, y + 14f), Offset(x + 250f, y + 14f), 4f, StrokeCap.Round)
    }
}

private fun DrawScope.cloudFar(p: RidePalette, s: Stage) {
    val base = s.road
    scroll(s.ride * 9f, 1400f) {
        listOf(0f to 520f, 430f to 380f, 760f to 600f, 1180f to 300f).forEach { (x, width) ->
            puff(x, base, width * 1.03f, Color(0xFFD9DDF5))
            puff(x, base, width, p.far.copy(alpha = 0.9f))
        }
    }
    repeat(3) { i ->
        val at = Offset(
            wrap(260f + i * 410f + s.ride * 14f + s.clock * 3f, 1300f) - 150f,
            s.road * (0.3f + (i * 2 % 3) * 0.12f) + sin(s.clock * 0.8f + i * 2f) * 10f,
        )
        val (body, stripe) = when (i) {
            0 -> Color(0xFFEF5B5B) to Color(0xFFFFE9C9)
            1 -> Color(0xFF5FA8E8) to Color(0xFFFFD45E)
            else -> Color(0xFFB197FC) to Color(0xFFFFF4E4)
        }
        balloon(at, (46f - i * 9f) * (0.6f + 0.4f * s.rider), body, stripe, p.line)
    }
    // 纸飞机顺着骑行方向滑过
    translate(1100f - wrap(s.clock * 46f + 380f, 1400f), s.road * 0.2f + sin(s.clock * 1.1f) * 22f) {
        rotate(sin(s.clock * 1.1f + 1.4f) * 8f, Offset.Zero) {
            val wing = Path().apply { moveTo(-36f, 0f); lineTo(36f, -17f); lineTo(14f, 2f); close() }
            val fold = Path().apply { moveTo(-36f, 0f); lineTo(14f, 2f); lineTo(20f, 15f); close() }
            drawPath(fold, Color(0xFFC9D2F0))
            drawPath(wing, White)
        }
    }
}

private fun DrawScope.island(center: Offset, width: Float, p: RidePalette, blossom: Boolean) {
    val rock = Path().apply {
        moveTo(center.x - width / 2f, center.y); lineTo(center.x + width / 2f, center.y)
        cubicTo(center.x + width * 0.35f, center.y + width * 0.25f, center.x + width * 0.1f, center.y + width * 0.3f, center.x + width * 0.02f, center.y + width * 0.62f)
        cubicTo(center.x - width * 0.08f, center.y + width * 0.3f, center.x - width * 0.36f, center.y + width * 0.22f, center.x - width / 2f, center.y)
        close()
    }
    drawPath(rock, Brush.verticalGradient(listOf(Color(0xFF9A86B4), Color(0xFF6F6294)), center.y, center.y + width * 0.62f))
    drawRect(
        Brush.verticalGradient(listOf(Color(0xFFD9F3FF), Color(0x00D9F3FF)), center.y, center.y + width * 0.95f),
        Offset(center.x + width * 0.27f, center.y), Size(width * 0.08f, width * 0.95f),
    )
    drawOval(p.near, Offset(center.x - width / 2f, center.y - width * 0.07f), Size(width, width * 0.14f))
    drawOval(Color(0xFFA5DE9A), Offset(center.x - width * 0.42f, center.y - width * 0.065f), Size(width * 0.84f, width * 0.08f))
    val trunk = Offset(center.x - width * 0.14f, center.y - width * 0.02f)
    drawLine(Color(0xFF7A5647), trunk, trunk + Offset(width * 0.02f, -width * 0.22f), width * 0.03f, StrokeCap.Round)
    val leaf = if (blossom) Color(0xFFFFA8BE) else Color(0xFF58AE72)
    drawCircle(leaf, width * 0.12f, trunk + Offset(-width * 0.05f, -width * 0.27f))
    drawCircle(leaf, width * 0.14f, trunk + Offset(width * 0.07f, -width * 0.31f))
    drawCircle(White.copy(alpha = 0.25f), width * 0.05f, trunk + Offset(width * 0.04f, -width * 0.36f))
    if (!blossom) return
    val house = Offset(center.x + width * 0.12f, center.y - width * 0.02f)
    drawRect(Color(0xFFFFF4E4), house + Offset(-width * 0.07f, -width * 0.1f), Size(width * 0.14f, width * 0.1f))
    val roof = Path().apply {
        moveTo(house.x - width * 0.09f, house.y - width * 0.1f); lineTo(house.x, house.y - width * 0.18f)
        lineTo(house.x + width * 0.09f, house.y - width * 0.1f); close()
    }
    drawPath(roof, p.accent)
}

private fun DrawScope.cloudNear(p: RidePalette, s: Stage) {
    val size = 0.5f + 0.5f * s.rider
    scroll(s.ride * 24f, 1700f) {
        island(Offset(300f, s.road * 0.6f + sin(s.clock * 0.7f) * 9f), 300f * size, p, blossom = true)
        island(Offset(1230f, s.road * 0.47f + sin(s.clock * 0.7f + 2f) * 9f), 190f * size, p, blossom = false)
        puff(560f, s.road + 6f, 420f, White)
        puff(1350f, s.road + 6f, 320f, White)
    }
}

private val Rainbow = listOf(
    Color(0xFFFF6B6B), Color(0xFFFFA94D), Color(0xFFFFE066), Color(0xFF8CE99A), Color(0xFF74C0FC), Color(0xFFB197FC),
)

private fun DrawScope.cloudRoad(s: Stage) {
    val band = 22f * (0.5f + 0.5f * s.rider)
    val top = s.road - 6f * s.rider
    val sea = top + band * Rainbow.size
    Rainbow.forEachIndexed { i, color -> drawRect(color, Offset(0f, top + i * band), Size(1000f, band + 1f)) }
    drawLine(White.copy(alpha = 0.65f), Offset(0f, top + 2f), Offset(1000f, top + 2f), 3f)
    scroll(s.ride * 200f, 1200f) {
        repeat(6) { i ->
            val glint = 0.5f + 0.4f * sin(s.clock * 4f + i * 1.9f)
            drawPath(star(Offset(i * 200f + 60f, top + band * (0.5f + i * 2 % 5)), 9f, 2.4f, 4), White.copy(alpha = glint))
        }
    }
    scroll(s.ride * 170f, 1200f) { repeat(8) { i -> puff(i * 150f - 20f, sea + 62f, 170f, Color(0xFFD5DFF6)) } }
    drawRect(
        Brush.verticalGradient(listOf(Color(0xFFE6EEFB), Color(0xFFC3D5F1)), sea + 50f, s.height),
        Offset(0f, sea + 50f), Size(1000f, s.height - sea),
    )
    scroll(s.ride * 215f, 1200f) { repeat(8) { i -> puff(i * 150f + 60f, sea + 98f, 190f, White) } }
    val depth = s.height - sea
    scroll(s.ride * 260f, 1300f) {
        repeat(5) { i ->
            val y = sea + depth * 0.5f + i % 2 * 34f
            puff(i * 260f, y + 14f, 340f, Color(0xFFCBD9F3))
            puff(i * 260f, y, 330f, Color(0xFFF6F9FE))
        }
    }
}

private fun DrawScope.cloudFront(s: Stage) {
    val y = s.road + (s.height - s.road) * 0.88f
    scroll(s.ride * 330f, 1700f) {
        puff(120f, y + 62f, 550f, Color(0xFFBFD0EE))
        puff(120f, y + 44f, 540f, White)
        puff(1040f, y + 88f, 440f, Color(0xFFBFD0EE))
        puff(1040f, y + 70f, 430f, White)
    }
}

// ───────────────────────── 骑手与车的配件 ─────────────────────────

/** 头部坐标系内的装备，绘制在轮廓线之上；鸟面朝 +x。 */
internal fun DrawScope.drawWorldGear(style: RideStyle, p: RidePalette, pigment: Float, cycle: Float, time: Float) {
    val line = p.line.copy(alpha = pigment)
    when (style) {
        RideStyle.Reef -> {
            val tube = Path().apply { moveTo(76f, -172f); quadraticTo(30f, -166f, 30f, -214f); lineTo(30f, -268f) }
            drawPath(tube, line, style = Stroke(12f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(tube, p.accent.copy(alpha = pigment), style = Stroke(6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawLine(Color(0xFFFFE08A).copy(alpha = pigment), Offset(30f, -254f), Offset(30f, -246f), 6f)
            drawLine(line, Offset(24f, -202f), Offset(70f, -199f), 6f)
            val lens = Path().apply { addRoundRect(RoundRect(Rect(64f, -216f, 106f, -180f), CornerRadius(13f))) }
            inkShape(lens, Color(0xFFBDF4FF).copy(alpha = 0.5f * pigment), line, 4f)
            drawLine(White.copy(alpha = 0.8f * pigment), Offset(72f, -206f), Offset(80f, -210f), 3f, StrokeCap.Round)
            repeat(5) { i ->
                val rise = wrap(time * 0.55f + i / 5f, 1f)
                drawCircle(
                    White.copy(alpha = (1f - rise) * 0.85f * pigment), 3f + rise * 7f,
                    Offset(30f - rise * 80f - sin(rise * 9f + i) * 5f, -280f - rise * 130f), style = Stroke(2.2f),
                )
            }
        }
        RideStyle.Orbit -> {
            rotate(-14f, Offset(-100f, -100f)) {
                val tank = Path().apply { addRoundRect(RoundRect(Rect(-120f, -160f, -84f, -94f), CornerRadius(15f))) }
                inkShape(tank, p.accent.copy(alpha = pigment), line, 3.5f)
                drawLine(line, Offset(-120f, -138f), Offset(-84f, -138f), 3f)
                drawLine(line, Offset(-120f, -116f), Offset(-84f, -116f), 3f)
            }
            val hose = Path().apply { moveTo(-112f, -158f); quadraticTo(-50f, -214f, 20f, -152f) }
            drawPath(hose, line, style = Stroke(3.5f, cap = StrokeCap.Round))
            val dome = Offset(72f, -197f)
            drawCircle(White.copy(alpha = 0.13f * pigment), 53f, dome)
            drawCircle(line, 53f, dome, style = Stroke(3.5f))
            drawArc(White.copy(alpha = 0.75f * pigment), 195f, 55f, false, dome - Offset(41f, 41f), Size(82f, 82f), style = Stroke(5f, cap = StrokeCap.Round))
            drawLine(line, Offset(16f, -149f), Offset(104f, -151f), 15f, StrokeCap.Round)
            drawLine(p.accent.copy(alpha = pigment), Offset(16f, -149f), Offset(104f, -151f), 8f, StrokeCap.Round)
            drawLine(line, Offset(56f, -248f), Offset(46f, -276f), 3f)
            drawCircle(Color(0xFFFFE08A).copy(alpha = pigment * (0.45f + 0.55f * abs(sin(time * 3f)))), 6f, Offset(46f, -278f))
        }
        RideStyle.Cloud -> {
            val cap = Path().apply {
                moveTo(22f, -174f)
                cubicTo(12f, -204f, 24f, -236f, 56f, -238f)
                cubicTo(84f, -242f, 108f, -228f, 111f, -206f)
                lineTo(72f, -208f)
                quadraticTo(56f, -192f, 52f, -170f)
                close()
            }
            inkShape(cap, Color(0xFF9A5B3C).copy(alpha = pigment), line, 3.5f)
            drawLine(line, Offset(26f, -214f), Offset(100f, -222f), 5f)
            drawCircle(Color(0xFFBDE6FF).copy(alpha = pigment), 11f, Offset(90f, -221f))
            drawCircle(line, 11f, Offset(90f, -221f), style = Stroke(3.5f))
            drawLine(White.copy(alpha = 0.9f * pigment), Offset(85f, -224f), Offset(89f, -227f), 2.5f, StrokeCap.Round)
            drawScarf(p, cycle, angular = false)
        }
        else -> Unit
    }
}

/** 车身坐标系内的随行物，绘制在鸟身之下；车头朝 +x。 */
internal fun DrawScope.drawWorldBike(style: RideStyle, p: RidePalette, time: Float, pigment: Float) {
    when (style) {
        RideStyle.Reef -> {
            // 跟着后轮游的小鱼
            repeat(3) { i ->
                val at = Offset(-262f - i * 58f + sin(time * 1.7f + i) * 8f, -60f + i * 34f + sin(time * 3f + i * 1.4f) * 9f)
                fish(at, 17f - i * 2f, Color(0xFFFF9A5C).copy(alpha = pigment), sin(time * 11f + i) * 4f)
                drawLine(White.copy(alpha = pigment), at + Offset(1f, -6f + i * 0.6f), at + Offset(1f, 6f - i * 0.6f), 4f)
                drawCircle(p.line.copy(alpha = pigment), 2.2f, at + Offset(10f, -2f))
            }
            repeat(6) { i ->
                val rise = wrap(time * 0.75f + i / 6f, 1f)
                drawCircle(
                    White.copy(alpha = (1f - rise) * 0.8f * pigment), 3f + (i % 3) * 2f,
                    Offset(-236f - rise * 150f, 232f - rise * 130f - (i % 3) * 9f), style = Stroke(2f),
                )
            }
        }
        RideStyle.Orbit -> repeat(7) { i ->
            val fade = wrap(time * 0.8f + i / 7f, 1f)
            val at = Offset(-244f - fade * 190f, 222f - fade * 80f - sin(i * 2.4f) * 22f)
            drawPath(star(at, 3f + 13f * (1f - fade), 1f + 3f * (1f - fade), 4), Color(0xFFFFE08A).copy(alpha = (1f - fade) * pigment))
        }
        RideStyle.Cloud -> {
            val knot = Offset(-96f, 12f)
            listOf(Color(0xFFEF5B5B), Color(0xFFFFD45E), Color(0xFF6DB8F2)).forEachIndexed { i, color ->
                val float = Offset(-204f - i * 46f + sin(time * 2.1f + i * 1.3f) * 9f, -224f + i * 40f - (i % 2) * 22f)
                val string = Path().apply {
                    moveTo(knot.x, knot.y)
                    quadraticTo((knot.x + float.x) / 2f + 22f, (knot.y + float.y) / 2f + 34f, float.x, float.y + 38f)
                }
                drawPath(string, p.line.copy(alpha = 0.7f), style = Stroke(1.8f))
                val tie = Path().apply { moveTo(float.x, float.y + 33f); lineTo(float.x - 6f, float.y + 43f); lineTo(float.x + 6f, float.y + 43f); close() }
                drawPath(tie, color)
                drawOval(color, float - Offset(28f, 35f), Size(56f, 70f))
                drawOval(p.line, float - Offset(28f, 35f), Size(56f, 70f), style = Stroke(3f))
                drawArc(White.copy(alpha = 0.7f), 200f, 50f, false, float - Offset(19f, 25f), Size(38f, 50f), style = Stroke(4f, cap = StrokeCap.Round))
            }
            repeat(4) { i ->
                val fade = wrap(time * 1.2f + i / 4f, 1f)
                drawCircle(White.copy(alpha = (1f - fade) * 0.9f), 8f + fade * 16f, Offset(-250f - fade * 130f, 236f - fade * 22f))
            }
            // 车头的螺旋桨，侧面看只剩一条忽长忽短的桨叶
            val hub = Offset(198f, -8f)
            drawLine(p.line, Offset(88f, -6f), hub, 5f, StrokeCap.Round)
            drawOval(p.line.copy(alpha = 0.1f), hub - Offset(6f, 52f), Size(12f, 104f))
            val swing = cos(time * 22f) * 50f
            drawLine(p.line, hub - Offset(0f, swing), hub + Offset(0f, swing), 10f, StrokeCap.Round)
            drawLine(Color(0xFFD9A066), hub - Offset(0f, swing), hub + Offset(0f, swing), 5f, StrokeCap.Round)
            drawCircle(p.line, 9f, hub + Offset(3f, 0f))
            drawCircle(p.accent.copy(alpha = pigment), 6f, hub + Offset(3f, 0f))
        }
        else -> Unit
    }
}
