package io.github.smalltailqwq.shallowdrop.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import io.github.smalltailqwq.shallowdrop.ui.chat.ChatScreen
import io.github.smalltailqwq.shallowdrop.ui.onboarding.OnboardingScreen
import io.github.smalltailqwq.shallowdrop.ui.settings.LicenseScreen
import io.github.smalltailqwq.shallowdrop.ui.settings.SettingsScreen
import io.github.smalltailqwq.shallowdrop.ui.theme.ShallowDropTheme
import io.github.smalltailqwq.shallowdrop.ui.theme.isAppInDarkTheme
import kotlinx.serialization.Serializable

/*
 * 导航（类型安全路由）：
 *   OnboardingRoute —— 首次无 Key 时的引导页
 *   ChatRoute       —— 对话页（会话切换在页内完成，不进返回栈）
 *   SettingsGraph   —— SettingsRoute、LicenseRoute
 */

@Serializable
data object OnboardingRoute

@Serializable
data object ChatRoute

@Serializable
data object SettingsGraph

@Serializable
data object SettingsRoute

@Serializable
data object LicenseRoute

private const val DURATION = 220

/** 根组件：主题 + 导航。onDarkThemeChange 用于同步系统栏图标颜色。 */
@Composable
fun ShallowDropApp(deps: UiDependencies, onDarkThemeChange: (Boolean) -> Unit = {}) {
    val settings by deps.settingsRepository.settings.collectAsStateWithLifecycle()
    val loaded by deps.settingsLoaded.collectAsStateWithLifecycle()
    val dark = isAppInDarkTheme(settings.theme)
    LaunchedEffect(dark) { onDarkThemeChange(dark) }

    ShallowDropTheme(darkTheme = dark) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // 设置读完前不决定起始页面，避免已有 Key 时闪现引导页
            if (loaded) AppNavHost(hasApiKey = settings.hasApiKey)
        }
    }
}

@Composable
private fun AppNavHost(hasApiKey: Boolean) {
    val navController = rememberNavController()
    val start: Any = remember { if (hasApiKey) ChatRoute else OnboardingRoute }

    // Key 被清除且当前在对话流程中时回到引导页
    LaunchedEffect(hasApiKey) {
        if (!hasApiKey && navController.hasInBackStack<ChatRoute>()) {
            navController.navigate(OnboardingRoute) { popUpTo(0) { inclusive = true } }
        }
    }

    NavHost(
        navController = navController,
        startDestination = start,
        enterTransition = { fadeIn(tween(DURATION)) + slideInHorizontally(tween(DURATION)) { it / 12 } },
        exitTransition = { fadeOut(tween(DURATION)) },
        popEnterTransition = { fadeIn(tween(DURATION)) },
        popExitTransition = { fadeOut(tween(DURATION)) + slideOutHorizontally(tween(DURATION)) { it / 12 } },
    ) {
        composable<OnboardingRoute> {
            OnboardingScreen(
                onDone = {
                    navController.navigate(ChatRoute) { popUpTo(0) { inclusive = true } }
                },
                onOpenSettings = { navController.navigate(SettingsGraph) { launchSingleTop = true } },
            )
        }
        composable<ChatRoute> {
            ChatScreen(onOpenSettings = { navController.navigate(SettingsGraph) { launchSingleTop = true } })
        }
        navigation<SettingsGraph>(startDestination = SettingsRoute) {
            composable<SettingsRoute> {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenLicense = { navController.navigate(LicenseRoute) },
                )
            }
            composable<LicenseRoute> {
                LicenseScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

private inline fun <reified T : Any> NavHostController.hasInBackStack(): Boolean =
    runCatching { getBackStackEntry<T>() }.isSuccess
