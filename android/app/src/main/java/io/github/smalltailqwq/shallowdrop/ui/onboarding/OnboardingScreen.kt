package io.github.smalltailqwq.shallowdrop.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.ui.UiDependencies
import io.github.smalltailqwq.shallowdrop.ui.common.ApiKeyField
import io.github.smalltailqwq.shallowdrop.ui.common.ApiKeyForm
import io.github.smalltailqwq.shallowdrop.ui.common.GetApiKeyButton
import io.github.smalltailqwq.shallowdrop.ui.common.uiViewModelFactory
import io.github.smalltailqwq.shallowdrop.ui.components.AppMark

class OnboardingViewModel(deps: UiDependencies) : ViewModel() {
    val form = ApiKeyForm(viewModelScope, deps.settingsRepository, deps.verifyApiKey)
    val settings = deps.settingsRepository.settings

    companion object {
        val Factory = uiViewModelFactory { deps, _ -> OnboardingViewModel(deps) }
    }
}

/** 首次使用：输入并校验 API Key。保存成功（hasApiKey 变为 true）后进入对话页。 */
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: OnboardingViewModel = viewModel(factory = OnboardingViewModel.Factory),
) {
    val state by viewModel.form.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    LaunchedEffect(settings.hasApiKey) {
        if (settings.hasApiKey) onDone()
    }
    Box(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding(),
    ) {
        // 允许先导入历史再填 Key
        IconButton(onClick = onOpenSettings, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
            Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings))
        }
        Column(
            Modifier
                .align(Alignment.Center)
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AppMark(Modifier.size(56.dp))
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.api_key_start),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            ApiKeyField(
                state = state,
                onValueChange = viewModel.form::onValueChange,
                onSubmit = { viewModel.form.submit() },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { viewModel.form.submit() },
                enabled = state.value.isNotBlank() && !state.verifying,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                if (state.verifying) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.api_key_verifying))
                } else {
                    Text(stringResource(R.string.confirm))
                }
            }
            Spacer(Modifier.height(8.dp))
            GetApiKeyButton()
        }
    }
}
