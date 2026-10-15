package com.example.service

import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.BlockedSearchKeyword
import com.example.service.accessibility.handlers.AccessibilityHandlerContext
import com.example.service.accessibility.handlers.SearchKeywordHandler
import com.example.service.search.SearchDetectorRouter
import com.example.service.search.SearchKeywordEngine
import com.example.service.search.SearchPlaceholderFilter
import com.example.service.search.YouTubeSearchDetector
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchKeyboardDismissalFixTest {

    private lateinit var engine: SearchKeywordEngine
    private var backActionCount = 0
    private var toastMessage: String? = null
    private lateinit var handlerContext: AccessibilityHandlerContext
    private lateinit var handler: SearchKeywordHandler

    @Before
    fun setup() {
        backActionCount = 0
        toastMessage = null

        engine = SearchKeywordEngine().apply {
            isYouTubeEnabled = true
            updateKeywords(
                listOf(
                    BlockedSearchKeyword(
                        id = 1L,
                        keyword = "football",
                        normalizedKeyword = "football",
                        enabled = true,
                        matchMode = "CONTAINS"
                    ),
                    BlockedSearchKeyword(
                        id = 2L,
                        keyword = "খারাপ গান",
                        normalizedKeyword = "খারাপ গান",
                        enabled = true,
                        matchMode = "CONTAINS"
                    )
                )
            )
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
            showToast = { msg, _ -> toastMessage = msg }
        )

        handler = SearchKeywordHandler { handlerContext }
    }

    @Test
    fun testPlaceholderFilter_identifiesPlaceholders() {
        assertTrue(SearchPlaceholderFilter.isPlaceholderOrHint("Search"))
        assertTrue(SearchPlaceholderFilter.isPlaceholderOrHint("Search..."))
        assertTrue(SearchPlaceholderFilter.isPlaceholderOrHint("Search videos"))
        assertTrue(SearchPlaceholderFilter.isPlaceholderOrHint("Search YouTube"))
        assertTrue(SearchPlaceholderFilter.isPlaceholderOrHint("সার্চ করুন"))
        assertTrue(SearchPlaceholderFilter.isPlaceholderOrHint("ভিডিও খুঁজুন"))
        assertTrue(SearchPlaceholderFilter.isPlaceholderOrHint(null))

        // Genuine queries must not be treated as placeholders
        assertFalse(SearchPlaceholderFilter.isPlaceholderOrHint("football highlights"))
        assertFalse(SearchPlaceholderFilter.isPlaceholderOrHint("bangla news"))
    }

    @Test
    fun testFocusEventsNeverBlock() {
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_FOCUSED).apply {
            packageName = "com.google.android.youtube"
        }
        val handled = handler.handleEvent("com.google.android.youtube", event) { null }
        assertFalse("Focus event must never be handled/blocked", handled)
        assertEquals(0, backActionCount)
    }

    @Test
    fun testNonMatchingQueryRemainsUntouched() {
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
            packageName = "com.google.android.youtube"
            text.add("educational physics lectures")
        }

        val handled = handler.handleEvent("com.google.android.youtube", event) { null }
        assertFalse("Non-matching query must remain untouched", handled)
        assertEquals("No back action should be triggered", 0, backActionCount)
    }

    @Test
    fun testQueryWithBlockedKeywordClearsEntireQueryAndPreventsNavigation() {
        // Query containing blocked keyword "football": "best football highlights 2026"
        var performedAction: Int? = null
        var setBundleArgs: Bundle? = null

        val mockSearchNode = object : AccessibilityNodeInfo() {
            override fun isEditable(): Boolean = true
            override fun getViewIdResourceName(): String = "com.google.android.youtube:id/search_edit_text"
            override fun getContentDescription(): CharSequence = "Search"
            override fun getText(): CharSequence = "best football highlights 2026"
            override fun performAction(action: Int, arguments: Bundle?): Boolean {
                performedAction = action
                setBundleArgs = arguments
                return true
            }
        }

        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
            packageName = "com.google.android.youtube"
            text.add("best football highlights 2026")
        }

        val handled = handler.handleEvent("com.google.android.youtube", event) { mockSearchNode }

        assertTrue("Event containing blocked keyword must be handled", handled)
        assertEquals("Navigation-prevention back action must execute", 1, backActionCount)
        assertEquals("ACTION_SET_TEXT must be called to clear search field", AccessibilityNodeInfo.ACTION_SET_TEXT, performedAction)

        val textArg = setBundleArgs?.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)
        assertEquals("Search field must be cleared to an EMPTY STRING, never a partial substring", "", textArg?.toString())
        assertTrue("Toast must notify user", toastMessage?.contains("football") == true)
    }

    @Test
    fun testRepeatedAccessibilityEventsDoNotCauseInfiniteLoop() {
        val mockSearchNode = object : AccessibilityNodeInfo() {
            override fun isEditable(): Boolean = true
            override fun getViewIdResourceName(): String = "com.google.android.youtube:id/search_edit_text"
            override fun getContentDescription(): CharSequence = "Search"
            override fun getText(): CharSequence = "best football highlights 2026"
            override fun performAction(action: Int, arguments: Bundle?): Boolean = true
        }

        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
            packageName = "com.google.android.youtube"
            text.add("best football highlights 2026")
        }

        // First event
        val firstHandled = handler.handleEvent("com.google.android.youtube", event) { mockSearchNode }
        assertTrue(firstHandled)
        assertEquals("First blocked event triggers back action", 1, backActionCount)

        // Immediate duplicate event (e.g. caused by clearing text or window refresh)
        val duplicateEvent = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
            packageName = "com.google.android.youtube"
            text.add("best football highlights 2026")
        }
        val secondHandled = handler.handleEvent("com.google.android.youtube", duplicateEvent) { mockSearchNode }
        assertTrue(secondHandled)
        assertEquals("Debounce suppression prevents repeated back action", 1, backActionCount)
    }

    @Test
    fun testYouTubeBlockedKeywordClearsEntireQueryAndPreventsNavigation() {
        var performedAction: Int? = null
        var setBundleArgs: Bundle? = null

        val mockSearchNode = object : AccessibilityNodeInfo() {
            override fun isEditable(): Boolean = true
            override fun getViewIdResourceName(): String = "com.google.android.youtube:id/search_edit_text"
            override fun getContentDescription(): CharSequence = "Search"
            override fun getText(): CharSequence = "নতুন খারাপ গান 2026"
            override fun performAction(action: Int, arguments: Bundle?): Boolean {
                performedAction = action
                setBundleArgs = arguments
                return true
            }
        }

        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
            packageName = "com.google.android.youtube"
            text.add("নতুন খারাপ গান 2026")
        }

        val handled = handler.handleEvent("com.google.android.youtube", event) { mockSearchNode }

        assertTrue("Event containing blocked Bangla keyword must be handled", handled)
        assertEquals("Navigation back action must be called", 1, backActionCount)
        assertEquals("ACTION_SET_TEXT should be used", AccessibilityNodeInfo.ACTION_SET_TEXT, performedAction)
        val textArg = setBundleArgs?.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)
        assertEquals("Entire query cleared to empty string", "", textArg?.toString())
    }
}
