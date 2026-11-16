package io.github.smalltailqwq.shallowdrop.ui.settings

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.smalltailqwq.shallowdrop.core.model.LocationMode
import io.github.smalltailqwq.shallowdrop.core.model.LocationPreference
import io.github.smalltailqwq.shallowdrop.ui.theme.ShallowDropTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "zh-rCN-w360dp-h800dp-xxhdpi")
class LocationDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun automaticSourcesCanBeSelectedAndDisabled() {
        var saved: LocationPreference? = null
        var requested: LocationPreference? = null
        compose.setContent { ShallowDropTheme {
            LocationDialogContent(LocationPreference(), LocationPreview(), { saved = it }, {}, {}, { requested = it }, {}, {})
        } }
        compose.onNodeWithText("设备 GPS 定位").performClick()
        compose.onNodeWithText("获取 / 刷新位置").performScrollTo().performClick()
        assertEquals(LocationMode.Gps, requested?.mode)
        compose.onNodeWithText("网络出口 IP 定位").performScrollTo().performClick()
        compose.onNodeWithText("获取 / 刷新位置").performScrollTo().performClick()
        assertEquals(LocationMode.Network, requested?.mode)
        compose.onNodeWithText("确定").performClick()
        assertEquals(LocationMode.Network, saved?.mode)
        compose.onNodeWithText("关闭").performScrollTo().performClick()
        compose.onNodeWithText("确定").performClick()
        assertEquals(LocationMode.Off, saved?.mode)
    }
}
