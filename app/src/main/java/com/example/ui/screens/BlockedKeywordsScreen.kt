package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AppRepository
import com.example.data.BlockedSearchKeyword
import com.example.data.UnlockRequest
import com.example.service.search.KeywordMatchMode
import com.example.service.search.KeywordNormalizer
import com.example.ui.InlineUnlockTimer
import com.example.ui.MainUiState
import com.example.ui.MainViewModel
import com.example.ui.components.*
import com.example.ui.theme.*

private data class TargetAppItem(
    val title: String,
    val subtitle: String,
    val enabled: Boolean,
    val unlockReq: UnlockRequest?,
    val onToggle: () -> Unit,
    val testTag: String
)

@Composable
fun BlockedKeywordsScreen(
    uiState: MainUiState,
    viewModel: MainViewModel
) {
    val youtubeEnabled = uiState.settingsMap[AppRepository.KEY_SEARCH_BLOCKING_YOUTUBE_ENABLED] == "true"

    val unlockMap = remember(uiState.unlockRequests) {
        buildMap { uiState.unlockRequests.forEach { put(it.targetKey, it); put(it.targetType, it) } }
    }

    val targetApps = listOf(
        TargetAppItem("YouTube", "com.google.android.youtube", youtubeEnabled,
            unlockMap[AppRepository.KEY_SEARCH_BLOCKING_YOUTUBE_ENABLED] ?: unlockMap["SEARCH_BLOCKING_YOUTUBE"],
            { viewModel.toggleSearchBlockingYouTube(youtubeEnabled) }, "toggle_search_youtube")
    )

    var showAddDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(BgDark).padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "search_blocking_top_bar") {
            SubScreenTopBar("Search Keyword Blocking", { viewModel.navigateBack() }, backTestTag = "back_button_search_blocking")
        }

        item(key = "target_apps_card") {
            Column {
                Text("Target Applications", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary, modifier = Modifier.padding(bottom = 8.dp))
                StandardCardSurface {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        targetApps.forEachIndexed { index, app ->
                            if (index > 0) CommonItemDivider()
                            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(app.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                                        Text(app.subtitle, fontSize = 11.sp, color = TextSecondary)
                                    }
                                    Switch(
                                        checked = app.enabled,
                                        onCheckedChange = { app.onToggle() },
                                        colors = standardSwitchColors(),
                                        modifier = Modifier.testTag(app.testTag)
                                    )
                                }
                                if (app.unlockReq != null) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    InlineUnlockTimer(app.unlockReq.unlockAtTime, { viewModel.cancelUnlockRequest(app.unlockReq.id) }, viewModel::checkAndProcessUnlockRequests)
                                }
                            }
                        }
                    }
                }
            }
        }

        item(key = "keywords_header") {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Blocked Keywords (${uiState.blockedKeywords.size})", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
                Button(
                    onClick = { showAddDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.testTag("btn_add_keyword")
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Keyword", tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }

        if (uiState.blockedKeywords.isEmpty()) {
            item(key = "keywords_empty_state") {
                CommonEmptyStateCard(Icons.Default.SearchOff, "No Blocked Keywords", "Tap the 'Add' button above to add custom words or phrases to restrict on YouTube.")
            }
        } else {
            items(uiState.blockedKeywords, key = { it.id }, contentType = { "blocked_keyword" }) { keyword ->
                val keywordUnlockReq = unlockMap[keyword.id.toString()]
                KeywordItemCard(
                    keyword = keyword,
                    pendingUnlockRequest = keywordUnlockReq,
                    onCancel = keywordUnlockReq?.let { req -> { viewModel.cancelUnlockRequest(req.id) } },
                    onTimerExpired = viewModel::checkAndProcessUnlockRequests,
                    onToggle = { viewModel.toggleBlockedKeyword(keyword.id, keyword.enabled) }
                )
            }
        }
    }

    if (showAddDialog) {
        AddKeywordDialog(
            existingKeywords = uiState.blockedKeywords.map { it.keyword },
            onDismiss = { showAddDialog = false },
            onConfirm = { keyword, mode ->
                viewModel.addBlockedKeyword(keyword, mode.name)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun KeywordItemCard(
    keyword: BlockedSearchKeyword,
    pendingUnlockRequest: UnlockRequest?,
    onCancel: (() -> Unit)?,
    onTimerExpired: (() -> Unit)?,
    onToggle: (Boolean) -> Unit
) {
    StandardCardSurface(cornerRadius = 12.dp) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(keyword.keyword, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (keyword.enabled) TextPrimary else TextMuted, modifier = Modifier.weight(1f))
                Text(keyword.matchMode, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = EmeraldGreenLight, modifier = Modifier.padding(end = 8.dp))
                Switch(checked = keyword.enabled, onCheckedChange = onToggle, colors = standardSwitchColors(), modifier = Modifier.testTag("toggle_keyword_${keyword.id}"))
            }
            if (pendingUnlockRequest != null && onCancel != null) {
                Spacer(modifier = Modifier.height(6.dp))
                InlineUnlockTimer(pendingUnlockRequest.unlockAtTime, onCancel, onTimerExpired)
            }
        }
    }
}

@Composable
fun AddKeywordDialog(
    existingKeywords: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String, KeywordMatchMode) -> Unit
) {
    var keywordText by remember { mutableStateOf("") }
    var selectedMode by remember { mutableStateOf(KeywordMatchMode.CONTAINS) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Blocked Keyword", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextPrimary) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = keywordText,
                    onValueChange = { keywordText = it; errorMessage = null },
                    placeholder = { Text("e.g. gaming, vlogs", color = TextMuted) },
                    singleLine = true,
                    isError = errorMessage != null,
                    modifier = Modifier.fillMaxWidth().testTag("input_keyword")
                )
                if (errorMessage != null) {
                    Text(errorMessage ?: "", fontSize = 12.sp, color = Color(0xFFEF4444), modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text("Matching Mode:", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextSecondary)
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(KeywordMatchMode.CONTAINS to "Contains", KeywordMatchMode.EXACT to "Exact", KeywordMatchMode.WORD to "Word").forEach { (mode, label) ->
                        val isSelected = selectedMode == mode
                        Surface(
                            onClick = { selectedMode = mode },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) EmeraldGreen.copy(alpha = 0.2f) else CardItemBgDark,
                            border = BorderStroke(1.dp, if (isSelected) EmeraldGreen else CardBorderDark)
                        ) {
                            Text(label, fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, color = if (isSelected) EmeraldGreenLight else TextSecondary, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when (val result = KeywordNormalizer.validateKeyword(keywordText, existingKeywords)) {
                        is KeywordNormalizer.ValidationResult.Success -> onConfirm(keywordText.trim(), selectedMode)
                        is KeywordNormalizer.ValidationResult.Error -> errorMessage = result.message
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.testTag("btn_save_keyword")
            ) {
                Text("Save", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("btn_cancel_keyword")) {
                Text("Cancel", color = TextSecondary)
            }
        },
        containerColor = CardDark,
        shape = RoundedCornerShape(16.dp)
    )
}
