package io.github.smalltailqwq.shallowdrop.ui.easteregg

import android.graphics.PathMeasure
import android.util.TypedValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.PathParser
import io.github.smalltailqwq.shallowdrop.R
import org.xmlpull.v1.XmlPullParser
import android.graphics.Path as AndroidPath

internal val PelicanHeadCenter = Offset(152f, -143.4f)
private val Eye = Offset(84f, -197f)

@Composable
internal fun rememberPelicanHead(): PelicanHead {
    val resources = LocalContext.current.resources
    return remember(resources) {
        val resource = TypedValue()
        resources.getValue(R.drawable.ic_app_mark, resource, true)
        val data = resources.assets.openXmlResourceParser(resource.assetCookie, resource.string.toString()).use { xml ->
            while (xml.eventType != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == XmlPullParser.START_TAG && xml.name == "path") {
                    return@use xml.getAttributeValue("http://schemas.android.com/apk/res/android", "pathData")
                }
                xml.next()
            }
            error("App mark has no path")
        }
        PelicanHead(PathParser.createPathFromPathData(data))
    }
}

internal class PelicanHead(logo: AndroidPath) {
    private val source: List<Offset>
    private val target: List<Offset>
    val outline: Path

    init {
        val measure = PathMeasure(logo, false)
        val point = FloatArray(2)
        val landmarks = listOf(Offset(40.4f, 68.95f), Offset(82.43f, 23.03f), Offset(6.67f, 26.14f), Offset(79.06f, 17.58f), Offset(1.9f, 25.15f))
        val distances = mutableListOf(0f)
        for (landmark in landmarks) {
            var closest = Float.MAX_VALUE
            var distance = distances.last()
            val start = distance
            repeat(4097) { i ->
                val at = start + (measure.length - start) * i / 4096f
                measure.getPosTan(at, point, null)
                val squared = (point[0] - landmark.x) * (point[0] - landmark.x) + (point[1] - landmark.y) * (point[1] - landmark.y)
                if (squared < closest) { closest = squared; distance = at }
            }
            distances += distance
        }
        distances += measure.length
        val contours = listOf(
            AndroidPath().apply {
                moveTo(65f, -220f); cubicTo(42f, -230f, 19f, -208f, 25f, -164f)
                cubicTo(30f, -132f, 29f, -72f, 0f, -83f)
            },
            AndroidPath().apply {
                moveTo(0f, -83f); cubicTo(10f, -65f, 40f, -50f, 45f, -33f)
                cubicTo(75f, -62f, 78f, -98f, 71f, -101f)
                cubicTo(72f, -134f, 88f, -145f, 106f, -167f)
            },
            AndroidPath().apply {
                moveTo(106f, -167f); cubicTo(119f, -137f, 159f, -118f, 196f, -142f)
                cubicTo(221f, -150f, 241f, -160f, 255f, -167f)
            },
            AndroidPath().apply { moveTo(255f, -167f); lineTo(100f, -174f) },
            AndroidPath().apply {
                moveTo(100f, -174f); cubicTo(156f, -176f, 211f, -170f, 255f, -166f)
                quadTo(269f, -171f, 263f, -173f)
            },
            AndroidPath().apply {
                moveTo(263f, -173f); cubicTo(213f, -180f, 154f, -190f, 99f, -188f)
                cubicTo(119f, -200f, 100f, -217f, 78f, -220f)
                quadTo(70f, -224f, 65f, -220f)
            },
        )
        val from = mutableListOf<Offset>()
        val to = mutableListOf<Offset>()
        // 按颈部、喉囊和上下喙分别采样，变形时保持各部位的对应关系。
        contours.forEachIndexed { section, contour ->
            val end = PathMeasure(contour, false)
            repeat(64) { i ->
                val fraction = i / 64f
                measure.getPosTan(distances[section] + (distances[section + 1] - distances[section]) * fraction, point, null)
                from += Offset(257.25f - point[0] * 2.1f, -219.05f + point[1] * 2.1f)
                end.getPosTan(end.length * fraction, point, null)
                to += Offset(point[0], point[1])
            }
        }
        source = from
        target = to
        val neckJoin = (64 until 128).minBy { (target[it] - Offset(71f, -101f)).getDistanceSquared() }
        outline = Path().apply {
            moveTo(target[0].x, target[0].y)
            for (i in 1..64) lineTo(target[i].x, target[i].y)
            moveTo(71f, -101f)
            for (i in neckJoin until target.size) lineTo(target[i].x, target[i].y)
            lineTo(target[0].x, target[0].y)
        }
    }

    fun path(amount: Float) = Path().apply {
        source.indices.forEach { i ->
            val point = source[i] + (target[i] - source[i]) * amount
            if (i == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
        }
        close()
        fillType = PathFillType.EvenOdd
        addOval(Rect(Eye - Offset(4.5f, 4.5f), Eye + Offset(4.5f, 4.5f)))
    }
}

internal fun DrawScope.drawPelicanHead(head: PelicanHead, amount: Float, p: RidePalette, pigment: Float, time: Float, style: RideStyle) {
    val silhouette = head.path(amount)
    drawPath(silhouette, p.feather)
    if (pigment == 0f) return
    clipPath(silhouette) {
        val pouch = Path().apply {
            moveTo(98f, -188f); lineTo(275f, -184f); lineTo(275f, -120f); lineTo(115f, -120f)
            quadraticTo(96f, -144f, 98f, -188f); close()
        }
        drawPath(pouch, p.beak)
        val bill = Path().apply {
            moveTo(99f, -188f); cubicTo(154f, -190f, 213f, -180f, 263f, -173f)
            lineTo(255f, -166f); lineTo(100f, -174f); close()
        }
        drawPath(bill, lerp(p.beak, p.feather, 0.35f))
    }
    // 深色底的风格描线是浅色，眼珠改用底色才看得见。
    val eyeColor = if (style == RideStyle.Night || style == RideStyle.Orbit) p.paper else p.line
    drawCircle(p.feather, 5.4f, Eye)
    if (time % 5.7f in 4.8f..4.95f) {
        drawLine(eyeColor.copy(alpha = pigment), Eye - Offset(4f, 0f), Eye + Offset(4f, 0f), 2.5f, StrokeCap.Round)
    } else {
        drawCircle(eyeColor.copy(alpha = pigment), 4f, Eye)
        drawCircle(p.feather, 1.2f, Eye + Offset(1f, -1f))
    }
}
