package io.github.smalltailqwq.shallowdrop.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.smalltailqwq.shallowdrop.core.model.AppSettings
import io.github.smalltailqwq.shallowdrop.core.model.CompactionThresholds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 仓库读写的是进程级单例 `preferencesDataStore("settings")`：同一沙箱里先跑的设置测试会先建好实例，
 * 测试另建 DataStore 写入的种子值对它不可见（曾表现为期望 75 实际 65）。
 * 因此越界值的归一化直接测 [CompactionThresholds.normalized]（读取时 toSettings 调用的就是它），
 * 持久化只经仓库公开接口往返。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CompactionSettingsTest {
    @Test fun outOfRangeStoredThresholdsNormalizeAsAPair() {
        assertEquals(CompactionThresholds(65, 80), CompactionThresholds())
        assertEquals(true, AppSettings().compactionEnabled)
        assertEquals(CompactionThresholds(75, 80), CompactionThresholds.normalized(94, 95))
        assertEquals(CompactionThresholds(1, 6), CompactionThresholds.normalized(0, 0))
        assertEquals(CompactionThresholds(23, 78), CompactionThresholds.normalized(23, 78))
    }

    @Test fun thresholdsPersistAndReloadAsAPair() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = DataStoreSettingsRepository(context, scope = scope)
            withTimeout(5000) { repository.loaded.first { it } }
            val changed = CompactionThresholds(23, 78)
            repository.setCompactionSettings(changed, enabled = false)
            withTimeout(5000) { repository.settings.first { it.compactionThresholds == changed && !it.compactionEnabled } }
            val restored = DataStoreSettingsRepository(context, scope = scope)
            withTimeout(5000) { restored.loaded.first { it } }
            assertEquals(changed, restored.settings.value.compactionThresholds)
            assertEquals(false, restored.settings.value.compactionEnabled)
            restored.setCompactionSettings(changed, enabled = true)
            withTimeout(5000) { repository.settings.first { it.compactionEnabled } }
            assertEquals(changed, repository.settings.value.compactionThresholds)
        } finally { scope.cancel() }
    }
}
