package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.service.BatteryOptimizationHelper
import com.example.ui.screens.BlockedKeywordsScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.NewInstallAppBlockScreen
import com.example.ui.screens.RememberProtectionScreen
import com.example.ui.theme.BgDark
import com.example.ui.theme.CardDark
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.EmeraldGreenLight
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onSetupStepCompleted: () -> Unit = {}
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var isBatteryDone by remember {
        mutableStateOf(BatteryOptimizationHelper.isBatteryRestrictionDone(context))
    }
    var isAutoStartDone by remember {
        mutableStateOf(BatteryOptimizationHelper.isAutoStartDone(context))
    }
    var isConfiguringBattery by remember { mutableStateOf(false) }
    var isConfiguringAutoStart by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isBatteryDone = BatteryOptimizationHelper.isBatteryRestrictionDone(context)
                isAutoStartDone = BatteryOptimizationHelper.isAutoStartDone(context)
                isConfiguringBattery = false
                isConfiguringAutoStart = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Step 1: Initial Setup - Battery Saver -> No Restrictions Dialog (Mandatory, No Skip/Later)
    if (!isBatteryDone && !isConfiguringBattery) {
        AlertDialog(
            onDismissRequest = {
                // Mandatory setup step: dismissing is disabled
            },
            icon = {
                Icon(
                    imageVector = Icons.Default.BatterySaver,
                    contentDescription = "Battery Saver No Restrictions",
                    tint = EmeraldGreenLight,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = "Battery Saver: No Restrictions",
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            },
            text = {
                Text(
                    text = "To prevent MIUI/HyperOS from aggressively terminating Focus Shield in the background, please tap Configure, open 'Battery saver', and select 'No restrictions'.",
                    fontSize = 14.sp,
                    color = TextSecondary,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        BatteryOptimizationHelper.setBatteryRestrictionDone(context, true)
                        isConfiguringBattery = true
                        BatteryOptimizationHelper.openAppDetails(context)
                        onSetupStepCompleted()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen),
                    modifier = Modifier
                        .testTag("setup_battery_button")
                        .semantics { contentDescription = "Configure Battery Saver" }
                ) {
                    Text(
                        text = "Configure",
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }
            },
            containerColor = CardDark,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.testTag("battery_setup_dialog")
        )
    } else if (!isAutoStartDone && !isConfiguringAutoStart) {
        // Step 2: Initial Setup - Background Auto-Start Dialog (Mandatory, No Skip/Later)
        AlertDialog(
            onDismissRequest = {
                // Mandatory setup step: dismissing is disabled
            },
            icon = {
                Icon(
                    imageVector = Icons.Default.RocketLaunch,
                    contentDescription = "Background Auto-Start",
                    tint = EmeraldGreenLight,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = "Background Auto-Start",
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            },
            text = {
                Text(
                    text = "To ensure Focus Shield remains active and protected against background termination, please enable Background Auto-Start in system settings.",
                    fontSize = 14.sp,
                    color = TextSecondary,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        BatteryOptimizationHelper.setAutoStartDone(context, true)
                        isConfiguringAutoStart = true
                        BatteryOptimizationHelper.openXiaomiAutostartSettings(context)
                        onSetupStepCompleted()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen),
                    modifier = Modifier
                        .testTag("setup_autostart_button")
                        .testTag("settings_xiaomi_autostart")
                        .semantics { contentDescription = "Background Auto-Start" }
                ) {
                    Text(
                        text = "Auto-Start",
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }
            },
            containerColor = CardDark,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.testTag("autostart_setup_dialog")
        )
    }

    // Handle back button when inside sub-screens
    if (uiState.currentScreen != ScreenDestination.TABS) {
        BackHandler {
            viewModel.navigateBack()
        }
    }

    Scaffold(
        containerColor = BgDark
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(BgDark)
        ) {
            Crossfade(
                targetState = uiState.currentScreen,
                animationSpec = tween(150),
                label = "ScreenNavigation"
            ) { screen ->
                when (screen) {
                    ScreenDestination.TABS -> {
                        HomeScreen(uiState = uiState, viewModel = viewModel)
                    }
                    ScreenDestination.REMEMBER_PROTECTION -> {
                        RememberProtectionScreen(uiState = uiState, viewModel = viewModel)
                    }
                    ScreenDestination.SEARCH_KEYWORDS -> {
                        BlockedKeywordsScreen(uiState = uiState, viewModel = viewModel)
                    }
                    ScreenDestination.NEW_INSTALL_APP_BLOCK -> {
                        NewInstallAppBlockScreen(uiState = uiState, viewModel = viewModel)
                    }
                }
            }
        }
    }
}

