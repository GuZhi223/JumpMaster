package com.jumpmaster.app.core

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.jumpmaster.app.core.adb.AdbConnectionService
import com.jumpmaster.app.core.root.RootCommandService
import com.jumpmaster.app.service.FloatingWindowService
import com.jumpmaster.app.service.ScreenCaptureService
import com.jumpmaster.app.service.TouchAccessibilityService
import kotlinx.coroutines.*
import kotlin.random.Random

/**
 * Main controller that orchestrates the jump flow:
 * capture → analyze → calculate → press → wait → repeat
 *
 * Supports three backends:
 * - LOCAL: MediaProjection + AccessibilityService (on-device)
 * - ADB: Wireless ADB debugging (remote device or self-control)
 * - ROOT: Local root shell commands
 */
class JumpController(private val context: Context) {

    companion object {
        private const val TAG = "JumpController"
    }

    enum class Status {
        IDLE, CAPTURE, DETECT, JUMP, CONNECTING, PAUSED, RESTING
    }

    interface Listener {
        fun onStatusChanged(status: Status)
        fun onJumpCompleted(count: Int, result: ImageAnalyzer.AnalysisResult?)
        fun onError(message: String)
    }

    var config: DeviceConfig = DeviceConfig.forScreen(1080, 1920)
        set(value) {
            field = value
            imageAnalyzer.updateConfig(value)
        }

    var listener: Listener? = null

    private val imageAnalyzer = ImageAnalyzer(config)
    private val handler = Handler(Looper.getMainLooper())
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var running = false
    private var paused = false
    private var jumpCount = 0
    private var jumpsSinceRest = 0
    private var nextRestAfter = Random.nextInt(3, 10)
    private var nextRestSeconds = Random.nextInt(5, 10)
    private var lastResult: ImageAnalyzer.AnalysisResult? = null
    private var lastScreenshot: Bitmap? = null

    fun isRunning(): Boolean = running
    fun isPaused(): Boolean = paused

    /**
     * Start the auto-jump loop.
     */
    fun start() {
        if (running) return

        paused = false
        when (config.captureMode) {
            CaptureMode.LOCAL -> startLocalMode()
            CaptureMode.ADB -> startAdbMode()
            CaptureMode.ROOT -> startRootMode()
        }
    }

    fun pause() {
        if (!running || paused) return
        paused = true
        updateFloatingStatus("paused")
        addLog("已暂停")
        setStatus(Status.PAUSED)
    }

    fun resume() {
        if (!running || !paused) return
        paused = false
        updateFloatingStatus("running")
        addLog("继续运行")
        when (config.captureMode) {
            CaptureMode.LOCAL -> executeLocalJumpCycle()
            CaptureMode.ADB -> executeAdbJumpCycle()
            CaptureMode.ROOT -> executeRootJumpCycle()
        }
    }

    /**
     * Stop the auto-jump loop.
     */
    fun stop() {
        running = false
        paused = false
        updateFloatingStatus("idle")
        addLog("已停止")
        handler.removeCallbacksAndMessages(null)
        scope.coroutineContext.cancelChildren()
    }

    fun getJumpCount(): Int = jumpCount
    fun getLastResult(): ImageAnalyzer.AnalysisResult? = lastResult
    fun getLastScreenshot(): Bitmap? = lastScreenshot

    // ── LOCAL mode (MediaProjection + AccessibilityService) ──

    private fun startLocalMode() {
        if (!ScreenCaptureService.isRunning()) {
            listener?.onError("截屏服务未启动")
            return
        }
        if (!TouchAccessibilityService.isRunning()) {
            listener?.onError("无障碍服务未启用，请在系统设置中开启")
            return
        }

        running = true
        paused = false
        jumpCount = 0
        resetRestPlan()
        updateFloatingStatus("running")
        addLog("本地模式启动")
        executeLocalJumpCycle()
    }

    private fun executeLocalJumpCycle() {
        if (!running || paused) return

        setStatus(Status.CAPTURE)
        updateFloatingStatus("capture")
        addLog("截屏中")

        handler.postDelayed({
            if (!running || paused) return@postDelayed
            // NOTE: Removed foreground check that relied on AccessibilityEvent
            // because AccessibilityEvent only fires on window/focus changes,
            // causing false "please switch to game" prompts after the first jump.
            // The user is already in the game when they start the controller.
            TouchAccessibilityService.captureScreenshot { accessibilityScreenshot ->
                val screenshot = accessibilityScreenshot ?: ScreenCaptureService.captureScreen()

                if (!running || paused) return@captureScreenshot

                if (screenshot == null) {
                    Log.w(TAG, "Failed to capture screenshot")
                    addLog("截屏失败")
                    scheduleNextRetry()
                    return@captureScreenshot
                }

                if (accessibilityScreenshot != null) {
                    addLog("无障碍截图 ${screenshot.width}x${screenshot.height}")
                } else {
                    addLog("投屏截图 ${screenshot.width}x${screenshot.height}")
                }

                setStatus(Status.DETECT)
                updateFloatingStatus("detect")
                addLog("识别中")

                handler.post { analyzeAndJumpLocal(screenshot) }
            }
        }, 200)
    }

    private fun analyzeAndJumpLocal(screenshot: Bitmap) {
        if (!running || paused) return
        Log.d(TAG, "Analyzing screenshot: ${screenshot.width}x${screenshot.height}")
        lastScreenshot = screenshot

        // Always save debug screenshot on first attempt
        if (jumpCount == 0 && lastResult == null) {
            saveDebugScreenshot(screenshot, null)
        }

        val result = imageAnalyzer.analyze(screenshot)
        if (result == null) {
            Log.w(TAG, "Analysis failed - piece/board not found in ${screenshot.width}x${screenshot.height} bitmap")
            listener?.onError("识别失败")
            addLog("识别失败")
            scheduleNextRetry()
            return
        }

        lastResult = result
        lastScreenshot = screenshot

        // Save annotated debug screenshot
        saveDebugScreenshot(screenshot, result)

        setStatus(Status.JUMP)
        updateFloatingStatus("jump")
        addLog("距离 ${result.distance.toInt()}px · ${result.pressTimeMs}ms")
        vibrate()

        val press = JumpTouch.createOriginalSwipePosition(screenshot.width, screenshot.height)
        Log.i(
            TAG,
            "Local long press from (${press.startX}, ${press.startY}) " +
                    "to (${press.endX}, ${press.endY}) for ${result.pressTimeMs}ms"
        )

        TouchAccessibilityService.longPress(
            x = press.startX,
            y = press.startY,
            endX = press.endX,
            endY = press.endY,
            durationMs = result.pressTimeMs
        ) { success ->
            if (!running) return@longPress
            if (success) {
                jumpCount++
                jumpsSinceRest++
                handler.post {
                    updateFloatingMetrics(jumpCount, result.pressTimeMs, result.distance, 0)
                    listener?.onJumpCompleted(jumpCount, result)
                }
            }
            scheduleNextLocalCycle()
        }
    }

    private fun scheduleNextRetry() {
        handler.postDelayed({
            if (running && !paused) executeLocalJumpCycle()
        }, 1000)
    }

    private fun scheduleNextLocalCycle() {
        if (scheduleRestIfNeeded { executeLocalJumpCycle() }) return

        val baseDelay = config.jumpDelay
        val jitter = Random.nextLong(-200, 300)
        val delay = (baseDelay + jitter).coerceAtLeast(800)
        updateFloatingMetrics(jumpCount, lastResult?.pressTimeMs ?: 0L, lastResult?.distance ?: 0f, delay)

        handler.postDelayed({
            if (running && !paused) {
                setStatus(Status.IDLE)
                executeLocalJumpCycle()
            }
        }, delay)
    }

    // ── ADB mode (wireless debugging) ──

    private fun startAdbMode() {
        val adbService = AdbConnectionService.getInstance()

        if (!adbService.isConnected()) {
            // Connect first
            if (config.adbHost.isBlank() || config.adbPort == 0) {
                listener?.onError("请先在设置中配置ADB连接信息")
                return
            }

            setStatus(Status.CONNECTING)
            updateFloatingStatus("connecting")

            adbService.listener = object : AdbConnectionService.ConnectionListener {
                override fun onStateChanged(state: AdbConnectionService.ConnectionState) {
                    when (state) {
                        AdbConnectionService.ConnectionState.CONNECTED -> {
                            running = true
                            paused = false
                            jumpCount = 0
                            resetRestPlan()
                            updateFloatingStatus("running")
                            addLog("ADB连接成功")
                            executeAdbJumpCycle()
                        }
                        AdbConnectionService.ConnectionState.ERROR -> {
                            listener?.onError("ADB连接失败")
                            addLog("ADB连接失败")
                        }
                        else -> {}
                    }
                }

                override fun onError(message: String) {
                    listener?.onError(message)
                }
            }

            adbService.connect(
                host = config.adbHost,
                port = config.adbPort,
                pairingCode = config.adbPairingCode.takeIf { it.isNotBlank() },
                pairingPort = config.adbPairingPort.takeIf { it > 0 }
            )
        } else {
            running = true
            paused = false
            jumpCount = 0
            resetRestPlan()
            updateFloatingStatus("running")
            addLog("复用ADB连接")
            executeAdbJumpCycle()
        }
    }

    private fun executeAdbJumpCycle() {
        if (!running || paused) return

        setStatus(Status.CAPTURE)
        updateFloatingStatus("capture")
        addLog("截屏中")

        scope.launch {
            val adbService = AdbConnectionService.getInstance()
            if (!adbService.ensureConnected(
                    host = config.adbHost,
                    port = config.adbPort,
                    pairingCode = config.adbPairingCode.takeIf { it.isNotBlank() },
                    pairingPort = config.adbPairingPort.takeIf { it > 0 }
                )
            ) {
                withContext(Dispatchers.Main) {
                    listener?.onError("ADB连接已断开")
                    running = false
                    addLog("ADB连接断开")
                }
                return@launch
            }
            adbService.getScreenDensity()?.let { density ->
                val adbHeadDiameter = density / 8f
                if (adbHeadDiameter > 0f && kotlin.math.abs(config.headDiameter - adbHeadDiameter) > 0.1f) {
                    config = config.copy(headDiameter = adbHeadDiameter)
                    Log.i(TAG, "ADB calibrated headDiameter=$adbHeadDiameter from wm density=$density")
                }
            }

            // Step 1: Screenshot via ADB
            val screenshot = adbService.captureScreen()
            if (!running || paused) return@launch
            if (screenshot == null) {
                Log.w(TAG, "ADB screenshot failed")
                addLog("ADB截屏失败")
                delay(1000)
                if (running && !paused) executeAdbJumpCycle()
                return@launch
            }
            saveDebugScreenshot(screenshot, null)

            setStatus(Status.DETECT)
            updateFloatingStatus("detect")
            addLog("识别中")

            // Step 2: Analyze
            val result = imageAnalyzer.analyze(screenshot)
            if (!running || paused) return@launch
            if (result == null) {
                Log.w(TAG, "ADB analysis failed")
                listener?.onError("识别失败")
                addLog("识别失败")
                delay(1000)
                if (running && !paused) executeAdbJumpCycle()
                return@launch
            }

            withContext(Dispatchers.Main) {
                lastResult = result
                lastScreenshot = screenshot
            }

            setStatus(Status.JUMP)
            updateFloatingStatus("jump")
            addLog("距离 ${result.distance.toInt()}px · ${result.pressTimeMs}ms")
            withContext(Dispatchers.Main) { vibrate() }
            saveDebugScreenshot(screenshot, result)
            if (!running || paused) return@launch

            // Step 3: Touch via ADB (input swipe)
            val press = JumpTouch.createOriginalSwipePosition(screenshot.width, screenshot.height)
            Log.i(
                TAG,
                "ADB long press from (${press.startX}, ${press.startY}) " +
                        "to (${press.endX}, ${press.endY}) for ${result.pressTimeMs}ms"
            )
            val success = adbService.performLongPress(
                x = press.startX,
                y = press.startY,
                endX = press.endX,
                endY = press.endY,
                durationMs = result.pressTimeMs
            )

            if (success) {
                jumpsSinceRest++
                withContext(Dispatchers.Main) {
                    jumpCount++
                    updateFloatingMetrics(jumpCount, result.pressTimeMs, result.distance, 0)
                    listener?.onJumpCompleted(jumpCount, result)
                }
            }

            // Step 4: Wait
            if (restIfNeededSuspend()) {
                if (running && !paused) {
                    withContext(Dispatchers.Main) { setStatus(Status.IDLE) }
                    executeAdbJumpCycle()
                }
                return@launch
            }
            val baseDelay = config.jumpDelay
            val jitter = Random.nextLong(-200, 300)
            val delayMs = (baseDelay + jitter).coerceAtLeast(800)
            updateFloatingMetrics(jumpCount, result.pressTimeMs, result.distance, delayMs)

            delay(delayMs)
            if (running && !paused) {
                withContext(Dispatchers.Main) { setStatus(Status.IDLE) }
                executeAdbJumpCycle()
            }
        }
    }

    // ── ROOT mode (local su shell) ──

    private fun startRootMode() {
        setStatus(Status.CONNECTING)
        updateFloatingStatus("connecting")
        addLog("正在请求 Root 权限")
        scope.launch {
            if (!RootCommandService.requestAccess()) {
                running = false
                setStatus(Status.IDLE)
                updateFloatingStatus("error")
                addLog("Root 权限被拒绝或设备未 Root")
                listener?.onError("Root 权限获取失败，请在 Root 管理器中允许 JumpMaster")
                return@launch
            }

            running = true
            paused = false
            jumpCount = 0
            resetRestPlan()
            RootCommandService.getScreenDensity()?.let { density ->
                val rootHeadDiameter = density / 8f
                if (rootHeadDiameter > 0f) config = config.copy(headDiameter = rootHeadDiameter)
            }
            updateFloatingStatus("running")
            addLog("Root 模式已激活")
            executeRootJumpCycle()
        }
    }

    private fun executeRootJumpCycle() {
        if (!running || paused) return
        setStatus(Status.CAPTURE)
        updateFloatingStatus("capture")
        addLog("Root 截屏中")

        scope.launch {
            val screenshot = RootCommandService.captureScreen()
            if (!running || paused) return@launch
            if (screenshot == null) {
                addLog("Root 截屏失败")
                listener?.onError("Root 截屏失败")
                delay(1000)
                if (running && !paused) executeRootJumpCycle()
                return@launch
            }

            setStatus(Status.DETECT)
            updateFloatingStatus("detect")
            addLog("识别中")
            val result = imageAnalyzer.analyze(screenshot)
            if (!running || paused) return@launch
            if (result == null) {
                addLog("识别失败")
                listener?.onError("识别失败")
                delay(1000)
                if (running && !paused) executeRootJumpCycle()
                return@launch
            }

            lastResult = result
            lastScreenshot = screenshot
            saveDebugScreenshot(screenshot, result)
            setStatus(Status.JUMP)
            updateFloatingStatus("jump")
            addLog("距离 ${result.distance.toInt()}px · ${result.pressTimeMs}ms")
            vibrate()

            val press = JumpTouch.createOriginalSwipePosition(screenshot.width, screenshot.height)
            val success = RootCommandService.performLongPress(
                press.startX,
                press.startY,
                press.endX,
                press.endY,
                result.pressTimeMs
            )
            if (success) {
                jumpCount++
                jumpsSinceRest++
                updateFloatingMetrics(jumpCount, result.pressTimeMs, result.distance, 0)
                listener?.onJumpCompleted(jumpCount, result)
            } else {
                addLog("Root 触摸失败")
            }

            if (restIfNeededSuspend()) {
                if (running && !paused) executeRootJumpCycle()
                return@launch
            }
            val delayMs = (config.jumpDelay + Random.nextLong(-200, 300)).coerceAtLeast(800)
            updateFloatingMetrics(jumpCount, result.pressTimeMs, result.distance, delayMs)
            delay(delayMs)
            if (running && !paused) {
                setStatus(Status.IDLE)
                executeRootJumpCycle()
            }
        }
    }

    // ── Shared helpers ──

    /**
     * Save a debug screenshot to /sdcard/JumpMaster/ for inspection.
     * Overwrites the same file each time.
     */
    private fun saveDebugScreenshot(bitmap: Bitmap, result: ImageAnalyzer.AnalysisResult?) {
        try {
            // Use app-specific external dir (no permission needed)
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                ?: File(context.filesDir, "debug_screenshots")
            if (!dir.exists()) dir.mkdirs()

            // Save raw screenshot
            val file = File(dir, "debug_screenshot.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
            Log.i(TAG, "Debug screenshot saved: ${file.absolutePath}")

            // Save annotated version if we have a result
            if (result != null) {
                val annotated = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                val canvas = Canvas(annotated)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                }

                // Red circle at piece position
                paint.color = AndroidColor.RED
                canvas.drawCircle(result.pieceX.toFloat(), result.pieceY.toFloat(), 15f, paint)

                // Blue circle at board position
                paint.color = AndroidColor.BLUE
                canvas.drawCircle(result.boardX.toFloat(), result.boardY.toFloat(), 15f, paint)

                // Line between them
                paint.color = AndroidColor.GREEN
                paint.strokeWidth = 2f
                canvas.drawLine(
                    result.pieceX.toFloat(), result.pieceY.toFloat(),
                    result.boardX.toFloat(), result.boardY.toFloat(),
                    paint
                )

                val annotatedFile = File(dir, "debug_annotated.png")
                FileOutputStream(annotatedFile).use { out ->
                    annotated.compress(Bitmap.CompressFormat.PNG, 90, out)
                }
                Log.i(TAG, "Annotated screenshot saved: ${annotatedFile.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save debug screenshot", e)
        }
    }

    private fun vibrate() {
        if (config.vibrate) {
            vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun setStatus(status: Status) {
        handler.post { listener?.onStatusChanged(status) }
    }

    private fun updateFloatingStatus(status: String) {
        val intent = Intent(context, FloatingWindowService::class.java).apply {
            action = FloatingWindowService.ACTION_UPDATE_STATUS
            putExtra(FloatingWindowService.EXTRA_STATUS, status)
        }
        try { context.startService(intent) } catch (e: Exception) {
            Log.w(TAG, "Failed to update floating status", e)
        }
    }

    private fun updateFloatingMetrics(count: Int, pressTimeMs: Long, distance: Float, etaMs: Long) {
        val intent = Intent(context, FloatingWindowService::class.java).apply {
            action = FloatingWindowService.ACTION_UPDATE_METRICS
            putExtra(FloatingWindowService.EXTRA_COUNT, count)
            putExtra(FloatingWindowService.EXTRA_PRESS_MS, pressTimeMs)
            putExtra(FloatingWindowService.EXTRA_DISTANCE, distance)
            putExtra(FloatingWindowService.EXTRA_ETA_MS, etaMs)
        }
        try { context.startService(intent) } catch (e: Exception) {
            Log.w(TAG, "Failed to update floating metrics", e)
        }
    }

    private fun addLog(message: String) {
        val intent = Intent(context, FloatingWindowService::class.java).apply {
            action = FloatingWindowService.ACTION_ADD_LOG
            putExtra(FloatingWindowService.EXTRA_LOG, message)
        }
        try { context.startService(intent) } catch (e: Exception) {
            Log.w(TAG, "Failed to add floating log", e)
        }
    }

    private fun resetRestPlan() {
        jumpsSinceRest = 0
        nextRestAfter = Random.nextInt(3, 10)
        nextRestSeconds = Random.nextInt(5, 10)
        updateFloatingMetrics(0, 0, 0f, 0)
    }

    private fun scheduleRestIfNeeded(onDone: () -> Unit): Boolean {
        if (jumpsSinceRest < nextRestAfter) return false

        val restMs = nextRestSeconds * 1000L
        setStatus(Status.RESTING)
        updateFloatingStatus("resting")
        addLog("连续 $jumpsSinceRest 次，休息 ${nextRestSeconds}s")
        updateFloatingMetrics(jumpCount, lastResult?.pressTimeMs ?: 0L, lastResult?.distance ?: 0f, restMs)

        handler.postDelayed({
            if (running && !paused) {
                resetRestPlanAfterRest()
                onDone()
            }
        }, restMs)
        return true
    }

    private suspend fun restIfNeededSuspend(): Boolean {
        if (jumpsSinceRest < nextRestAfter) return false

        val restSeconds = nextRestSeconds
        setStatus(Status.RESTING)
        updateFloatingStatus("resting")
        addLog("连续 $jumpsSinceRest 次，休息 ${restSeconds}s")
        for (remaining in restSeconds downTo 1) {
            if (!running || paused) return true
            updateFloatingMetrics(jumpCount, lastResult?.pressTimeMs ?: 0L, lastResult?.distance ?: 0f, remaining * 1000L)
            delay(1000)
        }
        resetRestPlanAfterRest()
        return true
    }

    private fun resetRestPlanAfterRest() {
        jumpsSinceRest = 0
        nextRestAfter = Random.nextInt(30, 100)
        nextRestSeconds = Random.nextInt(10, 60)
        addLog("休息结束")
    }
}
