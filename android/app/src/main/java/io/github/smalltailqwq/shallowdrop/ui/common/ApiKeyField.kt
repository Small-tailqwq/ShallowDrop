package io.github.smalltailqwq.shallowdrop.ui.common

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import io.github.smalltailqwq.shallowdrop.ui.motion.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import io.github.smalltailqwq.shallowdrop.ui.motion.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import io.github.smalltailqwq.shallowdrop.R

const val API_KEYS_URL = "https://platform.deepseek.com/api_keys"
const val TOP_UP_URL = "https://platform.deepseek.com/top_up"

@Composable
fun ApiKeyError.text(): String = when (this) {
    is ApiKeyError.Failed -> stringResource(messageRes(), messageRes?.let { stringResource(it) } ?: message)
    else -> stringResource(messageRes())
}

/** API Key 输入框：默认掩码，可切换显示；回车提交。 */
@Composable
fun ApiKeyField(
    state: ApiKeyFormState,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    if (autoFocus) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }
    OutlinedTextField(
        value = state.value,
        onValueChange = onValueChange,
        modifier = modifier.focusRequester(focusRequester),
        enabled = !state.verifying,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.api_key_placeholder)) },
        isError = state.error != null,
        supportingText = state.error?.let { { Text(it.text()) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
            autoCorrectEnabled = false,
        ),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = null)
            }
        },
    )
}

/** 跳转 DeepSeek 开放平台获取 Key */
@Composable
fun GetApiKeyButton(modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    TextButton(onClick = { uriHandler.openUri(API_KEYS_URL) }, modifier = modifier) {
        Text(stringResource(R.string.api_key_get))
    }
}
