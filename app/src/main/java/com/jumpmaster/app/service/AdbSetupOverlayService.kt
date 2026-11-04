package com.jumpmaster.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.jumpmaster.app.JumpMasterApp
import com.jumpmaster.app.MainActivity
import com.jumpmaster.app.R
import com.jumpmaster.app.core.adb.AdbConnectionService
import com.jumpmaster.app.data.ConfigRepository

/** Editable overlay kept above Developer options while the user reads pairing values. */
class AdbSetupOverlayService : Service() {

    companion object {
        private const val TAG = "AdbSetupOverlay"
        const val ACTION_SHOW = "com.jumpmaster.SHOW_ADB_SETUP"
        const val ACTION_HIDE = "com.jumpmaster.HIDE_ADB_SETUP"
    }

    private var windowManager: WindowManager? = null
    private var panel: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var focusedField: EditText? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                startForeground(3, createNotification())
                openWirelessDebuggingSettings()
                showPanel()
            }
            ACTION_HIDE -> stopAndRemove()
        }
        return START_NOT_STICKY
    }

    private fun showPanel() {
        if (panel != null) return
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = LayoutInflater.from(this).inflate(R.layout.floating_adb_setup, null)
        val displayMetrics = resources.displayMetrics
        val layoutParams = WindowManager.LayoutParams(
            minOf((280 * displayMetrics.density).toInt(), displayMetrics.widthPixels - 24),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 12
            y = 80
        }

        val current = ConfigRepository(this).loadConfig(
            displayMetrics.widthPixels,
            displayMetrics.heightPixels,
            displayMetrics.densityDpi
        )
        view.findViewById<EditText>(R.id.etAdbHost).setText(current.adbHost.ifBlank { "127.0.0.1" })
        view.findViewById<EditText>(R.id.etAdbPort).setText(current.adbPort.takeIf { it > 0 }?.toString().orEmpty())
        view.findViewById<EditText>(R.id.etPairingPort).setText(current.adbPairingPort.takeIf { it > 0 }?.toString().orEmpty())
        view.findViewById<EditText>(R.id.etPairingCode).setText(current.adbPairingCode)

        setupBuiltInKeypad(view)

        setupDrag(view.findViewById(R.id.dragHandle), view, layoutParams)
        view.findViewById<Button>(R.id.btnCloseAdbSetup).setOnClickListener { stopAndRemove() }
        view.findViewById<Button>(R.id.btnOpenWirelessDebugging).setOnClickListener {
            openWirelessDebuggingSettings()
        }
        view.findViewById<Button>(R.id.btnSaveAdbSetup).setOnClickListener {
            saveAndConnect(view)
        }

        panel = view
        params = layoutParams
        windowManager?.addView(view, layoutParams)
    }

    private fun saveAndConnect(view: View) {
        val host = view.findViewById<EditText>(R.id.etAdbHost).text.toString().trim()
        val port = view.findViewById<EditText>(R.id.etAdbPort).text.toString().toIntOrNull() ?: 0
        val pairingPort = view.findViewById<EditText>(R.id.etPairingPort).text.toString().toIntOrNull() ?: 0
        val pairingCode = view.findViewById<EditText>(R.id.etPairingCode).text.toString().trim()
        val status = view.findViewById<TextView>(R.id.tvAdbSetupStatus)
        if (host.isBlank() || port !in 1..65535) {
            status.text = "请填写有效的 IP 地址和连接端口"
            return
        }
        if ((pairingCode.isNotBlank() || pairingPort > 0) &&
            (pairingCode.length != 6 || pairingPort !in 1..65535)) {
            status.text = "配对码与配对端口需要同时正确填写"
            return
        }

        ConfigRepository(this).saveAdbConfig(host, port, pairingCode, pairingPort)
        status.text = "正在连接 $host:$port …"
        val adb = AdbConnectionService.getInstance()
        adb.disconnect()
        adb.listener = object : AdbConnectionService.ConnectionListener {
            override fun onStateChanged(state: AdbConnectionService.ConnectionState) {
                view.post {
                    status.text = when (state) {
                        AdbConnectionService.ConnectionState.CONNECTED -> "连接成功，可以关闭面板"
                        AdbConnectionService.ConnectionState.CONNECTING -> "正在连接…"
                        AdbConnectionService.ConnectionState.ERROR -> "连接失败，请核对两种端口"
                        AdbConnectionService.ConnectionState.DISCONNECTED -> "未连接"
                    }
                }
            }

            override fun onError(message: String) {
                view.post { status.text = message.lineSequence().firstOrNull() ?: "连接失败" }
            }
        }
        adb.connect(
            host,
            port,
            pairingCode.takeIf { it.isNotBlank() },
            pairingPort.takeIf { it > 0 }
        )
    }

    private fun openWirelessDebuggingSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
            addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to open developer settings", e)
        }
    }

    /**
     * Settings pages can suppress focusable overlays. Keep this window non-focusable and
     * enter all values with an in-panel keypad, matching proven wireless-ADB tools.
     */
    private fun setupBuiltInKeypad(view: View) {
        val fields = listOf(
            view.findViewById<EditText>(R.id.etAdbHost),
            view.findViewById<EditText>(R.id.etAdbPort),
            view.findViewById<EditText>(R.id.etPairingPort),
            view.findViewById<EditText>(R.id.etPairingCode)
        )
        fields.forEach { field ->
            field.showSoftInputOnFocus = false
            field.setOnClickListener {
                fields.forEach { it.isSelected = false }
                focusedField = field
                field.isSelected = true
            }
        }
        focusedField = fields.first()
        fields.first().isSelected = true

        val digitButtons = listOf(
            R.id.btnKey0 to "0", R.id.btnKey1 to "1", R.id.btnKey2 to "2",
            R.id.btnKey3 to "3", R.id.btnKey4 to "4", R.id.btnKey5 to "5",
            R.id.btnKey6 to "6", R.id.btnKey7 to "7", R.id.btnKey8 to "8",
            R.id.btnKey9 to "9"
        )
        digitButtons.forEach { (id, digit) ->
            view.findViewById<Button>(id).setOnClickListener { focusedField?.append(digit) }
        }
        view.findViewById<Button>(R.id.btnKeyDot).setOnClickListener {
            val field = focusedField
            if (field?.id == R.id.etAdbHost && !field.text.endsWith(".")) field.append(".")
        }
        view.findViewById<Button>(R.id.btnKeyBackspace).setOnClickListener {
            focusedField?.let { field ->
                val end = field.text.length
                if (end > 0) field.text.delete(end - 1, end)
            }
        }
    }

    private fun setupDrag(handle: View, view: View, layoutParams: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = layoutParams.x
                    startY = layoutParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = startX + (event.rawX - touchX).toInt()
                    layoutParams.y = startY + (event.rawY - touchY).toInt()
                    windowManager?.updateViewLayout(view, layoutParams)
                    true
                }
                else -> false
            }
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, JumpMasterApp.CHANNEL_FLOATING)
            .setContentTitle("JumpMaster 无线 ADB 配对")
            .setContentText("配对悬浮面板正在显示")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun stopAndRemove() {
        panel?.let { view -> runCatching { windowManager?.removeView(view) } }
        panel = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        panel?.let { view -> runCatching { windowManager?.removeView(view) } }
        panel = null
        super.onDestroy()
    }
}
