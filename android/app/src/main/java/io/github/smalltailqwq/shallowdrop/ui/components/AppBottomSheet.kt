package io.github.smalltailqwq.shallowdrop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.ui.motion.pressClickable
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBottomSheet(
    onDismissRequest: () -> Unit,
    skipPartiallyExpanded: Boolean = false,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    content: @Composable ColumnScope.() -> Unit,
) {
    val window = LocalWindowInfo.current.containerSize
    val landscape = window.width > window.height
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = landscape || skipPartiallyExpanded)
    val scope = rememberCoroutineScope()
    val expandLabel = stringResource(R.string.sheet_expand)
    val collapseLabel = stringResource(R.string.sheet_collapse)
    val closeLabel = stringResource(R.string.sheet_close)
    fun close() { scope.launch { state.hide(); if (!state.isVisible) onDismissRequest() } }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = state,
        sheetMaxWidth = if (landscape) Dp.Unspecified else BottomSheetDefaults.SheetMaxWidth,
        contentWindowInsets = { WindowInsets.safeDrawing },
        containerColor = containerColor,
        dragHandle = null,
    ) {
        val partial = state.currentValue == SheetValue.PartiallyExpanded
        Box(
            Modifier.align(Alignment.CenterHorizontally).padding(vertical = 4.dp).size(64.dp, 40.dp)
                .clip(CircleShape).pressClickable(role = Role.Button) {
                    when {
                        partial -> scope.launch { state.expand() }
                        state.hasPartiallyExpandedState -> scope.launch { state.partialExpand() }
                        else -> close()
                    }
                }.semantics {
                    contentDescription = if (partial) expandLabel else if (state.hasPartiallyExpandedState) collapseLabel else closeLabel
                    dismiss(closeLabel) { close(); true }
                    if (partial) expand(expandLabel) { scope.launch { state.expand() }; true }
                    else if (state.hasPartiallyExpandedState) collapse(collapseLabel) { scope.launch { state.partialExpand() }; true }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(32.dp, 4.dp).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
        }
        content()
    }
}
