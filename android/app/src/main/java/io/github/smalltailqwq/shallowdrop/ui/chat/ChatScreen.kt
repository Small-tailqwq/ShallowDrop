package io.github.smalltailqwq.shallowdrop.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import io.github.smalltailqwq.shallowdrop.ui.motion.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.ui.chat.message.SourcesSheet
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.Source
import io.github.smalltailqwq.shallowdrop.ui.common.resolve
import io.github.smalltailqwq.shallowdrop.ui.components.AppMark
import io.github.smalltailqwq.shallowdrop.ui.components.DsIcon
import io.github.smalltailqwq.shallowdrop.ui.theme.ChatTextTheme
import io.github.smalltailqwq.shallowdrop.ui.sider.SessionListViewModel
import io.github.smalltailqwq.shallowdrop.ui.sider.SessionSider
import io.github.smalltailqwq.shallowdrop.ui.sider.SearchTarget
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** 宽度不小于该值时侧栏常驻 */
private val WIDE_BREAKPOINT = 840.dp

/** 侧栏宽度（窄屏抽屉与宽屏常驻相同） */
private val SIDER_WIDTH = 300.dp

/** 对话页：侧栏（窄屏抽屉 / 宽屏常驻）+ 顶栏 + 消息列表或欢迎 + 输入栏。 */
@Composable
fun ChatScreen(
    onOpenSettings: () -> Unit,
    viewModel: ChatViewModel = viewModel(factory = ChatViewModel.Factory),
    sessionList: SessionListViewModel = viewModel(factory = SessionListViewModel.Factory),
    /** 进入语音对话，参数为当前会话（新对话为 null） */
    onOpenVoice: (String?) -> Unit = {},
    /** 语音对话结束后要打开的会话；打开后调用 onOpenSessionHandled */
    openSession: String? = null,
    onOpenSessionHandled: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    searchTarget: SearchTarget? = null,
    onSearchTargetHandled: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val groups by sessionList.groups.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(viewModel, sessionList) {
        merge(viewModel.events, sessionList.events).collect { snackbar.showSnackbar(it.resolve(context)) }
    }

    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        val wide = maxWidth >= WIDE_BREAKPOINT
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        var siderVisible by rememberSaveable { mutableStateOf(true) }
        val closeDrawer: () -> Unit = { scope.launch { drawerState.close() } }

        LaunchedEffect(openSession) {
            if (openSession != null) {
                drawerState.snapTo(DrawerValue.Closed)
                viewModel.openSession(openSession)
                onOpenSessionHandled()
            }
        }

        LaunchedEffect(searchTarget) {
            if (searchTarget != null) {
                drawerState.snapTo(DrawerValue.Closed)
                viewModel.openSearchResult(searchTarget)
                onSearchTargetHandled()
            }
        }

        val sider = @Composable { modifier: Modifier ->
            SessionSider(
                groups = groups,
                activeId = state.sessionId,
                onSelect = {
                    viewModel.openSession(it)
                    closeDrawer()
                },
                onOpenSearch = onOpenSearch,
                onRename = sessionList::rename,
                onDelete = sessionList::delete,
                onOpenSettings = {
                    closeDrawer()
                    onOpenSettings()
                },
                modifier = modifier,
            )
        }

        val content = @Composable {
            ChatContent(
                state = state,
                viewModel = viewModel,
                snackbar = snackbar,
                onMenu = {
                    if (wide) siderVisible = !siderVisible else scope.launch { drawerState.open() }
                },
                onOpenSettings = onOpenSettings,
                onOpenVoice = { onOpenVoice(state.sessionId) },
            )
        }

        if (wide) {
            Row(Modifier.fillMaxSize()) {
                AnimatedVisibility(
                    visible = siderVisible,
                    enter = expandHorizontally() + fadeIn(),
                    exit = shrinkHorizontally() + fadeOut(),
                ) {
                    Row {
                        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.width(SIDER_WIDTH).fillMaxHeight()) {
                            sider(
                                Modifier.windowInsetsPadding(
                                    WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom + WindowInsetsSides.Start),
                                ),
                            )
                        }
                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                Box(Modifier.weight(1f)) { content() }
            }
        } else {
            val focusManager = LocalFocusManager.current
            LaunchedEffect(drawerState) {
                snapshotFlow { drawerState.targetValue }.drop(1).collect { target ->
                    if (target == DrawerValue.Closed) focusManager.clearFocus()
                }
            }
            BackHandler(enabled = drawerState.isOpen) { closeDrawer() }
            val siderWidthPx = with(LocalDensity.current) { SIDER_WIDTH.toPx() }
            ModalNavigationDrawer(
                drawerState = drawerState,
                scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f),
                drawerContent = {
                    ModalDrawerSheet(
                        drawerShape = RectangleShape,
                        drawerContainerColor = MaterialTheme.colorScheme.background,
                        modifier = Modifier.width(SIDER_WIDTH),
                    ) {
                        sider(Modifier)
                    }
                },
            ) {
                // 推入式抽屉：对话页随侧栏一起右移
                Box(Modifier.graphicsLayer { translationX = siderWidthPx + drawerState.currentOffset }) { content() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatContent(
    state: ChatUiState,
    viewModel: ChatViewModel,
    snackbar: SnackbarHostState,
    onMenu: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenVoice: () -> Unit,
) {
    val speech: MessageSpeechViewModel = viewModel(factory = MessageSpeechViewModel.Factory)
    val readingId by speech.messageId.collectAsStateWithLifecycle()
    val languageTag = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].toLanguageTag()
    val context = LocalContext.current
    LifecycleStartEffect(speech) { onStopOrDispose { speech.stop() } }
    DisposableEffect(state.sessionId) { onDispose { speech.stop() } }
    LaunchedEffect(speech) { speech.events.collect { snackbar.showSnackbar(it.resolve(context)) } }
    LaunchedEffect(state.conversation, state.settings.autoReadAloud, languageTag) {
        speech.updateAutomatic(state.conversation, state.settings.autoReadAloud, languageTag)
    }
    val listState = rememberLazyListState()
    // 每个会话各自的跟随状态，打开会话时从底部开始
    val follow = rememberFollowState(state.sessionId)
    val scope = rememberCoroutineScope()
    var sheetSources by remember { mutableStateOf<Pair<List<Source>, Int>?>(null) }
    val conversation = state.conversation
    val title = when {
        state.sessionId == null -> ""
        conversation == null -> ""
        else -> conversation.session.title.ifEmpty { stringResource(R.string.untitled_chat) }
    }
    val actions = remember(viewModel) {
        MessageActions(
            onRegenerate = viewModel::regenerate,
            onFork = viewModel::fork,
            onContinue = viewModel::continueReply,
            onEdit = viewModel::editAndResend,
            onSwitchBranch = viewModel::switchBranch,
            onOpenSources = { sheetSources = it to R.string.search_results },
            onOpenPages = { sheetSources = it to R.string.browsed_pages },
            onChangeApiKey = onOpenSettings,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                expandedHeight = (56 + 32 * (LocalDensity.current.fontScale - 1f).coerceAtLeast(0f)).dp,
                title = { ChatTopTitle(title, state.settings.model, viewModel::setModel) },
                navigationIcon = {
                    IconButton(onClick = onMenu) {
                        DsIcon(R.drawable.ic_menu_outline_20, contentDescription = stringResource(R.string.open_sidebar), modifier = Modifier.size(22.dp))
                    }
                },
                actions = {
                    IconButton(onClick = onOpenVoice) {
                        // chat-demo 没有电话图标，沿用 Material
                        Icon(Icons.Outlined.Call, contentDescription = stringResource(R.string.voice_chat), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                    }
                    IconButton(onClick = viewModel::toggleAutoReadAloud,
                        modifier = Modifier.semantics { toggleableState = ToggleableState(state.settings.autoReadAloud) }) {
                        DsIcon(
                            if (state.settings.autoReadAloud) R.drawable.ic_speaker_outline_20 else R.drawable.ic_speaker_off_outline_20,
                            contentDescription = stringResource(if (state.settings.autoReadAloud) R.string.auto_read_aloud_disable else R.string.auto_read_aloud_enable),
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { viewModel.openSession(null) }, enabled = state.sessionId != null) {
                        DsIcon(R.drawable.ic_new_chat_outline_20, contentDescription = stringResource(R.string.new_chat), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        ChatTextTheme(state.settings.chatFontSize) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding(),
            ) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        conversation != null && conversation.turns.isNotEmpty() -> MessageList(
                            conversation = conversation,
                            listState = listState,
                            follow = follow,
                            actions = actions,
                            replySwipe = state.settings.replySwipe,
                            onNewChat = { viewModel.openSession(null) },
                            readingId = readingId,
                            onReadAloud = { id, text -> speech.toggle(id, text, languageTag) },
                            searchTarget = state.searchTarget,
                            onSearchTargetHandled = viewModel::clearSearchTarget,
                            modifier = Modifier.fillMaxSize(),
                        )
                        // 已选会话但尚未加载完成时留空，避免闪现欢迎页
                        state.sessionId != null && conversation == null -> Unit
                        else -> Welcome(Modifier.align(Alignment.Center))
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = conversation != null && !follow.following && listState.canScrollForward,
                        enter = fadeIn(androidx.compose.animation.core.tween(180)) + scaleIn(androidx.compose.animation.core.tween(180), initialScale = 0.95f),
                        exit = fadeOut(androidx.compose.animation.core.tween(120)) + scaleOut(androidx.compose.animation.core.tween(120), targetScale = 0.95f),
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                    ) {
                        SmallFloatingActionButton(
                            onClick = {
                                scope.launch {
                                    listState.scrollToBottom(animated = true)
                                    follow.following = true
                                }
                            },
                            shape = CircleShape,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            DsIcon(R.drawable.ic_chevron_down_outline_14, contentDescription = stringResource(R.string.scroll_to_bottom), modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (conversation?.replySaveFailed == true) {
                        ReplySaveNotice(enabled = !state.generating, onRetry = viewModel::retrySaveReply)
                    }
                    ChatInputBar(
                        state = state,
                        onTextChange = viewModel::onTextChange,
                        onSend = {
                            follow.following = true
                            viewModel.send()
                        },
                        onStop = viewModel::stop,
                        onToggleThinking = { viewModel.toggleThinking() },
                        onToggleSearch = { viewModel.toggleSearch() },
                        onAddAttachments = viewModel::addAttachments,
                        onRemoveAttachment = { viewModel.removeAttachment(it) },
                        onRetryAttachment = viewModel::retryAttachment,
                        modifier = Modifier.widthIn(max = 784.dp).padding(start = 8.dp, end = 8.dp, top = 8.dp),
                    )
                    // 有消息时在输入栏下方提示 AI 生成；新对话保持干净
                    if (conversation != null && conversation.turns.isNotEmpty()) {
                        Text(
                            stringResource(R.string.content_caveat),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    } else {
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }

    sheetSources?.let { (sources, title) ->
        SourcesSheet(sources, title = title, onDismiss = { sheetSources = null })
    }
}

@Composable
internal fun ReplySaveNotice(enabled: Boolean, onRetry: () -> Unit) {
    Row(
        Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.reply_save_failed),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
        )
        TextButton(onClick = onRetry, enabled = enabled) {
            Text(stringResource(R.string.retry_save_reply))
        }
    }
}

/** 新对话空态，对照 Welcome.tsx：标志 + 问候语；AI 生成提示移到输入栏下方，随对话出现。 */
@Composable
private fun Welcome(modifier: Modifier = Modifier) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        AppMark(Modifier.size(width = 56.dp, height = 40.dp))
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.welcome),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            textAlign = TextAlign.Center,
        )
    }
}
