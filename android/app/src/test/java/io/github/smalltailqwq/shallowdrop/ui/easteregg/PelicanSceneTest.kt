package io.github.smalltailqwq.shallowdrop.ui.easteregg

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import io.github.smalltailqwq.shallowdrop.ui.theme.ShallowDropTheme
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "zh-rCN-w412dp-h892dp-xhdpi")
class PelicanSceneTest {
    @get:Rule val compose = createComposeRule()

    @Test fun inkRevealsAndKeepsRiding() = render(RideStyle.Ink)
    @Test fun coastRevealsAndKeepsRiding() = render(RideStyle.Coast)
    @Test fun nightRevealsAndKeepsRiding() = render(RideStyle.Night)

    @Test
    @Config(qualifiers = "zh-rCN-w892dp-h412dp-xhdpi")
    fun landscapeFitsTheCyclist() = render(RideStyle.Coast)

    @Test
    fun closeReturnsFromAnimation() {
        val visible = mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ShallowDropTheme {
                if (visible.value) PelicanEasterEgg(RideStyle.Ink) { visible.value = false }
            }
        }
        compose.onNodeWithContentDescription("关闭动画").assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithContentDescription("骑着自行车的鹈鹕").assertDoesNotExist()
    }

    private fun render(style: RideStyle) {
        val time = mutableFloatStateOf(0.9f)
        compose.setContent { PelicanScene(style, { time.floatValue }, Modifier.fillMaxSize()) }
        val logo = capture(style, "logo")
        compose.runOnIdle { time.floatValue = 3.4f }
        val entrance = capture(style, "entrance")
        compose.runOnIdle { time.floatValue = 4.5f }
        capture(style, "head")
        compose.runOnIdle { time.floatValue = 5.4f }
        capture(style, "reveal")
        compose.runOnIdle { time.floatValue = 8f }
        val riding = capture(style, "riding")
        compose.runOnIdle { time.floatValue = 8.3f }
        val nextFrame = capture(style, "pedaling")
        assertFalse("The scenery must appear after the logo", logo.sameAs(entrance))
        assertFalse("The entrance must reach the riding scene", entrance.sameAs(riding))
        assertFalse("The scene must keep moving after the entrance", riding.sameAs(nextFrame))
    }

    private fun capture(style: RideStyle, stage: String): Bitmap {
        val bitmap = compose.onNodeWithContentDescription("骑着自行车的鹈鹕").captureToImage().asAndroidBitmap()
        val directory = File("build/screenshots/easter-egg").apply { mkdirs() }
        File(directory, "${style.name}-$stage-${bitmap.width}x${bitmap.height}.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return bitmap
    }
}
