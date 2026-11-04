package com.jumpmaster.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.lifecycleScope
import com.jumpmaster.app.core.CaptureMode
import com.jumpmaster.app.core.DeviceConfig
import com.jumpmaster.app.core.JumpController
import com.jumpmaster.app.core.adb.AdbConnectionService
import com.jumpmaster.app.core.root.RootCommandService
import com.jumpmaster.app.data.ConfigRepository
import com.jumpmaster.app.service.FloatingWindowService
import com.jumpmaster.app.service.AdbSetupOverlayService
import com.jumpmaster.app.service.ScreenCaptureService
import com.jumpmaster.app.service.TouchAccessibilityService
import com.jumpmaster.app.ui.navigation.AppNavigation
import com.jumpmaster.app.ui.theme.JumpMasterTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "JumpMaster"
    }

    private lateinit var configRepository: ConfigRepository
    private lateinit var jumpController: JumpController

    // State for Compose
    private val _status = mutableStateOf(JumpController.Status.IDLE)
    private val _jumpCount = mutableStateOf(0)
    private val _isRunning = mutableStateOf(false)
    private val _accessibilityRunning = mutableStateOf(false)
    private val _config = mutableStateOf(DeviceConfig())
    private val _lastResult = mutableStateOf<com.jumpmaster.app.core.ImageAnalyzer.AnalysisResult?>(null)
    private val _lastScreenshot = mutableStateOf<android.graphics.Bitmap?>(null)
    private var waitingForAccessibility = false
    private var waitingForAdbSetupOverlay = false

    // MediaProjection permission launcher
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.i(TAG, "screenCaptureLauncher callback: resultCode=${result.resultCode}, data=${result.data != null}")
        try {
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                startLocalModeServices(result.resultCode, result.data!!)
            } else {
                Log.w(TAG, "Screen capture permission denied: resultCode=${result.resultCode}")
                Toast.makeText(this, "截屏权限被拒绝", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "screenCaptureLauncher callback failed", e)
            Toast.makeText(this, "启动截屏服务失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // Overlay permission launcher
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val hasOverlay = Settings.canDrawOverlays(this)
        Log.i(TAG, "overlayPermissionLauncher callback: canDrawOverlays=$hasOverlay")
        try {
            if (hasOverlay) {
                if (waitingForAdbSetupOverlay) {
                    waitingForAdbSetupOverlay = false
                    openAdbSetup()
                } else {
                    startFloatingWindowService()
                    proceedAfterOverlayPermission()
                }
            } else {
                Log.w(TAG, "Overlay permission denied")
                waitingForAdbSetupOverlay = false
                Toast.makeText(this, "悬浮窗权限被拒绝", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "overlayPermissionLauncher callback failed", e)
            Toast.makeText(this, "权限回调失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // Notification permission launcher (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.i(TAG, "Notification permission: granted=$granted")
        if (!granted) {
            Toast.makeText(this, "通知权限被拒绝，服务可能无法正常运行", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate: sdk=${Build.VERSION.SDK_INT}, device=${Build.MODEL}")

        try {
            configRepository = ConfigRepository(this)
            jumpController = JumpController(this)
            Log.i(TAG, "Config and controller initialized")

            // Load config for current device
            val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            _config.value = configRepository.loadConfig(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
            jumpController.config = _config.value
            Log.i(TAG, "Config loaded: ${metrics.widthPixels}x${metrics.heightPixels}, mode=${_config.value.captureMode}")

            // Set up controller listener
            jumpController.listener = object : JumpController.Listener {
                override fun onStatusChanged(status: JumpController.Status) {
                    Log.d(TAG, "Controller status: $status")
                    _status.value = status
                }

                override fun onJumpCompleted(count: Int, result: com.jumpmaster.app.core.ImageAnalyzer.AnalysisResult?) {
                    Log.d(TAG, "Jump #$count completed")
                    _jumpCount.value = count
                    _lastResult.value = result
                    _lastScreenshot.value = jumpController.getLastScreenshot()
                }

                override fun onError(message: String) {
                    Log.e(TAG, "Controller error: $message")
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                }
            }

            // Set up floating window toggle callback
            FloatingWindowService.setToggleCallback {
                Log.d(TAG, "Floating window toggle clicked")
                runOnUiThread { toggleJump() }
            }
            FloatingWindowService.setStopCallback {
                Log.d(TAG, "Floating window stop clicked")
                runOnUiThread {
                    if (_isRunning.value) stopJump()
                }
            }

            // Request notification permission for Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Log.d(TAG, "Requesting notification permission")
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            Log.i(TAG, "Setting up Compose UI")
            setContent {
                JumpMasterTheme {
                    val navController = rememberNavController()
                    AppNavigation(
                        navController = navController,
                        status = _status.value,
                        jumpCount = _jumpCount.value,
                        isRunning = _isRunning.value,
                        accessibilityRunning = _accessibilityRunning.value,
                        config = _config.value,
                        lastResult = _lastResult.value,
                        lastScreenshot = _lastScreenshot.value,
                        onToggle = { prepareServices() },
                        onConfigChange = { newConfig ->
                            _config.value = newConfig
                            jumpController.config = newConfig
                            configRepository.saveConfig(newConfig)
                        },
                        onSaveAdb = {
                            val c = _config.value
                            configRepository.saveConfig(c)
                            Log.i(TAG, "ADB config saved: host=${c.adbHost}, port=${c.adbPort}, pairingPort=${c.adbPairingPort}")

                            if (c.adbHost.isBlank() || c.adbPort == 0) {
                                Toast.makeText(this, "请填写 IP 地址和连接端口", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this, "正在测试连接 ${c.adbHost}:${c.adbPort} ...", Toast.LENGTH_SHORT).show()
                                // Disconnect any existing connection first
                                AdbConnectionService.getInstance().disconnect()
                                // Test connection
                                AdbConnectionService.getInstance().listener = object : AdbConnectionService.ConnectionListener {
                                    override fun onStateChanged(state: AdbConnectionService.ConnectionState) {
                                        when (state) {
                                            AdbConnectionService.ConnectionState.CONNECTED -> {
                                                Log.i(TAG, "ADB test: connected!")
                                                Toast.makeText(this@MainActivity, "ADB 连接成功!", Toast.LENGTH_SHORT).show()
                                            }
                                            AdbConnectionService.ConnectionState.ERROR -> {
                                                Log.e(TAG, "ADB test: failed")
                                                // Error toast is already shown from the service
                                            }
                                            else -> {}
                                        }
                                    }
                                    override fun onError(message: String) {
                                        Log.e(TAG, "ADB test error: $message")
                                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                                    }
                                }
                                AdbConnectionService.getInstance().connect(
                                    host = c.adbHost,
                                    port = c.adbPort,
                                    pairingCode = c.adbPairingCode.takeIf { it.isNotBlank() },
                                    pairingPort = c.adbPairingPort.takeIf { it > 0 }
                                )
                            }
                        },
                        onOpenAccessibilitySettings = { openAccessibilitySettings(false) },
                        onOpenAdbSetup = { openAdbSetup() },
                        onActivateRoot = { activateRoot() }
                    )
                }
            }
            Log.i(TAG, "onCreate completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "onCreate failed", e)
            Toast.makeText(this, "初始化失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * App button: request permissions + start services, but don't start the controller.
     * Shows the floating window so the user can switch to the game first.
     */
    private fun prepareServices() {
        Log.i(TAG, "prepareServices: mode=${_config.value.captureMode}, canOverlay=${Settings.canDrawOverlays(this)}")
        try {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                overlayPermissionLauncher.launch(intent)
                return
            }
            when (_config.value.captureMode) {
                CaptureMode.LOCAL -> startLocalMode()
                CaptureMode.ADB -> startAdbModePrepare()
                CaptureMode.ROOT -> startRootModePrepare()
            }
        } catch (e: Exception) {
            Log.e(TAG, "prepareServices failed", e)
            Toast.makeText(this, "启动失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Floating window button: toggle the actual jump controller on/off.
     */
    private fun toggleJump() {
        Log.i(TAG, "toggleJump: isRunning=${_isRunning.value}")
        try {
            if (_isRunning.value) {
                if (jumpController.isPaused()) {
                    jumpController.resume()
                    Log.i(TAG, "Jump controller resumed from floating window")
                } else {
                    jumpController.pause()
                    Log.i(TAG, "Jump controller paused from floating window")
                }
            } else {
                // Start the controller (services should already be running)
                _isRunning.value = true
                jumpController.start()
                Log.i(TAG, "Jump controller started from floating window")
            }
        } catch (e: Exception) {
            Log.e(TAG, "toggleJump failed", e)
            Toast.makeText(this, "操作失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startJump() {
        Log.i(TAG, "startJump: mode=${_config.value.captureMode}, canOverlay=${Settings.canDrawOverlays(this)}")

        // Step 1: Check overlay permission (needed for both modes)
        if (!Settings.canDrawOverlays(this)) {
            Log.d(TAG, "Requesting overlay permission")
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                overlayPermissionLauncher.launch(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch overlay permission", e)
                Toast.makeText(this, "无法请求悬浮窗权限: ${e.message}", Toast.LENGTH_LONG).show()
            }
            return
        }

        // Step 2: Mode-specific flow
        when (_config.value.captureMode) {
            CaptureMode.LOCAL -> startLocalMode()
            CaptureMode.ADB -> startAdbModePrepare()
            CaptureMode.ROOT -> startRootModePrepare()
        }
    }

    private fun proceedAfterOverlayPermission() {
        Log.i(TAG, "proceedAfterOverlayPermission: mode=${_config.value.captureMode}")
        when (_config.value.captureMode) {
            CaptureMode.LOCAL -> startLocalMode()
            CaptureMode.ADB -> startAdbModePrepare()
            CaptureMode.ROOT -> startRootModePrepare()
        }
    }

    // ── LOCAL mode (MediaProjection + AccessibilityService) ──

    private fun startLocalMode() {
        if (!TouchAccessibilityService.isRunning()) {
            openAccessibilitySettings(true)
            return
        }
        Log.i(TAG, "startLocalMode: requesting screen capture permission")
        try {
            val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
            Log.d(TAG, "Screen capture intent launched")
        } catch (e: Exception) {
            Log.e(TAG, "startLocalMode failed", e)
            Toast.makeText(this, "无法请求截屏权限: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openAccessibilitySettings(continueAfterGrant: Boolean) {
        waitingForAccessibility = continueAfterGrant
        Toast.makeText(this, "请找到 JumpMaster 并开启无障碍服务", Toast.LENGTH_LONG).show()
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            Log.e(TAG, "Unable to open accessibility settings", e)
            Toast.makeText(this, "无法打开无障碍设置: ${e.message}", Toast.LENGTH_LONG).show()
            waitingForAccessibility = false
        }
    }

    private fun openAdbSetup() {
        if (!Settings.canDrawOverlays(this)) {
            waitingForAdbSetupOverlay = true
            Toast.makeText(this, "请先授予悬浮窗权限，再打开无线调试助手", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
            return
        }
        val intent = Intent(this, AdbSetupOverlayService::class.java).apply {
            action = AdbSetupOverlayService.ACTION_SHOW
        }
        startForegroundService(intent)
    }

    override fun onResume() {
        super.onResume()
        val enabled = TouchAccessibilityService.isRunning()
        _accessibilityRunning.value = enabled

        if (::configRepository.isInitialized) {
            val metrics = resources.displayMetrics
            val refreshed = configRepository.loadConfig(
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi
            )
            _config.value = refreshed
            if (::jumpController.isInitialized) jumpController.config = refreshed
        }

        if (waitingForAccessibility && enabled) {
            waitingForAccessibility = false
            Handler(Looper.getMainLooper()).post { startLocalMode() }
        }
    }

    private fun startLocalModeServices(resultCode: Int, data: Intent) {
        Log.i(TAG, "startLocalModeServices: resultCode=$resultCode")

        try {
            // Start capture service
            val captureIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            }
            Log.d(TAG, "Starting ScreenCaptureService")
            startForegroundService(captureIntent)
            Log.i(TAG, "ScreenCaptureService started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ScreenCaptureService", e)
            Toast.makeText(this, "启动截屏服务失败: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        try {
            Log.d(TAG, "Starting FloatingWindowService")
            startFloatingWindowService()
            Log.i(TAG, "FloatingWindowService started - waiting for user to click start in floating window")
            Toast.makeText(this, "请切换到跳一跳游戏，然后点击悬浮窗开始", Toast.LENGTH_LONG).show()
            moveTaskToBack(true)
            Handler(Looper.getMainLooper()).postDelayed({ moveTaskToBack(true) }, 500)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start FloatingWindowService", e)
            Toast.makeText(this, "启动悬浮窗失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ── ADB mode (wireless debugging) ──

    private fun startAdbModePrepare() {
        val config = _config.value
        Log.i(TAG, "startAdbModePrepare: host=${config.adbHost}, port=${config.adbPort}")

        if (config.adbHost.isBlank() || config.adbPort == 0) {
            Log.w(TAG, "ADB config missing")
            Toast.makeText(this, "请先在设置中配置ADB连接信息", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            startFloatingWindowService()
            Log.i(TAG, "ADB mode: floating window shown, waiting for user to click start")
            Toast.makeText(this, "请切换到跳一跳游戏，然后点击悬浮窗开始", Toast.LENGTH_LONG).show()
            moveTaskToBack(true)
            Handler(Looper.getMainLooper()).postDelayed({ moveTaskToBack(true) }, 500)
        } catch (e: Exception) {
            Log.e(TAG, "startAdbModePrepare failed", e)
            Toast.makeText(this, "ADB模式启动失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startRootModePrepare() {
        try {
            startFloatingWindowService()
            Toast.makeText(this, "请切换到跳一跳游戏，然后点击悬浮窗开始", Toast.LENGTH_LONG).show()
            moveTaskToBack(true)
            Handler(Looper.getMainLooper()).postDelayed({ moveTaskToBack(true) }, 500)
        } catch (e: Exception) {
            Log.e(TAG, "startRootModePrepare failed", e)
            Toast.makeText(this, "Root 模式启动失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun activateRoot() {
        lifecycleScope.launch {
            val granted = RootCommandService.requestAccess()
            Toast.makeText(
                this@MainActivity,
                if (granted) "Root 权限已获取" else "Root 权限获取失败，请检查 Root 管理器",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ── Shared ──

    private fun startFloatingWindowService() {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "startFloatingWindowService: no overlay permission")
            return
        }
        val intent = Intent(this, FloatingWindowService::class.java).apply {
            action = FloatingWindowService.ACTION_SHOW
        }
        Log.d(TAG, "Starting FloatingWindowService (foreground)")
        startForegroundService(intent)
    }

    private fun stopJump() {
        Log.i(TAG, "stopJump")
        _isRunning.value = false
        jumpController.stop()

        try {
            when (_config.value.captureMode) {
                CaptureMode.LOCAL -> {
                    val stopCapture = Intent(this, ScreenCaptureService::class.java).apply {
                        action = ScreenCaptureService.ACTION_STOP
                    }
                    startService(stopCapture)
                    Log.d(TAG, "ScreenCaptureService stop sent")
                }
                CaptureMode.ADB -> Log.d(TAG, "ADB connection kept for reuse")
                CaptureMode.ROOT -> Log.d(TAG, "Root mode stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop capture service", e)
        }

        try {
            val stopFloating = Intent(this, FloatingWindowService::class.java).apply {
                action = FloatingWindowService.ACTION_HIDE
            }
            startService(stopFloating)
            Log.d(TAG, "FloatingWindowService stop sent")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop floating service", e)
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        try {
            if (_isRunning.value) {
                stopJump()
            }
            FloatingWindowService.setToggleCallback(null)
            FloatingWindowService.setStopCallback(null)
            AdbConnectionService.getInstance().destroy()
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy cleanup failed", e)
        }
        super.onDestroy()
    }
}
