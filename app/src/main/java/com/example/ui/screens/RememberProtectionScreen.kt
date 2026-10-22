package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AppRepository
import com.example.service.AccessibilityHelper
import com.example.service.safety.RememberProtectionEngine
import com.example.service.safety.SavedProtectionTarget
import com.example.ui.InlineUnlockTimer
import com.example.ui.MainUiState
import com.example.ui.MainViewModel
import com.example.ui.components.StandardCardSurface
import com.example.ui.components.SubScreenTopBar
import com.example.ui.components.standardSwitchColors
import com.example.ui.theme.*

/**
 * Remember Protection — Configuration Management Screen.
 */
@Composable
fun RememberProtectionScreen(
    uiState: MainUiState,
    viewModel: MainViewModel
) {
    val context = LocalContext.current

    val savedTargetsRaw = uiState.settingsMap[AppRepository.KEY_SAVED_PROTECTION_TARGET].orEmpty()
    val rememberedTargets = remember(savedTargetsRaw) {
        RememberProtectionEngine.decodeTargets(savedTargetsRaw).ifEmpty {
            RememberProtectionEngine.getSavedTargets(context)
        }
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var targetToDelete by remember { mutableStateOf<SavedProtectionTarget?>(null) }
    var focusedTargetForDetails by remember { mutableStateOf<SavedProtectionTarget?>(null) }

    val pendingDeleteRequestsMap = remember(uiState.unlockRequests) {
        uiState.unlockRequests
            .filter { it.targetType == "REMEMBER_TARGET_DELETE" || it.targetType == "REMEMBER_PATH_DELETE" }
            .associateBy { it.targetKey }
    }

    val listState = rememberLazyListState()

    LaunchedEffect(uiState.selectedRememberTargetId, rememberedTargets) {
        uiState.selectedRememberTargetId?.let { id ->
            rememberedTargets.find { it.id == id }?.let { target ->
                focusedTargetForDetails = target
                val idx = rememberedTargets.indexOf(target)
                if (idx >= 0) listState.animateScrollToItem(idx + 1)
            }
        }
    }

    val isRememberEnabled = uiState.settingsMap[AppRepository.KEY_REMEMBER_PROTECTION_ENABLED] != "false"
    val masterUnlockReq = uiState.unlockRequests.find {
        it.targetType == "REMEMBER_PROTECTION_MASTER" || it.targetKey == AppRepository.KEY_REMEMBER_PROTECTION_ENABLED
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().background(BgDark).padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "remember_protection_top_bar") {
            SubScreenTopBar(title = "Remember Protection", onBack = { viewModel.navigateBack() }, backTestTag = "back_button_remember_protection")
        }

        item(key = "remember_protection_master_card") {
            StandardCardSurface(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = if (isRememberEnabled) EmeraldGreenLight else TextSecondary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Remember Protection", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            Text(
                                text = when {
                                    !isRememberEnabled -> "Remember Protection is disabled"
                                    masterUnlockReq != null -> "Deactivation countdown in progress (10h)"
                                    else -> "Remember Protection is active"
                                },
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = isRememberEnabled,
                            onCheckedChange = { viewModel.toggleRememberProtectionMaster(isRememberEnabled) },
                            colors = standardSwitchColors(),
                            modifier = Modifier.testTag("toggle_remember_protection_master")
                        )
                    }

                    if (masterUnlockReq != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        InlineUnlockTimer(
                            unlockAtTime = masterUnlockReq.unlockAtTime,
                            onCancel = { viewModel.cancelUnlockRequest(masterUnlockReq.id) },
                            onTimerExpired = { viewModel.checkAndProcessUnlockRequests() }
                        )
                    }
                }
            }
        }

        if (rememberedTargets.isNotEmpty()) {
            item(key = "remember_paths_header") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Saved configurations", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
                    Text("${rememberedTargets.size}", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color(0xFF38BDF8))
                }
            }
        } else {
            item(key = "empty_targets_state") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("No saved configuration yet", fontSize = 14.sp, color = TextSecondary)
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { showAddDialog = true },
                        modifier = Modifier.fillMaxWidth().height(42.dp).testTag("add_remember_path_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        shape = RoundedCornerShape(8.dp)
                    ) { Text("Add Target", fontWeight = FontWeight.Bold) }
                }
            }
        }

        itemsIndexed(
            items = rememberedTargets,
            key = { index, item -> "remember_target_${item.id.ifEmpty { index.toString() }}" }
        ) { _, target ->
            RememberTargetRow(
                target = target,
                isSelected = target.id == uiState.selectedRememberTargetId,
                pendingDeleteReq = pendingDeleteRequestsMap[target.id],
                onClick = { focusedTargetForDetails = target },
                onDeleteClick = { targetToDelete = target },
                onCancelDelete = { viewModel.cancelUnlockRequest(it) },
                onTimerExpired = { viewModel.checkAndProcessUnlockRequests() }
            )
        }

        if (rememberedTargets.isNotEmpty()) {
            item(key = "add_target_button") {
                Button(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.fillMaxWidth().height(42.dp).testTag("add_remember_path_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                    shape = RoundedCornerShape(8.dp)
                ) { Text("Add Target", fontWeight = FontWeight.Bold) }
            }
        }
    }

    if (showAddDialog) {
        AddTargetDialog(
            onDismiss = { showAddDialog = false },
            onSave = { newTarget ->
                viewModel.addSavedProtectionTarget(newTarget)
                showAddDialog = false
            }
        )
    }

    targetToDelete?.let { target ->
        DeleteTargetDialog(
            target = target,
            onDismiss = { targetToDelete = null },
            onConfirm = {
                viewModel.deleteSavedProtectionTarget(it.id)
                targetToDelete = null
            }
        )
    }

    focusedTargetForDetails?.let { target ->
        TargetDetailsDialog(
            target = target,
            onDismiss = {
                focusedTargetForDetails = null
                viewModel.clearSelectedRememberTarget()
            },
            onDeleteRequest = { toDelete ->
                focusedTargetForDetails = null
                targetToDelete = toDelete
                viewModel.clearSelectedRememberTarget()
            }
        )
    }
}


