package com.jumpmaster.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jumpmaster.app.core.CaptureMode
import com.jumpmaster.app.core.JumpController
import com.jumpmaster.app.ui.components.LedIndicator
import com.jumpmaster.app.ui.theme.*

@Composable
fun HomeScreen(
    status: JumpController.Status,
    jumpCount: Int,
    pressCoefficient: Float,
    isRunning: Boolean,
    captureMode: CaptureMode,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Pulse animation for running state
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseOut),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseOut),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseAlpha"
    )

    val statusColor = when (status) {
        JumpController.Status.IDLE -> Color(0xFF4A4A5A)
        JumpController.Status.CONNECTING -> YellowAccent
        JumpController.Status.CAPTURE -> BluePrimary
        JumpController.Status.DETECT -> PurpleAccent
        JumpController.Status.JUMP -> GreenAccent
        JumpController.Status.PAUSED -> YellowAccent
        JumpController.Status.RESTING -> YellowAccent
    }
    val statusLabel = when (status) {
        JumpController.Status.IDLE -> "等待中"
        JumpController.Status.CONNECTING -> "连接中"
        JumpController.Status.CAPTURE -> "截屏中"
        JumpController.Status.DETECT -> "识别中"
        JumpController.Status.JUMP -> "跳跃中"
        JumpController.Status.PAUSED -> "已暂停"
        JumpController.Status.RESTING -> "休息中"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBg)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, top = 20.dp, end = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                val modeLabel = when (captureMode) {
                    CaptureMode.LOCAL -> "本地模式"
                    CaptureMode.ADB -> "ADB 模式"
                    CaptureMode.ROOT -> "Root 模式"
                }
                Text(
                    text = "Jump Master · $modeLabel",
                    style = AppTypography.small,
                    color = TextMuted,
                    letterSpacing = 3.sp
                )
                Text(
                    text = "跳一跳助手",
                    style = AppTypography.h1.copy(
                        brush = Brush.linearGradient(
                            colors = listOf(BluePrimary, PurpleAccent)
                        )
                    )
                )
            }
        }

        // Status LEDs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            LedIndicator(BluePrimary, status == JumpController.Status.CAPTURE, "截屏")
            Spacer(modifier = Modifier.width(32.dp))
            LedIndicator(PurpleAccent, status == JumpController.Status.DETECT, "识别")
            Spacer(modifier = Modifier.width(32.dp))
            LedIndicator(GreenAccent, status == JumpController.Status.JUMP, "跳跃")
            Spacer(modifier = Modifier.width(32.dp))
            LedIndicator(YellowAccent, false, "错误")
        }

        // Main button area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            // Pulse rings when running
            if (isRunning) {
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .scale(pulseScale)
                        .border(1.dp, statusColor.copy(alpha = pulseAlpha * 0.3f), CircleShape)
                )
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .scale(pulseScale)
                        .border(1.dp, statusColor.copy(alpha = pulseAlpha * 0.15f), CircleShape)
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Status text
                Text(
                    text = statusLabel,
                    style = AppTypography.mono,
                    color = statusColor,
                    letterSpacing = 2.sp
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Main button
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .clip(CircleShape)
                        .background(
                            if (isRunning) Brush.radialGradient(
                                colors = listOf(statusColor.copy(0.2f), statusColor.copy(0.08f)),
                                radius = 200f
                            ) else Brush.radialGradient(
                                colors = listOf(Color(0xFF1E1E2E), Color(0xFF14141C)),
                                radius = 200f
                            )
                        )
                        .border(
                            width = 2.dp,
                            color = if (isRunning) statusColor.copy(0.6f) else Color(0xFF2A2A3A),
                            shape = CircleShape
                        )
                        .shadow(
                            elevation = if (isRunning) 0.dp else 8.dp,
                            shape = CircleShape
                        )
                        .clickable { onToggle() },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = if (isRunning) "停止" else "开始",
                            tint = if (isRunning) statusColor else BluePrimary,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (isRunning) "停止" else "开始",
                            style = AppTypography.small,
                            color = if (isRunning) statusColor else TextMuted,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }
        }

        // Stats bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(DarkSurface)
                    .border(1.dp, DarkBorder, RoundedCornerShape(16.dp))
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Jump count
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "$jumpCount",
                        style = AppTypography.monoLarge.copy(fontFamily = FontFamily.Monospace),
                        color = BluePrimary
                    )
                    Text(text = "跳跃次数", style = AppTypography.monoSmall, color = TextMuted)
                }

                Box(modifier = Modifier.width(1.dp).height(32.dp).background(DarkBorder))

                // Press coefficient
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = String.format("%.2f", pressCoefficient),
                        style = AppTypography.monoLarge.copy(fontFamily = FontFamily.Monospace),
                        color = PurpleAccent
                    )
                    Text(text = "按压系数", style = AppTypography.monoSmall, color = TextMuted)
                }

                Box(modifier = Modifier.width(1.dp).height(32.dp).background(DarkBorder))

                // Connection status
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (isRunning) "●" else "○",
                        style = AppTypography.monoLarge.copy(fontFamily = FontFamily.Monospace),
                        color = if (isRunning) GreenAccent else TextMuted
                    )
                    Text(text = "连接状态", style = AppTypography.monoSmall, color = TextMuted)
                }
            }
        }
    }
}
