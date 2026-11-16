package io.github.smalltailqwq.shallowdrop.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.smalltailqwq.shallowdrop.core.model.ChatFontSize
import io.github.smalltailqwq.shallowdrop.core.model.ReplySwipeSettings
import io.github.smalltailqwq.shallowdrop.core.model.ReplySwipeDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatFontSizeSettingsTest {
    @Test
    fun chatDisplayPreferencesPersistAndReload() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val repository = DataStoreSettingsRepository(context, scope = scope)
            withTimeout(5000) { repository.loaded.first { it } }
            assertEquals(ChatFontSize.Standard, repository.settings.value.chatFontSize)
            assertEquals(false, repository.settings.value.autoReadAloud)
            assertEquals(ReplySwipeSettings(), repository.settings.value.replySwipe)
            repository.setChatFontSize(ChatFontSize.ExtraLarge)
            repository.setAutoReadAloud(true)
            val swipe = ReplySwipeSettings(true, ReplySwipeDirection.LeftToRight, 64, 128)
            repository.setReplySwipe(swipe)
            withTimeout(5000) { repository.settings.first { it.chatFontSize == ChatFontSize.ExtraLarge && it.autoReadAloud && it.replySwipe == swipe } }
            val restored = DataStoreSettingsRepository(context, scope = scope)
            withTimeout(5000) { restored.loaded.first { it } }
            assertEquals(ChatFontSize.ExtraLarge, restored.settings.value.chatFontSize)
            assertEquals(true, restored.settings.value.autoReadAloud)
            assertEquals(swipe, restored.settings.value.replySwipe)
        } finally {
            scope.cancel()
        }
    }
}
