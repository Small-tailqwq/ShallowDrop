package io.github.smalltailqwq.shallowdrop.ui.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import io.github.smalltailqwq.shallowdrop.R

/** 窗口动画保留 Material 对话框的焦点管理，同时覆盖进入和退出。 */
@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: @Composable () -> Unit,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    androidx.compose.material3.AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        icon = icon,
        title = {
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window
            SideEffect { window?.setWindowAnimations(R.style.AppDialogAnimation) }
            title()
        },
        text = text,
        properties = properties,
    )
}
