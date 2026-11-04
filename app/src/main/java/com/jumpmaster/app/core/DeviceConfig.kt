package com.jumpmaster.app.core

enum class CaptureMode {
    /** MediaProjection + AccessibilityService (local, on-device) */
    LOCAL,
    /** ADB wireless debugging (remote or local device) */
    ADB,
    /** Root shell commands executed directly on this device */
    ROOT
}

data class DeviceConfig(
    val screenWidth: Int = 1080,
    val screenHeight: Int = 1920,
    val pressCoefficient: Float = 1.392f,
    val underGameScoreY: Int = 300,
    val pieceBaseHeight12: Int = 20,
    val pieceBodyWidth: Int = 70,
    val headDiameter: Float = 60f,
    val jumpDelay: Long = 1500L,
    val startX: Int = 500,
    val startY: Int = 1600,
    val showMarkers: Boolean = true,
    val saveScreenshots: Boolean = false,
    val vibrate: Boolean = true,
    // ADB wireless debugging settings
    val captureMode: CaptureMode = CaptureMode.LOCAL,
    val adbHost: String = "",
    val adbPort: Int = 0,
    val adbPairingCode: String = "",
    val adbPairingPort: Int = 0
) {
    companion object {
        // Preset configs from original Python project's config/ directory
        private val PRESETS = mapOf(
            "1920x1080" to DeviceConfig(
                screenWidth = 1080, screenHeight = 1920,
                pressCoefficient = 1.392f, underGameScoreY = 300,
                pieceBaseHeight12 = 20, pieceBodyWidth = 70
            ),
            "2560x1440" to DeviceConfig(
                screenWidth = 1440, screenHeight = 2560,
                pressCoefficient = 1.035f, underGameScoreY = 400,
                pieceBaseHeight12 = 27, pieceBodyWidth = 93
            ),
            "2160x1080" to DeviceConfig(
                screenWidth = 1080, screenHeight = 2160,
                pressCoefficient = 1.392f, underGameScoreY = 300,
                pieceBaseHeight12 = 20, pieceBodyWidth = 70
            ),
            "1280x720" to DeviceConfig(
                screenWidth = 720, screenHeight = 1280,
                pressCoefficient = 2.09f, underGameScoreY = 200,
                pieceBaseHeight12 = 13, pieceBodyWidth = 47
            ),
            "960x540" to DeviceConfig(
                screenWidth = 540, screenHeight = 960,
                pressCoefficient = 2.79f, underGameScoreY = 150,
                pieceBaseHeight12 = 10, pieceBodyWidth = 35
            )
        )

        fun forScreen(width: Int, height: Int, densityDpi: Int? = null): DeviceConfig {
            val key = "${height}x${width}"
            PRESETS[key]?.let { preset ->
                return preset.copy(
                    headDiameter = calibratedHeadDiameter(width, densityDpi, preset.headDiameter)
                )
            }

            // Fallback: scale from 1080p baseline
            val base = PRESETS["1920x1080"]!!
            val scale = width.toFloat() / base.screenWidth.toFloat()
            return base.copy(
                screenWidth = width,
                screenHeight = height,
                pressCoefficient = base.pressCoefficient / scale,
                underGameScoreY = (base.underGameScoreY * scale).toInt(),
                pieceBaseHeight12 = (base.pieceBaseHeight12 * scale).toInt(),
                pieceBodyWidth = (base.pieceBodyWidth * scale).toInt(),
                headDiameter = calibratedHeadDiameter(width, densityDpi, base.headDiameter * scale)
            )
        }

        private fun calibratedHeadDiameter(
            screenWidth: Int,
            densityDpi: Int?,
            fallback: Float
        ): Float {
            val byDisplayDensity = densityDpi?.let { it / 8f } ?: fallback
            val byScreenWidth = screenWidth / 13.5f
            return maxOf(byDisplayDensity, byScreenWidth)
        }
    }
}
