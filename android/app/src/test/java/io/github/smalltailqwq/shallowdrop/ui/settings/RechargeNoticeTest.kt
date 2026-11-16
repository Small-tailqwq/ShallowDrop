package io.github.smalltailqwq.shallowdrop.ui.settings

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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
class RechargeNoticeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun mustReadAndExplicitlyAgreeBeforeContinuing() {
        var continued = false
        compose.setContent { ShallowDropTheme { RechargeNotice({ continued = true }, {}) } }
        compose.onNodeWithText("同意并前往官方充值").assertIsNotEnabled()
        compose.onNodeWithText("充值、到账与退款由 DeepSeek 官方处理。本软件不提供充值服务或任何服务保证。请谨慎操作。").performScrollTo()
        compose.onNodeWithText("同意并前往官方充值").assertIsNotEnabled()
        compose.onNodeWithText("我已阅读并同意，付款前会核实收款方、充值账户和金额").performClick()
        compose.onNodeWithText("同意并前往官方充值").assertIsEnabled().performClick()
        assertTrue(continued)
    }

    @Test fun onlyExactHttpsPlatformOriginIsAccepted() {
        assertTrue(isOfficialPlatformUrl("https://platform.deepseek.com/top_up"))
        assertTrue(isOfficialPlatformUrl("https://platform.deepseek.com/sign_in?redirect=%2Ftop_up"))
        listOf(
            "http://platform.deepseek.com/top_up",
            "https://platform.deepseek.com.evil.example/top_up",
            "https://platform.deepseek.com@evil.example/top_up",
            "https://user@platform.deepseek.com/top_up",
            "https://platform.deepseek.com:8443/top_up",
            "javascript:alert(1)",
            "file:///sdcard/top_up.html",
        ).forEach { assertFalse(it, isOfficialPlatformUrl(it)) }
    }
}
