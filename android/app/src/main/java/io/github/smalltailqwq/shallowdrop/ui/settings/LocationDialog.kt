package io.github.smalltailqwq.shallowdrop.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.core.content.ContextCompat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.core.model.LocationMode
import io.github.smalltailqwq.shallowdrop.core.model.LocationPreference
import io.github.smalltailqwq.shallowdrop.core.model.locationForReminder
import io.github.smalltailqwq.shallowdrop.ui.motion.AlertDialog
import io.github.smalltailqwq.shallowdrop.ui.motion.TextButton

@Composable
fun locationModeLabel(mode: LocationMode): String = stringResource(when (mode) {
    LocationMode.Off -> R.string.location_off
    LocationMode.Gps -> R.string.location_gps
    LocationMode.Network -> R.string.location_network
    LocationMode.Custom -> R.string.location_custom
})

@Composable
fun LocationDialog(
    initial: LocationPreference,
    onSave: (LocationPreference) -> Unit,
    onDismiss: () -> Unit,
    viewModel: LocationViewModel = viewModel(factory = LocationViewModel.Factory),
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val preview by viewModel.state.collectAsStateWithLifecycle()
    var draft by rememberSaveable(stateSaver = listSaver(
        save = { listOf(it.mode.name, it.custom) },
        restore = { LocationPreference(LocationMode.valueOf(it[0]), it[1]) },
    )) { mutableStateOf(initial) }
    var saveAfterPermission by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (draft.mode == LocationMode.Gps) {
            if (saveAfterPermission && it[Manifest.permission.ACCESS_FINE_LOCATION] == true) onSave(draft)
            else viewModel.preview(draft, locale)
        }
        saveAfterPermission = false
    }
    DisposableEffect(viewModel) { onDispose { viewModel.clear() } }
    LocationDialogContent(
        initial, preview, onSave = {
            draft = it
            if (it.mode == LocationMode.Gps && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                saveAfterPermission = true
                permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            } else onSave(it)
        }, onDismiss,
        onChanged = { draft = it; viewModel.clear() },
        onPreview = {
            draft = it
            saveAfterPermission = false
            if (it.mode == LocationMode.Gps) permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            else viewModel.preview(it, locale)
        },
        onPermissions = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) },
        onSystemLocation = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
    )
}

@Composable
internal fun LocationDialogContent(
    initial: LocationPreference,
    preview: LocationPreview,
    onSave: (LocationPreference) -> Unit,
    onDismiss: () -> Unit,
    onChanged: (LocationPreference) -> Unit,
    onPreview: (LocationPreference) -> Unit,
    onPermissions: () -> Unit,
    onSystemLocation: () -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf(initial.mode) }
    var custom by rememberSaveable { mutableStateOf(initial.custom) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.location_title)) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.selectableGroup()) {
                    LocationMode.entries.forEach { option ->
                        Row(Modifier.fillMaxWidth().selectable(mode == option, role = Role.RadioButton, onClick = {
                            mode = option; onChanged(LocationPreference(mode, custom))
                        }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(mode == option, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(locationModeLabel(option))
                        }
                    }
                }
                Text(stringResource(when (mode) {
                    LocationMode.Off -> R.string.location_off_hint
                    LocationMode.Gps -> R.string.location_gps_hint
                    LocationMode.Network -> R.string.location_network_hint
                    LocationMode.Custom -> R.string.location_custom_hint
                }), style = MaterialTheme.typography.bodySmall)
                if (mode == LocationMode.Custom) {
                    OutlinedTextField(custom, onValueChange = {
                        custom = it.take(160); onChanged(LocationPreference(mode, custom))
                    }, placeholder = { Text(stringResource(R.string.location_custom_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                if (mode == LocationMode.Gps || mode == LocationMode.Network) {
                    if (preview.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    preview.place?.let { Text(stringResource(R.string.location_result, it), style = MaterialTheme.typography.bodyMedium) }
                    preview.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = { onPreview(LocationPreference(mode, custom)) }, enabled = !preview.loading) { Text(stringResource(R.string.location_refresh)) }
                }
                if (mode == LocationMode.Gps) {
                    Row {
                        TextButton(onClick = onPermissions) { Text(stringResource(R.string.location_app_permissions)) }
                        TextButton(onClick = onSystemLocation) { Text(stringResource(R.string.location_system_settings)) }
                    }
                }
                if (mode != LocationMode.Off) Text(stringResource(R.string.location_context_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(LocationPreference(mode, custom.trim())) }, enabled = mode != LocationMode.Custom || locationForReminder(custom).isNotBlank()) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
