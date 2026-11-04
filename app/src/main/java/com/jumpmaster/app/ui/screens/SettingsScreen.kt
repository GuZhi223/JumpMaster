package com.jumpmaster.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jumpmaster.app.core.CaptureMode
import com.jumpmaster.app.core.DeviceConfig
import com.jumpmaster.app.core.adb.AdbConnectionService
import com.jumpmaster.app.core.root.RootCommandService
import com.jumpmaster.app.ui.components.ParameterSlider
import com.jumpmaster.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    config: DeviceConfig,
    accessibilityRunning: Boolean,
    onConfigChange: (DeviceConfig) -> Unit,
    onSaveAdb: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenAdbSetup: () -> Unit,
    onActivateRoot: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBg)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, top = 20.dp, end = 24.dp, bottom = 20.dp)
                .border(1.dp, DarkBorder, RoundedCornerShape(0.dp)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color(0xFF666666))
            }
            Text(text = "设置", style = AppTypography.h2)
        }

        // Scrollable content
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            // Section: 控制模式
            SectionLabel("控制模式")
            CardSection {
                Text(
                    text = "选择截屏和触摸操作的方式",
                    style = AppTypography.monoSmall,
                    color = TextMuted,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ModeChip(
                        label = "本地模式",
                        desc = "MediaProjection + 无障碍",
                        selected = config.captureMode == CaptureMode.LOCAL,
                        modifier = Modifier.weight(1f),
                        onClick = { onConfigChange(config.copy(captureMode = CaptureMode.LOCAL)) }
                    )
                    ModeChip(
                        label = "ADB模式",
                        desc = "无线调试连接",
                        selected = config.captureMode == CaptureMode.ADB,
                        modifier = Modifier.weight(1f),
                        onClick = { onConfigChange(config.copy(captureMode = CaptureMode.ADB)) }
                    )
                    ModeChip(
                        label = "Root模式",
                        desc = "本机 Root 控制",
                        selected = config.captureMode == CaptureMode.ROOT,
                        modifier = Modifier.weight(1f),
                        onClick = { onConfigChange(config.copy(captureMode = CaptureMode.ROOT)) }
                    )
                }
            }

            if (config.captureMode == CaptureMode.ROOT) {
                Spacer(modifier = Modifier.height(20.dp))
                SectionLabel("Root 权限")
                CardSection {
                    val rootState = RootCommandService.state.value
                    val statusText = when (rootState) {
                        RootCommandService.State.UNKNOWN -> "○ 尚未检测"
                        RootCommandService.State.CHECKING -> "● 正在请求授权…"
                        RootCommandService.State.GRANTED -> "● 已获得 Root 权限"
                        RootCommandService.State.DENIED -> "● Root 权限不可用"
                    }
                    val statusColor = when (rootState) {
                        RootCommandService.State.GRANTED -> GreenAccent
                        RootCommandService.State.CHECKING -> YellowAccent
                        RootCommandService.State.DENIED -> RedAccent
                        RootCommandService.State.UNKNOWN -> TextMuted
                    }
                    Text(statusText, style = AppTypography.monoSmall, color = statusColor)
                    Text(
                        text = "通过 su 直接执行截屏和触摸，无需无线调试与无障碍服务。",
                        style = AppTypography.monoSmall,
                        color = TextMuted,
                        modifier = Modifier.padding(vertical = 10.dp)
                    )
                    Button(
                        onClick = onActivateRoot,
                        enabled = rootState != RootCommandService.State.CHECKING,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = PurpleAccent),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(if (rootState == RootCommandService.State.GRANTED) "重新检测" else "检测并请求 Root 权限")
                    }
                }
            }

            // Section: ADB 无线调试 (only visible in ADB mode)
            if (config.captureMode == CaptureMode.ADB) {
                Spacer(modifier = Modifier.height(20.dp))
                SectionLabel("ADB 无线调试")
                CardSection {
                    Text(
                        text = "在目标设备上开启：开发者选项 → 无线调试",
                        style = AppTypography.monoSmall,
                        color = YellowAccent,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    Button(
                        onClick = onOpenAdbSetup,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PurpleAccent,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("打开无线调试 + 悬浮填写", style = AppTypography.body)
                    }

                    Text(
                        text = "悬浮面板会停留在系统设置上方，无需来回切换应用。",
                        style = AppTypography.monoSmall,
                        color = TextMuted,
                        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
                    )

                    // Host / Port
                    OutlinedTextField(
                        value = config.adbHost,
                        onValueChange = { onConfigChange(config.copy(adbHost = it)) },
                        label = { Text("IP 地址", color = TextSecondary) },
                        placeholder = { Text("192.168.1.100", color = TextMuted) },
                        singleLine = true,
                        textStyle = AppTypography.mono.copy(color = TextPrimary),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BluePrimary,
                            unfocusedBorderColor = DarkBorder,
                            cursorColor = BluePrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = if (config.adbPort > 0) config.adbPort.toString() else "",
                        onValueChange = { str ->
                            val port = str.filter { it.isDigit() }.toIntOrNull() ?: 0
                            onConfigChange(config.copy(adbPort = port))
                        },
                        label = { Text("连接端口", color = TextSecondary) },
                        placeholder = { Text("无线调试页面显示的端口", color = TextMuted) },
                        singleLine = true,
                        textStyle = AppTypography.mono.copy(color = TextPrimary),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BluePrimary,
                            unfocusedBorderColor = DarkBorder,
                            cursorColor = BluePrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "配对信息 (首次连接需要)",
                        style = AppTypography.caption,
                        color = TextSecondary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    OutlinedTextField(
                        value = if (config.adbPairingPort > 0) config.adbPairingPort.toString() else "",
                        onValueChange = { str ->
                            val port = str.filter { it.isDigit() }.toIntOrNull() ?: 0
                            onConfigChange(config.copy(adbPairingPort = port))
                        },
                        label = { Text("配对端口", color = TextSecondary) },
                        placeholder = { Text("使用配对码配对设备时显示的端口", color = TextMuted) },
                        singleLine = true,
                        textStyle = AppTypography.mono.copy(color = TextPrimary),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PurpleAccent,
                            unfocusedBorderColor = DarkBorder,
                            cursorColor = PurpleAccent
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = config.adbPairingCode,
                        onValueChange = { onConfigChange(config.copy(adbPairingCode = it)) },
                        label = { Text("配对码", color = TextSecondary) },
                        placeholder = { Text("6位数字配对码", color = TextMuted) },
                        singleLine = true,
                        textStyle = AppTypography.mono.copy(color = TextPrimary),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PurpleAccent,
                            unfocusedBorderColor = DarkBorder,
                            cursorColor = PurpleAccent
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Connection status (Compose-observable)
                    val adbState = AdbConnectionService.connectionState.value
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val statusColor = when (adbState) {
                            AdbConnectionService.ConnectionState.CONNECTED -> GreenAccent
                            AdbConnectionService.ConnectionState.CONNECTING -> YellowAccent
                            AdbConnectionService.ConnectionState.ERROR -> RedAccent
                            AdbConnectionService.ConnectionState.DISCONNECTED -> TextMuted
                        }
                        val statusText = when (adbState) {
                            AdbConnectionService.ConnectionState.CONNECTED -> "● 已连接"
                            AdbConnectionService.ConnectionState.CONNECTING -> "● 连接中..."
                            AdbConnectionService.ConnectionState.ERROR -> "● 连接错误"
                            AdbConnectionService.ConnectionState.DISCONNECTED -> "○ 未连接"
                        }
                        Text(text = statusText, style = AppTypography.monoSmall, color = statusColor)

                        // Save button
                        Button(
                            onClick = onSaveAdb,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BluePrimary,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
                        ) {
                            Text("保存", style = AppTypography.body)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Section: 核心参数
            SectionLabel("核心参数")
            CardSection {
                ParameterSlider(
                    label = "按压系数",
                    value = config.pressCoefficient,
                    min = 0.5f, max = 3.0f, step = 0.01f,
                    unit = "", color = BluePrimary,
                    onValueChange = { onConfigChange(config.copy(pressCoefficient = it)) }
                )
                Spacer(modifier = Modifier.height(20.dp))
                ParameterSlider(
                    label = "跳跃延迟",
                    value = config.jumpDelay.toFloat(),
                    min = 500f, max = 3000f, step = 100f,
                    unit = "ms", color = PurpleAccent,
                    onValueChange = { onConfigChange(config.copy(jumpDelay = it.toLong())) }
                )
                Spacer(modifier = Modifier.height(20.dp))
                ParameterSlider(
                    label = "识别起始 Y",
                    value = config.underGameScoreY.toFloat(),
                    min = 100f, max = 600f, step = 10f,
                    unit = "px", color = GreenAccent,
                    onValueChange = { onConfigChange(config.copy(underGameScoreY = it.toInt())) }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Section: 显示设置
            SectionLabel("显示设置")
            CardSection {
                SwitchRow("显示调试标记", "在截图上标注识别点", config.showMarkers) {
                    onConfigChange(config.copy(showMarkers = it))
                }
                SwitchRow("自动保存截图", "保存每次识别的截图", config.saveScreenshots) {
                    onConfigChange(config.copy(saveScreenshots = it))
                }
                SwitchRow("震动反馈", "跳跃时震动提醒", config.vibrate) {
                    onConfigChange(config.copy(vibrate = it))
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Section: 无障碍服务 (only in LOCAL mode)
            if (config.captureMode == CaptureMode.LOCAL) {
                SectionLabel("无障碍服务")
                CardSection {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = "服务状态", style = AppTypography.body, color = Color(0xFFCCCCCC))
                            Text(
                                text = if (accessibilityRunning) "● 已启用" else "○ 未启用",
                                style = AppTypography.monoSmall,
                                color = if (accessibilityRunning) GreenAccent else Color(0xFF555555)
                            )
                        }
                        Button(
                            onClick = onOpenAccessibilitySettings,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (accessibilityRunning) DarkBorder else BluePrimary,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(if (accessibilityRunning) "管理" else "前往开启")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeChip(
    label: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) BluePrimary.copy(alpha = 0.15f) else Color(0xFF0D0D12))
            .border(
                1.dp,
                if (selected) BluePrimary.copy(alpha = 0.5f) else DarkBorder,
                RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = AppTypography.body,
            color = if (selected) BluePrimary else TextSecondary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = desc,
            style = AppTypography.monoSmall,
            color = if (selected) TextSecondary else TextMuted
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = AppTypography.small,
        color = TextMuted,
        letterSpacing = 2.sp,
        modifier = Modifier.padding(bottom = 12.dp)
    )
}

@Composable
private fun CardSection(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DarkSurface)
            .border(1.dp, DarkBorder, RoundedCornerShape(16.dp))
            .padding(20.dp),
        content = content
    )
}

@Composable
private fun SwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = AppTypography.body, color = Color(0xFFCCCCCC))
            Text(text = desc, style = AppTypography.monoSmall, color = TextMuted)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = BluePrimary,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFF1E1E2A),
                uncheckedBorderColor = Color(0xFF2A2A3A)
            )
        )
    }
}
