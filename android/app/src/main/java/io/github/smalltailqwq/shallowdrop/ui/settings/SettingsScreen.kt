package io.github.smalltailqwq.shallowdrop.ui.settings

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.smalltailqwq.shallowdrop.BuildConfig
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.core.contract.ImageRestoreState
import io.github.smalltailqwq.shallowdrop.core.model.AppLocale
import io.github.smalltailqwq.shallowdrop.core.model.ChatModel
import io.github.smalltailqwq.shallowdrop.core.model.ThemePreference
import io.github.smalltailqwq.shallowdrop.ui.common.ApiKeyField
import io.github.smalltailqwq.shallowdrop.ui.common.ConfirmDialog
import io.github.smalltailqwq.shallowdrop.ui.common.GetApiKeyButton
import io.github.smalltailqwq.shallowdrop.ui.common.resolve
import io.github.smalltailqwq.shallowdrop.ui.components.BackButton
import io.github.smalltailqwq.shallowdrop.ui.components.DsIcon
import io.github.smalltailqwq.shallowdrop.ui.theme.groupedCardColor
import io.github.smalltailqwq.shallowdrop.ui.theme.groupedPageColor
import java.time.LocalDate

/** TODO 发布前确认开源仓库地址 */
const val SOURCE_CODE_URL = "https://github.com/Small-tailqwq/ShallowDrop"

private enum class SettingsDialog { ApiKey, Language, Theme, Model, ImportSource, ImportConfirm, DeleteAll }

/** 设置页：通用（API Key、语言、主题、模型）、数据管理（导入 / 导出 / 删除全部）、关于。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenLicense: () -> Unit,
    /** 从其他应用打开或分享进来的 ZIP；接手后调用 onIncomingImportHandled */
    incomingImport: Uri? = null,
    onIncomingImportHandled: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val task by viewModel.task.collectAsStateWithLifecycle()
    val imageRestore by viewModel.imageRestore.collectAsStateWithLifecycle()
    val maskedKey by viewModel.maskedKey.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var dialog by rememberSaveable { mutableStateOf<SettingsDialog?>(null) }
    var pendingImport by rememberSaveable { mutableStateOf<Uri?>(null) }
    var pendingLink by rememberSaveable { mutableStateOf<String?>(null) }
    var linkText by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { snackbar.showSnackbar(it.resolve(context)) }
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingImport = uri
            dialog = SettingsDialog.ImportConfirm
        }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) viewModel.exportHistory(uri)
    }
    val busy = task != null

    LaunchedEffect(incomingImport, busy) {
        if (incomingImport != null && !busy) {
            pendingLink = null
            pendingImport = incomingImport
            dialog = SettingsDialog.ImportConfirm
            onIncomingImportHandled()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { BackButton(onBack, Modifier.padding(start = 12.dp)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = groupedPageColor()),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = groupedPageColor(),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .navigationBarsPadding(),
            ) {
                Section(stringResource(R.string.general)) {
                    SettingRow(rememberVectorPainter(Icons.Outlined.Key), stringResource(R.string.api_key), maskedKey ?: stringResource(R.string.api_key_not_set)) {
                        viewModel.apiKeyForm.reset()
                        dialog = SettingsDialog.ApiKey
                    }
                    SettingRow(painterResource(R.drawable.ic_globe_language_outline_14), stringResource(R.string.language), localeLabel(settings.locale)) {
                        dialog = SettingsDialog.Language
                    }
                    SettingRow(painterResource(R.drawable.ic_dark_outline_16), stringResource(R.string.theme), themeLabel(settings.theme)) {
                        dialog = SettingsDialog.Theme
                    }
                    SettingRow(rememberVectorPainter(Icons.Outlined.SmartToy), stringResource(R.string.model), settings.model.id) {
                        dialog = SettingsDialog.Model
                    }
                }
                Section(stringResource(R.string.data)) {
                    val importing = task as? DataTask.Importing
                    val downloading = task as? DataTask.Downloading
                    SettingRow(
                        painterResource(R.drawable.ic_upload_outline_14),
                        stringResource(R.string.history_import),
                        progress = importing != null || downloading != null,
                        enabled = !busy,
                    ) {
                        // 官方 App「复制下载链接」后回到这里，剪贴板里有链接就直接填上
                        linkText = readClipboardText(context)?.let(viewModel::parseLink).orEmpty()
                        dialog = SettingsDialog.ImportSource
                    }
                    val progressModifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    if (downloading != null) {
                        val bytes = downloading.progress?.bytes ?: 0L
                        val total = downloading.progress?.total
                        if (total != null && total > 0) {
                            LinearProgressIndicator(
                                progress = { (bytes.toFloat() / total).coerceIn(0f, 1f) },
                                modifier = progressModifier,
                            )
                        } else {
                            LinearProgressIndicator(modifier = progressModifier)
                        }
                    }
                    importing?.progress?.let { progress ->
                        val total = progress.total
                        if (total != null && total > 0) {
                            LinearProgressIndicator(
                                progress = { progress.done.toFloat() / total },
                                modifier = progressModifier,
                            )
                        }
                    }
                    if (imageRestore == ImageRestoreState.Pending) {
                        SettingRow(
                            rememberVectorPainter(Icons.Outlined.BrokenImage),
                            stringResource(R.string.images_restore_pending),
                            value = stringResource(R.string.retry),
                            progress = task == DataTask.RestoringImages,
                            chevron = false,
                            enabled = !busy,
                            onClick = viewModel::retryImageRestore,
                        )
                    }
                    SettingRow(
                        painterResource(R.drawable.ic_download_outline_16),
                        stringResource(R.string.history_export),
                        progress = task == DataTask.Exporting,
                        enabled = !busy,
                    ) { exportPicker.launch("deepseek_data-${LocalDate.now()}.zip") }
                    SettingRow(
                        painterResource(R.drawable.ic_trash_outline_16),
                        stringResource(R.string.delete_all),
                        value = if (task == DataTask.Deleting) stringResource(R.string.deleting) else null,
                        danger = true,
                        enabled = !busy,
                    ) { dialog = SettingsDialog.DeleteAll }
                }
                Section(stringResource(R.string.about)) {
                    SettingRow(rememberVectorPainter(Icons.Outlined.Info), stringResource(R.string.version), BuildConfig.VERSION_NAME, chevron = false, onClick = null)
                    SettingRow(rememberVectorPainter(Icons.Outlined.Gavel), stringResource(R.string.license), "MIT", onClick = onOpenLicense)
                    SettingRow(rememberVectorPainter(Icons.Outlined.Code), stringResource(R.string.source_code), trailing = painterResource(R.drawable.ic_right_up_outline_14)) {
                        uriHandler.openUri(SOURCE_CODE_URL)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    when (dialog) {
        SettingsDialog.ApiKey -> ApiKeyDialog(viewModel, hasKey = settings.hasApiKey, onDismiss = { dialog = null })
        SettingsDialog.Language -> ChoiceDialog(
            title = stringResource(R.string.language),
            options = AppLocale.entries,
            selected = settings.locale,
            label = { localeLabel(it) },
            onSelect = {
                dialog = null
                viewModel.setLocale(it)
            },
            onDismiss = { dialog = null },
        )
        SettingsDialog.Theme -> ChoiceDialog(
            title = stringResource(R.string.theme),
            options = ThemePreference.entries,
            selected = settings.theme,
            label = { themeLabel(it) },
            onSelect = {
                dialog = null
                viewModel.setTheme(it)
            },
            onDismiss = { dialog = null },
        )
        SettingsDialog.Model -> ChoiceDialog(
            title = stringResource(R.string.model),
            options = ChatModel.entries,
            selected = settings.model,
            label = { it.id },
            onSelect = {
                dialog = null
                viewModel.setModel(it)
            },
            onDismiss = { dialog = null },
        )
        SettingsDialog.ImportSource -> ImportSourceDialog(
            link = linkText,
            onLinkChange = { linkText = it },
            valid = viewModel.parseLink(linkText) != null,
            onSubmit = {
                viewModel.parseLink(linkText)?.let {
                    pendingImport = null
                    pendingLink = it
                    dialog = SettingsDialog.ImportConfirm
                }
            },
            onPickFile = {
                dialog = null
                importPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            },
            onDismiss = { dialog = null },
        )
        SettingsDialog.ImportConfirm -> ConfirmDialog(
            title = stringResource(R.string.history_import_confirm_title),
            text = stringResource(R.string.history_import_confirm_description),
            confirmLabel = stringResource(R.string.history_import_confirm),
            onConfirm = {
                dialog = null
                val link = pendingLink
                val uri = pendingImport
                if (link != null) viewModel.importHistoryFromLink(link) else if (uri != null) viewModel.importHistory(uri)
                pendingImport = null
                pendingLink = null
                linkText = ""
            },
            onDismiss = {
                dialog = null
                pendingImport = null
                pendingLink = null
            },
        )
        SettingsDialog.DeleteAll -> ConfirmDialog(
            title = stringResource(R.string.delete_all_title),
            text = stringResource(R.string.delete_all_description),
            confirmLabel = stringResource(R.string.delete),
            danger = true,
            onConfirm = {
                dialog = null
                viewModel.deleteAll()
            },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

/** 粘贴官方 App 的导出下载链接，或改为选择本地 ZIP */
@Composable
private fun ImportSourceDialog(
    link: String,
    onLinkChange: (String) -> Unit,
    valid: Boolean,
    onSubmit: () -> Unit,
    onPickFile: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_import)) },
        text = {
            Column {
                OutlinedTextField(
                    value = link,
                    onValueChange = onLinkChange,
                    label = { Text(stringResource(R.string.history_import_link)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (valid) onSubmit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = onPickFile, modifier = Modifier.padding(top = 8.dp)) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.history_import_from_file))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = valid) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

private fun readClipboardText(context: Context): String? {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
    val clip = runCatching { clipboard.primaryClip }.getOrNull() ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()
}

@Composable
private fun localeLabel(locale: AppLocale) = when (locale) {
    AppLocale.System -> stringResource(R.string.follow_system)
    AppLocale.ZhCN -> stringResource(R.string.locale_zh)
    AppLocale.EnUS -> stringResource(R.string.locale_en)
}

@Composable
private fun themeLabel(theme: ThemePreference) = when (theme) {
    ThemePreference.System -> stringResource(R.string.follow_system)
    ThemePreference.Light -> stringResource(R.string.theme_light)
    ThemePreference.Dark -> stringResource(R.string.theme_dark)
}

/** 分组：灰色小标题 + 圆角卡片 */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 14.dp, top = 18.dp, bottom = 8.dp),
    )
    Surface(shape = RoundedCornerShape(16.dp), color = groupedCardColor()) {
        Column(Modifier.padding(vertical = 4.dp)) { content() }
    }
}

@Composable
private fun SettingRow(
    icon: Painter,
    title: String,
    value: String? = null,
    danger: Boolean = false,
    enabled: Boolean = true,
    progress: Boolean = false,
    chevron: Boolean = true,
    trailing: Painter? = null,
    onClick: (() -> Unit)?,
) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color.copy(alpha = if (enabled) 1f else 0.5f), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, color = color.copy(alpha = if (enabled) 1f else 0.5f), modifier = Modifier.weight(1f))
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp).widthIn(max = 180.dp),
            )
        }
        when {
            progress -> CircularProgressIndicator(Modifier.padding(start = 8.dp).size(18.dp), strokeWidth = 2.dp)
            trailing != null -> Icon(trailing, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp).size(14.dp))
            chevron && onClick != null && !danger -> DsIcon(
                R.drawable.ic_chevron_right_outline_14,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                autoMirror = true,
                modifier = Modifier.padding(start = 6.dp).size(14.dp),
            )
        }
    }
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup()) {
                options.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = option == selected, onClick = { onSelect(option) }, role = Role.RadioButton)
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(label(option), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ApiKeyDialog(viewModel: SettingsViewModel, hasKey: Boolean, onDismiss: () -> Unit) {
    val state by viewModel.apiKeyForm.state.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = { if (!state.verifying) onDismiss() },
        title = { Text(stringResource(if (hasKey) R.string.api_key_edit else R.string.api_key_start)) },
        text = {
            Column {
                ApiKeyField(
                    state = state,
                    onValueChange = viewModel.apiKeyForm::onValueChange,
                    onSubmit = { viewModel.apiKeyForm.submit(onDismiss) },
                    modifier = Modifier.fillMaxWidth(),
                    autoFocus = true,
                )
                GetApiKeyButton()
            }
        },
        confirmButton = {
            TextButton(
                onClick = { viewModel.apiKeyForm.submit(onDismiss) },
                enabled = state.value.isNotBlank() && !state.verifying,
            ) {
                Text(stringResource(if (state.verifying) R.string.api_key_verifying else R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.verifying) { Text(stringResource(R.string.cancel)) }
        },
    )
}

