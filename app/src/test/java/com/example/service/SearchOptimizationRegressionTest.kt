package com.example.service

import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.BlockedSearchKeyword
import com.example.service.accessibility.handlers.AccessibilityHandlerContext
import com.example.service.accessibility.handlers.SearchKeywordHandler
import com.example.service.search.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchOptimizationRegressionTest {

    private lateinit var engine: SearchKeywordEngine
    private var backActionCount = 0
    private var toastCount = 0
    private var lastToastMsg: String? = null
    private lateinit var handlerContext: AccessibilityHandlerContext
    private lateinit var handler: SearchKeywordHandler

    @Before
    fun setup() {
        backActionCount = 0
        toastCount = 0
        lastToastMsg = null

        engine = SearchKeywordEngine().apply {
            isYouTubeEnabled = true
        }

        handlerContext = AccessibilityHandlerContext(
            service = Robolectric.setupService(FocusAccessibilityService::class.java),
            searchKeywordEngine = engine,
            searchDetectorRouter = SearchDetectorRouter(),
            isPackageSystemCore = { false },
            getSetting = { "true" },
            isSettingEnabled = { _, _ -> true },
            getLockedAppsMap = { emptyMap() },
            getKnownAppsSet = { emptySet() },
            performHomeAction = { false },
            performBackAction = {
                backActionCount++
                true
            },
            showToast = { msg, _ ->
                toastCount++
                lastToastMsg = msg
            }
        )

        handler = SearchKeywordHandler { handlerContext }
    }

    @Test
    fun testKeywordNormalization_validatesAndNormalizesThroughSnapshot() {
        // Test that stale or corrupted stored "normalizedKeyword" is ignored,
        // and raw keyword is properly normalized through KeywordNormalizer.
        val keywords = listOf(
            BlockedSearchKeyword(
                id = 1L,
                keyword = "  f\u200B\u200Coot\u200Dball  ",
                normalizedKeyword = "stale_corrupted_value",
                enabled = true,
                matchMode = "CONTAINS"
            )
        )

        val snapshot = KeywordSnapshot.create(keywords)
        assertEquals(1, snapshot.size)
        val item = snapshot[0]
        assertEquals("football", item.normalizedKeyword)
        assertEquals("f\u200B\u200Coot\u200Dball", item.originalKeyword)
    }

    @Test
    fun testDuplicateNormalizedKeywords_deduplicatedInSnapshot() {
        // Multiple raw keywords that normalize to the same value
        val keywords = listOf(
            BlockedSearchKeyword(
                id = 10L,
                keyword = "Football",
                normalizedKeyword = "",
                enabled = true,
                matchMode = "CONTAINS"
            ),
            BlockedSearchKeyword(
                id = 11L,
                keyword = "football",
                normalizedKeyword = "",
                enabled = true,
                matchMode = "CONTAINS"
            ),
            BlockedSearchKeyword(
                id = 12L,
                keyword = "  FOOTBALL  ",
                normalizedKeyword = "",
                enabled = true,
                matchMode = "EXACT"
            ),
            BlockedSearchKeyword(
                id = 13L,
                keyword = "f\u200B\u200Cootball",
                normalizedKeyword = "",
                enabled = true,
                matchMode = "CONTAINS"
            )
        )

        val snapshot = KeywordSnapshot.create(keywords)
        assertEquals("Duplicate normalized keywords must be collapsed into 1 entry", 1, snapshot.size)
        assertEquals(10L, snapshot[0].id)
        assertEquals("football", snapshot[0].normalizedKeyword)
    }

    @Test
    fun testBlankAndInvalidKeywords_filteredSafely() {
        val keywords = listOf(
            BlockedSearchKeyword(id = 1L, keyword = "", normalizedKeyword = "", enabled = true),
            BlockedSearchKeyword(id = 2L, keyword = "   ", normalizedKeyword = "", enabled = true),
            BlockedSearchKeyword(id = 3L, keyword = "\u200B\u200C\u200D", normalizedKeyword = "", enabled = true),
            BlockedSearchKeyword(id = 4L, keyword = "valid", normalizedKeyword = "", enabled = false), // disabled
            BlockedSearchKeyword(id = 5L, keyword = "allowed_block", normalizedKeyword = "", enabled = true)
        )

        val snapshot = KeywordSnapshot.create(keywords)
        assertEquals(1, snapshot.size)
        assertEquals("allowed_block", snapshot[0].normalizedKeyword)
    }

    @Test
    fun testKeywordSnapshotReplacement_isThreadSafeAndAtomic() = runBlocking {
        // Concurrently update keywords and evaluate queries
        val initialKeywords = (1..50).map { i ->
            BlockedSearchKeyword(id = i.toLong(), keyword = "keyword$i", normalizedKeyword = "", enabled = true)
        }
        engine.updateKeywords(initialKeywords)

        val updateJobs = (1..10).map { batch ->
            async(Dispatchers.Default) {
                val batchKeywords = (1..50).map { i ->
                    BlockedSearchKeyword(
                        id = (batch * 100 + i).toLong(),
                        keyword = "batch${batch}_key$i",
                        normalizedKeyword = "",
                        enabled = true
                    )
                }
                engine.updateKeywords(batchKeywords)
            }
        }

        val evalJobs = (1..10).map {
            async(Dispatchers.Default) {
                for (q in 0..100) {
                    val res = engine.evaluate("batch1_key5", SearchBlockingTarget.YOUTUBE)
                    assertNotNull(res)
                }
            }
        }

        updateJobs.awaitAll()
        evalJobs.awaitAll()

        assertTrue("Snapshot count must be positive after concurrent operations", engine.getActiveKeywordCount() > 0)
    }

    @Test
    fun testNormalizedDuplicateQueries_debouncedSafely() {
        engine.updateKeywords(
            listOf(
                BlockedSearchKeyword(id = 1L, keyword = "football", normalizedKeyword = "football", enabled = true)
            )
        )

        val mockNode = object : AccessibilityNodeInfo() {
            override fun isEditable(): Boolean = true
            override fun getViewIdResourceName(): String = "com.google.android.youtube:id/search_edit_text"
            override fun getText(): CharSequence = "Football"
            override fun performAction(action: Int, arguments: Bundle?): Boolean = true
        }

        val event1 = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
            packageName = "com.google.android.youtube"
            text.add("Football")
        }

        val handled1 = handler.handleEvent("com.google.android.youtube", event1) { mockNode }
        assertTrue(handled1)
        assertEquals(1, backActionCount)
        assertEquals(1, toastCount)

        // Same query with different casing ("football") within debounce window must be suppressed
        val event2 = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
            packageName = "com.google.android.youtube"
            text.add("football")
        }

        val handled2 = handler.handleEvent("com.google.android.youtube", event2) { mockNode }
        assertTrue("Debounced event must still return true as handled", handled2)
        assertEquals("Duplicate normalized query must NOT trigger another back action", 1, backActionCount)
        assertEquals("Duplicate normalized query must NOT trigger another toast", 1, toastCount)

        // Different query ("basketball") is NOT suppressed
        engine.updateKeywords(
            listOf(
                BlockedSearchKeyword(id = 1L, keyword = "football", normalizedKeyword = "football", enabled = true),
                BlockedSearchKeyword(id = 2L, keyword = "basketball", normalizedKeyword = "basketball", enabled = true)
            )
        )
        val event3 = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
            packageName = "com.google.android.youtube"
            text.add("basketball")
        }
        val handled3 = handler.handleEvent("com.google.android.youtube", event3) { mockNode }
        assertTrue(handled3)
        assertEquals("New query must trigger back action", 2, backActionCount)
        assertEquals("New query must trigger toast", 2, toastCount)
    }

    @Test
    fun testSearchFieldDetector_falsePositivePrevention() {
        // 1. Comment box with comment resource ID must NEVER be detected as search field
        val commentNode = object : AccessibilityNodeInfo() {
            override fun isEditable(): Boolean = true
            override fun getViewIdResourceName(): String = "com.google.android.youtube:id/comment_edit_text"
            override fun getContentDescription(): CharSequence = "Add a comment"
            override fun getText(): CharSequence = "search for funny clips"
        }
        assertFalse(
            "Comment box must be excluded from search field detection",
            SearchFieldDetector.isSearchField(commentNode)
        )

        // 2. Regular EditText where user typed "search" must NOT be detected as search field
        val notesNode = object : AccessibilityNodeInfo() {
            override fun isEditable(): Boolean = true
            override fun getViewIdResourceName(): String = "com.example.notes:id/note_body"
            override fun getText(): CharSequence = "I will search tomorrow"
        }
        assertFalse(
            "Arbitrary text containing 'search' must not mark field as search field",
            SearchFieldDetector.isSearchField(notesNode)
        )

        // 3. Static button/label with content description "Search" is NOT an editable search field
        val buttonNode = object : AccessibilityNodeInfo() {
            override fun isEditable(): Boolean = false
            override fun getClassName(): CharSequence = "android.widget.ImageView"
            override fun getContentDescription(): CharSequence = "Search"
        }
        assertFalse(
            "Static ImageView or Button must not be classified as a search input field",
            SearchFieldDetector.isSearchField(buttonNode)
        )

        // 4. Legitimate search field matches via Resource ID
        val realSearchNode = object : AccessibilityNodeInfo() {
            override fun isEditable(): Boolean = true
            override fun getViewIdResourceName(): String = "com.google.android.youtube:id/search_edit_text"
            override fun getText(): CharSequence = "news"
        }
        assertTrue(
            "Known search resource ID must be identified as search field",
            SearchFieldDetector.isSearchField(realSearchNode)
        )
    }

    @Test
    fun testIrrelevantAccessibilityEvents_exitEarly() {
        // Focus events exit early
        val focusEvent = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_FOCUSED).apply {
            packageName = "com.google.android.youtube"
        }
        assertFalse(handler.handleEvent("com.google.android.youtube", focusEvent) { null })

        // Non-target packages exit early
        val otherAppEvent = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED).apply {
            packageName = "com.unrelated.calculator"
        }
        assertFalse(handler.handleEvent("com.unrelated.calculator", otherAppEvent) { null })
    }

    @Test
    fun testLargeKeywordList_performanceAndConcurrency() = runBlocking {
        // 500 keywords
        val keywords = (1..500).map { i ->
            BlockedSearchKeyword(
                id = i.toLong(),
                keyword = "blocked_term_$i",
                normalizedKeyword = "blocked_term_$i",
                enabled = true,
                matchMode = if (i % 2 == 0) "CONTAINS" else "EXACT"
            )
        }
        engine.updateKeywords(keywords)

        val matchCount = AtomicInteger(0)
        val startTime = System.currentTimeMillis()

        // Evaluate 2,000 queries across 4 coroutines
        val jobs = (1..4).map {
            async(Dispatchers.Default) {
                for (q in 1..500) {
                    val query = if (q % 5 == 0) "watching blocked_term_42 today" else "safe query $q"
                    val res = engine.evaluate(query, SearchBlockingTarget.YOUTUBE)
                    if (res is SearchPolicyResult.Blocked) {
                        matchCount.incrementAndGet()
                    }
                }
            }
        }
        jobs.awaitAll()
        val duration = System.currentTimeMillis() - startTime

        assertEquals(400, matchCount.get()) // 100 matches per coroutine * 4 = 400
        assertTrue("2000 evaluations over 500 keywords must complete quickly (took ${duration}ms)", duration < 2000)
    }

    @Test
    fun testWordMatchSemanticsPreserved() {
        // User directive requirement:
        // "Do NOT fix or modify the existing 'WORD' vs 'CONTAINS' matching behavior in this task.
        // Leave the current 'WORD' matching implementation exactly as it is."
        engine.updateKeywords(
            listOf(
                BlockedSearchKeyword(
                    id = 1L,
                    keyword = "cat",
                    normalizedKeyword = "cat",
                    enabled = true,
                    matchMode = "WORD"
                )
            )
        )

        // In existing implementation, WORD uses contains: "scatter" contains "cat" -> Blocked
        val result = engine.evaluate("scatter", SearchBlockingTarget.YOUTUBE)
        assertTrue("WORD matching semantics must remain identical to existing implementation", result is SearchPolicyResult.Blocked)
    }
}
