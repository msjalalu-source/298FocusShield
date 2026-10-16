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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Search
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
import com.example.data.LockedApp
import com.example.data.UnlockRequest
import com.example.ui.InlineUnlockTimer
import com.example.ui.MainUiState
import com.example.ui.MainViewModel
import com.example.ui.components.SubScreenTopBar
import com.example.ui.theme.*

@Composable
fun ManualAppBlockScreen(
    uiState: MainUiState,
    viewModel: MainViewModel
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.syncInstalledApps(force = false)
    }

    var searchQuery by remember { mutableStateOf("") }

    val appUnlockRequestMap = remember(uiState.unlockRequests) {
        uiState.unlockRequests
            .filter { it.targetType == "APP_LOCK" || it.targetType == "LOCKED_APP" }
            .associateBy { it.targetKey }
    }

    val filteredApps = remember(uiState.apps, searchQuery) {
        val query = searchQuery.trim()
        val baseList = uiState.apps.filter { it.packageName != context.packageName }
        if (query.isEmpty()) {
            baseList.sortedWith(compareByDescending<LockedApp> { it.isAppLocked }.thenBy { it.appName.lowercase() })
        } else {
            baseList.filter {
                it.appName.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
            }.sortedWith(compareByDescending<LockedApp> { it.isAppLocked }.thenBy { it.appName.lowercase() })
        }
    }

    var appToUnlock by remember { mutableStateOf<Pair<String, String>?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "manual_app_block_top_bar") {
            SubScreenTopBar(
                title = "Manually Block Installed Apps",
                onBack = { viewModel.navigateBack() },
                backTestTag = "back_button_manual_app_block",
                subtitle = "App Drawer Lock",
                subtitleColor = CyanAccent
            )
        }

        item(key = "search_field") {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_search_installed_apps"),
                placeholder = { Text("Search installed apps...", color = TextMuted, fontSize = 13.sp) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = "Search", tint = TextMuted, modifier = Modifier.size(20.dp))
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier.testTag("btn_clear_search_installed_apps")
                        ) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search", tint = TextMuted, modifier = Modifier.size(18.dp))
                        }
                    }
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyanAccent,
                    unfocusedBorderColor = CardBorderDark,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = CyanAccent
                ),
                shape = RoundedCornerShape(12.dp)
            )
        }

        if (filteredApps.isEmpty()) {
            item(key = "apps_empty") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = EmeraldGreenLight, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (searchQuery.isEmpty()) "No Launcher Apps Found" else "No matching apps found",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        if (searchQuery.isEmpty()) "Launcher-visible applications will appear here." else "Try a different search term.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }
        } else {
            items(items = filteredApps, key = { it.packageName }) { app ->
                val pendingUnlockReq = appUnlockRequestMap[app.packageName]
                ManualAppItem(
                    app = app,
                    context = context,
                    pendingUnlockRequest = pendingUnlockReq,
                    onLockClick = {
                        viewModel.setAppLocked(app.packageName, true)
                    },
                    onUnlockClick = {
                        appToUnlock = app.packageName to app.appName
                    },
                    onCancelUnlock = pendingUnlockReq?.let { req -> { viewModel.cancelUnlockRequest(req.id) } },
                    onTimerExpired = { viewModel.checkAndProcessUnlockRequests() }
                )
            }
        }
    }

    appToUnlock?.let { (pkg, name) ->
        AlertDialog(
            onDismissRequest = { appToUnlock = null },
            title = { Text("Unlock \"$name\"?", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = {
                Text(
                    "Unlocking requires a 12-hour delayed unlock. The app remains blocked until the timer expires.",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.requestUnblockApp(pkg)
                        appToUnlock = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AmberWarning),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("confirm_unlock_app_btn")
                ) {
                    Text("Start 12h Delay", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { appToUnlock = null }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = CardDark,
            shape = RoundedCornerShape(14.dp)
        )
    }
}

@Composable
private fun ManualAppItem(
    app: LockedApp,
    context: Context,
    pendingUnlockRequest: UnlockRequest?,
    onLockClick: () -> Unit,
    onUnlockClick: () -> Unit,
    onCancelUnlock: (() -> Unit)?,
    onTimerExpired: () -> Unit
) {
    val pm = context.packageManager
    val iconBitmap = remember(app.packageName) {
        try {
            pm.getApplicationIcon(app.packageName).toBitmap(72, 72).asImageBitmap()
        } catch (_: Throwable) {
            null
        }
    }

    val isLocked = app.isAppLocked
    val isPendingUnlock = pendingUnlockRequest != null

    Surface(
        color = CardDark,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            1.dp,
            when {
                isPendingUnlock -> AmberWarning.copy(alpha = 0.4f)
                isLocked -> Color(0xFFEF4444).copy(alpha = 0.35f)
                else -> CardBorderDark
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (iconBitmap != null) {
                    Image(
                        bitmap = iconBitmap,
                        contentDescription = app.appName,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Android,
                        contentDescription = null,
                        tint = CyanAccent,
                        modifier = Modifier.size(38.dp)
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.appName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = app.packageName,
                        fontSize = 11.sp,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.width(8.dp))

                if (isPendingUnlock) {
                    Surface(
                        color = AmberWarning.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "Unlocking",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = AmberWarning,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                } else if (isLocked) {
                    Button(
                        onClick = onUnlockClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFEF4444).copy(alpha = 0.18f),
                            contentColor = Color(0xFFF87171)
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("btn_unlock_${app.packageName.replace('.', '_')}")
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Locked", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(
                        onClick = onLockClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EmeraldGreen.copy(alpha = 0.15f),
                            contentColor = EmeraldGreenLight
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("btn_lock_${app.packageName.replace('.', '_')}")
                    ) {
                        Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Lock", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            if (isPendingUnlock && pendingUnlockRequest != null) {
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
