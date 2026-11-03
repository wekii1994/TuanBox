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

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.yumeyucca.yumebox.common.util.ByteFormatter
import com.github.yumeyucca.yumebox.common.util.toast
import com.github.yumeyucca.yumebox.data.model.StatisticsTimeRange
import com.github.yumeyucca.yumebox.feature.meta.presentation.viewmodel.RecentRequestRecord
import com.github.yumeyucca.yumebox.feature.meta.presentation.viewmodel.TargetSiteRecord
import com.github.yumeyucca.yumebox.feature.meta.presentation.viewmodel.TrafficStatisticsUiState
import com.github.yumeyucca.yumebox.feature.meta.presentation.viewmodel.TrafficStatisticsViewModel
import com.github.yumeyucca.yumebox.feature.meta.presentation.viewmodel.extractDisplayDestination
import com.github.yumeyucca.yumebox.presentation.component.*
import com.github.yumeyucca.yumebox.presentation.navigation.Route
import com.github.yumeyucca.yumebox.presentation.theme.AppTheme
import com.github.yumeyucca.yumebox.presentation.theme.UiDp
import com.github.yumeyucca.yumebox.screen.connection.ConnectionDetailHolder
import org.koin.androidx.compose.koinViewModel
import com.github.yumeyucca.yumebox.core.locale.YumeTxt
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private enum class TrafficDetailSection(val title: String) {
    RECENT_REQUESTS("最近连接"),
    TARGET_SITES("目标服务"),
}

@Composable
fun TrafficStatisticsScreen(navigator: Navigator = LocalNavigator.current) {
    val viewModel = koinViewModel<TrafficStatisticsViewModel>()
    val scrollBehavior = MiuixScrollBehavior()
    val context = LocalContext.current
    val spacing = AppTheme.spacing

    val uiState by viewModel.overviewState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.notices.collect { context.toast(it) }
        }
    }
    var selectedDetailSection by rememberSaveable { mutableStateOf(TrafficDetailSection.RECENT_REQUESTS) }

    val timeRanges = StatisticsTimeRange.entries
    val selectedRangeIndex = timeRanges.indexOf(uiState.selectedTimeRange).coerceAtLeast(0)
    val isToday = uiState.selectedTimeRange == StatisticsTimeRange.TODAY

    GlassScaffold(
        topBar = {
            TopBar(
                title = YumeTxt.TrafficStatistics.Title,
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(
                        onClick = {
                            viewModel.clearAllStatistics()
                        }
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Delete,
                            contentDescription = "清空统计数据",
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        StandalonePageList(
            scrollBehavior = scrollBehavior,
            scaffoldPadding = innerPadding,
        ) {
            stickySegmentedTabs(
                key = "__time_range_tabs__",
                tabs = timeRanges.map { if (it == StatisticsTimeRange.WEEK) "近 7 天" else it.label },
                selectedTabIndex = selectedRangeIndex,
                onTabSelected = { index ->
                    timeRanges.getOrNull(index)?.let(viewModel::setTimeRange)
                },
                tabRowPadding = spacing.screenHorizontal,
            )

            item(key = "overview_card") {
                Spacer(modifier = Modifier.height(spacing.space12))
                OverviewSectionCard(
                    uiState = uiState,
                    isToday = isToday,
                )
            }

            item(key = "details_card") {
                Spacer(modifier = Modifier.height(spacing.space12))
                LiveDetailsSection(
                    viewModel = viewModel,
                    selectedSection = selectedDetailSection,
                    onSectionSelect = { selectedDetailSection = it },
                    navigator = navigator,
                )
            }

            item(key = "bottom_spacer") {
                Spacer(modifier = Modifier.height(spacing.space24))
            }
        }
    }
}

@Composable
private fun OverviewSectionCard(
    uiState: TrafficStatisticsUiState,
    isToday: Boolean,
) {
    val spacing = AppTheme.spacing
    val componentSizes = AppTheme.sizes
    val radii = AppTheme.radii

    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.space16),
            verticalArrangement = Arrangement.spacedBy(spacing.space16),
        ) {
            // Upload & Download Summary
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.space4)) {
                    Text(
                        text = if (isToday) "今日上传" else "近 7 天上传",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = ByteFormatter.format(uiState.summary.totalUpload),
                        style = MiuixTheme.textStyles.title2,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                }

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(spacing.space4),
                ) {
                    Text(
                        text = if (isToday) "今日下载" else "近 7 天下载",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = ByteFormatter.format(uiState.summary.totalDownload),
                        style = MiuixTheme.textStyles.title2,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
            }

            HorizontalDivider(
                thickness = componentSizes.thinDividerThickness,
                color = MiuixTheme.colorScheme.outline.copy(alpha = AppTheme.opacity.outline),
            )

            // Dynamic Context Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isToday) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(spacing.space8),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "今日累计 · ${ByteFormatter.format(uiState.todayTotal)}",
                            style = MiuixTheme.textStyles.body2,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        if (uiState.yesterdayTotal > 0L) {
                            val diffText = if (uiState.trafficDifference >= 0) {
                                "+${ByteFormatter.format(uiState.trafficDifference)}"
                            } else {
                                "-${ByteFormatter.format(abs(uiState.trafficDifference))}"
                            }
                            Text(
                                text = "较昨日 $diffText",
                                style = MiuixTheme.textStyles.footnote1,
                                color = if (uiState.trafficDifference > 0) {
                                    MiuixTheme.colorScheme.primary
                                } else {
                                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                                },
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "近 7 天累计 · ${ByteFormatter.format(uiState.weekTotal)}",
                            style = MiuixTheme.textStyles.body2,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        if (uiState.chartAverage > 0L) {
                            Text(
                                text = "日均 ${ByteFormatter.format(uiState.chartAverage)}",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            // Chart
            TrafficBarChart(
                items = uiState.chartBars,
                animationKey = uiState.selectedTimeRange,
                averageValue = uiState.chartAverage.takeIf { !isToday && it > 0L },
                averageLabel = YumeTxt.TrafficStatistics.Summary.Average.takeIf { !isToday && uiState.chartAverage > 0L },
                showSelectionTip = !isToday,
                tipTitleFor = { bar ->
                    YumeTxt.TrafficStatistics.Summary.DayTrafficTip.format(bar.label)
                },
                chartHeight = if (isToday) componentSizes.trafficChartHeight else componentSizes.trafficBarChartHeight,
                barWidth = if (isToday) UiDp.dp8 else componentSizes.trafficBarWidth,
            )
        }
    }
}

@Composable
private fun LiveDetailsSection(
    viewModel: TrafficStatisticsViewModel,
    selectedSection: TrafficDetailSection,
    onSectionSelect: (TrafficDetailSection) -> Unit,
    navigator: Navigator,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val nowMillis by produceState(System.currentTimeMillis(), lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = System.currentTimeMillis()
                delay(30_000L)
            }
        }
    }
    DetailsSectionCard(state, selectedSection, onSectionSelect, navigator, nowMillis)
}

@Composable
private fun DetailsSectionCard(
    uiState: TrafficStatisticsUiState,
    selectedSection: TrafficDetailSection,
    onSectionSelect: (TrafficDetailSection) -> Unit,
    navigator: Navigator,
    nowMillis: Long,
) {
    val spacing = AppTheme.spacing
    val componentSizes = AppTheme.sizes

    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            SegmentedTabRow(
                tabs = TrafficDetailSection.entries.map { it.title },
                selectedTabIndex = selectedSection.ordinal,
                onTabSelected = { index ->
                    TrafficDetailSection.entries.getOrNull(index)?.let(onSectionSelect)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.space16, vertical = spacing.space12),
            )

            Text(
                text = uiState.connectionError ?: "当前连接及最近 100 条关闭记录；流量为连接累计值，与上方时间范围无关",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = spacing.space16, vertical = spacing.space8),
            )
            AnimatedContent(
                targetState = selectedSection,
                transitionSpec = {
                    fadeIn(animationSpec = tween(200)) togetherWith fadeOut(animationSpec = tween(200))
                },
                label = "DetailsSectionContent",
            ) { section ->
                when (section) {
                    TrafficDetailSection.RECENT_REQUESTS -> {
                        if (uiState.recentRequests.isEmpty()) {
                            EmptyDetailNotice(message = "暂无连接记录")
                        } else {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                uiState.recentRequests.forEachIndexed { index, request ->
                                    key(request.connection.id) {
                                        RecentRequestItemCard(
                                            record = request,
                                            nowMillis = nowMillis,
                                            onClick = {
                                                ConnectionDetailHolder.setup(request.connection)
                                                navigator.push(
                                                    Route.ConnectionDetail(
                                                        connectionId = request.connection.id,
                                                        canInterrupt = request.isActive,
                                                    )
                                                )
                                            },
                                        )
                                        if (index < uiState.recentRequests.lastIndex) {
                                            HorizontalDivider(
                                                modifier = Modifier.padding(start = 64.dp, end = spacing.space16),
                                                thickness = componentSizes.thinDividerThickness,
                                                color = MiuixTheme.colorScheme.outline.copy(alpha = AppTheme.opacity.outline),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    TrafficDetailSection.TARGET_SITES -> {
                        if (uiState.targetSites.isEmpty()) {
                            EmptyDetailNotice(message = "暂无目标站点统计")
                        } else {
                            val maxBytes = uiState.targetSites.firstOrNull()?.totalBytes?.coerceAtLeast(1L) ?: 1L
                            Column(modifier = Modifier.fillMaxWidth()) {
                                uiState.targetSites.forEachIndexed { index, site ->
                                    key(site.siteKey) {
                                        TargetSiteItemCard(
                                            record = site,
                                            nowMillis = nowMillis,
                                            rank = index + 1,
                                            maxBytes = maxBytes,
                                        )
                                        if (index < uiState.targetSites.lastIndex) {
                                            HorizontalDivider(
                                                modifier = Modifier.padding(start = UiDp.dp48, end = spacing.space16),
                                                thickness = componentSizes.thinDividerThickness,
                                                color = MiuixTheme.colorScheme.outline.copy(alpha = AppTheme.opacity.outline),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecentRequestItemCard(
    record: RecentRequestRecord,
    nowMillis: Long,
    onClick: () -> Unit,
) {
    val spacing = AppTheme.spacing
    val connection = record.connection
    val effectiveHost = remember(record.displayHost, connection) {
        record.displayHost.ifBlank { connection.extractDisplayDestination() }
    }
    val timeText = formatMillisTime(record.startMillis, nowMillis)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.space16, vertical = spacing.space12),
        horizontalArrangement = Arrangement.spacedBy(spacing.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIconBadge(
            packageName = record.sourcePackageName,
            appName = record.sourceAppName ?: effectiveHost,
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(spacing.space4),
        ) {
            // Host and time
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = effectiveHost,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = "建立于 $timeText",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }

            // SubDetail or App Name
            if (record.subDetail != null || record.sourceAppName != null) {
                val detail = record.subDetail ?: record.sourceAppName.orEmpty()
                Text(
                    text = detail,
                    style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Wrapping badges keep long node/rule names from squeezing the byte counts.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.space6),
                verticalArrangement = Arrangement.spacedBy(spacing.space4),
            ) {
                record.serviceTag?.let { SmallBadge(text = it, isPrimary = true) }
                SmallBadge(text = record.exitNode, isPrimary = false)
                SmallBadge(text = record.ruleLabel, isPrimary = false)
            }
            Text(
                text = "↑ ${ByteFormatter.format(connection.upload)}  ↓ ${ByteFormatter.format(connection.download)}",
                style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun TargetSiteItemCard(
    record: TargetSiteRecord,
    nowMillis: Long,
    rank: Int,
    maxBytes: Long,
) {
    val spacing = AppTheme.spacing
    val radii = AppTheme.radii
    val progress = (record.totalBytes.toFloat() / maxBytes.toFloat()).coerceIn(0.02f, 1f)
    val timeText = formatMillisTime(record.lastSeenAt, nowMillis)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.space16, vertical = spacing.space12),
        horizontalArrangement = Arrangement.spacedBy(spacing.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (record.isIp) {
            IpBadge()
        } else {
            RankBadge(rank = rank)
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(spacing.space4),
        ) {
            // Site name and Primary Domain
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = record.displayName,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = ByteFormatter.format(record.totalBytes),
                    style = MiuixTheme.textStyles.footnote1,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }

            // Primary Domain and Tag
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.space6),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = record.primaryDomain,
                    style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                record.serviceTag?.let { tag ->
                    SmallBadge(text = tag, isPrimary = true)
                }
            }

            // Progress bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(UiDp.dp4)
                    .clip(RoundedCornerShape(radii.full))
                    .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(UiDp.dp4)
                        .clip(RoundedCornerShape(radii.full))
                        .background(MiuixTheme.colorScheme.primary)
                )
            }

            // Breakdown and Stats
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "↑ ${ByteFormatter.format(record.totalUpload)}  ↓ ${ByteFormatter.format(record.totalDownload)}",
                    style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    text = "${record.connectionCount} 条连接 · 最近建立于 $timeText",
                    style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
private fun BadgeBox(
    modifier: Modifier = Modifier,
    backgroundColor: androidx.compose.ui.graphics.Color,
    content: @Composable BoxScope.() -> Unit,
) {
    val radii = AppTheme.radii
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(RoundedCornerShape(radii.radius8))
            .background(backgroundColor),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
private fun IpBadge() {
    BadgeBox(backgroundColor = MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)) {
        Text(
            text = "IP",
            style = MiuixTheme.textStyles.footnote1.copy(fontSize = 10.sp),
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun RankBadge(rank: Int) {
    val isTop3 = rank <= 3
    val bgColor = if (isTop3) {
        MiuixTheme.colorScheme.primary.copy(alpha = 0.15f)
    } else {
        MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    }
    val textColor = if (isTop3) {
        MiuixTheme.colorScheme.primary
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    }

    BadgeBox(backgroundColor = bgColor) {
        Text(
            text = "$rank",
            style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp),
            fontWeight = if (isTop3) FontWeight.Bold else FontWeight.Medium,
            color = textColor,
        )
    }
}

@Composable
private fun SmallBadge(text: String, isPrimary: Boolean) {
    val radii = AppTheme.radii
    val bgColor = if (isPrimary) {
        MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
    } else {
        MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    }
    val textColor = if (isPrimary) {
        MiuixTheme.colorScheme.primary
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    }

    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 10.sp),
        color = textColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(radii.radius8))
            .background(bgColor)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun EmptyDetailNotice(message: String) {
    val spacing = AppTheme.spacing
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.space24, horizontal = spacing.space16),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun AppIconBadge(packageName: String?, appName: String) {
    val componentSizes = AppTheme.sizes
    val opacity = AppTheme.opacity
    val radii = AppTheme.radii

    val iconPx = with(LocalDensity.current) { componentSizes.iconBadgeMedium.roundToPx() }
    val bitmap = rememberAppIconBitmap(packageName, iconPx)
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = appName,
            modifier = Modifier
                .size(componentSizes.iconBadgeMedium)
                .clip(RoundedCornerShape(radii.radius12)),
        )
        return
    }

    Box(
        modifier = Modifier
            .size(componentSizes.iconBadgeMedium)
            .clip(RoundedCornerShape(radii.radius12))
            .background(MiuixTheme.colorScheme.primary.copy(alpha = opacity.subtleStrong)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = appName.take(1).ifBlank { "?" },
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
    }
}

private val DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private fun formatMillisTime(millis: Long, nowMillis: Long): String {
    if (millis <= 0L) return "未知时间"
    if (millis > nowMillis) return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DATE_TIME_FORMATTER)
    val diffSeconds = (nowMillis - millis) / 1000
    return when {
        diffSeconds < 60 -> "刚刚"
        diffSeconds < 3600 -> "${diffSeconds / 60} 分钟前"
        diffSeconds < 86400 -> "${diffSeconds / 3600} 小时前"
        else -> {
            val instant = Instant.ofEpochMilli(millis)
            val zonedDateTime = instant.atZone(ZoneId.systemDefault())
            zonedDateTime.format(DATE_TIME_FORMATTER)
        }
    }
}
