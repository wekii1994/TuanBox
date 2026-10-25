/*
 * This file is part of YumeBox.
 *
 * YumeBox is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (c)  YumeYucca 2025 - Present
 *
 */

package com.github.yumeyucca.yumebox.screen.traffic.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.yumeyucca.yumebox.data.model.traffic.RateRingBuffer
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val ColorProxy = Color(0xFF38BDF8)
private val ColorDirect = Color(0xFF34D399)
private val ColorReject = Color(0xFF94A3B8)

/**
 * 全局态势感知大卡片：整合波形图与三色分流效率看版
 *
 * 接收 State<Pair<Long, Long>> 引用而非具体数值，保持本卡片不发生整卡重组
 */
@Composable
fun GlobalTelemetryCard(
    ringBuffer: RateRingBuffer,
    realtimeSpeedState: State<Pair<Long, Long>>,
    proxyBytes: Long,
    directBytes: Long,
    rejectBytes: Long,
    modifier: Modifier = Modifier
) {
    val cardBackground = MiuixTheme.colorScheme.surface
    val cardBorder = MiuixTheme.colorScheme.outline.copy(alpha = 0.12f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(cardBackground)
            .border(1.dp, cardBorder, RoundedCornerShape(24.dp))
            .padding(18.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 顶部栏：标题 + 速率文本（隔离重组作用域）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF00E5FF))
                    )
                    Text(
                        text = "实时速率",
                        style = MiuixTheme.textStyles.title4,
                        color = MiuixTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                }

                SpeedHeaderText(speedState = realtimeSpeedState)
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 零重组波形图
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
            ) {
                RealtimeWaveCanvas(
                    ringBuffer = ringBuffer,
                    realtimeSpeedState = realtimeSpeedState
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 分流效能多色胶囊看板（含三色圆环图）
            RoutingEfficiencySection(
                proxyBytes = proxyBytes,
                directBytes = directBytes,
                rejectBytes = rejectBytes
            )
        }
    }
}

/**
 * 独立封装的速率显示微组件，将每秒重组严格锁定在两行文本内
 */
@Composable
private fun SpeedHeaderText(
    speedState: State<Pair<Long, Long>>,
    modifier: Modifier = Modifier
) {
    val (downSpeed, upSpeed) = speedState.value

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "↓ ",
                color = Color(0xFF00E5FF),
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
            Text(
                text = formatSpeed(downSpeed),
                color = Color(0xFF00E5FF),
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "↑ ",
                color = Color(0xFFA855F7),
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
            Text(
                text = formatSpeed(upSpeed),
                color = Color(0xFFA855F7),
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/**
 * 分流效能组件：左侧三色圆环图 (Donut Chart) + 右侧多色内嵌比例条与图例
 */
@Composable
private fun RoutingEfficiencySection(
    proxyBytes: Long,
    directBytes: Long,
    rejectBytes: Long,
    modifier: Modifier = Modifier
) {
    val total = (proxyBytes + directBytes + rejectBytes).toFloat()
    val hasData = total > 0f
    val safeTotal = total.coerceAtLeast(1f)
    val proxyRatio = if (hasData) proxyBytes / safeTotal else 0f
    val directRatio = if (hasData) directBytes / safeTotal else 0f
    val rejectRatio = if (hasData) rejectBytes / safeTotal else 0f

    val animatedProxy by animateFloatAsState(proxyRatio, tween(600), label = "proxyRatio")
    val animatedDirect by animateFloatAsState(directRatio, tween(600), label = "directRatio")
    val animatedReject by animateFloatAsState(rejectRatio, tween(600), label = "rejectRatio")

    val ringTrackColor = MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 左侧：三色圆环图 (Donut Chart)，带轨道底色
        Canvas(modifier = Modifier.size(44.dp)) {
            val strokeWidth = 5.dp.toPx()
            drawCircle(
                color = ringTrackColor,
                style = Stroke(strokeWidth)
            )

            var startAngle = -90f
            val proxySweep = animatedProxy * 360f
            val directSweep = animatedDirect * 360f
            val rejectSweep = animatedReject * 360f

            if (proxySweep > 0f) {
                drawArc(
                    color = ColorProxy,
                    startAngle = startAngle,
                    sweepAngle = proxySweep,
                    useCenter = false,
                    style = Stroke(strokeWidth, cap = StrokeCap.Round)
                )
                startAngle += proxySweep
            }
            if (directSweep > 0f) {
                drawArc(
                    color = ColorDirect,
                    startAngle = startAngle,
                    sweepAngle = directSweep,
                    useCenter = false,
                    style = Stroke(strokeWidth, cap = StrokeCap.Round)
                )
                startAngle += directSweep
            }
            if (rejectSweep > 0f) {
                drawArc(
                    color = ColorReject,
                    startAngle = startAngle,
                    sweepAngle = rejectSweep,
                    useCenter = false,
                    style = Stroke(strokeWidth, cap = StrokeCap.Round)
                )
            }
        }

        // 右侧：三色内嵌比例条与图例
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val barTrackColor = MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(barTrackColor)
            ) {
                if (animatedProxy > 0f) {
                    Box(
                        modifier = Modifier
                            .weight(animatedProxy.coerceAtLeast(0.01f))
                            .fillMaxHeight()
                            .background(ColorProxy)
                    )
                }
                if (animatedDirect > 0f) {
                    Box(
                        modifier = Modifier
                            .weight(animatedDirect.coerceAtLeast(0.01f))
                            .fillMaxHeight()
                            .background(ColorDirect)
                    )
                }
                if (animatedReject > 0f) {
                    Box(
                        modifier = Modifier
                            .weight(animatedReject.coerceAtLeast(0.01f))
                            .fillMaxHeight()
                            .background(ColorReject)
                    )
                }
            }

            // 百分比图例
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                EfficiencyLegendItem("代理", "%.1f%%".format(proxyRatio * 100), ColorProxy)
                EfficiencyLegendItem("直连", "%.1f%%".format(directRatio * 100), ColorDirect)
                EfficiencyLegendItem("拦截", "%.1f%%".format(rejectRatio * 100), ColorReject)
            }
        }
    }
}

@Composable
private fun EfficiencyLegendItem(label: String, percent: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        Text(
            text = percent,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

private fun formatSpeed(bytesPerSec: Long): String {
    return when {
        bytesPerSec >= 1024 * 1024 * 1024 -> "%.2f GB/s".format(bytesPerSec.toFloat() / (1024 * 1024 * 1024))
        bytesPerSec >= 1024 * 1024 -> "%.1f MB/s".format(bytesPerSec.toFloat() / (1024 * 1024))
        bytesPerSec >= 1024 -> "%.0f KB/s".format(bytesPerSec.toFloat() / 1024)
        else -> "$bytesPerSec B/s"
    }
}
