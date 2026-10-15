package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AppRepository
import com.example.service.AccessibilityHelper
import com.example.service.dpc.DeviceAdminManager
import com.example.service.safety.RememberProtectionEngine
import com.example.service.safety.SavedProtectionTarget
import com.example.ui.MainUiState
import com.example.ui.MainViewModel
import com.example.ui.ScreenDestination
import com.example.ui.components.CommonItemDivider
import com.example.ui.components.IconContainerBox
import com.example.ui.dialogs.UninstallProtectionDialog
import com.example.ui.theme.*

// Styling palette for Home Screen feature containers
private val HomePurpleContainer = Color(0xFFA855F7).copy(alpha = 0.15f)
private val HomePurpleText = Color(0xFFC084FC)

private val HomeCoralContainer = Color(0xFFEF4444).copy(alpha = 0.15f)
private val HomeCoralText = Color(0xFFF87171)

private val HomeBlueContainer = Color(0xFF0284C7).copy(alpha = 0.15f)
private val HomeBlueText = Color(0xFF38BDF8)

private val HomeAmberText = Color(0xFFFBBF24)

private val HomeIndigoContainer = Color(0xFF6366F1).copy(alpha = 0.15f)
private val HomeIndigoText = Color(0xFF818CF8)

private val HomeCyanContainer = Color(0xFF06B6D4).copy(alpha = 0.15f)
private val HomeCyanText = Color(0xFF22D3EE)

private val HomeHeaderGradient = Brush.verticalGradient(listOf(CardDark, BgDark))

@Composable
fun HomeScreen(
    uiState: MainUiState,
    viewModel: MainViewModel
) {
    val context = LocalContext.current

    val onNavigateRemember = remember(viewModel) {
        { viewModel.navigateTo(ScreenDestination.REMEMBER_PROTECTION) }
    }
    val onNavigateSearchKeywords = remember(viewModel) {
        { viewModel.navigateTo(ScreenDestination.SEARCH_KEYWORDS) }
    }

    val adminLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshDeviceAdminStatus()
    }

    var showUninstallProtectionDialog by remember { mutableStateOf(false) }
    val isServiceActive = uiState.isAccessibilityEnabled

    val isUninstallSettingEnabled = remember(uiState.settingsMap) {
        uiState.settingsMap[AppRepository.KEY_UNINSTALL_PROTECTION] == "true"
    }
    val uninstallProtected by remember(isUninstallSettingEnabled, uiState.isDeviceAdminActive) {
        derivedStateOf { isUninstallSettingEnabled && uiState.isDeviceAdminActive }
    }
    val youtubeEnabled = remember(uiState.settingsMap) {
        uiState.settingsMap[AppRepository.KEY_SEARCH_BLOCKING_YOUTUBE_ENABLED] == "true"
    }
    val searchBlockingEnabled by remember(youtubeEnabled) {
        derivedStateOf { youtubeEnabled }
    }

    val pendingUninstallUnlockReq = remember(uiState.unlockRequests) {
        uiState.unlockRequests.find {
            it.targetType == "UNINSTALL_PROTECTION" || it.targetKey == AppRepository.KEY_UNINSTALL_PROTECTION
        }
    }

    val onUninstallProtectionClick = {
        if (pendingUninstallUnlockReq != null) {
            showUninstallProtectionDialog = true
        } else if (!DeviceAdminManager.isAdminActive(context)) {
            val intent = DeviceAdminManager.createAddAdminIntent(
                context,
                "Focus Shield requires Device Admin permission to protect the app from direct uninstallation."
            )
            adminLauncher.launch(intent)
        } else if (!uninstallProtected) {
            viewModel.toggleUninstallProtection(false)
        } else {
            showUninstallProtectionDialog = true
        }
    }

    val onNewInstallAppBlockClick = {
        viewModel.navigateTo(ScreenDestination.NEW_INSTALL_APP_BLOCK)
    }

    val savedTargetsRaw = uiState.settingsMap[AppRepository.KEY_SAVED_PROTECTION_TARGET] ?: ""
    val rememberedTargets: List<SavedProtectionTarget> = remember(savedTargetsRaw) {
        val decoded = RememberProtectionEngine.decodeTargets(savedTargetsRaw)
        if (decoded.isEmpty() && savedTargetsRaw.isEmpty()) {
            RememberProtectionEngine.getSavedTargets(context)
        } else {
            decoded
        }
    }
    val rememberedTargetsCount by remember(rememberedTargets) {
        derivedStateOf { rememberedTargets.size }
    }
    val blockedKeywordsCount by remember(uiState.blockedKeywords) {
        derivedStateOf { uiState.blockedKeywords.size }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(BgDark),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // 1. Top Header with Deep Blue Gradient
            item(key = "top_header") {
                HomeTopHeader()
            }

            // 2. Main Content status card
            item(key = "status_and_quick_access") {
                Column(
                    modifier = Modifier
                        .offset(y = (-14).dp)
                        .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                        .background(BgDark)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    HomeOverallStatusCard(
                        isActive = isServiceActive,
                        onClick = {
                            if (!isServiceActive) {
                                AccessibilityHelper.openAccessibilityServiceSettings(context)
                            }
                        }
                    )
                }
            }

            // 3. Protection Features Section
            item(key = "protection_features_section") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HomeSectionHeader(
                        title = "Protection Features",
                        icon = Icons.Default.VerifiedUser,
                        iconTint = EmeraldGreen
                    )

                    HomeQuickAccessTiles(
                        rememberedTargetsCount = rememberedTargetsCount,
                        searchBlockingEnabled = searchBlockingEnabled,
                        searchKeywordsCount = blockedKeywordsCount,
                        uninstallProtected = uninstallProtected,
                        isUninstallUnlockPending = pendingUninstallUnlockReq != null,
                        onUninstallProtectionClick = onUninstallProtectionClick,
                        newInstallAppBlocked = true,
                        isNewAppUnlockPending = false,
                        onNewInstallAppBlockClick = onNewInstallAppBlockClick,
                        onNavigateRemember = onNavigateRemember,
                        onNavigateSearchKeywords = onNavigateSearchKeywords
                    )
                }
            }

            // 4. System & Permissions Section
            item(key = "system_permissions_section") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HomeSectionHeader(
                        title = "System & Permissions",
                        icon = Icons.Default.SecurityUpdateGood,
                        iconTint = TextSecondary
                    )

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp)),
                        color = CardDark,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, CardBorderDark)
                    ) {
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            HomeNavRow(
                                icon = Icons.Default.AccessibilityNew,
                                iconContainer = HomeIndigoContainer,
                                iconTint = HomeIndigoText,
                                title = "Accessibility",
                                subtitle = "Accessibility, permissions, services",
                                onClick = {
                                    AccessibilityHelper.openAccessibilityServiceSettings(context)
                                },
                                testTag = "settings_accessibility"
                            )
                        }
                    }
                }
            }

            // 5. Developer Credit
            item(key = "developer_credit") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Made by Jalal Uddin",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Normal,
                        color = TextMuted,
                        modifier = Modifier.testTag("developer_credit")
                    )
                }
            }
        }

        // Uninstall Protection State / Delayed Unlock Dialog
        UninstallProtectionDialog(
            show = showUninstallProtectionDialog,
            pendingUnlockReq = pendingUninstallUnlockReq,
            uninstallProtected = uninstallProtected,
            onDismiss = { showUninstallProtectionDialog = false },
            onCancelUnlock = { viewModel.cancelUnlockRequest(it) },
            onTimerExpired = { viewModel.checkAndProcessUnlockRequests() },
            onToggle = { isDeactivation -> viewModel.toggleUninstallProtection(isDeactivation) }
        )
    }
}

@Composable
private fun HomeTopHeader() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(HomeHeaderGradient)
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 28.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = "Focus Shield Logo",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column {
                Text(
                    text = "Focus Shield",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = 0.2.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Stay Safe. Stay Focused.",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color.White.copy(alpha = 0.85f)
                )
            }
        }
    }
}

@Composable
private fun HomeOverallStatusCard(
    isActive: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = if (isActive) Color(0xFF064E3B) else Color(0xFF78350F),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (isActive) EmeraldGreenDark else AmberWarning.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (isActive) EmeraldGreen.copy(alpha = 0.25f) else AmberWarning.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isActive) Icons.Default.Check else Icons.Default.PriorityHigh,
                        contentDescription = null,
                        tint = if (isActive) EmeraldGreenLight else AmberWarning,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column {
                    Text(
                        text = if (isActive) "All Protections Active" else "Protection Inactive",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (isActive) "Your device is protected" else "Tap to enable Accessibility Service",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                        color = Color.White.copy(alpha = 0.9f)
                    )
                }
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "View details",
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun HomeSectionHeader(
    title: String,
    icon: ImageVector,
    iconTint: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
    }
}

@Composable
private fun HomeQuickAccessTiles(
    rememberedTargetsCount: Int,
    searchBlockingEnabled: Boolean,
    searchKeywordsCount: Int,
    uninstallProtected: Boolean,
    isUninstallUnlockPending: Boolean,
    onUninstallProtectionClick: () -> Unit,
    newInstallAppBlocked: Boolean,
    isNewAppUnlockPending: Boolean,
    onNewInstallAppBlockClick: () -> Unit,
    onNavigateRemember: () -> Unit,
    onNavigateSearchKeywords: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Row 1: 1 -> Remember Protection, 2 -> Search Protection
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            QuickAccessCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Bookmark,
                iconContainer = HomePurpleContainer,
                iconTint = HomePurpleText,
                title = "Remember Protection",
                statusText = "$rememberedTargetsCount targets saved",
                statusColor = HomePurpleText,
                onClick = onNavigateRemember,
                testTag = "tile_remember_protection"
            )

            QuickAccessCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.ContentPasteSearch,
                iconContainer = HomeCoralContainer,
                iconTint = HomeCoralText,
                title = "Search Protection",
                statusText = if (searchBlockingEnabled) "$searchKeywordsCount Keywords" else "Inactive",
                statusColor = if (searchBlockingEnabled) HomeCoralText else TextMuted,
                onClick = onNavigateSearchKeywords,
                testTag = "tile_search_protection"
            )
        }

        // Row 2: 3 -> Uninstall Protection, 4 -> Newly Installed App Block
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val uninstallStatusText = when {
                isUninstallUnlockPending -> "Unlocking..."
                uninstallProtected -> "Active"
                else -> "Disabled"
            }
            val uninstallStatusColor = when {
                isUninstallUnlockPending -> HomeAmberText
                uninstallProtected -> HomeBlueText
                else -> TextMuted
            }
            QuickAccessCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.AdminPanelSettings,
                iconContainer = HomeBlueContainer,
                iconTint = HomeBlueText,
                title = "Uninstall Protection",
                statusText = uninstallStatusText,
                statusColor = uninstallStatusColor,
                onClick = onUninstallProtectionClick,
                testTag = "tile_uninstall_protection"
            )

            val newAppStatusText = when {
                isNewAppUnlockPending -> "Unlocking..."
                newInstallAppBlocked -> "Active"
                else -> "Disabled"
            }
            val newAppStatusColor = when {
                isNewAppUnlockPending -> HomeAmberText
                newInstallAppBlocked -> HomeCyanText
                else -> TextMuted
            }
            QuickAccessCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.InstallMobile,
                iconContainer = HomeCyanContainer,
                iconTint = HomeCyanText,
                title = "Newly Installed App Block",
                statusText = newAppStatusText,
                statusColor = newAppStatusColor,
                onClick = onNewInstallAppBlockClick,
                testTag = "tile_new_install_app_block"
            )
        }
    }
}

@Composable
private fun QuickAccessCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    iconContainer: Color,
    iconTint: Color,
    title: String,
    statusText: String,
    statusColor: Color,
    onClick: () -> Unit,
    testTag: String = ""
) {
    Surface(
        onClick = onClick,
        modifier = modifier.then(if (testTag.isNotEmpty()) Modifier.testTag(testTag) else Modifier),
        color = CardDark,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, CardBorderDark)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconContainerBox(
                    icon = icon,
                    iconTint = iconTint,
                    containerColor = iconContainer
                )

                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Navigate to $title",
                    tint = TextMuted,
                    modifier = Modifier.size(18.dp)
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Text(
                        text = statusText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Normal,
                        color = statusColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeNavRow(
    icon: ImageVector,
    iconContainer: Color,
    iconTint: Color,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    testTag: String = ""
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .then(if (testTag.isNotEmpty()) Modifier.testTag(testTag) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            IconContainerBox(
                icon = icon,
                iconTint = iconTint,
                containerColor = iconContainer
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Normal,
                        color = TextMuted,
                        lineHeight = 14.sp
                    )
                }
            }
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "Navigate",
            tint = TextMuted,
            modifier = Modifier.size(18.dp)
        )
    }
}
