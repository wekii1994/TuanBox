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

@file:Suppress("FunctionName")

package com.github.yumeyucca.yumebox.screen.traffic

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.yumeyucca.yumebox.data.controller.traffic.TrafficCollector
import com.github.yumeyucca.yumebox.data.model.traffic.TrafficDimension
import com.github.yumeyucca.yumebox.data.model.traffic.TrafficRankItem
import com.github.yumeyucca.yumebox.data.store.room.DimensionTrafficStat
import com.github.yumeyucca.yumebox.data.store.room.HourlyTrendPoint
import com.github.yumeyucca.yumebox.data.store.room.TrafficStatisticsDao
import com.github.yumeyucca.yumebox.screen.traffic.component.GlobalTelemetryCard
import kotlinx.coroutines.flow.sample
import org.koin.compose.koinInject
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

enum class StatisticsTimeRange(val label: String, val durationMs: Long) {
    HOUR_24("24小时", 24 * 3600 * 1000L),
    DAY_7("7天", 7 * 24 * 3600 * 1000L),
    DAY_30("30天", 30 * 24 * 3600 * 1000L)
}

/**
 * 流量统计高信息密度主界面：
 * 支持 24小时/7天/30天 周期切换、4 维深度透视、实时零重组波形图、
 * 滑动防抖重排、微光呼吸绿点低功耗优化、以及平板/折叠屏 Master-Detail 双栏并列
 */
@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)
@Composable
fun TrafficStatisticsScreen(
    collector: TrafficCollector = koinInject(),
    trafficDao: TrafficStatisticsDao = koinInject(),
    isFindProcessModeOff: Boolean = false
) {
    // 监听页面生命周期，进入前台高频采集，离开时降频
    DisposableEffect(Unit) {
        collector.setTrafficPageVisibility(true)
        onDispose {
            collector.setTrafficPageVisibility(false)
        }
    }

    // 状态引用向下传递，避免顶层整页每秒重组
    val realtimeSpeedState = collector.realtimeSpeed.collectAsState()

    var selectedDimension by remember { mutableStateOf(TrafficDimension.APP) }
    var selectedTimeRange by remember { mutableStateOf(StatisticsTimeRange.HOUR_24) }
    var selectedItemForDetail by remember { mutableStateOf<TrafficRankItem?>(null) }

    // 跨小时查询边界溢出保护：endTime 固定为 Long.MAX_VALUE
    val startTime = remember(selectedTimeRange) {
        System.currentTimeMillis() - selectedTimeRange.durationMs
    }
    val endTime = Long.MAX_VALUE

    // 观察全局分流效能统计 Flow
    val efficiencyStat by remember(startTime, endTime) {
        trafficDao.queryGlobalEfficiency(startTime, endTime)
    }.collectAsState(initial = null)

    val totalProxy = efficiencyStat?.totalProxyBytes ?: 0L
    val totalDirect = efficiencyStat?.totalDirectBytes ?: 0L
    val totalReject = efficiencyStat?.totalRejectBytes ?: 0L

    // 观察对应维度的排行数据流（3 秒节流采样，消除高频跳动）
    val rankingDataFlow = remember(selectedDimension, startTime, endTime) {
        when (selectedDimension) {
            TrafficDimension.APP -> trafficDao.queryRankingByApp(startTime, endTime)
            TrafficDimension.NODE -> trafficDao.queryRankingByNode(startTime, endTime)
            TrafficDimension.DOMAIN -> trafficDao.queryRankingByDomain(startTime, endTime)
            TrafficDimension.RULE -> trafficDao.queryRankingByRule(startTime, endTime)
        }.sample(3000L)
    }
    val rawRanking by rankingDataFlow.collectAsState(initial = emptyList())

    val activeUids by collector.activeUids.collectAsState()
    val activeHosts by collector.activeHosts.collectAsState()
    val activeNodes by collector.activeNodes.collectAsState()

    // 映射为复合展示条目，注入 AppInfo 与活跃连接状态
    val rankItems = remember(rawRanking, selectedDimension, activeUids, activeHosts, activeNodes) {
        rawRanking.map { stat ->
            val hasActive = when (selectedDimension) {
                TrafficDimension.APP -> activeUids.contains(stat.dimensionKey.toIntOrNull() ?: -1)
                TrafficDimension.DOMAIN -> activeHosts.contains(stat.dimensionKey)
                TrafficDimension.NODE -> activeNodes.contains(stat.dimensionKey)
                TrafficDimension.RULE -> false
            }
            val title = when (selectedDimension) {
                TrafficDimension.APP -> collector.getAppInfo(stat.dimensionKey.toIntOrNull() ?: -1).appName
                else -> stat.dimensionKey
            }
            TrafficRankItem(
                identifier = stat.dimensionKey,
                displayName = title,
                totalBytes = stat.totalBytes,
                proxyBytes = stat.proxyBytes,
                directBytes = stat.directBytes,
                rejectBytes = stat.rejectBytes,
                hasActiveConnections = hasActive
            )
        }
    }

    Scaffold(
        containerColor = Color(0xFF0F1115)
    ) { paddingValues ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            val isTabletOrLandscape = maxWidth >= 600.dp

            if (isTabletOrLandscape) {
                // ================= 平板/折叠屏 双栏自适应布局 =================
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TopHeaderSection(
                            timeRange = selectedTimeRange,
                            onTimeRangeSelected = { selectedTimeRange = it }
                        )
                        GlobalTelemetryCard(
                            ringBuffer = collector.ringBuffer,
                            realtimeSpeedState = realtimeSpeedState,
                            proxyBytes = totalProxy,
                            directBytes = totalDirect,
                            rejectBytes = totalReject
                        )
                        MiuixSegmentedControl(
                            selected = selectedDimension,
                            onSelect = { selectedDimension = it }
                        )
                        RankingList(
                            items = rankItems,
                            selectedItem = selectedItemForDetail,
                            onItemClick = { selectedItemForDetail = it },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // 右侧常驻详情卡片
                    Box(
                        modifier = Modifier
                            .weight(1.1f)
                            .fillMaxHeight()
                            .padding(vertical = 16.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color(0xFF14161C))
                            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
                            .padding(20.dp)
                    ) {
                        selectedItemForDetail?.let { item ->
                            DetailPaneContent(
                                item = item,
                                dimension = selectedDimension,
                                trafficDao = trafficDao,
                                startTime = startTime
                            )
                        } ?: run {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = "选择左侧条目查看多维分流透视",
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                        }
                    }
                }
            } else {
                // ================= 手机单栏布局（普通 Column 嵌套单一 LazyColumn，杜绝 Crash） =================
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TopHeaderSection(
                        timeRange = selectedTimeRange,
                        onTimeRangeSelected = { selectedTimeRange = it }
                    )

                    GlobalTelemetryCard(
                        ringBuffer = collector.ringBuffer,
                        realtimeSpeedState = realtimeSpeedState,
                        proxyBytes = totalProxy,
                        directBytes = totalDirect,
                        rejectBytes = totalReject
                    )

                    if (isFindProcessModeOff && selectedDimension == TrafficDimension.APP) {
                        FindProcessModeWarningBanner()
                    }

                    MiuixSegmentedControl(
                        selected = selectedDimension,
                        onSelect = { selectedDimension = it }
                    )

                    RankingList(
                        items = rankItems,
                        selectedItem = null,
                        onItemClick = { selectedItemForDetail = it },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 手机端弹出式 BottomSheet 详情
            if (!isTabletOrLandscape && selectedItemForDetail != null) {
                ModalBottomSheet(
                    onDismissRequest = { selectedItemForDetail = null },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    containerColor = Color(0xFF14161C),
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                ) {
                    DetailPaneContent(
                        item = selectedItemForDetail!!,
                        dimension = selectedDimension,
                        trafficDao = trafficDao,
                        startTime = startTime,
                        modifier = Modifier.padding(bottom = 32.dp)
                    )
                }
            }
        }
    }
}

/**
 * 排行榜列表：滑动时锁定排位，停止滑动后瞬间同步最新 items
 */
@Composable
private fun RankingList(
    items: List<TrafficRankItem>,
    selectedItem: TrafficRankItem?,
    onItemClick: (TrafficRankItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    var displayedItems by remember { mutableStateOf(items) }

    LaunchedEffect(items, listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            displayedItems = items
        }
    }

    val maxTotalBytes = remember(displayedItems) {
        displayedItems.maxOfOrNull { it.totalBytes }?.coerceAtLeast(1L) ?: 1L
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(
            items = displayedItems,
            key = { it.identifier }
        ) { item ->
            RankListItem(
                item = item,
                maxBytes = maxTotalBytes,
                isSelected = item.identifier == selectedItem?.identifier,
                onClick = { onItemClick(item) }
            )
        }
    }
}

/**
 * 列表项复合组件
 */
@Composable
private fun RankListItem(
    item: TrafficRankItem,
    maxBytes: Long,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val relativeRatio = (item.totalBytes.toFloat() / maxBytes).coerceIn(0.04f, 1f)
    val proxyPart = (item.proxyBytes.toFloat() / item.totalBytes.coerceAtLeast(1L)).coerceIn(0f, 1f)
    val directPart = (item.directBytes.toFloat() / item.totalBytes.coerceAtLeast(1L)).coerceIn(0f, 1f)
    val rejectPart = (item.rejectBytes.toFloat() / item.totalBytes.coerceAtLeast(1L)).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (isSelected) Color(0xFF1E222B) else Color(0xFF14161C))
            .border(
                1.dp,
                if (isSelected) Color(0xFF00E5FF).copy(alpha = 0.5f) else Color.White.copy(alpha = 0.04f),
                RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = item.displayName,
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    ActiveConnectionIndicator(hasActive = item.hasActiveConnections)
                }

                Text(
                    text = formatBytes(item.totalBytes),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 相对长度 + 内部三色比例的胶囊条
            Box(
                modifier = Modifier
                    .fillMaxWidth(relativeRatio)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = 0.05f))
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    if (proxyPart > 0f) {
                        Box(
                            modifier = Modifier
                                .weight(proxyPart.coerceAtLeast(0.01f))
                                .fillMaxHeight()
                                .background(Color(0xFF38BDF8))
                        )
                    }
                    if (directPart > 0f) {
                        Box(
                            modifier = Modifier
                                .weight(directPart.coerceAtLeast(0.01f))
                                .fillMaxHeight()
                                .background(Color(0xFF34D399))
                        )
                    }
                    if (rejectPart > 0f) {
                        Box(
                            modifier = Modifier
                                .weight(rejectPart.coerceAtLeast(0.01f))
                                .fillMaxHeight()
                                .background(Color(0xFF94A3B8))
                        )
                    }
                }
            }
        }
    }
}

/**
 * 活跃连接微光呼吸绿点：仅在 hasActive == true 时才注册 InfiniteTransition，非活跃项零功耗
 */
@Composable
private fun ActiveConnectionIndicator(hasActive: Boolean) {
    if (hasActive) {
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseAlpha"
        )
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(Color(0xFF10B981).copy(alpha = alpha))
        )
    }
}

@Composable
private fun TopHeaderSection(
    timeRange: StatisticsTimeRange,
    onTimeRangeSelected: (StatisticsTimeRange) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "流量统计",
            style = MiuixTheme.textStyles.title2,
            color = MiuixTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold
        )

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatisticsTimeRange.entries.forEach { range ->
                val isSelected = range == timeRange
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) Color(0xFF00E5FF).copy(alpha = 0.15f) else Color.Transparent)
                        .clickable { onTimeRangeSelected(range) }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = range.label,
                        fontSize = 12.sp,
                        color = if (isSelected) Color(0xFF00E5FF) else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
private fun MiuixSegmentedControl(
    selected: TrafficDimension,
    onSelect: (TrafficDimension) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF14161C))
            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        TrafficDimension.entries.forEach { dimension ->
            val isSelected = dimension == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                    .clickable { onSelect(dimension) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = dimension.displayName,
                    fontSize = 13.sp,
                    color = if (isSelected) Color.White else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun FindProcessModeWarningBanner() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFF59E0B).copy(alpha = 0.12f))
            .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text = "提示：检测到内核 find-process-mode 未开启，应用维度统计可能无法捕获完整进程 UID。",
            style = MiuixTheme.textStyles.footnote1,
            color = Color(0xFFFBBF24)
        )
    }
}

/**
 * 详情抽屉与大屏右栏内容（包含 24 小时流量走势柱状图）
 */
@Composable
fun DetailPaneContent(
    item: TrafficRankItem,
    dimension: TrafficDimension,
    trafficDao: TrafficStatisticsDao,
    startTime: Long,
    modifier: Modifier = Modifier
) {
    var hourlyTrend by remember { mutableStateOf<List<HourlyTrendPoint>>(emptyList()) }

    LaunchedEffect(item.identifier, dimension, startTime) {
        hourlyTrend = when (dimension) {
            TrafficDimension.APP -> trafficDao.queryHourlyTrend(startTime, uid = item.identifier.toIntOrNull() ?: -1)
            TrafficDimension.DOMAIN -> trafficDao.queryHourlyTrend(startTime, host = item.identifier)
            TrafficDimension.NODE -> trafficDao.queryHourlyTrend(startTime, node = item.identifier)
            TrafficDimension.RULE -> emptyList()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF181A20))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = item.displayName,
            style = MiuixTheme.textStyles.title3,
            color = MiuixTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "累计产生流量: ${formatBytes(item.totalBytes)}",
                style = MiuixTheme.textStyles.body2,
                color = Color(0xFF00E5FF),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "代理: ${formatBytes(item.proxyBytes)}",
                style = MiuixTheme.textStyles.footnote1,
                color = Color(0xFF38BDF8)
            )
            Text(
                text = "直连: ${formatBytes(item.directBytes)}",
                style = MiuixTheme.textStyles.footnote1,
                color = Color(0xFF34D399)
            )
            Text(
                text = "拦截: ${formatBytes(item.rejectBytes)}",
                style = MiuixTheme.textStyles.footnote1,
                color = Color(0xFF94A3B8)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "24 小时流量走势",
            style = MiuixTheme.textStyles.headline2.copy(fontWeight = FontWeight.SemiBold),
            color = MiuixTheme.colorScheme.onSurface
        )

        HourlyBarChart(
            data = hourlyTrend,
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
        )
    }
}

/**
 * 24 小时流量走势柱状图
 */
@Composable
fun HourlyBarChart(
    data: List<HourlyTrendPoint>,
    modifier: Modifier = Modifier
) {
    val maxVal = remember(data) { data.maxOfOrNull { it.totalBytes } ?: 1L }

    Box(modifier = modifier) {
        if (data.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "暂无小时级流量数据",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                data.forEach { point ->
                    val barHeightRatio = (point.totalBytes.toFloat() / maxVal.coerceAtLeast(1L)).coerceIn(0.06f, 1f)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(barHeightRatio)
                            .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                            .background(Color(0xFF00E5FF))
                    )
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 * 1024 -> "%.2f GB".format(bytes.toFloat() / (1024 * 1024 * 1024))
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes.toFloat() / (1024 * 1024))
        bytes >= 1024 -> "%.0f KB".format(bytes.toFloat() / 1024)
        else -> "$bytes B"
    }
}
