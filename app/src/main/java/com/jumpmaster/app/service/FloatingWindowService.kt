package com.jumpmaster.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.jumpmaster.app.JumpMasterApp
import com.jumpmaster.app.MainActivity
import com.jumpmaster.app.R

class FloatingWindowService : Service() {

    companion object {
        private const val TAG = "FloatingWindow"
        const val ACTION_SHOW = "com.jumpmaster.SHOW_FLOATING"
        const val ACTION_HIDE = "com.jumpmaster.HIDE_FLOATING"
        const val ACTION_UPDATE_STATUS = "com.jumpmaster.UPDATE_STATUS"
        const val ACTION_UPDATE_COUNT = "com.jumpmaster.UPDATE_COUNT"
        const val ACTION_UPDATE_METRICS = "com.jumpmaster.UPDATE_METRICS"
        const val ACTION_ADD_LOG = "com.jumpmaster.ADD_LOG"
        const val EXTRA_STATUS = "status"
        const val EXTRA_COUNT = "count"
        const val EXTRA_PRESS_MS = "press_ms"
        const val EXTRA_DISTANCE = "distance"
        const val EXTRA_ETA_MS = "eta_ms"
        const val EXTRA_LOG = "log"

        private var onToggleClicked: (() -> Unit)? = null
        private var onStopClicked: (() -> Unit)? = null

        fun setToggleCallback(callback: (() -> Unit)?) {
            onToggleClicked = callback
        }

        fun setStopCallback(callback: (() -> Unit)?) {
            onStopClicked = callback
        }
    }

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isViewAttached = false
    private var currentStatus = "idle"
    private var expandedLogs = false
    private var lastCount = 0
    private var lastPressMs = 0L
    private var lastDistance = 0f
    private var etaMs = 0L
    private val logs = ArrayDeque<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: action=${intent?.action}")
        when (intent?.action) {
            ACTION_SHOW -> {
                try {
                    Log.d(TAG, "Starting foreground")
                    startForeground(2, createNotification())
                    Log.d(TAG, "Showing floating window")
                    showFloatingWindow()
                    Log.i(TAG, "Floating window shown")
                } catch (e: Exception) {
                    Log.e(TAG, "ACTION_SHOW failed", e)
                }
            }
            ACTION_HIDE -> {
                Log.d(TAG, "Hiding floating window")
                hideFloatingWindow()
                stopSelf()
            }
            ACTION_UPDATE_STATUS -> {
                val status = intent.getStringExtra(EXTRA_STATUS) ?: "idle"
                updateStatus(status)
            }
            ACTION_UPDATE_COUNT -> {
                val count = intent.getIntExtra(EXTRA_COUNT, 0)
                updateMetrics(count, lastPressMs, lastDistance, etaMs)
            }
            ACTION_UPDATE_METRICS -> {
                updateMetrics(
                    count = intent.getIntExtra(EXTRA_COUNT, lastCount),
                    pressMs = intent.getLongExtra(EXTRA_PRESS_MS, lastPressMs),
                    distance = intent.getFloatExtra(EXTRA_DISTANCE, lastDistance),
                    eta = intent.getLongExtra(EXTRA_ETA_MS, etaMs)
                )
            }
            ACTION_ADD_LOG -> {
                intent.getStringExtra(EXTRA_LOG)?.let { addLog(it) }
            }
            else -> {
                Log.w(TAG, "Unknown action: ${intent?.action}")
            }
        }
        return START_NOT_STICKY
    }

    private fun createNotification(): Notification {
        Log.d(TAG, "Creating notification")
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, JumpMasterApp.CHANNEL_FLOATING)
            .setContentTitle("跳一跳助手")
            .setContentText("悬浮窗运行中")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun showFloatingWindow() {
        if (floatingView != null) {
            Log.d(TAG, "Floating view already exists, skipping")
            return
        }

        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val inflater = LayoutInflater.from(this)
            floatingView = inflater.inflate(R.layout.floating_window, null)

            val params = layoutParams ?: createLayoutParams()
            layoutParams = params

            setupDragListener(floatingView!!, params)
            setupButtons(floatingView!!)
            updateStatus(currentStatus)
            updateMetrics(lastCount, lastPressMs, lastDistance, etaMs)
            renderLogs()

            windowManager?.addView(floatingView, params)
            isViewAttached = true
            Log.i(TAG, "Floating view added to window manager")
        } catch (e: Exception) {
            Log.e(TAG, "showFloatingWindow failed", e)
        }
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            x = 16
            y = 120
        }
    }

    private fun setupDragListener(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) moved = true
                    params.x = initialX + dx
                    params.y = initialY + dy
                    layoutParams = params
                    try {
                        windowManager?.updateViewLayout(view, params)
                    } catch (e: Exception) {
                        Log.e(TAG, "updateViewLayout failed", e)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        expandedLogs = !expandedLogs
                        renderLogs()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun setupButtons(view: View) {
        val btnToggle = view.findViewById<Button>(R.id.btnToggle)
        btnToggle?.setOnClickListener {
            Log.d(TAG, "Toggle button clicked")
            onToggleClicked?.invoke()
        }
        view.findViewById<Button>(R.id.btnStop)?.setOnClickListener {
            Log.d(TAG, "Stop button clicked")
            onStopClicked?.invoke()
        }
    }

    private fun hideFloatingWindow() {
        floatingView?.let {
            try {
                windowManager?.removeView(it)
                isViewAttached = false
                Log.d(TAG, "Floating view removed")
            } catch (e: Exception) {
                Log.e(TAG, "removeView failed", e)
            }
        }
        floatingView = null
    }

    private fun updateStatus(status: String) {
        currentStatus = status
        floatingView?.post {
            try {
                val tvTitle = floatingView?.findViewById<TextView>(R.id.tvTitle)
                val btnToggle = floatingView?.findViewById<Button>(R.id.btnToggle)
                val btnStop = floatingView?.findViewById<Button>(R.id.btnStop)
                val statusDot = floatingView?.findViewById<View>(R.id.statusDot)

                when (status) {
                    "running" -> {
                        tvTitle?.text = "运行中"
                        btnToggle?.text = "暂停"
                        btnStop?.visibility = View.VISIBLE
                        setDotColor(statusDot, "#34D399")
                    }
                    "capture" -> {
                        tvTitle?.text = "截屏中"
                        btnToggle?.text = "暂停"
                        btnStop?.visibility = View.VISIBLE
                        setDotColor(statusDot, "#60A5FA")
                    }
                    "detect" -> {
                        tvTitle?.text = "识别中"
                        btnToggle?.text = "暂停"
                        btnStop?.visibility = View.VISIBLE
                        setDotColor(statusDot, "#60A5FA")
                    }
                    "jump" -> {
                        tvTitle?.text = "按压中"
                        btnToggle?.text = "暂停"
                        btnStop?.visibility = View.VISIBLE
                        setDotColor(statusDot, "#34D399")
                    }
                    "resting" -> {
                        tvTitle?.text = "休息中"
                        btnToggle?.text = "暂停"
                        btnStop?.visibility = View.VISIBLE
                        setDotColor(statusDot, "#FBBF24")
                    }
                    "paused" -> {
                        tvTitle?.text = "已暂停"
                        btnToggle?.text = "继续"
                        btnStop?.visibility = View.VISIBLE
                        setDotColor(statusDot, "#FBBF24")
                    }
                    "connecting" -> {
                        tvTitle?.text = "连接中"
                        btnToggle?.text = "暂停"
                        btnStop?.visibility = View.VISIBLE
                        setDotColor(statusDot, "#60A5FA")
                    }
                    "idle" -> {
                        tvTitle?.text = "跳跃助手"
                        btnToggle?.text = "开始"
                        btnStop?.visibility = View.GONE
                        setDotColor(statusDot, "#6B7280")
                    }
                    "error" -> setDotColor(statusDot, "#FB7185")
                }
            } catch (e: Exception) {
                Log.e(TAG, "updateStatus failed", e)
            }
        }
    }

    private fun updateMetrics(count: Int, pressMs: Long, distance: Float, eta: Long) {
        lastCount = count
        lastPressMs = pressMs
        lastDistance = distance
        etaMs = eta
        floatingView?.post {
            try {
                val tvStats = floatingView?.findViewById<TextView>(R.id.tvStats)
                val parts = mutableListOf("#$count")
                if (pressMs > 0) parts += "${pressMs}ms"
                if (eta > 0) parts += "${String.format("%.1f", eta / 1000f)}s"
                tvStats?.text = parts.joinToString(" · ")
            } catch (e: Exception) {
                Log.e(TAG, "updateMetrics failed", e)
            }
        }
    }

    private fun addLog(message: String) {
        val item = "${System.currentTimeMillis().toClock()} $message"
        logs.addFirst(item)
        while (logs.size > 5) logs.removeLast()
        renderLogs()
    }

    private fun renderLogs() {
        floatingView?.post {
            val tvLog = floatingView?.findViewById<TextView>(R.id.tvLog) ?: return@post
            tvLog.maxLines = if (expandedLogs) 5 else 1
            tvLog.text = logs.take(if (expandedLogs) 5 else 1).joinToString("\n").ifBlank { "等待开始" }
        }
    }

    private fun setDotColor(view: View?, color: String) {
        view?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(color))
        }
    }

    private fun Long.toClock(): String {
        val total = this / 1000
        val minute = (total / 60) % 60
        val second = total % 60
        return String.format("%02d:%02d", minute, second)
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        hideFloatingWindow()
        super.onDestroy()
    }
}
