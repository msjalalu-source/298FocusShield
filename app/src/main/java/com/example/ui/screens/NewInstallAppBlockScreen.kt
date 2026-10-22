package com.example.ui.screens

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.material.icons.filled.InstallMobile
import com.example.data.AppRepository
import com.example.data.UnlockRequest
import com.example.ui.InlineUnlockTimer
import com.example.ui.MainUiState
import com.example.ui.MainViewModel
import com.example.ui.components.StandardCardSurface
import com.example.ui.components.SubScreenTopBar
import com.example.ui.components.standardSwitchColors
import com.example.ui.theme.*

@Composable
fun NewInstallAppBlockScreen(
    uiState: MainUiState,
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    val isNewInstallAppBlocked = uiState.settingsMap[AppRepository.KEY_NEW_INSTALL_APP_BLOCKED] != "false"
    val pendingFeatureUnlockReq = remember(uiState.unlockRequests) {
        uiState.unlockRequests.find {
            it.targetType == "NEW_INSTALL_APP_BLOCK" || it.targetKey == AppRepository.KEY_NEW_INSTALL_APP_BLOCKED
        }
    }
    val appUnlockRequestMap = remember(uiState.unlockRequests) {
        uiState.unlockRequests.filter { it.targetType == "NEW_INSTALL_APP" }.associateBy { it.targetKey }
    }
    val blockedPackages = remember(uiState.blockedNewApps) { uiState.blockedNewApps.toList().sorted() }
    var appToUnblock by remember { mutableStateOf<Pair<String, String>?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(BgDark).padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "new_install_top_bar") {
            SubScreenTopBar(
                title = "Newly Installed Apps",
                onBack = { viewModel.navigateBack() },
                backTestTag = "back_button_new_install_app_block",
                subtitle = if (isNewInstallAppBlocked) "Protection Active" else "Protection Inactive",
                subtitleColor = if (isNewInstallAppBlocked) CyanAccent else TextMuted
            )
        }

        item(key = "new_install_master_card") {
            StandardCardSurface(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.InstallMobile,
                            contentDescription = null,
                            tint = if (isNewInstallAppBlocked) CyanAccent else TextSecondary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Automatic App Blocking", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            Text(
                                text = when {
                                    !isNewInstallAppBlocked -> "Automatic blocking is disabled"
                                    pendingFeatureUnlockReq != null -> "Deactivation countdown in progress (10h)"
                                    else -> "Automatically locks newly installed apps"
                                },
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = isNewInstallAppBlocked,
                            onCheckedChange = { viewModel.toggleNewInstallAppBlocked(isNewInstallAppBlocked) },
                            colors = standardSwitchColors(),
                            modifier = Modifier.testTag("toggle_new_install_app_block")
                        )
                    }

                    if (pendingFeatureUnlockReq != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        InlineUnlockTimer(
                            unlockAtTime = pendingFeatureUnlockReq.unlockAtTime,
                            onCancel = { viewModel.cancelUnlockRequest(pendingFeatureUnlockReq.id) },
                            onTimerExpired = { viewModel.checkAndProcessUnlockRequests() }
                        )
                    }
                }
            }
        }

        if (blockedPackages.isEmpty()) {
            item(key = "blocked_apps_empty") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = EmeraldGreenLight, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("No Blocked Apps", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Text("Newly installed apps will automatically be locked and listed here.", fontSize = 12.sp, color = TextSecondary)
                }
            }
        } else {
            items(items = blockedPackages, key = { it }) { packageName ->
                val pendingUnlockReq = appUnlockRequestMap[packageName]
                BlockedAppItem(
                    packageName = packageName,
                    context = context,
                    pendingUnlockRequest = pendingUnlockReq,
                    onUnblockClick = { appName -> appToUnblock = packageName to appName },
                    onCancelUnlock = pendingUnlockReq?.let { req -> { viewModel.cancelUnlockRequest(req.id) } },
                    onTimerExpired = { viewModel.checkAndProcessUnlockRequests() }
                )
            }
        }
    }

    appToUnblock?.let { (pkg, name) ->
        AlertDialog(
            onDismissRequest = { appToUnblock = null },
            title = { Text("Unblock \"$name\"?", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = { Text("Unblocking requires a 10-hour delayed unlock. The app remains blocked until the timer expires.", fontSize = 13.sp, color = TextSecondary) },
            confirmButton = {
                Button(
                    onClick = { viewModel.requestUnblockNewInstallApp(pkg); appToUnblock = null },
                    colors = ButtonDefaults.buttonColors(containerColor = AmberWarning),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("confirm_unblock_app_btn")
                ) { Text("Start 10h Delay", color = Color.Black, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { appToUnblock = null }) { Text("Cancel", color = TextSecondary) }
            },
            containerColor = CardDark,
            shape = RoundedCornerShape(14.dp)
        )
    }
}

@Composable
private fun BlockedAppItem(
    packageName: String,
    context: Context,
    pendingUnlockRequest: UnlockRequest?,
    onUnblockClick: (String) -> Unit,
    onCancelUnlock: (() -> Unit)?,
    onTimerExpired: () -> Unit
) {
    val pm = context.packageManager
    val appName = remember(packageName) {
        try { pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() } catch (_: Throwable) { packageName }
    }
    val iconBitmap = remember(packageName) {
        try { pm.getApplicationIcon(packageName).toBitmap(72, 72).asImageBitmap() } catch (_: Throwable) { null }
    }

    Surface(
        color = CardDark,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if (pendingUnlockRequest != null) AmberWarning.copy(alpha = 0.35f) else CardBorderDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (iconBitmap != null) {
                    Image(bitmap = iconBitmap, contentDescription = appName, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)))
                } else {
                    Icon(Icons.Default.Android, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(36.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(appName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(packageName, fontSize = 11.sp, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (pendingUnlockRequest == null) {
                    Button(
                        onClick = { onUnblockClick(appName) },
                        colors = ButtonDefaults.buttonColors(containerColor = AmberWarning.copy(alpha = 0.15f), contentColor = AmberWarning),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("btn_unblock_${packageName.replace('.', '_')}")
                    ) {
                        Text("Unblock", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (pendingUnlockRequest != null) {
                Spacer(Modifier.height(8.dp))
                InlineUnlockTimer(
                    unlockAtTime = pendingUnlockRequest.unlockAtTime,
                    onCancel = onCancelUnlock,
                    onTimerExpired = onTimerExpired,
                    labelPrefix = "Unlocks in"
                )
            }
        }
    }
}
