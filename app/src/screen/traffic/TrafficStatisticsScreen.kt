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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.github.yumeyucca.yumebox.common.util.ByteFormatter
import com.github.yumeyucca.yumebox.common.util.toast
import com.github.yumeyucca.yumebox.core.locale.YumeTxt
import com.github.yumeyucca.yumebox.data.trafficv2.*
import com.github.yumeyucca.yumebox.feature.meta.presentation.viewmodel.*
import com.github.yumeyucca.yumebox.presentation.component.*
import com.github.yumeyucca.yumebox.presentation.navigation.Route
import com.github.yumeyucca.yumebox.presentation.theme.AppTheme
import com.github.yumeyucca.yumebox.presentation.theme.UiDp
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun TrafficStatisticsScreen(navigator: Navigator = LocalNavigator.current) {
    val viewModel = koinViewModel<TrafficStatisticsV2ViewModel>()
    val coordinator = koinInject<TrafficRuntimeCoordinator>()
    val scrollBehavior = MiuixScrollBehavior()
    val context = LocalContext.current
    val spacing = AppTheme.spacing

    val state by viewModel.state.collectAsStateWithLifecycle()
    val liveTraffic by coordinator.live.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.messages.collect { context.toast(it) }
        }
    }

    var showClearDialog by rememberSaveable { mutableStateOf(false) }
    var selectedServiceKey by rememberSaveable { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable(state.selection.range, state.selection.metric) { mutableStateOf(false) }

    val timeRanges = listOf(ServiceRange.TODAY to "今日", ServiceRange.WEEK to "近 7 天")
    val selectedRangeIndex = if (state.selection.range == ServiceRange.WEEK) 1 else 0

    GlassScaffold(
        topBar = {
            TopBar(
                title = YumeTxt.TrafficStatistics.Title,
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = { showClearDialog = true }) {
                        Icon(
                            imageVector = MiuixIcons.Delete,
                            contentDescription = "清空统计数据",
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        StandalonePageList(
            scrollBehavior = scrollBehavior,
            scaffoldPadding = innerPadding,
        ) {
            stickySegmentedTabs(
                key = "__time_range_tabs__",
                tabs = timeRanges.map { it.second },
                selectedTabIndex = selectedRangeIndex,
                onTabSelected = { index ->
                    viewModel.selectRange(timeRanges[index].first)
                },
                tabRowPadding = spacing.screenHorizontal,
            )

            item(key = "overview_card") {
                Spacer(modifier = Modifier.height(spacing.space12))
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(spacing.space16),
                        verticalArrangement = Arrangement.spacedBy(spacing.space16),
                    ) {
                        TrafficMetricChoices(
                            total = state.page.total,
                            selected = state.selection.metric,
                            onSelect = viewModel::selectMetric,
                        )

                        HorizontalDivider(
                            thickness = AppTheme.sizes.thinDividerThickness,
                            color = MiuixTheme.colorScheme.outline.copy(alpha = AppTheme.opacity.outline),
                        )

                        val zone = remember { ZoneId.systemDefault() }
                        val today = remember { LocalDate.now(zone) }
                        val isToday = state.selection.range == ServiceRange.TODAY
                        val firstDate = if (isToday) today else today.minusDays(6)
                        val days = if (isToday) 1 else 7
                        val points = remember(state.rows, firstDate, days, zone, isToday) {
                            trafficChartPoints(state.rows, firstDate, days, zone, daily = !isToday)
                        }
                        val chartBars = remember(points, state.selection.metric) {
                            points.map { point ->
                                BarChartItem(
                                    label = point.label,
                                    value = point.bytes.value(state.selection.metric),
                                )
                            }
                        }
                        TrafficBarChart(
                            items = chartBars,
                            animationKey = Pair(state.selection.range, state.selection.metric),
                            showSelectionTip = true,
                            tipTitleFor = { bar -> "${bar.label} 流量" },
                            chartHeight = if (isToday) AppTheme.sizes.trafficChartHeight else AppTheme.sizes.trafficBarChartHeight,
                            barWidth = if (isToday) UiDp.dp8 else AppTheme.sizes.trafficBarWidth,
                        )

                        if (state.selection.range == ServiceRange.TODAY && !state.page.legacyWithoutHour.empty) {
                            Text(
                                text = "部分旧记录只有每日总量，未分配到小时图表",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        val statusMsg = state.error ?: liveTraffic.error
                        if (statusMsg != null) {
                            Text(
                                text = statusMsg,
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            item(key = "services_card_header") {
                Spacer(modifier = Modifier.height(spacing.space12))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.space16),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "目标服务",
                        style = MiuixTheme.textStyles.title3,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = when (state.selection.metric) {
                            TrafficMetric.DOWNLOAD -> "按下载排序"
                            TrafficMetric.UPLOAD -> "按上传排序"
                            TrafficMetric.TOTAL -> "按合计排序"
                        },
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            if (state.loading) {
                item(key = "services_loading") {
                    AppCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "正在读取统计…",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(spacing.space16),
                        )
                    }
                }
            } else if (state.page.total.value(state.selection.metric) == 0L) {
                item(key = "services_empty") {
                    AppCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "当前时段暂无此项流量",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(spacing.space16),
                        )
                    }
                }
            } else {
                item(key = "services_card") {
                    AppCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            val metric = state.selection.metric
                            val (visible, remainder) = state.page.visible(metric, if (expanded) Int.MAX_VALUE else 8)
                            val totalValue = state.page.total.value(metric)
                            visible.forEachIndexed { index, row ->
                                if (index > 0) {
                                    HorizontalDivider(
                                        thickness = AppTheme.sizes.thinDividerThickness,
                                        color = MiuixTheme.colorScheme.outline.copy(alpha = AppTheme.opacity.outline),
                                    )
                                }
                                ServiceRowItem(
                                    row = row,
                                    metric = metric,
                                    total = totalValue,
                                    onClick = { selectedServiceKey = row.key },
                                )
                            }
                            if (remainder.value(metric) > 0) {
                                HorizontalDivider(
                                    thickness = AppTheme.sizes.thinDividerThickness,
                                    color = MiuixTheme.colorScheme.outline.copy(alpha = AppTheme.opacity.outline),
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { expanded = true }
                                        .padding(spacing.space16),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "其他 ${state.page.services.count { it.bytes.value(metric) > 0 } - visible.size} 个目标",
                                        style = MiuixTheme.textStyles.body2,
                                        color = MiuixTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = ByteFormatter.format(remainder.value(metric)),
                                        style = MiuixTheme.textStyles.body2,
                                        fontWeight = FontWeight.Medium,
                                        color = MiuixTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item(key = "bottom_spacer") {
                Spacer(modifier = Modifier.height(spacing.space24))
            }
        }
    }

    AppConfirmDialog(
        show = showClearDialog,
        title = "清空统计",
        message = "确定要清空全部流量统计数据吗？此操作不可恢复。",
        onDismissRequest = { showClearDialog = false },
        onConfirm = {
            showClearDialog = false
            viewModel.clearStatistics()
        },
    )

    selectedServiceKey?.let { key ->
        val detail = remember(state.rows, key) {
            projectServiceDetail(state.rows, key)
        }
        val evidence = remember(key, coordinator) {
            coordinator.evidence.forService(key, System.currentTimeMillis())
        }
        AppActionBottomSheet(
            show = detail != null,
            title = detail?.label.orEmpty(),
            onDismissRequest = { selectedServiceKey = null },
        ) {
            if (detail != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = spacing.space8),
                    verticalArrangement = Arrangement.spacedBy(spacing.space16),
                ) {
                    AppCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(spacing.space16),
                            verticalArrangement = Arrangement.spacedBy(spacing.space8),
                        ) {
                            Text(
                                text = if (state.selection.range == ServiceRange.TODAY) "今日流量" else "近 7 天流量",
                                style = MiuixTheme.textStyles.headline1,
                                fontWeight = FontWeight.Bold,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("下载", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                Text(ByteFormatter.format(detail.bytes.down), fontWeight = FontWeight.Medium)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("上传", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                Text(ByteFormatter.format(detail.bytes.up), fontWeight = FontWeight.Medium)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("合计", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                Text(
                                    ByteFormatter.format(detail.bytes.value(TrafficMetric.TOTAL)),
                                    fontWeight = FontWeight.Bold,
                                    color = MiuixTheme.colorScheme.primary,
                                )
                            }
                            if (detail.lastTrafficAt > 0L) {
                                val timeStr = remember(detail.lastTrafficAt) {
                                    val instant = Instant.ofEpochMilli(detail.lastTrafficAt)
                                    val zone = ZoneId.systemDefault()
                                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone).format(instant)
                                }
                                HorizontalDivider(
                                    thickness = AppTheme.sizes.thinDividerThickness,
                                    color = MiuixTheme.colorScheme.outline.copy(alpha = AppTheme.opacity.outline),
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text("最近流量时间", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                    Text(timeStr, style = MiuixTheme.textStyles.footnote1)
                                }
                            }
                        }
                    }

                    if (detail.sources.isNotEmpty()) {
                        AppCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(spacing.space16),
                                verticalArrangement = Arrangement.spacedBy(spacing.space8),
                            ) {
                                Text(
                                    text = "来源应用",
                                    style = MiuixTheme.textStyles.headline1,
                                    fontWeight = FontWeight.Bold,
                                )
                                detail.sources.forEachIndexed { idx, share ->
                                    if (idx > 0) {
                                        HorizontalDivider(
                                            thickness = AppTheme.sizes.thinDividerThickness,
                                            color = MiuixTheme.colorScheme.outline.copy(alpha = AppTheme.opacity.outline),
                                        )
                                    }
                                    Column(verticalArrangement = Arrangement.spacedBy(spacing.space4)) {
                                        Text(share.app.label, fontWeight = FontWeight.Medium)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Text(
                                                text = "下载 ${ByteFormatter.format(share.bytes.down)} · 上传 ${ByteFormatter.format(share.bytes.up)}",
                                                style = MiuixTheme.textStyles.footnote1,
                                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            )
                                            Text(
                                                text = ByteFormatter.format(share.bytes.value(TrafficMetric.TOTAL)),
                                                style = MiuixTheme.textStyles.footnote1,
                                                fontWeight = FontWeight.Medium,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (detail.key == "other") {
                        Text(
                            text = "包含缺少目标或连接明细的流量，以及无法恢复服务归属的旧记录。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = spacing.space8),
                        )
                    }

                    AppCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(spacing.space16),
                            verticalArrangement = Arrangement.spacedBy(spacing.space8),
                        ) {
                            Text(
                                text = "当前会话最近观测到的目标（最多保留 1 小时）",
                                style = MiuixTheme.textStyles.headline1,
                                fontWeight = FontWeight.Bold,
                            )
                            if (evidence.isEmpty()) {
                                Text(
                                    text = "暂无目标记录",
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            } else {
                                evidence.take(20).forEach { entry ->
                                    val target = listOfNotNull(entry.host, entry.ip).distinct().joinToString(" · ")
                                    Text(
                                        text = target,
                                        style = MiuixTheme.textStyles.body2,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (evidence.size > 20) {
                                    Text(
                                        text = "另有 ${evidence.size - 20} 个目标，请在连接页查看当前连接",
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }

                    Button(
                        text = "查看当前相关连接",
                        onClick = {
                            val keyToNavigate = detail.key
                            selectedServiceKey = null
                            navigator.push(Route.Connection(serviceKey = keyToNavigate))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ServiceRowItem(row: ServiceRow, metric: TrafficMetric, total: Long, onClick: () -> Unit) {
    val value = row.bytes.value(metric)
    val fraction = if (total > 0) (value.toDouble() / total).coerceIn(0.0, 1.0) else 0.0
    val percentage = if (value > 0 && fraction < .001) "<0.1%"
    else String.format(Locale.getDefault(), "%.1f%%", fraction * 100)
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                row.label,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
            )
            Text(ByteFormatter.format(value))
        }
        val sub = row.subtitle()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                sub,
                Modifier.weight(1f),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                percentage,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(MiuixTheme.colorScheme.surfaceVariant)
        ) {
            if (fraction > 0) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction.toFloat())
                        .height(3.dp)
                        .background(MiuixTheme.colorScheme.primary)
                )
            }
        }
    }
}
