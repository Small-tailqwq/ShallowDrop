package io.github.smalltailqwq.shallowdrop

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.core.content.IntentCompat
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.smalltailqwq.shallowdrop.data.settings.AppLocales
import io.github.smalltailqwq.shallowdrop.ui.ShallowDropApp
import io.github.smalltailqwq.shallowdrop.ui.UiDependencies
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** 单 Activity，内容为 [ShallowDropApp]。 */
class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        // API 33 以下的应用内语言
        super.attachBaseContext(AppLocales.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val deps = UiDependencies.from(application)

        if (AppLocales.needsRecreate) {
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    deps.settingsLoaded.collect { loaded ->
                        if (!loaded) return@collect
                        deps.settingsRepository.settings
                            .map { it.locale }
                            .distinctUntilChanged()
                            .drop(1)
                            .collect { recreate() }
                    }
                }
            }
        }

        // 重建（如切换语言）时 savedInstanceState 非空，不重复处理同一个 Intent
        if (savedInstanceState == null) handleImportIntent(intent, deps)

        setContent {
            ShallowDropApp(deps, onDarkThemeChange = ::applySystemBars)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleImportIntent(intent, UiDependencies.from(application))
    }

    /** 浏览器下载完官方导出的 ZIP 后「用 ShallowDrop 打开」或分享进来时，转交设置页确认导入 */
    private fun handleImportIntent(intent: Intent?, deps: UiDependencies) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return
        deps.incomingImport.value = uri
    }

    /** 按应用主题（而非系统深浅色）设置状态栏 / 导航栏图标颜色 */
    private fun applySystemBars(dark: Boolean) {
        val style = if (dark) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
}
