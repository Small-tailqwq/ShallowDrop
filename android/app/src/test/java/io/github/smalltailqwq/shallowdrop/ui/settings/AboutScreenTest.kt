package io.github.smalltailqwq.shallowdrop.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.smalltailqwq.shallowdrop.BuildConfig
import io.github.smalltailqwq.shallowdrop.ui.theme.ShallowDropTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "zh-rCN-w360dp-h800dp-xxhdpi")
class AboutScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aboutOpensEachDestinationAndShowsBuildVersion() {
        val actions = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) { actions += uri }
            }) {
                ShallowDropTheme { AboutScreen({ actions += "back" }, { actions += "licenses" }, { actions += "disclaimer" }) }
            }
        }
        compose.onNodeWithText("ShallowDrop").assertIsDisplayed()
        compose.onNodeWithText(BuildConfig.VERSION_NAME).assertIsDisplayed()
        compose.onNodeWithText("免责声明").performScrollTo().performClick()
        compose.onNodeWithText("开源许可").performScrollTo().performClick()
        compose.onNodeWithText("源代码").performScrollTo().performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        assertEquals(listOf("disclaimer", "licenses", SOURCE_CODE_URL, "back"), actions)
    }

    @Test fun everyListedLicenseCanBeReadFromThePackagedAssets() {
        val assets = RuntimeEnvironment.getApplication().assets
        thirdPartyLicenses.flatMap { it.files }.distinct().forEach { path ->
            assertTrue(path, assets.open(path).bufferedReader().use { it.readText().length } > 30)
        }
        compose.setContent { ShallowDropTheme { LicenseScreen {} } }
        compose.onNodeWithText("KaTeX fonts · 0.16.22").performScrollTo().performClick()
        compose.onNodeWithText("SIL OPEN FONT LICENSE Version 1.1", substring = true).assertExists()
    }

    @Test
    @Config(qualifiers = "zh-rCN-w800dp-h360dp-xhdpi", fontScale = 2f)
    fun landscapeLargeTextAboutCanReachBottomAndReturn() {
        var returned = false
        compose.setContent { ShallowDropTheme { AboutScreen({ returned = true }, {}, {}) } }
        compose.onNodeWithText("源代码").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").assertIsDisplayed().performClick()
        assertTrue(returned)
    }
}
