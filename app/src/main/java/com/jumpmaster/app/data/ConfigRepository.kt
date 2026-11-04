package com.jumpmaster.app.data

import android.content.Context
import android.content.SharedPreferences
import com.jumpmaster.app.core.CaptureMode
import com.jumpmaster.app.core.DeviceConfig

/**
 * Persists user configuration using SharedPreferences.
 * Falls back to device-appropriate defaults when no saved config exists.
 */
class ConfigRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("jumpmaster_config", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_PRESS_COEFFICIENT = "press_coefficient"
        private const val KEY_JUMP_DELAY = "jump_delay"
        private const val KEY_SHOW_MARKERS = "show_markers"
        private const val KEY_SAVE_SCREENSHOTS = "save_screenshots"
        private const val KEY_VIBRATE = "vibrate"
        private const val KEY_SCREEN_WIDTH = "screen_width"
        private const val KEY_SCREEN_HEIGHT = "screen_height"
        private const val KEY_CAPTURE_MODE = "capture_mode"
        private const val KEY_ADB_HOST = "adb_host"
        private const val KEY_ADB_PORT = "adb_port"
        private const val KEY_ADB_PAIRING_CODE = "adb_pairing_code"
        private const val KEY_ADB_PAIRING_PORT = "adb_pairing_port"
    }

    fun loadConfig(screenWidth: Int, screenHeight: Int, densityDpi: Int? = null): DeviceConfig {
        val base = DeviceConfig.forScreen(screenWidth, screenHeight, densityDpi)
        val modeName = prefs.getString(KEY_CAPTURE_MODE, CaptureMode.LOCAL.name) ?: CaptureMode.LOCAL.name
        val captureMode = try { CaptureMode.valueOf(modeName) } catch (_: Exception) { CaptureMode.LOCAL }

        return base.copy(
            pressCoefficient = prefs.getFloat(KEY_PRESS_COEFFICIENT, base.pressCoefficient),
            jumpDelay = prefs.getLong(KEY_JUMP_DELAY, base.jumpDelay),
            showMarkers = prefs.getBoolean(KEY_SHOW_MARKERS, base.showMarkers),
            saveScreenshots = prefs.getBoolean(KEY_SAVE_SCREENSHOTS, base.saveScreenshots),
            vibrate = prefs.getBoolean(KEY_VIBRATE, base.vibrate),
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            captureMode = captureMode,
            adbHost = prefs.getString(KEY_ADB_HOST, "") ?: "",
            adbPort = prefs.getInt(KEY_ADB_PORT, 0),
            adbPairingCode = prefs.getString(KEY_ADB_PAIRING_CODE, "") ?: "",
            adbPairingPort = prefs.getInt(KEY_ADB_PAIRING_PORT, 0)
        )
    }

    fun saveConfig(config: DeviceConfig) {
        prefs.edit().apply {
            putFloat(KEY_PRESS_COEFFICIENT, config.pressCoefficient)
            putLong(KEY_JUMP_DELAY, config.jumpDelay)
            putBoolean(KEY_SHOW_MARKERS, config.showMarkers)
            putBoolean(KEY_SAVE_SCREENSHOTS, config.saveScreenshots)
            putBoolean(KEY_VIBRATE, config.vibrate)
            putInt(KEY_SCREEN_WIDTH, config.screenWidth)
            putInt(KEY_SCREEN_HEIGHT, config.screenHeight)
            putString(KEY_CAPTURE_MODE, config.captureMode.name)
            putString(KEY_ADB_HOST, config.adbHost)
            putInt(KEY_ADB_PORT, config.adbPort)
            putString(KEY_ADB_PAIRING_CODE, config.adbPairingCode)
            putInt(KEY_ADB_PAIRING_PORT, config.adbPairingPort)
            apply()
        }
    }

    /** Update only wireless ADB fields from the setup overlay. */
    fun saveAdbConfig(host: String, port: Int, pairingCode: String, pairingPort: Int) {
        prefs.edit().apply {
            putString(KEY_ADB_HOST, host)
            putInt(KEY_ADB_PORT, port)
            putString(KEY_ADB_PAIRING_CODE, pairingCode)
            putInt(KEY_ADB_PAIRING_PORT, pairingPort)
            apply()
        }
    }
}
