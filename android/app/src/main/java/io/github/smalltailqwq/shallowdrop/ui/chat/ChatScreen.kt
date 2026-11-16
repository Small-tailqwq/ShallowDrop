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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.ui.chat.message.SourcesSheet
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.Source
import io.github.smalltailqwq.shallowdrop.ui.common.resolve
import io.github.smalltailqwq.shallowdrop.ui.components.AppMark
import io.github.smalltailqwq.shallowdrop.ui.components.DsIcon
import io.github.smalltailqwq.shallowdrop.ui.sider.SessionListViewModel
import io.github.smalltailqwq.shallowdrop.ui.sider.SessionSider
import kotlinx.coroutines.flow.merge
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
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val groups by sessionList.groups.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(viewModel, sessionList) {
        merge(viewModel.events, sessionList.events).collect { snackbar.showSnackbar(it.resolve(context)) }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= WIDE_BREAKPOINT
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        var siderVisible by rememberSaveable { mutableStateOf(true) }
        val closeDrawer: () -> Unit = { scope.launch { drawerState.close() } }

        val sider = @Composable { modifier: Modifier ->
            SessionSider(
                groups = groups,
                activeId = state.sessionId,
                onNewChat = {
                    viewModel.openSession(null)
                    closeDrawer()
                },
                onSelect = {
                    viewModel.openSession(it)
                    closeDrawer()
                },
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
) {
    val listState = rememberLazyListState()
    // 每个会话各自的跟随状态，打开会话时从底部开始
    val follow = rememberFollowState(state.sessionId)
    val scope = rememberCoroutineScope()
    var sheetSources by remember { mutableStateOf<List<Source>?>(null) }
    val conversation = state.conversation
    val title = when {
        state.sessionId == null -> ""
        conversation == null -> ""
        else -> conversation.session.title.ifEmpty { stringResource(R.string.untitled_chat) }
    }
    val actions = remember(viewModel) {
        MessageActions(
            onRegenerate = viewModel::regenerate,
            onContinue = viewModel::continueReply,
            onEdit = viewModel::editAndResend,
            onSwitchBranch = viewModel::switchBranch,
            onOpenSources = { sheetSources = it },
            onChangeApiKey = onOpenSettings,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onMenu) {
                        DsIcon(R.drawable.ic_menu_outline_20, contentDescription = stringResource(R.string.open_sidebar), modifier = Modifier.size(22.dp))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.openSession(null) }, enabled = state.sessionId != null) {
                        DsIcon(R.drawable.ic_new_chat_outline_20, contentDescription = stringResource(R.string.new_chat), modifier = Modifier.size(22.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
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
                        modifier = Modifier.fillMaxSize(),
                    )
                    // 已选会话但尚未加载完成时留空，避免闪现欢迎页
                    state.sessionId != null && conversation == null -> Unit
                    else -> Welcome(Modifier.align(Alignment.Center))
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = conversation != null && !follow.following && listState.canScrollForward,
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut(),
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
                    modifier = Modifier.widthIn(max = 800.dp).padding(start = 12.dp, end = 12.dp, top = 8.dp),
                )
                // 有消息时在输入栏下方提示 AI 生成；新对话保持干净
                if (conversation != null && conversation.turns.isNotEmpty()) {
                    Text(
                        stringResource(R.string.content_caveat),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                } else {
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }

    sheetSources?.let { sources ->
        SourcesSheet(sources, onDismiss = { sheetSources = null })
    }
}

/** 新对话空态，对照 Welcome.tsx：标志 + 问候语；AI 生成提示移到输入栏下方，随对话出现。 */
@Composable
private fun Welcome(modifier: Modifier = Modifier) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        AppMark(Modifier.size(40.dp))
        Spacer(Modifier.height(14.dp))
        Text(
            stringResource(R.string.welcome),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            textAlign = TextAlign.Center,
        )
    }
}
