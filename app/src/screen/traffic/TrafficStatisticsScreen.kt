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

@file:Suppress("FunctionName", "UnusedVariable")

package com.github.yumeyucca.yumebox.screen.traffic

import android.content.Context
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.github.yumeyucca.yumebox.common.util.formatBytes
import com.github.yumeyucca.yumebox.screen.connection.ConnectionDetailHolder
import com.github.yumeyucca.yumebox.common.util.toast
import com.github.yumeyucca.yumebox.data.controller.AppIdentityResolver
import com.github.yumeyucca.yumebox.data.model.AppTrafficUsage
import com.github.yumeyucca.yumebox.data.model.StatisticsTimeRange
import com.github.yumeyucca.yumebox.data.model.TrafficStatisticsBuckets
import com.github.yumeyucca.yumebox.feature.meta.presentation.component.TabRowWithContour
import com.github.yumeyucca.yumebox.feature.meta.presentation.viewmodel.RecentRequestRecord
import com.github.yumeyucca.yumebox.feature.meta.presentation.viewmodel.TargetSiteRecord
import com.github.yumeyucca.yumebox.feature.meta.presentation.viewmodel.TrafficStatisticsViewModel
import com.github.yumeyucca.yumebox.feature.meta.presentation.viewmodel.extractDisplayDestination
import com.github.yumeyucca.yumebox.presentation.component.*
import com.github.yumeyucca.yumebox.presentation.component.AppCard
import com.github.yumeyucca.yumebox.presentation.navigation.Route
import com.github.yumeyucca.yumebox.presentation.theme.AppTheme
import com.github.yumeyucca.yumebox.presentation.theme.UiDp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import com.github.yumeyucca.yumebox.core.locale.YumeTxt
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.Collator
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private enum class TrafficDetailSection(val title: String) {
    RECENT_REQUESTS("最近请求"),
    TARGET_SITES("目标站点"),
    APP_USAGES("应用排行"),
}

private enum class AppTrafficSortMode {
    NAME,
    USAGE,
}

@Composable
fun TrafficStatisticsScreen(navigator: Navigator = LocalNavigator.current) {
    val viewModel = koinViewModel<TrafficStatisticsViewModel>()
    val scrollBehavior = MiuixScrollBehavior()
    val context = LocalContext.current
    val spacing = AppTheme.spacing
    val componentSizes = AppTheme.sizes

    val uiState by viewModel.uiState.collectAsState()
    var appSortMode by rememberSaveable { mutableStateOf(AppTrafficSortMode.USAGE) }
    var selectedDetailSection by rememberSaveable { mutableStateOf(TrafficDetailSection.RECENT_REQUESTS) }

    val appNameCollator = remember { Collator.getInstance() }
    val displayedApps =
        remember(uiState.topApps, appSortMode, appNameCollator) {
            when (appSortMode) {
                AppTrafficSortMode.NAME ->
                    uiState.topApps.sortedWith { left, right ->
                        appNameCollator.compare(left.appName, right.appName)
                    }

                AppTrafficSortMode.USAGE ->
                    uiState.topApps.sortedByDescending(AppTrafficUsage::totalBytes)
            }
        }

    val timeRanges = StatisticsTimeRange.entries
    val selectedRangeIndex = timeRanges.indexOf(uiState.selectedTimeRange).coerceAtLeast(0)
    val isToday = uiState.selectedTimeRange == StatisticsTimeRange.TODAY

    Scaffold(
        topBar = {
            TopBar(
                title = YumeTxt.TrafficStatistics.Title,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    NavigationBackIcon(navigator = navigator)
                },
                actions = {
                    IconButton(
                        onClick = {
                            viewModel.clearAllStatistics()
                            context.toast(YumeTxt.TrafficStatistics.Action.ClearSuccess)
                        }
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Delete,
                            contentDescription = YumeTxt.TrafficStatistics.Action.Clear,
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        }
    ) { innerPadding ->
        val mainLikePadding = rememberStandalonePageMainPadding()
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            val isWideLayout = maxWidth >= 700.dp
            ScreenLazyColumn(
                scrollBehavior = scrollBehavior,
                innerPadding = combinePaddingValues(innerPadding, mainLikePadding),
            ) {
                if (isWideLayout) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.space16),
                            horizontalArrangement = Arrangement.spacedBy(spacing.space16),
                            verticalAlignment = Alignment.Top,
                        ) {
                            OverviewSectionCard(
                                modifier = Modifier.weight(1f),
                                uiState = uiState,
                                selectedRangeIndex = selectedRangeIndex,
                                timeRanges = timeRanges,
                                isToday = isToday,
                                onSelectRange = { range -> viewModel.setTimeRange(range) },
                                onSelectBar = { index ->
                                    viewModel.setSelectedBarIndex(
                                        if (uiState.selectedBarIndex == index) -1 else index
                                    )
                                },
                            )
                            DetailsSectionCard(
                                modifier = Modifier.weight(1f),
                                uiState = uiState,
                                context = context,
                                navigator = navigator,
                                selectedSection = selectedDetailSection,
                                onSectionChange = { selectedDetailSection = it },
                                appSortMode = appSortMode,
                                onSortModeChange = { appSortMode = it },
                                displayedApps = displayedApps,
                            )
                        }
                    }
                } else {
                    item {
                        OverviewSectionCard(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.space16),
                            uiState = uiState,
                            selectedRangeIndex = selectedRangeIndex,
                            timeRanges = timeRanges,
                            isToday = isToday,
                            onSelectRange = { range -> viewModel.setTimeRange(range) },
                            onSelectBar = { index ->
                                viewModel.setSelectedBarIndex(
                                    if (uiState.selectedBarIndex == index) -1 else index
                                )
                            },
                        )
                    }
                    item {
                        Spacer(modifier = Modifier.height(spacing.space16))
                        DetailsSectionCard(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.space16),
                            uiState = uiState,
                            context = context,
                            navigator = navigator,
                            selectedSection = selectedDetailSection,
                            onSectionChange = { selectedDetailSection = it },
                            appSortMode = appSortMode,
                            onSortModeChange = { appSortMode = it },
                            displayedApps = displayedApps,
                        )
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(spacing.space32))
                }
            }
        }
    }
}

@Composable
private fun OverviewSectionCard(
    modifier: Modifier = Modifier,
    uiState: com.github.yumeyucca.yumebox.feature.meta.presentation.viewmodel.TrafficStatisticsUiState,
    selectedRangeIndex: Int,
    timeRanges: List<StatisticsTimeRange>,
    isToday: Boolean,
    onSelectRange: (StatisticsTimeRange) -> Unit,
    onSelectBar: (Int) -> Unit,
) {
    val spacing = AppTheme.spacing
    val componentSizes = AppTheme.sizes

    AppCard(
        modifier = modifier,
        insideMargin = PaddingValues(spacing.space16),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.space16)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "概览",
                    style = MiuixTheme.textStyles.title4,
                    color = MiuixTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                TabRowWithContour(
                    tabs = timeRanges.map { it.label },
                    selectedTabIndex = selectedRangeIndex,
                    onTabSelected = { index ->
                        timeRanges.getOrNull(index)?.let(onSelectRange)
                    },
                    modifier = Modifier.width(160.dp),
                )
            }

            // Summary metrics
            val displayTotal = if (isToday) uiState.todayTotal else uiState.weekTotal
            val summaryCaption =
                if (isToday) YumeTxt.TrafficStatistics.Summary.TodayTraffic
                else YumeTxt.TrafficStatistics.Summary.WeekTraffic

            val differenceText =
                if (isToday) {
                    when {
                        uiState.trafficDifference > 0 -> "比昨日增加 +${formatBytes(uiState.trafficDifference)}"
                        uiState.trafficDifference < 0 -> "比昨日减少 -${formatBytes(abs(uiState.trafficDifference))}"
                        else -> "与昨日基本持平"
                    }
                } else {
                    if (uiState.chartAverage > 0L) {
                        "近 7 天累计 · 日均 ${formatBytes(uiState.chartAverage)}"
                    } else {
                        "近 7 天累计流量"
                    }
                }

            Column(verticalArrangement = Arrangement.spacedBy(spacing.space4)) {
                Text(
                    text = summaryCaption,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    text = formatBytes(displayTotal),
                    style = MiuixTheme.textStyles.title1,
                    color = MiuixTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp,
                )
                Text(
                    text = differenceText,
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (uiState.trafficDifference > 0 && isToday) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }

            // Selected bar detail or clock context
            val selectedBar = uiState.chartBars.getOrNull(uiState.selectedBarIndex)
            val selectedBarText =
                if (selectedBar != null && selectedBar.label.isNotBlank()) {
                    "${selectedBar.label}: ${formatBytes(selectedBar.value)}"
                } else {
                    null
                }

            Box(
                modifier = Modifier.fillMaxWidth().heightIn(min = UiDp.dp24),
                contentAlignment = Alignment.CenterStart,
            ) {
                AnimatedContent(
                    targetState = selectedBarText ?: uiState.todayTimeContext,
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
                    label = "bar_info",
                ) { infoText ->
                    if (infoText.isNotBlank()) {
                        Text(
                            text = infoText,
                            style = MiuixTheme.textStyles.footnote1,
                            color = if (selectedBarText != null) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontWeight = if (selectedBarText != null) FontWeight.Medium else FontWeight.Normal,
                        )
                    }
                }
            }

            TrafficBarChart(
                items = uiState.chartBars,
                animationKey = uiState.selectedTimeRange,
                averageValue = uiState.chartAverage.takeIf { !isToday && it > 0L },
                averageLabel = YumeTxt.TrafficStatistics.Summary.Average.takeIf { !isToday && uiState.chartAverage > 0L },
                showSelectionTip = false,
                selectedIndex = uiState.selectedBarIndex,
                onItemClick = onSelectBar,
                chartHeight = if (isToday) componentSizes.trafficChartHeight else componentSizes.trafficBarChartHeight,
                barWidth = if (isToday) UiDp.dp8 else componentSizes.trafficBarWidth,
            )
        }
    }
}

@Composable
private fun DetailsSectionCard(
    modifier: Modifier = Modifier,
    uiState: com.github.yumeyucca.yumebox.feature.meta.presentation.viewmodel.TrafficStatisticsUiState,
    context: Context,
    navigator: Navigator,
    selectedSection: TrafficDetailSection,
    onSectionChange: (TrafficDetailSection) -> Unit,
    appSortMode: AppTrafficSortMode,
    onSortModeChange: (AppTrafficSortMode) -> Unit,
    displayedApps: List<AppTrafficUsage>,
) {
    val spacing = AppTheme.spacing
    val componentSizes = AppTheme.sizes
    val sections = TrafficDetailSection.entries
    val currentSectionIndex = sections.indexOf(selectedSection).coerceAtLeast(0)

    AppCard(
        modifier = modifier,
        insideMargin = PaddingValues(spacing.space16),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.space16)) {
            // Section Switcher Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TabRowWithContour(
                    tabs = sections.map { it.title },
                    selectedTabIndex = currentSectionIndex,
                    onTabSelected = { index ->
                        sections.getOrNull(index)?.let(onSectionChange)
                    },
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(spacing.space12))
                val countText = when (selectedSection) {
                    TrafficDetailSection.RECENT_REQUESTS -> "${uiState.recentRequests.size} 条"
                    TrafficDetailSection.TARGET_SITES -> "${uiState.targetSites.size} 个"
                    TrafficDetailSection.APP_USAGES -> "${displayedApps.size} 款"
                }
                Text(
                    text = countText,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                )
            }

            AnimatedContent(
                targetState = selectedSection,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) },
                label = "detail_section",
            ) { section ->
                when (section) {
                    TrafficDetailSection.RECENT_REQUESTS -> {
                        if (uiState.recentRequests.isEmpty()) {
                            EmptyDetailNotice("暂无最近请求数据，开启代理后发起网络访问即可查看")
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(spacing.space10)) {
                                uiState.recentRequests.forEach { request ->
                                    RecentRequestItemCard(
                                        record = request,
                                        onClick = {
                                            ConnectionDetailHolder.setup(
                                                info = request.connection,
                                                canInterrupt = request.isActive,
                                            )
                                            navigator.push(Route.ConnectionDetail(request.connection.id))
                                        },
                                    )
                                }
                            }
                        }
                    }

                    TrafficDetailSection.TARGET_SITES -> {
                        if (uiState.targetSites.isEmpty()) {
                            EmptyDetailNotice("暂无目标站点统计，开启代理后产生流量将自动聚合")
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(spacing.space10)) {
                                uiState.targetSites.forEach { site ->
                                    TargetSiteItemCard(record = site)
                                }
                            }
                        }
                    }

                    TrafficDetailSection.APP_USAGES -> {
                        Column {
                            OverlayDropdownPreference(
                                items = listOf(
                                    YumeTxt.TrafficStatistics.Metric.SortByUsage,
                                    YumeTxt.TrafficStatistics.Metric.SortByName,
                                ),
                                selectedIndex = if (appSortMode == AppTrafficSortMode.USAGE) 0 else 1,
                                title = "排序依据",
                                maxHeight = UiDp.dp200,
                                onSelectedIndexChange = { index ->
                                    onSortModeChange(if (index == 0) AppTrafficSortMode.USAGE else AppTrafficSortMode.NAME)
                                },
                            )
                            Spacer(modifier = Modifier.height(spacing.space8))
                            if (displayedApps.isEmpty()) {
                                EmptyDetailNotice(YumeTxt.TrafficStatistics.Section.EmptyApps)
                            } else {
                                val maxBytes =
                                    displayedApps
                                        .filterNot {
                                            it.appKey == TrafficStatisticsBuckets.UNATTRIBUTED_APP_KEY ||
                                                    it.appKey == AppIdentityResolver.UNKNOWN_APP_KEY
                                        }
                                        .maxOfOrNull(AppTrafficUsage::totalBytes)
                                        ?.coerceAtLeast(1L) ?: 1L

                                displayedApps.forEachIndexed { index, usage ->
                                    AppTrafficProgressRow(
                                        context = context,
                                        usage = usage,
                                        progress = (usage.totalBytes.toFloat() / maxBytes.toFloat()).coerceIn(0f, 1f),
                                    )
                                    if (index < displayedApps.lastIndex) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(start = UiDp.dp72, end = spacing.space16),
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

@Composable
private fun RecentRequestItemCard(
    record: RecentRequestRecord,
    onClick: () -> Unit,
) {
    val spacing = AppTheme.spacing
    val radii = AppTheme.radii
    val connection = record.connection
    val destination = remember(connection) { connection.extractDisplayDestination() }
    val totalTraffic = connection.upload + connection.download

    val displayHost = record.displayHost
    val effectiveHost = if (displayHost.isNotBlank()) displayHost else destination
    val sourceAppName = record.sourceAppName
    val sourcePackageName = record.sourcePackageName
    val subHost = record.subHostDetail

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(radii.radius16))
            .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(spacing.space12),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.space8)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.space2)) {
                    Text(
                        text = effectiveHost,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val appPart = if (sourcePackageName.isNullOrBlank() || sourcePackageName == sourceAppName) {
                        sourceAppName
                    } else {
                        "$sourceAppName · $sourcePackageName"
                    }
                    val subtitleText = if (!subHost.isNullOrBlank()) {
                        "$appPart · $subHost"
                    } else {
                        appPart
                    }
                    Text(
                        text = subtitleText,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.width(spacing.space12))
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(spacing.space2)) {
                    Text(
                        text = formatConnectionTime(connection.start),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = formatBytes(totalTraffic),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // Chips: Status, Outbound Groups, Nodes
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.space6),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusBadge(
                    text = if (record.isActive) "活跃" else "已结束",
                    isActive = record.isActive,
                )
                val topLevelGroupName = record.topLevelGroupName
                if (!topLevelGroupName.isNullOrBlank()) {
                    SmallBadge(text = topLevelGroupName, isPrimary = true)
                }
                val bottomNodeName = record.bottomNodeName
                if (!bottomNodeName.isNullOrBlank()) {
                    SmallBadge(text = bottomNodeName, isPrimary = false)
                }
            }
        }
    }
}

@Composable
private fun TargetSiteItemCard(record: TargetSiteRecord) {
    val spacing = AppTheme.spacing
    val radii = AppTheme.radii

    val serviceTag = record.serviceTag
    val connCount = record.connectionCount
    val timeText = formatMillisTime(record.lastSeenAt)
    val subtitleText = if (!serviceTag.isNullOrBlank()) {
        "$serviceTag · $timeText"
    } else {
        timeText
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(radii.radius16))
            .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(spacing.space12),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.space8)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.space2)) {
                    Text(
                        text = record.displayName,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = subtitleText,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Spacer(modifier = Modifier.width(spacing.space12))
                Text(
                    text = formatBytes(record.totalBytes),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.space8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SmallBadge(text = "上行: ${formatBytes(record.totalUpload)}", isPrimary = true)
                SmallBadge(text = "下行: ${formatBytes(record.totalDownload)}", isPrimary = false)
                if (connCount > 1) {
                    SmallBadge(text = "$connCount 个连接", isPrimary = false)
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(text: String, isActive: Boolean) {
    val radii = AppTheme.radii
    val bgColor = if (isActive) MiuixTheme.colorScheme.primary.copy(alpha = 0.15f) else MiuixTheme.colorScheme.surfaceVariant
    val textColor = if (isActive) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary

    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = textColor,
        fontSize = 11.sp,
        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(radii.radius8))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun SmallBadge(text: String, isPrimary: Boolean) {
    val radii = AppTheme.radii
    val bgColor = if (isPrimary) MiuixTheme.colorScheme.primary.copy(alpha = 0.1f) else MiuixTheme.colorScheme.surface.copy(alpha = 0.6f)
    val textColor = if (isPrimary) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface

    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = textColor,
        fontSize = 11.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(radii.radius8))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 2.dp),
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
            maxLines = 2,
        )
    }
}

private fun formatConnectionTime(start: String): String {
    if (start.isBlank()) return "--"
    return runCatching {
        val instant = OffsetDateTime.parse(start).toInstant()
        val now = Instant.now()
        val durationSeconds = java.time.Duration.between(instant, now).seconds
        when {
            durationSeconds < 60 -> "刚刚"
            durationSeconds < 3600 -> "${durationSeconds / 60} 分钟前"
            durationSeconds < 86400 -> "${durationSeconds / 3600} 小时前"
            else -> {
                val localDateTime = instant.atZone(ZoneId.systemDefault()).toLocalDateTime()
                localDateTime.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
            }
        }
    }.getOrDefault("--")
}

private fun formatMillisTime(timeMillis: Long): String {
    if (timeMillis <= 0L) return "--"
    return runCatching {
        val instant = Instant.ofEpochMilli(timeMillis)
        val now = Instant.now()
        val durationSeconds = java.time.Duration.between(instant, now).seconds
        when {
            durationSeconds < 60 -> "刚刚"
            durationSeconds < 3600 -> "${durationSeconds / 60} 分钟前"
            durationSeconds < 86400 -> "${durationSeconds / 3600} 小时前"
            else -> {
                val localDateTime = instant.atZone(ZoneId.systemDefault()).toLocalDateTime()
                localDateTime.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
            }
        }
    }.getOrDefault("--")
}

@Composable
private fun AppTrafficProgressRow(
    context: Context,
    usage: AppTrafficUsage,
    progress: Float,
) {
    val spacing = AppTheme.spacing
    val radii = AppTheme.radii

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.space16, vertical = spacing.space12),
        horizontalArrangement = Arrangement.spacedBy(spacing.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIconBadge(
            context = context,
            packageName = usage.packageName,
            appName = usage.appName,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(spacing.space6),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = usage.appName,
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = formatBytes(usage.totalBytes),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            // Track + fill
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(UiDp.dp6)
                    .clip(RoundedCornerShape(radii.full))
                    .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceAtLeast(0.02f))
                        .height(UiDp.dp6)
                        .clip(RoundedCornerShape(radii.full))
                        .background(MiuixTheme.colorScheme.primary)
                )
            }
        }
    }
}

@Composable
private fun AppIconBadge(context: Context, packageName: String?, appName: String) {
    val componentSizes = AppTheme.sizes
    val opacity = AppTheme.opacity
    val radii = AppTheme.radii

    val iconBitmap by
    produceState<ImageBitmap?>(initialValue = null, key1 = packageName) {
        value =
            withContext(Dispatchers.IO) {
                packageName
                    ?.takeIf { it.isNotBlank() }
                    ?.let { target ->
                        runCatching {
                            context.packageManager
                                .getApplicationIcon(target)
                                .toBitmap(width = 84, height = 84)
                                .asImageBitmap()
                        }
                            .getOrNull()
                    }
            }
    }

    val bitmap = iconBitmap
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
