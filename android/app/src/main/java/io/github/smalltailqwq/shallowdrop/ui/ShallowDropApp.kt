package io.github.smalltailqwq.shallowdrop.ui

import io.github.smalltailqwq.shallowdrop.ui.motion.MotionEaseOut
import io.github.smalltailqwq.shallowdrop.ui.motion.PAGE_MOTION_MS
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import io.github.smalltailqwq.shallowdrop.ui.chat.ChatScreen
import io.github.smalltailqwq.shallowdrop.ui.components.LocalAttachmentFile
import io.github.smalltailqwq.shallowdrop.ui.onboarding.OnboardingScreen
import io.github.smalltailqwq.shallowdrop.ui.settings.FontSizeScreen
import io.github.smalltailqwq.shallowdrop.ui.settings.LicenseScreen
import io.github.smalltailqwq.shallowdrop.ui.settings.AboutScreen
import io.github.smalltailqwq.shallowdrop.ui.settings.DisclaimerScreen
import io.github.smalltailqwq.shallowdrop.ui.sider.SearchScreen
import io.github.smalltailqwq.shallowdrop.ui.sider.SearchTarget
import io.github.smalltailqwq.shallowdrop.ui.settings.ReplySwipeScreen
import io.github.smalltailqwq.shallowdrop.ui.settings.SettingsViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import io.github.smalltailqwq.shallowdrop.ui.settings.SettingsScreen
import io.github.smalltailqwq.shallowdrop.ui.theme.ShallowDropTheme
import io.github.smalltailqwq.shallowdrop.ui.theme.isAppInDarkTheme
import io.github.smalltailqwq.shallowdrop.ui.voice.VoiceScreen
import kotlinx.serialization.Serializable

/*
 * 导航（类型安全路由）：
 *   OnboardingRoute —— 首次无 Key 时的引导页
 *   ChatRoute       —— 对话页（会话切换在页内完成，不进返回栈）
 *   SearchRoute     —— 搜索会话（标题、消息、文件名），选中后回到对话页并打开
 *   VoiceRoute      —— 语音对话，挂断后回到对话页并打开写入的会话
 *   SettingsGraph   —— 设置、关于、免责声明、开源许可、充值、字号和回复滑动
 */

@Serializable
data object OnboardingRoute

@Serializable
data object ChatRoute

/** 独立搜索页：选中会话后回到对话页并打开它 */
@Serializable
data object SearchRoute

@Serializable
data object SettingsGraph

@Serializable
data object SettingsRoute

@Serializable
data object AboutRoute

@Serializable
data object DisclaimerRoute

@Serializable
data object RechargeRoute

@Serializable
data object LicenseRoute

@Serializable
data object FontSizeRoute

@Serializable
data object ReplySwipeRoute

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
    var searchTarget by remember { mutableStateOf<SearchTarget?>(null) }

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
        composable<ChatRoute>(
            exitTransition = {
                if (targetState.destination.hasRoute<SearchRoute>()) ExitTransition.None else null
            },
            popEnterTransition = {
                if (initialState.destination.hasRoute<SearchRoute>()) EnterTransition.None else null
            },
        ) {
            ChatScreen(
                onOpenSettings = { navController.navigate(SettingsGraph) { launchSingleTop = true } },
                onOpenVoice = { navController.navigate(VoiceRoute(it)) { launchSingleTop = true } },
                onOpenSearch = { navController.navigate(SearchRoute) { launchSingleTop = true } },
                openSession = voiceSession,
                onOpenSessionHandled = { voiceSession = null },
                searchTarget = searchTarget,
                onSearchTargetHandled = { searchTarget = null },
            )
        }
        composable<SearchRoute>(
            enterTransition = { slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { it } },
            popExitTransition = { slideOutHorizontally(tween(280, easing = FastOutSlowInEasing)) { it } },
        ) {
            SearchScreen(
                onBack = { navController.popBackStack() },
                onSelect = { target ->
                    searchTarget = target
                    navController.popBackStack()
                },
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
                    onOpenAbout = { navController.navigate(AboutRoute) { launchSingleTop = true } },
                    onOpenRecharge = { navController.navigate(RechargeRoute) { launchSingleTop = true } },
                    onOpenFontSize = { navController.navigate(FontSizeRoute) { launchSingleTop = true } },
                    onOpenReplySwipe = { navController.navigate(ReplySwipeRoute) { launchSingleTop = true } },
                    incomingImport = incomingImport,
                    onIncomingImportHandled = { deps.incomingImport.value = null },
                )
            }
            composable<RechargeRoute> {
                io.github.smalltailqwq.shallowdrop.ui.settings.RechargeScreen(onBack = { navController.popBackStack() })
            }
            // 预览类设置页离开时才保存：写入由设置页的 ViewModel 执行，页面出栈后协程不被取消
            composable<FontSizeRoute> { entry ->
                val settingsViewModel = settingsViewModel(navController, entry)
                val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
                FontSizeScreen(
                    initial = remember { settings.chatFontSize },
                    onSave = settingsViewModel::setChatFontSize,
                    onBack = { navController.popBackStack() },
                )
            }
            composable<ReplySwipeRoute> { entry ->
                val settingsViewModel = settingsViewModel(navController, entry)
                val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
                ReplySwipeScreen(
                    initial = remember { settings.replySwipe },
                    onSave = settingsViewModel::setReplySwipe,
                    onBack = { navController.popBackStack() },
                )
            }
            composable<AboutRoute> {
                AboutScreen(
                    onBack = { navController.popBackStack() },
                    onOpenLicense = { navController.navigate(LicenseRoute) { launchSingleTop = true } },
                    onOpenDisclaimer = { navController.navigate(DisclaimerRoute) { launchSingleTop = true } },
                )
            }
            composable<DisclaimerRoute> {
                DisclaimerScreen(onBack = { navController.popBackStack() })
            }
            composable<LicenseRoute> {
                LicenseScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

/** 子页面共用设置页（SettingsRoute）那份 ViewModel */
@Composable
private fun settingsViewModel(navController: NavHostController, entry: NavBackStackEntry): SettingsViewModel {
    val parent = remember(entry) { navController.getBackStackEntry<SettingsRoute>() }
    return viewModel(parent, factory = SettingsViewModel.Factory)
}

private inline fun <reified T : Any> NavHostController.hasInBackStack(): Boolean =
    runCatching { getBackStackEntry<T>() }.isSuccess
