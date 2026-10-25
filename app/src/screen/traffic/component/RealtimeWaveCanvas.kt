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

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.yumeyucca.yumebox.data.model.traffic.RateRingBuffer

private val ColorDownCyan = Color(0xFF00E5FF)
private val ColorDownCyanBright = Color(0xFF22D3EE)
private val ColorUpViolet = Color(0xFFA855F7)
private val ColorUpVioletBright = Color(0xFFC084FC)

/**
 * 零重组平滑双轨贝塞尔波形图：
 * 1. 数组预分配：使用 remember { LongArray(60) } 避免 GC 分配
 * 2. 状态依赖锁定在 drawWithCache / onDrawBehind 内，完全绕过 Composition 阶段
 */
@Composable
fun RealtimeWaveCanvas(
    ringBuffer: RateRingBuffer,
    realtimeSpeedState: State<Pair<Long, Long>>,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 2.dp,
    downColor: Color = ColorDownCyan,
    upColor: Color = ColorUpViolet
) {
    val downSnapshot = remember { LongArray(60) }
    val upSnapshot = remember { LongArray(60) }
    val downPath = remember { Path() }
    val downFillPath = remember { Path() }
    val upPath = remember { Path() }
    val upFillPath = remember { Path() }

    Spacer(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                val strokePx = strokeWidth.toPx()
                val width = size.width
                val height = size.height

                val downStroke = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)
                val upStroke = Stroke(width = strokePx * 0.85f, cap = StrokeCap.Round, join = StrokeJoin.Round)

                val downGradient = Brush.verticalGradient(
                    colors = listOf(
                        ColorDownCyanBright.copy(alpha = 0.35f),
                        downColor.copy(alpha = 0.05f),
                        Color.Transparent
                    ),
                    startY = 0f,
                    endY = height
                )

                val upGradient = Brush.verticalGradient(
                    colors = listOf(
                        ColorUpVioletBright.copy(alpha = 0.22f),
                        Color.Transparent
                    ),
                    startY = 0f,
                    endY = height
                )

                onDrawBehind {
                    // 在绘制阶段内部读取 State，建立 Draw 阶段依赖，零重组驱动重绘
                    @Suppress("UNUSED_VARIABLE")
                    val speedValue = realtimeSpeedState.value

                    // 从 RingBuffer 零 GC 导出 60 个历史点镜像
                    val count = ringBuffer.exportSnapshot(downSnapshot, upSnapshot)
                    if (count < 2) return@onDrawBehind

                    // 动态计算 Y 轴峰值（底限设为 10 KB/s，防止极小速率时波形剧烈抖动）
                    var maxRate = 1024L * 10L
                    for (i in 0 until count) {
                        if (downSnapshot[i] > maxRate) maxRate = downSnapshot[i]
                        if (upSnapshot[i] > maxRate) maxRate = upSnapshot[i]
                    }
                    val safeMax = maxRate.toFloat()

                    // 水平等分虚线辅助线 (25%, 50%, 75%)
                    val gridColor = Color.White.copy(alpha = 0.04f)
                    drawLine(gridColor, Offset(0f, height * 0.25f), Offset(width, height * 0.25f), strokeWidth = 1f)
                    drawLine(gridColor, Offset(0f, height * 0.50f), Offset(width, height * 0.50f), strokeWidth = 1f)
                    drawLine(gridColor, Offset(0f, height * 0.75f), Offset(width, height * 0.75f), strokeWidth = 1f)

                    val stepX = width / (count - 1).coerceAtLeast(1).toFloat()

                    // 1. 构建下行贝塞尔平滑曲线
                    downPath.reset()
                    downFillPath.reset()
                    buildCubicBezierPath(downSnapshot, count, safeMax, stepX, height, downPath, downFillPath, width)
                    drawPath(path = downFillPath, brush = downGradient)
                    drawPath(path = downPath, color = downColor, style = downStroke)

                    // 2. 构建上行贝塞尔平滑曲线
                    upPath.reset()
                    upFillPath.reset()
                    buildCubicBezierPath(upSnapshot, count, safeMax, stepX, height, upPath, upFillPath, width)
                    drawPath(path = upFillPath, brush = upGradient)
                    drawPath(path = upPath, color = upColor, style = upStroke)
                }
            }
    )
}

private fun buildCubicBezierPath(
    data: LongArray,
    count: Int,
    maxVal: Float,
    stepX: Float,
    height: Float,
    linePath: Path,
    fillPath: Path,
    width: Float
) {
    val firstY = height - (data[0].toFloat() / maxVal * (height * 0.85f)) - height * 0.05f
    linePath.moveTo(0f, firstY)
    fillPath.moveTo(0f, height)
    fillPath.lineTo(0f, firstY)

    for (i in 1 until count) {
        val prevX = (i - 1) * stepX
        val prevY = height - (data[i - 1].toFloat() / maxVal * (height * 0.85f)) - height * 0.05f
        val curX = i * stepX
        val curY = height - (data[i].toFloat() / maxVal * (height * 0.85f)) - height * 0.05f

        val midX = (prevX + curX) / 2f
        linePath.cubicTo(midX, prevY, midX, curY, curX, curY)
        fillPath.cubicTo(midX, prevY, midX, curY, curX, curY)
    }

    fillPath.lineTo(width, height)
    fillPath.close()
}
