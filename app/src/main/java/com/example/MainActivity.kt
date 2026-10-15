package com.example

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.service.BatteryOptimizationHelper
import com.example.ui.MainScreen
import com.example.ui.MainViewModel
import com.example.ui.theme.FocusShieldTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FocusShieldTheme {
                MainScreen(
                    viewModel = viewModel,
                    onSetupStepCompleted = {
                        checkSetupFlow()
                    }
                )
            }
        }
        checkSetupFlow()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshAccessibilityStatus()
        viewModel.refreshDeviceAdminStatus()
        viewModel.syncInstalledApps()
        checkSetupFlow()
    }

    private fun checkSetupFlow() {
        // Step 1: Battery Saver -> No restrictions setup
        if (!BatteryOptimizationHelper.isBatteryRestrictionDone(this)) {
            return
        }

        // Step 2: Background Auto-Start setup
        if (!BatteryOptimizationHelper.isAutoStartDone(this)) {
            return
        }
    }
}

