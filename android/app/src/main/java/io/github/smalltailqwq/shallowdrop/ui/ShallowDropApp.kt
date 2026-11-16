package io.github.smalltailqwq.shallowdrop.ui

import io.github.smalltailqwq.shallowdrop.ui.motion.MotionEaseOut
import io.github.smalltailqwq.shallowdrop.ui.motion.PAGE_MOTION_MS
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import io.github.smalltailqwq.shallowdrop.ui.chat.ChatScreen
import io.github.smalltailqwq.shallowdrop.ui.components.LocalAttachmentFile
import io.github.smalltailqwq.shallowdrop.ui.onboarding.OnboardingScreen
import io.github.smalltailqwq.shallowdrop.ui.settings.LicenseScreen
import io.github.smalltailqwq.shallowdrop.ui.settings.SettingsScreen
import io.github.smalltailqwq.shallowdrop.ui.theme.ShallowDropTheme
import io.github.smalltailqwq.shallowdrop.ui.theme.isAppInDarkTheme
import io.github.smalltailqwq.shallowdrop.ui.voice.VoiceScreen
import kotlinx.serialization.Serializable

/*
 * 导航（类型安全路由）：
 *   OnboardingRoute —— 首次无 Key 时的引导页
 *   ChatRoute       —— 对话页（会话切换在页内完成，不进返回栈）
 *   VoiceRoute      —— 语音对话，挂断后回到对话页并打开写入的会话
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
data object RechargeRoute

@Serializable
data object LicenseRoute

/** 语音对话；sessionId 为 null 时首轮新建会话 */
@Serializable
data class VoiceRoute(val sessionId: String? = null)

private const val DURATION = PAGE_MOTION_MS

/** 根组件：主题 + 导航。onDarkThemeChange 用于同步系统栏图标颜色。 */
@Composable
fun ShallowDropApp(deps: UiDependencies, onDarkThemeChange: (Boolean) -> Unit = {}) {
    val settings by deps.settingsRepository.settings.collectAsStateWithLifecycle()
    val loaded by deps.settingsLoaded.collectAsStateWithLifecycle()
    val dark = isAppInDarkTheme(settings.theme)
    LaunchedEffect(dark) { onDarkThemeChange(dark) }

    ShallowDropTheme(darkTheme = dark) {
        CompositionLocalProvider(LocalAttachmentFile provides deps.attachmentFile) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                // 设置读完前不决定起始页面，避免已有 Key 时闪现引导页
                if (loaded) AppNavHost(hasApiKey = settings.hasApiKey, deps = deps)
            }
        }
    }
}

@Composable
private fun AppNavHost(hasApiKey: Boolean, deps: UiDependencies) {
    val navController = rememberNavController()
    val start: Any = remember { if (hasApiKey) ChatRoute else OnboardingRoute }

    // Key 被清除且当前在对话流程中时回到引导页
    LaunchedEffect(hasApiKey) {
        if (!hasApiKey && navController.hasInBackStack<ChatRoute>()) {
            navController.navigate(OnboardingRoute) { popUpTo(0) { inclusive = true } }
        }
    }

    // 语音对话写入的会话，回到对话页时打开
    var voiceSession by rememberSaveable { mutableStateOf<String?>(null) }

    // 外部打开或分享进来的 ZIP：转到设置页由其确认导入
    val incomingImport by deps.incomingImport.collectAsStateWithLifecycle()
    LaunchedEffect(incomingImport) {
        if (incomingImport == null) return@LaunchedEffect
        if (navController.hasInBackStack<SettingsRoute>()) {
            navController.popBackStack<SettingsRoute>(inclusive = false)
        } else {
            navController.navigate(SettingsGraph) { launchSingleTop = true }
        }
    }

    NavHost(
        navController = navController,
        startDestination = start,
        enterTransition = { fadeIn(tween(DURATION, easing = MotionEaseOut)) + slideInHorizontally(tween(DURATION, easing = MotionEaseOut)) { it / 12 } },
        exitTransition = { fadeOut(tween(DURATION, easing = MotionEaseOut)) },
        popEnterTransition = { fadeIn(tween(DURATION, easing = MotionEaseOut)) },
        popExitTransition = { fadeOut(tween(DURATION, easing = MotionEaseOut)) + slideOutHorizontally(tween(DURATION, easing = MotionEaseOut)) { it / 12 } },
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
            ChatScreen(
                onOpenSettings = { navController.navigate(SettingsGraph) { launchSingleTop = true } },
                onOpenVoice = { navController.navigate(VoiceRoute(it)) { launchSingleTop = true } },
                openSession = voiceSession,
                onOpenSessionHandled = { voiceSession = null },
            )
        }
        composable<VoiceRoute> {
            VoiceScreen(
                onClose = { sessionId ->
                    if (sessionId != null) voiceSession = sessionId
                    navController.popBackStack()
                },
            )
        }
        navigation<SettingsGraph>(startDestination = SettingsRoute) {
            composable<SettingsRoute> {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenLicense = { navController.navigate(LicenseRoute) },
                    onOpenRecharge = { navController.navigate(RechargeRoute) { launchSingleTop = true } },
                    incomingImport = incomingImport,
                    onIncomingImportHandled = { deps.incomingImport.value = null },
                )
            }
            composable<RechargeRoute> {
                io.github.smalltailqwq.shallowdrop.ui.settings.RechargeScreen(onBack = { navController.popBackStack() })
            }
            composable<LicenseRoute> {
                LicenseScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

private inline fun <reified T : Any> NavHostController.hasInBackStack(): Boolean =
    runCatching { getBackStackEntry<T>() }.isSuccess
