package io.github.smalltailqwq.shallowdrop.ui.common

import android.app.Application
import android.content.Context
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import io.github.smalltailqwq.shallowdrop.ui.motion.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import io.github.smalltailqwq.shallowdrop.ui.motion.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.ui.UiDependencies
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.UiText

/** ViewModel 工厂：依赖从 Application 取（见 [UiDependencies.from]）。 */
inline fun <reified VM : ViewModel> uiViewModelFactory(
    crossinline create: (deps: UiDependencies, handle: SavedStateHandle) -> VM,
): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
        create(UiDependencies.from(app), createSavedStateHandle())
    }
}

@Composable
fun UiText.resolve(): String = when (this) {
    is UiText.Res -> stringResource(id)
    is UiText.Raw -> text
    is UiText.Plural -> pluralStringResource(id, count, count)
    is UiText.Format -> stringResource(id, arg)
}

/** 非 Composable 场景（如 Snackbar 协程）解析文案 */
fun UiText.resolve(context: Context): String = when (this) {
    is UiText.Res -> context.getString(id)
    is UiText.Raw -> text
    is UiText.Plural -> context.resources.getQuantityString(id, count, count)
    is UiText.Format -> context.getString(id, arg)
}

/** 二次确认弹窗；danger 时确认按钮用错误色。 */
@Composable
fun ConfirmDialog(
    title: String,
    text: String?,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = false,
    enabled: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = { if (enabled) onDismiss() },
        title = { Text(title) },
        text = text?.let { { Text(it, modifier = Modifier.verticalScroll(rememberScrollState())) } },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = enabled) {
                Text(confirmLabel, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = enabled) { Text(stringResource(R.string.cancel)) }
        },
    )
}
