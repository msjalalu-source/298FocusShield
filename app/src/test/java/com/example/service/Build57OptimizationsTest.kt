package com.example.service

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.AppRepository
import com.example.data.LockedApp
import com.example.service.search.BaseMediaSearchDetector
import com.example.service.search.YouTubeSearchDetector
import com.example.service.systemui.SystemUiDetector
import com.example.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.room.Room

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Build57OptimizationsTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: AppRepository
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Dispatchers.setMain(testDispatcher)
        AppRepository.resetForTesting()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = AppRepository(database.appDao(), context)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        database.close()
    }

    // =============================================================================================
    // 3. YouTube Detector Deduplication Tests
    // =============================================================================================

    @Test
    fun testYouTubeDetector_targetAndPackageHandling() {
        // Verify YouTube detector
        assertTrue(YouTubeSearchDetector.canHandle("com.google.android.youtube"))
        assertTrue(YouTubeSearchDetector.canHandle("com.google.android.apps.youtube.kids"))
        assertTrue(YouTubeSearchDetector.canHandle("com.google.android.youtube.music"))
        assertFalse(YouTubeSearchDetector.canHandle("com.example.unrelated"))

        // Inherits from BaseMediaSearchDetector
        assertTrue(YouTubeSearchDetector is BaseMediaSearchDetector)
    }

    // =============================================================================================
    // 4. Compose Search Debounce Tests
    // =============================================================================================

    @Test
    fun testSearchQueryDebounce_flowVerification() = runTest {
        val queryFlow = kotlinx.coroutines.flow.MutableStateFlow("")
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        val debouncedFlow = queryFlow
            .debounce { query -> if (query.isEmpty()) 0L else 150L }
            .distinctUntilChanged()

        val results = mutableListOf<String>()
        val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            debouncedFlow.collect { results.add(it) }
        }

        // Rapid typing: Emit characters with only 50ms between strokes
        queryFlow.value = "A"
        advanceTimeBy(50)
        queryFlow.value = "Al"
        advanceTimeBy(50)
        queryFlow.value = "Alph"
        advanceTimeBy(50)
        queryFlow.value = "Alpha"

        // No emission yet because typing was faster than the 150ms debounce threshold
        assertEquals(listOf(""), results)

        // Advance 160ms after last keypress
        advanceTimeBy(160)
        assertEquals(listOf("", "Alpha"), results)

        // Clear query -> should emit immediately without 150ms delay
        queryFlow.value = ""
        advanceTimeBy(1)
        assertEquals(listOf("", "Alpha", ""), results)

        collectJob.cancel()
    }
}
