package io.github.smalltailqwq.shallowdrop.ui.settings

import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.smalltailqwq.shallowdrop.BuildConfig
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.ui.components.BackButton
import io.github.smalltailqwq.shallowdrop.ui.easteregg.PelicanEasterEgg
import io.github.smalltailqwq.shallowdrop.ui.easteregg.RideStyle
import io.github.smalltailqwq.shallowdrop.ui.easteregg.VersionTapTrigger
import io.github.smalltailqwq.shallowdrop.ui.theme.groupedCardColor
import io.github.smalltailqwq.shallowdrop.ui.theme.groupedPageColor

const val SOURCE_CODE_URL = "https://github.com/Small-tailqwq/ShallowDrop"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit, onOpenLicense: () -> Unit, onOpenDisclaimer: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val haptic = LocalHapticFeedback.current
    val versionTaps = remember { VersionTapTrigger() }
    var rideStyle by rememberSaveable { mutableStateOf<RideStyle?>(null) }
    LifecycleResumeEffect(Unit) { onPauseOrDispose { versionTaps.reset() } }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.about), style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { BackButton(onBack, Modifier.padding(start = 12.dp)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = groupedPageColor()),
            )
        },
        containerColor = groupedPageColor(),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp).navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(24.dp))
                Image(painterResource(R.drawable.ic_app_mark), contentDescription = null, modifier = Modifier.size(88.dp))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.about_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
                Spacer(Modifier.height(16.dp))
                Surface(shape = RoundedCornerShape(16.dp), color = groupedCardColor()) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        SettingRow(rememberVectorPainter(Icons.Outlined.Info), stringResource(R.string.version), BuildConfig.VERSION_NAME, chevron = false) {
                            if (versionTaps.tap(SystemClock.uptimeMillis())) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                rideStyle = RideStyle.entries.random()
                            }
                        }
                        SettingRow(rememberVectorPainter(Icons.Outlined.Description), stringResource(R.string.disclaimer), onClick = onOpenDisclaimer)
                        SettingRow(rememberVectorPainter(Icons.Outlined.Gavel), stringResource(R.string.license), onClick = onOpenLicense)
                        SettingRow(rememberVectorPainter(Icons.Outlined.Code), stringResource(R.string.source_code), "GitHub", trailing = painterResource(R.drawable.ic_right_up_outline_14)) {
                            uriHandler.openUri(SOURCE_CODE_URL)
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    rideStyle?.let { style -> PelicanEasterEgg(style, onDismiss = { rideStyle = null }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisclaimerScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.disclaimer), style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { BackButton(onBack, Modifier.padding(start = 12.dp)) },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            SelectionContainer {
                Text(
                    stringResource(R.string.disclaimer_body),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(20.dp).navigationBarsPadding(),
                )
            }
        }
    }
}
