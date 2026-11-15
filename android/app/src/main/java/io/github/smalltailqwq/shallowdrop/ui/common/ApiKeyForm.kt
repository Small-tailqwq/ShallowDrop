package io.github.smalltailqwq.shallowdrop.ui.common

import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.core.contract.SettingsRepository
import io.github.smalltailqwq.shallowdrop.core.model.ApiException
import io.github.smalltailqwq.shallowdrop.core.model.ReplyErrorKind
import io.github.smalltailqwq.shallowdrop.ui.chat.reply.errorCodeMessage
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 校验失败的提示；formatArg 非空时套用 api_key_verification_failed。 */
sealed interface ApiKeyError {
    data object Invalid : ApiKeyError

    data object Network : ApiKeyError

    data class Failed(val messageRes: Int?, val message: String) : ApiKeyError
}

data class ApiKeyFormState(
    val value: String = "",
    val verifying: Boolean = false,
    val error: ApiKeyError? = null,
)

/**
 * API Key 表单，对照 useApiKeyForm.ts：先用 GET /models 校验，通过才加密保存。
 * 引导页与设置页共用。
 */
class ApiKeyForm(
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val verify: suspend (String) -> Unit,
) {
    private val mutableState = MutableStateFlow(ApiKeyFormState())
    val state: StateFlow<ApiKeyFormState> = mutableState.asStateFlow()

    fun onValueChange(value: String) = mutableState.update { it.copy(value = value) }

    fun reset() {
        mutableState.value = ApiKeyFormState()
    }

    fun submit(onSaved: () -> Unit = {}) {
        val apiKey = state.value.value.trim()
        if (apiKey.isEmpty() || state.value.verifying) return
        mutableState.update { it.copy(verifying = true, error = null) }
        scope.launch {
            val error = try {
                verify(apiKey)
                settings.setApiKey(apiKey)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                when {
                    e.status == 401 -> ApiKeyError.Invalid
                    e.kind == ReplyErrorKind.Network -> ApiKeyError.Network
                    else -> ApiKeyError.Failed(errorCodeMessage(e.code), e.message)
                }
            } catch (_: IOException) {
                ApiKeyError.Network
            } catch (e: Exception) {
                ApiKeyError.Failed(null, e.message ?: e.javaClass.simpleName)
            }
            if (error == null) {
                mutableState.value = ApiKeyFormState()
                onSaved()
            } else {
                mutableState.update { it.copy(verifying = false, error = error) }
            }
        }
    }
}

/** 错误文案资源选择，供 Composable 使用 */
fun ApiKeyError.messageRes(): Int = when (this) {
    ApiKeyError.Invalid -> R.string.api_key_invalid
    ApiKeyError.Network -> R.string.error_network
    is ApiKeyError.Failed -> R.string.api_key_verification_failed
}
