package com.jumpmaster.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jumpmaster.app.core.ImageAnalyzer
import com.jumpmaster.app.ui.theme.*

@Composable
fun DebugScreen(
    lastResult: ImageAnalyzer.AnalysisResult?,
    lastScreenshot: Bitmap?,
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
            Text(text = "调试预览", style = AppTypography.h2)
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Screenshot preview
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface)
            ) {
                Column {
                    // Header bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(0.5.dp, Color(0xFF1A1A22))
                            .padding(10.dp, 10.dp, 16.dp, 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "autojump.png",
                            style = AppTypography.monoSmall,
                            color = Color(0xFF666666)
                        )
                        Text(
                            text = if (lastScreenshot != null) "${lastScreenshot.width}x${lastScreenshot.height}" else "无截图",
                            style = AppTypography.monoSmall,
                            color = TextMuted
                        )
                    }

                    // Screenshot image or placeholder
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(9f / 16f)
                            .heightIn(max = 400.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color(0xFF1A2332),
                                        Color(0xFF243447),
                                        Color(0xFF2D4156),
                                        Color(0xFF1A2332)
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (lastScreenshot != null) {
                            Image(
                                bitmap = lastScreenshot.asImageBitmap(),
                                contentDescription = "截图",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        } else {
                            Text(
                                text = "等待截屏...",
                                style = AppTypography.body,
                                color = TextMuted
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Detection info cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Piece position
                InfoCard(
                    color = RedAccent,
                    label = "棋子位置",
                    value = if (lastResult != null) "${lastResult.pieceX}, ${lastResult.pieceY}" else "--",
                    modifier = Modifier.weight(1f)
                )
                // Board position
                InfoCard(
                    color = Color(0xFF3B82F6),
                    label = "目标位置",
                    value = if (lastResult != null) "${lastResult.boardX}, ${lastResult.boardY}" else "--",
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Calculation results
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(DarkSurface)
                    .border(1.dp, DarkBorder, RoundedCornerShape(16.dp))
                    .padding(18.dp)
            ) {
                Text(
                    text = "计算结果".uppercase(),
                    style = AppTypography.small,
                    color = TextMuted,
                    letterSpacing = 2.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                ResultRow(
                    "欧氏距离",
                    if (lastResult != null) String.format("%.1f px", lastResult.distance) else "--",
                    BluePrimary, true
                )
                ResultRow(
                    "按压系数",
                    if (lastResult != null) String.format("%.3f", lastResult.distance * 0) else "--",
                    PurpleAccent, true
                )
                ResultRow(
                    "按压时长",
                    if (lastResult != null) "${lastResult.pressTimeMs} ms" else "--",
                    GreenAccent, true
                )
                ResultRow(
                    "识别耗时",
                    if (lastResult != null) "${lastResult.analysisTimeMs} ms" else "--",
                    YellowAccent, false
                )
            }
        }
    }
}

@Composable
private fun InfoCard(color: Color, label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSurface)
            .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Text(text = label, style = AppTypography.monoSmall, color = Color(0xFF888888))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = value,
            style = AppTypography.mono.copy(fontFamily = FontFamily.Monospace),
            color = color
        )
    }
}

@Composable
private fun ResultRow(label: String, value: String, color: Color, showDivider: Boolean) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, style = AppTypography.caption, color = Color(0xFF777777))
            Text(
                text = value,
                style = AppTypography.mono.copy(fontFamily = FontFamily.Monospace),
                color = color
            )
        }
        if (showDivider) {
            Divider(color = Color(0xFF1A1A22), thickness = 1.dp)
        }
    }
}

