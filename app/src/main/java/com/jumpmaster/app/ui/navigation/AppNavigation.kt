package com.jumpmaster.app.ui.navigation

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import com.jumpmaster.app.core.CaptureMode
import com.jumpmaster.app.core.DeviceConfig
import com.jumpmaster.app.core.ImageAnalyzer
import com.jumpmaster.app.core.JumpController
import com.jumpmaster.app.ui.components.BottomNavBar
import com.jumpmaster.app.ui.screens.DebugScreen
import com.jumpmaster.app.ui.screens.HomeScreen
import com.jumpmaster.app.ui.screens.SettingsScreen

@Composable
fun AppNavigation(
    navController: NavHostController,
    status: JumpController.Status,
    jumpCount: Int,
    isRunning: Boolean,
    accessibilityRunning: Boolean,
    config: DeviceConfig,
    lastResult: ImageAnalyzer.AnalysisResult?,
    lastScreenshot: Bitmap?,
    onToggle: () -> Unit,
    onConfigChange: (DeviceConfig) -> Unit,
    onSaveAdb: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenAdbSetup: () -> Unit,
    onActivateRoot: () -> Unit
) {
    var currentScreen by remember { mutableStateOf("home") }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            BottomNavBar(
                currentScreen = currentScreen,
                onNavigate = { currentScreen = it }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            when (currentScreen) {
                "home" -> HomeScreen(
                    status = status,
                    jumpCount = jumpCount,
                    pressCoefficient = config.pressCoefficient,
                    isRunning = isRunning,
                    captureMode = config.captureMode,
                    onToggle = onToggle
                )
                "settings" -> SettingsScreen(
                    config = config,
                    accessibilityRunning = accessibilityRunning,
                    onConfigChange = onConfigChange,
                    onSaveAdb = onSaveAdb,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                    onOpenAdbSetup = onOpenAdbSetup,
                    onActivateRoot = onActivateRoot,
                    onBack = { currentScreen = "home" }
                )
                "debug" -> DebugScreen(
                    lastResult = lastResult,
                    lastScreenshot = lastScreenshot,
                    onBack = { currentScreen = "home" }
                )
            }
        }
    }
}
