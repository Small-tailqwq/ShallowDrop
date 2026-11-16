package io.github.smalltailqwq.shallowdrop.ui.easteregg

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal fun DrawScope.drawPelican(
    style: RideStyle,
    palette: RidePalette,
    head: PelicanHead,
    time: Float,
    pigment: Float,
    bodyReveal: Float,
    morph: Float,
) {
    val p = palette.copy(
        feather = lerp(palette.line, palette.feather, pigment),
        wing = lerp(palette.line, palette.wing, pigment),
        beak = lerp(palette.line, palette.beak, pigment),
        accent = lerp(palette.line, palette.accent, pigment),
    )
    val cycle = time * 2f * PI.toFloat() / 1.65f
    val bob = sin(cycle * 2f) * 2.3f
    val pedal = Offset(cos(cycle) * 32f, 151f + sin(cycle) * 32f)
    val farPedal = Offset(-cos(cycle) * 32f, 151f - sin(cycle) * 32f)
    val outline = if (style == RideStyle.Ink) 3.2f else 4.5f

    translate(0f, bob) { drawPelicanHead(head, morph, p, pigment, time, style) }

    if (bodyReveal > 0f) {
        val mask = Path().apply {
            repeat(64) { point ->
                val angle = point * 2f * PI.toFloat() / 64f
                val radius = bodyReveal * 540f * (1f + sin(angle * 7f) * 0.04f)
                val x = 85f + cos(angle) * radius
                val y = -105f + sin(angle) * radius
                if (point == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        clipPath(mask) {
            drawOval(p.line.copy(alpha = 0.09f), Offset(-255f, 237f), Size(510f, 26f))
            drawLeg(Offset(-55f, 20f + bob), farPedal, p.beak, p.line.copy(alpha = 0.7f), outline)
            drawBicycle(style, p, time, pigment)
            translate(0f, bob) {
                rotate(sin(cycle - 0.6f) * 3f, Offset(-120f, -30f)) {
                    val tail = Path().apply {
                        moveTo(-112f, -12f); quadraticTo(-156f, -10f, -188f, -62f)
                        quadraticTo(-154f, -48f, -132f, -55f); close()
                    }
                    inkShape(tail, p.feather, p.line, outline)
                    drawLine(p.wing, Offset(-170f, -49f), Offset(-130f, -25f), 2f, StrokeCap.Round)
                }
                val body = Path().apply {
                    moveTo(-136f, -63f)
                    cubicTo(-117f, -117f, -48f, -123f, 0f, -83f)
                    lineTo(71f, -101f)
                    cubicTo(80f, -32f, 30f, 43f, -36f, 48f)
                    cubicTo(-97f, 53f, -149f, 7f, -136f, -63f)
                    close()
                }
                drawPath(body, p.feather)
                val bodyEdge = Path().apply {
                    moveTo(0f, -83f)
                    cubicTo(-48f, -123f, -117f, -117f, -136f, -63f)
                    cubicTo(-149f, 7f, -97f, 53f, -36f, 48f)
                    cubicTo(30f, 43f, 80f, -32f, 71f, -101f)
                }
                drawPath(bodyEdge, p.line, style = Stroke(outline, cap = StrokeCap.Round))
                val belly = Path().apply {
                    moveTo(-116f, -4f); cubicTo(-58f, 34f, 8f, 15f, 43f, -33f)
                    cubicTo(16f, 37f, -60f, 65f, -116f, -4f); close()
                }
                drawPath(belly, p.wing.copy(alpha = if (style == RideStyle.Night) 0.65f else 0.35f))
            }
            drawLeg(Offset(-32f, 30f + bob), pedal, p.beak, p.line, outline)
            translate(0f, bob) {
                rotate(sin(cycle) * 1.6f, Offset(-65f, -78f)) {
                    val wing = Path().apply {
                        moveTo(-101f, -77f)
                        if (style == RideStyle.Night) {
                            lineTo(-54f, -96f); lineTo(29f, -67f); lineTo(111f, -46f)
                            lineTo(118f, -33f); lineTo(30f, -30f); lineTo(-56f, -6f); lineTo(-107f, -39f)
                        } else {
                            cubicTo(-58f, -111f, -10f, -65f, 31f, -55f)
                            quadraticTo(83f, -42f, 116f, -45f)
                            quadraticTo(128f, -35f, 112f, -31f)
                            quadraticTo(76f, -25f, 30f, -35f)
                            cubicTo(-15f, 6f, -70f, 15f, -101f, -24f)
                            quadraticTo(-119f, -45f, -101f, -77f)
                        }
                        close()
                    }
                    inkShape(wing, p.wing, p.line, outline)
                    repeat(if (style == RideStyle.Ink) 5 else 3) { i ->
                        val feather = Path().apply {
                            moveTo(-85f + i * 10f, -52f + i * 11f)
                            quadraticTo(-51f + i * 10f, -30f + i * 7f, -15f + i * 11f, -46f + i * 4f)
                        }
                        drawPath(feather, p.feather.copy(alpha = 0.5f), style = Stroke(if (style == RideStyle.Coast) 4f else 2f, cap = StrokeCap.Round))
                    }
                }
            }
        }
    }

    translate(0f, bob) {
        if (pigment > 0f) {
            drawPath(head.outline, p.line.copy(alpha = pigment), style = Stroke(outline, cap = StrokeCap.Round))
            when (style) {
                RideStyle.Ink -> Unit
                RideStyle.Coast -> {
                    val cap = Path().apply {
                        moveTo(49f, -207f); cubicTo(52f, -235f, 97f, -238f, 110f, -207f); close()
                    }
                    inkShape(cap, p.accent.copy(alpha = pigment), p.line.copy(alpha = pigment), 3f)
                    drawLine(p.line.copy(alpha = pigment), Offset(47f, -207f), Offset(126f, -207f), 4f, StrokeCap.Round)
                    drawScarf(p, cycle, angular = false)
                }
                RideStyle.Night -> {
                    val helmet = Path().apply {
                        moveTo(46f, -201f); cubicTo(40f, -239f, 91f, -245f, 111f, -219f)
                        lineTo(118f, -205f); lineTo(76f, -205f); lineTo(61f, -191f); close()
                    }
                    inkShape(helmet, p.wing.copy(alpha = pigment), p.line.copy(alpha = pigment), 3f)
                    repeat(3) { i -> drawLine(p.line.copy(alpha = 0.7f * pigment), Offset(62f + i * 12f, -224f), Offset(67f + i * 12f, -215f), 3f, StrokeCap.Round) }
                    drawScarf(p, cycle, angular = true)
                }
                else -> drawWorldGear(style, p, pigment, cycle, time)
            }
        }
    }
}

private fun DrawScope.drawBicycle(style: RideStyle, p: RidePalette, time: Float, pigment: Float) {
    val wheelRadius = 91f
    val frameColor = if (style == RideStyle.Ink) p.line else p.accent
    for (x in listOf(-150f, 150f)) {
        val center = Offset(x, 157f)
        if (style == RideStyle.Night) {
            drawCircle(p.wing.copy(alpha = 0.1f * pigment), wheelRadius + 13f, center, style = Stroke(14f))
        }
        drawCircle(p.line, wheelRadius, center, style = Stroke(if (style == RideStyle.Coast) 10f else 7f))
        drawCircle(p.feather.copy(alpha = 0.7f), wheelRadius - 9f, center, style = Stroke(2f))
        rotate(time * 155f, center) {
            repeat(12) { i ->
                val angle = i * PI.toFloat() / 6f
                drawLine(p.line.copy(alpha = 0.3f), center, center + Offset(cos(angle) * 81f, sin(angle) * 81f), 1.4f)
            }
            drawLine(p.accent, center + Offset(0f, 57f), center + Offset(0f, 75f), 5f, StrokeCap.Round)
        }
        drawCircle(p.line, 7f, center)
        if (style == RideStyle.Ink) {
            drawArc(p.line.copy(alpha = 0.45f), 195f, 150f, false, Offset(x - 99f, 58f), Size(198f, 198f), style = Stroke(3f))
        }
    }
    val frame = Path().apply {
        moveTo(-150f, 157f); lineTo(-67f, 38f); lineTo(0f, 151f); close()
        moveTo(-67f, 38f); lineTo(96f, 38f); lineTo(0f, 151f)
        moveTo(150f, 157f); lineTo(90f, 18f); lineTo(85f, -30f)
    }
    drawPath(frame, p.line, style = Stroke(11f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(frame, frameColor, style = Stroke(6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    val chain = Path().apply {
        moveTo(-150f, 146f); lineTo(0f, 130f)
        cubicTo(33f, 130f, 33f, 174f, 0f, 174f)
        lineTo(-150f, 167f); quadraticTo(-168f, 156f, -150f, 146f); close()
    }
    drawPath(chain, p.line.copy(alpha = 0.6f), style = Stroke(2.3f))
    drawCircle(p.line, 19f, Offset(0f, 151f), style = Stroke(4f))
    val angle = time * 2f * PI.toFloat() / 1.65f
    val crank = Offset(cos(angle) * 32f, sin(angle) * 32f)
    drawLine(p.line, Offset(0f, 151f) - crank, Offset(0f, 151f) + crank, 5f, StrokeCap.Round)
    drawLine(p.line, Offset(-67f, 44f), Offset(-67f, 15f), 6f)
    drawLine(p.line, Offset(-95f, 15f), Offset(-40f, 15f), 12f, StrokeCap.Round)
    val handle = Path().apply {
        moveTo(85f, -30f); quadraticTo(100f, -47f, 129f, -42f)
        quadraticTo(148f, -38f, 136f, -24f)
    }
    drawPath(handle, p.line, style = Stroke(6f, cap = StrokeCap.Round))
    if (style == RideStyle.Coast) {
        drawLine(p.line, Offset(-214f, 42f), Offset(-128f, 42f), 5f, StrokeCap.Round)
        drawLine(p.line.copy(alpha = 0.5f), Offset(-203f, 45f), Offset(-150f, 157f), 3f)
        val bag = Path().apply {
            moveTo(-208f, 45f); lineTo(-139f, 45f); lineTo(-145f, 114f); quadraticTo(-180f, 129f, -204f, 111f); close()
        }
        inkShape(bag, p.near, p.line, 3f)
        drawLine(p.accent, Offset(-179f, 48f), Offset(-178f, 103f), 8f)
    }
    drawWorldBike(style, p, time, pigment)
    if (style == RideStyle.Night) {
        drawCircle(p.beak.copy(alpha = 0.12f * pigment), 22f, Offset(130f, 0f))
        drawCircle(p.feather, 6f, Offset(130f, 0f))
        val light = Path().apply { moveTo(135f, 0f); lineTo(340f, -30f); lineTo(340f, 95f); close() }
        drawPath(light, Brush.horizontalGradient(listOf(p.beak.copy(alpha = 0.15f * pigment), Color.Transparent), 135f, 340f))
    }
}

private fun DrawScope.drawLeg(hip: Offset, foot: Offset, fill: Color, outline: Color, width: Float) {
    val delta = foot - hip
    val distance = delta.getDistance()
    val bend = sqrt((103f * 103f - distance * distance / 4f).coerceAtLeast(0f))
    val knee = (hip + foot) / 2f + Offset(delta.y, -delta.x) / distance * bend
    val leg = Path().apply { moveTo(hip.x, hip.y); lineTo(knee.x, knee.y); lineTo(foot.x, foot.y) }
    drawPath(leg, outline, style = Stroke(width + 8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(leg, fill, style = Stroke(7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawLine(outline, foot + Offset(-10f, 5f), foot + Offset(22f, 5f), 6f, StrokeCap.Round)
    drawLine(fill, foot + Offset(-4f, 0f), foot + Offset(18f, 0f), 6f, StrokeCap.Round)
}

internal fun DrawScope.drawScarf(p: RidePalette, cycle: Float, angular: Boolean) {
    val flutter = sin(cycle - 0.8f) * 7f
    val scarf = Path().apply {
        moveTo(30f, -126f); lineTo(70f, -117f); lineTo(63f, -104f); lineTo(28f, -113f); close()
        moveTo(29f, -120f)
        if (angular) {
            lineTo(-30f, -143f + flutter); lineTo(-85f, -132f - flutter)
            lineTo(-67f, -116f - flutter); lineTo(-30f, -127f + flutter)
        } else {
            cubicTo(-12f, -149f + flutter, -45f, -119f - flutter, -92f, -142f + flutter)
            lineTo(-83f, -121f + flutter)
            cubicTo(-46f, -98f - flutter, -10f, -132f + flutter, 29f, -107f)
        }
        close()
    }
    inkShape(scarf, p.accent, p.line, 2f)
}

internal fun DrawScope.inkShape(path: Path, fill: Color, outline: Color, width: Float) {
    drawPath(path, fill)
    drawPath(path, outline, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
