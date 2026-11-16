package io.github.smalltailqwq.shallowdrop.data.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import io.github.smalltailqwq.shallowdrop.core.model.CompactionThresholds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CompactionSettingsTest {
    @Test fun storedThresholdsNormalizePersistAndReloadAsAPair() = runBlocking {
        assertEquals(CompactionThresholds(65, 80), CompactionThresholds())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val seedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val seed = PreferenceDataStoreFactory.create(scope = seedScope) { context.preferencesDataStoreFile("settings") }
        seed.edit {
            it[intPreferencesKey("compaction_background_percent")] = 94
            it[intPreferencesKey("compaction_foreground_percent")] = 95
        }
        seedScope.coroutineContext.job.cancelAndJoin()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = DataStoreSettingsRepository(context, scope = scope)
            withTimeout(5000) { repository.loaded.first { it } }
            assertEquals(CompactionThresholds(75, 80), repository.settings.value.compactionThresholds)
            assertEquals(true, repository.settings.value.compactionEnabled)
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
