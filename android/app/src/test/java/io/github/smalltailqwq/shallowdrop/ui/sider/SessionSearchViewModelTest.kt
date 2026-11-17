package io.github.smalltailqwq.shallowdrop.ui.sider

import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import io.github.smalltailqwq.shallowdrop.core.contract.ChatRepository
import io.github.smalltailqwq.shallowdrop.core.contract.MessageMatch
import io.github.smalltailqwq.shallowdrop.ui.fake.FakeChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SessionSearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun changingQueryDiscardsOldResultsAndCancelsPendingSearch() = runTest(dispatcher) {
        val calls = mutableListOf<String>()
        var cancelled = false
        val repository = object : ChatRepository by FakeChatRepository(ApplicationProvider.getApplicationContext(), backgroundScope) {
            override suspend fun searchMessages(query: String): List<MessageMatch> {
                calls += query
                if (query == "slow") {
                    try { awaitCancellation() } finally { cancelled = true }
                }
                return listOf(MessageMatch("s", "m", 0, false, query))
            }
        }
        val viewModel = SessionSearchViewModel(repository)
        val store = ViewModelStore().apply { put("search", viewModel) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.matches.collect {} }
        try {
            viewModel.setQuery("first")
            runCurrent()
            advanceTimeBy(200)
            runCurrent()
            assertEquals("first", viewModel.matches.value?.query)

            viewModel.setQuery("slow")
            runCurrent()
            assertNull(viewModel.matches.value)
            advanceTimeBy(200)
            runCurrent()
            viewModel.setQuery("latest")
            runCurrent()
            assertTrue(cancelled)
            assertNull(viewModel.matches.value)
            advanceTimeBy(200)
            runCurrent()
            assertEquals("latest", viewModel.matches.value?.query)

            viewModel.setQuery(" latest ")
            runCurrent()
            advanceTimeBy(200)
            runCurrent()
            assertEquals(listOf("first", "slow", "latest"), calls)

            viewModel.setQuery("")
            runCurrent()
            assertNull(viewModel.matches.value)
        } finally {
            store.clear()
        }
    }
}
