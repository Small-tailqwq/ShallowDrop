package io.github.smalltailqwq.shallowdrop.data.settings

import android.annotation.SuppressLint
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import io.github.smalltailqwq.shallowdrop.core.model.AppLocale
import java.util.Locale

/**
 * 应用内语言切换。
 * API 33+ 交给系统 [LocaleManager]（系统持久化并自动重建 Activity，也会出现在系统的“应用语言”设置里）；
 * 更低版本把选择镜像到 SharedPreferences，供 Activity.attachBaseContext 同步读取后包装 Context，
 * 切换后由 Activity 自行 recreate。
 */
object AppLocales {
    private const val PREFS = "locale_mirror"
    private const val KEY_TAG = "tag"

    fun tag(locale: AppLocale): String? = when (locale) {
        AppLocale.System -> null
        AppLocale.ZhCN -> "zh-CN"
        AppLocale.EnUS -> "en-US"
    }

    /** 需要 Activity 手动重建才能生效（API 33 以下）。 */
    val needsRecreate: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

    // 不走 Play 应用包语言拆分，无需 Play Core 下载语言资源
    @SuppressLint("AppBundleLocaleChanges")
    fun apply(context: Context, locale: AppLocale) {
        val tag = tag(locale)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            if (tag == null) remove(KEY_TAG) else putString(KEY_TAG, tag)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val manager = context.getSystemService(LocaleManager::class.java) ?: return
            val target = LocaleList.forLanguageTags(tag.orEmpty())
            if (manager.applicationLocales != target) {
                manager.applicationLocales = target
            }
        }
    }

    /** API 33 以下按镜像的语言包装 Context；跟随系统或高版本原样返回。 */
    @SuppressLint("AppBundleLocaleChanges")
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TAG, null) ?: return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
