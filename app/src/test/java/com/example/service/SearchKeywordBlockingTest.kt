package com.example.service

import android.view.accessibility.AccessibilityEvent
import com.example.data.*
import com.example.service.search.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchKeywordBlockingTest {

    private lateinit var engine: SearchKeywordEngine

    @Before
    fun setup() {
        engine = SearchKeywordEngine().apply {
            isYouTubeEnabled = true
        }
    }

    @Test
    fun testAddCustomKeywordSuccessfully() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repo = AppRepository.getInstance(context)

        val result = repo.addBlockedKeyword("cricket", "CONTAINS")
        assertTrue("Adding custom keyword should succeed", result.isSuccess)
        val id = result.getOrNull()
        assertNotNull(id)
        assertTrue(id!! > 0)

        val activeList = repo.getActiveBlockedKeywords()
        assertTrue("Active list must contain added keyword", activeList.any { it.keyword == "cricket" })
    }

    @Test
    fun testDuplicateKeywordSafelyRejected() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repo = AppRepository.getInstance(context)

        repo.addBlockedKeyword("tennis", "CONTAINS")
        val duplicateResult = repo.addBlockedKeyword("  TENNIS  ", "CONTAINS")
        assertTrue("Duplicate keyword must return failure Result", duplicateResult.isFailure)
        assertTrue(duplicateResult.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun testEnabledKeywordMatchesQuery() {
        engine.updateKeywords(
            listOf(
                BlockedSearchKeyword(
                    id = 1L,
                    keyword = "football",
                    normalizedKeyword = "football",
                    enabled = true,
                    matchMode = "CONTAINS"
                )
            )
        )

        val result = engine.evaluate("best football highlights 2026", SearchBlockingTarget.YOUTUBE)
        assertTrue("Enabled keyword should block query", result is SearchPolicyResult.Blocked)
        assertEquals("football", (result as SearchPolicyResult.Blocked).matchedKeyword)
    }

    @Test
    fun testDisabledKeywordDoesNotMatch() {
        engine.updateKeywords(
            listOf(
                BlockedSearchKeyword(
                    id = 2L,
                    keyword = "basketball",
                    normalizedKeyword = "basketball",
                    enabled = false,
                    matchMode = "CONTAINS"
                )
            )
        )

        val result = engine.evaluate("basketball finals live", SearchBlockingTarget.YOUTUBE)
        assertTrue("Disabled keyword must NOT block query", result is SearchPolicyResult.Allowed)
    }

    @Test
    fun testContainsMatchingWorks() {
        val keyword = "football"
        assertTrue(engine.isMatch("best football highlights 2026", keyword, KeywordMatchMode.CONTAINS))
        assertTrue(engine.isMatch("football live", keyword, KeywordMatchMode.CONTAINS))
        assertFalse(engine.isMatch("tennis match 2026", keyword, KeywordMatchMode.CONTAINS))
    }

    @Test
    fun testExactMatchingWorks() {
        val keyword = "football"
        assertTrue(engine.isMatch("football", keyword, KeywordMatchMode.EXACT))
        assertFalse(engine.isMatch("best football highlights 2026", keyword, KeywordMatchMode.EXACT))
        assertFalse(engine.isMatch("football match", keyword, KeywordMatchMode.EXACT))
    }

    @Test
    fun testUnicodeAndNormalizedText() {
        // Normalization checks
        val raw = "   Hello    World   "
        assertEquals("hello world", engine.normalize(raw))

        // Bangla and Unicode
        val rawBangla = "   বাংলা   গান   "
        assertEquals("বাংলা গান", engine.normalize(rawBangla))

        // Anti-evasion: zero-width spaces stripped
        val evasion = "f\u200Bout\u200Cball"
        assertEquals("foutball", engine.normalize(evasion))

        // Bangla keyword matching
        engine.updateKeywords(
            listOf(
                BlockedSearchKeyword(
                    id = 10L,
                    keyword = "খারাপ গান",
                    normalizedKeyword = "খারাপ গান",
                    enabled = true,
                    matchMode = "CONTAINS"
                )
            )
        )

        val banglaQuery = "নতুন খারাপ গান ২০২৩"
        val banglaResult = engine.evaluate(banglaQuery, SearchBlockingTarget.YOUTUBE)
        assertTrue("Bangla keyword match in YouTube", banglaResult is SearchPolicyResult.Blocked)
    }

    @Test
    fun testYouTubeSearchBlockingWorks() {
        engine.updateKeywords(
            listOf(
                BlockedSearchKeyword(
                    id = 1L,
                    keyword = "clickbait",
                    normalizedKeyword = "clickbait",
                    enabled = true,
                    matchMode = "CONTAINS"
                )
            )
        )

        assertTrue(YouTubeSearchDetector.canHandle("com.google.android.youtube"))
        assertTrue(YouTubeSearchDetector.canHandle("com.google.android.apps.youtube.kids"))
        assertFalse(YouTubeSearchDetector.canHandle("com.example.unrelated.app"))

        val result = engine.evaluate("crazy clickbait compilation", SearchBlockingTarget.YOUTUBE)
        assertTrue(result is SearchPolicyResult.Blocked)
    }

    @Test
    fun testRoomConstraintOrDbFailureDoesNotCrash() = runBlocking {
        val failingDao = object : AppDao {
            override fun getAllAppsFlow(): Flow<List<LockedApp>> = kotlinx.coroutines.flow.emptyFlow()
            override suspend fun getAllAppsList(): List<LockedApp> = emptyList()
            override suspend fun getAppByPackage(packageName: String): LockedApp? = null
            override suspend fun insertApp(app: LockedApp): Long = 0L
            override suspend fun updateApp(app: LockedApp) {}
            override suspend fun updateAppMetadata(packageName: String, appName: String, isSystemApp: Boolean) {}
            override suspend fun insertApps(apps: List<LockedApp>) {}
            override suspend fun deleteAppByPackage(packageName: String) {}

            override fun getAllUnlockRequestsFlow(): Flow<List<UnlockRequest>> = kotlinx.coroutines.flow.emptyFlow()
            override suspend fun getPendingUnlockRequests(): List<UnlockRequest> = emptyList()
            override suspend fun insertUnlockRequest(request: UnlockRequest): Long = 0L
            override suspend fun insertUnlockRequests(requests: List<UnlockRequest>): List<Long> = emptyList()
            override suspend fun updateUnlockRequest(request: UnlockRequest) {}
            override suspend fun deleteUnlockRequest(id: Int) {}
            override suspend fun deleteUnlockRequestsByTargetKeyOrType(targetKey: String, targetType: String) {}

            override fun getAllSettingsFlow(): Flow<List<AppSetting>> = kotlinx.coroutines.flow.emptyFlow()
            override suspend fun getAllSettingsList(): List<AppSetting> = emptyList()
            override suspend fun getSettingValue(key: String): String? = null
            override suspend fun insertSetting(setting: AppSetting) {}
            override suspend fun insertSettings(settings: List<AppSetting>) {}
            override suspend fun deleteSettingByKey(key: String) {}

            override fun getAllBlockedKeywordsFlow(): Flow<List<BlockedSearchKeyword>> = kotlinx.coroutines.flow.emptyFlow()
            override fun getActiveBlockedKeywordsFlow(): Flow<List<BlockedSearchKeyword>> = kotlinx.coroutines.flow.emptyFlow()
            override suspend fun getAllBlockedKeywordsList(): List<BlockedSearchKeyword> = throw RuntimeException("DB offline")
            override suspend fun getActiveBlockedKeywordsList(): List<BlockedSearchKeyword> = throw RuntimeException("DB offline")
            override suspend fun getBlockedKeywordByNormalized(normalized: String): BlockedSearchKeyword? = throw RuntimeException("DB offline")
            override suspend fun getBlockedKeywordById(id: Long): BlockedSearchKeyword? = throw RuntimeException("DB offline")
            override suspend fun insertBlockedKeyword(keyword: BlockedSearchKeyword): Long = throw RuntimeException("DB offline")
            override suspend fun updateBlockedKeyword(keyword: BlockedSearchKeyword) = throw RuntimeException("DB offline")
            override suspend fun updateBlockedKeywordEnabled(id: Long, enabled: Boolean) = throw RuntimeException("DB offline")
            override suspend fun deleteBlockedKeywordById(id: Long) = throw RuntimeException("DB offline")
            override suspend fun deleteAllBlockedKeywords() = throw RuntimeException("DB offline")
        }

        val safeRepo = AppRepository(failingDao, RuntimeEnvironment.getApplication())

        // 1. Add keyword fails safely with Result.failure, no uncaught exception
        val addResult = safeRepo.addBlockedKeyword("crash_test", "CONTAINS")
        assertTrue(addResult.isFailure)

        // 2. Read operations fail safely returning empty lists
        val activeList = safeRepo.getActiveBlockedKeywords()
        assertTrue(activeList.isEmpty())

        val allList = safeRepo.getAllBlockedKeywords()
        assertTrue(allList.isEmpty())

        // 3. Update / Delete operations return failure Result, no crash
        val updateResult = safeRepo.updateBlockedKeyword(1L, "crash_test", "CONTAINS", true)
        assertTrue(updateResult.isFailure)

        val deleteResult = safeRepo.deleteBlockedKeyword(1L)
        assertTrue(deleteResult.isFailure)
    }

    @Test
    fun test16DefaultBlockedItemsInitializedOnFreshInstall() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repo = AppRepository.getInstance(context)

        // Clear any previous keywords to simulate completely fresh install
        repo.deleteAllBlockedKeywords()
        assertEquals(0, repo.getAllBlockedKeywords().size)

        // Run ensureDefaultBlockedKeywords (simulating fresh install initialization)
        repo.ensureDefaultBlockedKeywords()

        val allKeywords = repo.getAllBlockedKeywords()
        assertEquals("Exactly 16 default items must be initialized", 16, allKeywords.size)

        val expectedItems = listOf(
            "Aashiq Banaya",
            "hot",
            "adult",
            "porn",
            "sex",
            "x",
            "xxx",
            "18+",
            "intimate",
            "kiss",
            "v=0bAVd9jJE2Q&vl=en",
            "v=19E65tOn3tI",
            "v=kbObKrBoIjI",
            "v=3QCgD4R4Ly8",
            "v=RlbGLrMuZ5I",
            "v=7vBsI0Hv5L0"
        )

        for (expected in expectedItems) {
            val found = allKeywords.find { it.keyword == expected }
            assertNotNull("Default item '$expected' must be present in database", found)
            assertTrue("Default item '$expected' must be enabled by default", found!!.enabled)
            assertEquals("Default item '$expected' must have CONTAINS matchMode", "CONTAINS", found.matchMode)
        }
    }

    @Test
    fun testExistingInstallAddsMissingDefaultsWithoutDuplicates() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repo = AppRepository.getInstance(context)

        repo.deleteAllBlockedKeywords()

        // 1. Simulate existing install: user added a custom keyword and already has 2 of the defaults
        repo.addBlockedKeyword("custom_user_study_topic", "CONTAINS")
        repo.addBlockedKeyword("hot", "CONTAINS")
        repo.addBlockedKeyword("v=19E65tOn3tI", "CONTAINS")

        val initialList = repo.getAllBlockedKeywords()
        assertEquals(3, initialList.size)

        // 2. Run ensureDefaultBlockedKeywords (simulating upgrade on existing installation)
        repo.ensureDefaultBlockedKeywords()

        val updatedList = repo.getAllBlockedKeywords()
        // 1 user-added keyword + 16 default items = 17 items total
        assertEquals(
            "Must have 17 items (1 user keyword + 16 defaults with no duplicates)",
            17,
            updatedList.size
        )

        // User-added keyword remains intact
        assertTrue(
            "User-added keyword must remain intact",
            updatedList.any { it.keyword == "custom_user_study_topic" }
        )

        // Verify no duplicate copies for existing defaults
        val hotCount = updatedList.count { it.normalizedKeyword == "hot" }
        assertEquals("Duplicate prevention: 'hot' must exist exactly once", 1, hotCount)

        val youtubeCount = updatedList.count { it.normalizedKeyword == "v=19e65ton3ti" }
        assertEquals("Duplicate prevention: 'v=19E65tOn3tI' must exist exactly once", 1, youtubeCount)

        // 3. Calling again must be strictly idempotent
        repo.ensureDefaultBlockedKeywords()
        assertEquals(
            "Re-running ensureDefaultBlockedKeywords must not create any duplicates",
            17,
            repo.getAllBlockedKeywords().size
        )
    }

    @Test
    fun testDefaultYouTubePatternsMatching() {
        val defaultEntities = AppRepository.DEFAULT_BLOCKED_ITEMS.mapIndexed { index, kw ->
            BlockedSearchKeyword(
                id = index + 1L,
                keyword = kw,
                normalizedKeyword = KeywordNormalizer.normalize(kw),
                enabled = true,
                matchMode = "CONTAINS"
            )
        }
        engine.updateKeywords(defaultEntities)

        // Test the 6 YouTube patterns with various URLs and queries
        val testCasesBlocked = listOf(
            "https://www.youtube.com/watch?v=0bAVd9jJE2Q&vl=en",
            "v=0bAVd9jJE2Q&vl=en",
            "https://m.youtube.com/watch?v=19E65tOn3tI",
            "v=19E65tOn3tI",
            "youtube.com/watch?v=kbObKrBoIjI&feature=share",
            "v=kbObKrBoIjI",
            "https://www.youtube.com/watch?v=3QCgD4R4Ly8",
            "v=3QCgD4R4Ly8",
            "watch?v=RlbGLrMuZ5I",
            "https://youtu.be/watch?v=RlbGLrMuZ5I&t=10s",
            "https://www.youtube.com/watch?v=7vBsI0Hv5L0",
            "v=7vBsI0Hv5L0",
            // Case-insensitive test
            "V=7VBSI0HV5L0",
            "WATCH?V=19E65TON3TI"
        )

        for (query in testCasesBlocked) {
            val result = engine.evaluate(query, SearchBlockingTarget.YOUTUBE)
            assertTrue(
                "Query '$query' must be blocked by YouTube pattern",
                result is SearchPolicyResult.Blocked
            )
        }

        // Test that unrelated video IDs are allowed
        val allowedQueries = listOf(
            "https://www.youtube.com/watch?v=unrelatedVideoId123",
            "v=abc123def789",
            "kotlin jetpack compose tutorial"
        )

        for (query in allowedQueries) {
            val result = engine.evaluate(query, SearchBlockingTarget.YOUTUBE)
            assertTrue(
                "Query '$query' should be allowed",
                result is SearchPolicyResult.Allowed
            )
        }
    }

    @Test
    fun testDefaultKeywordsMatching() {
        val defaultEntities = AppRepository.DEFAULT_BLOCKED_ITEMS.mapIndexed { index, kw ->
            BlockedSearchKeyword(
                id = index + 1L,
                keyword = kw,
                normalizedKeyword = KeywordNormalizer.normalize(kw),
                enabled = true,
                matchMode = "CONTAINS"
            )
        }
        engine.updateKeywords(defaultEntities)

        val keywordTestCases = listOf(
            "Aashiq Banaya song" to "Aashiq Banaya",
            "aashiq banaya remix" to "Aashiq Banaya",
            "hot viral video" to "hot",
            "adult movie trailer" to "adult",
            "porn hub clip" to "porn",
            "sex education documentary" to "sex",
            "x video" to "x",
            "xxx thriller" to "xxx",
            "18+ rated movie" to "18+",
            "intimate romance scene" to "intimate",
            "kissing scene hd" to "kiss"
        )

        for ((query, expectedKeyword) in keywordTestCases) {
            val result = engine.evaluate(query, SearchBlockingTarget.YOUTUBE)
            assertTrue(
                "Query '$query' must be blocked by keyword '$expectedKeyword'",
                result is SearchPolicyResult.Blocked
            )
        }
    }

    @Test
    fun testYouTubePatternBlocking() {
        val defaultEntities = AppRepository.DEFAULT_BLOCKED_ITEMS.mapIndexed { index, kw ->
            BlockedSearchKeyword(
                id = index + 1L,
                keyword = kw,
                normalizedKeyword = KeywordNormalizer.normalize(kw),
                enabled = true,
                matchMode = "CONTAINS"
            )
        }
        engine.updateKeywords(defaultEntities)

        val youtubePatterns = listOf(
            "https://www.youtube.com/watch?v=0bAVd9jJE2Q&vl=en" to "v=0bAVd9jJE2Q&vl=en",
            "youtube.com/watch?v=19E65tOn3tI" to "v=19E65tOn3tI",
            "watch?v=kbObKrBoIjI&feature=share" to "v=kbObKrBoIjI",
            "https://m.youtube.com/watch?v=3QCgD4R4Ly8" to "v=3QCgD4R4Ly8",
            "v=RlbGLrMuZ5I" to "v=RlbGLrMuZ5I",
            "https://youtu.be/watch?v=7vBsI0Hv5L0" to "v=7vBsI0Hv5L0"
        )

        for ((urlQuery, expectedPattern) in youtubePatterns) {
            val result = engine.evaluate(urlQuery, SearchBlockingTarget.YOUTUBE)
            assertTrue(
                "YouTube URL/query '$urlQuery' must be blocked by pattern '$expectedPattern'",
                result is SearchPolicyResult.Blocked
            )
        }
    }

    @Test
    fun testAllSixteenDefaultsInitializationAndDeduplication() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repo = AppRepository.getInstance(context)

        // Ensure defaults are populated
        repo.ensureDefaultBlockedKeywords()

        val allKeywords = repo.getActiveBlockedKeywords()
        for (item in AppRepository.DEFAULT_BLOCKED_ITEMS) {
            val found = allKeywords.any { it.keyword.equals(item, ignoreCase = true) }
            assertTrue("Default item '$item' must exist in repository", found)
        }

        val initialCount = allKeywords.size

        // Calling ensureDefaultBlockedKeywords again must NOT duplicate any entries
        repo.ensureDefaultBlockedKeywords()
        val afterSecondCall = repo.getActiveBlockedKeywords()
        assertEquals("Duplicate default items must not be created", initialCount, afterSecondCall.size)
    }

    @Test
    fun testPreserveUserAddedKeywords() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repo = AppRepository.getInstance(context)

        // User adds a custom keyword
        val userKeyword = "custom_user_keyword_xyz"
        repo.addBlockedKeyword(userKeyword, "CONTAINS")

        // Run ensure defaults
        repo.ensureDefaultBlockedKeywords()

        val activeList = repo.getActiveBlockedKeywords()
        assertTrue(
            "User-added keyword must be preserved",
            activeList.any { it.keyword == userKeyword }
        )
    }
}
