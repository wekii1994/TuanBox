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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.yumeyucca.yumebox.common.util.formatBytes
import com.github.yumeyucca.yumebox.screen.connection.ConnectionDetailHolder
import com.github.yumeyucca.yumebox.common.util.toast
import com.github.yumeyucca.yumebox.data.model.StatisticsTimeRange
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
import org.koin.androidx.compose.koinViewModel
import com.github.yumeyucca.yumebox.core.locale.YumeTxt
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private enum class TrafficDetailSection(val title: String) {
    RECENT_REQUESTS("最近请求"),
    TARGET_SITES("目标站点"),
}

@Composable
fun TrafficStatisticsScreen(navigator: Navigator = LocalNavigator.current) {
    val viewModel = koinViewModel<TrafficStatisticsViewModel>()
    val scrollBehavior = MiuixScrollBehavior()
    val context = LocalContext.current
    val spacing = AppTheme.spacing
    val componentSizes = AppTheme.sizes

    val uiState by viewModel.uiState.collectAsState()
    var selectedDetailSection by rememberSaveable { mutableStateOf(TrafficDetailSection.RECENT_REQUESTS) }

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
                                navigator = navigator,
                                selectedSection = selectedDetailSection,
                                onSectionChange = { selectedDetailSection = it },
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
                            navigator = navigator,
                            selectedSection = selectedDetailSection,
                            onSectionChange = { selectedDetailSection = it },
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
    navigator: Navigator,
    selectedSection: TrafficDetailSection,
    onSectionChange: (TrafficDetailSection) -> Unit,
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
                            val maxBytes = remember(uiState.targetSites) {
                                uiState.targetSites.maxOfOrNull(TargetSiteRecord::totalBytes)?.coerceAtLeast(1L) ?: 1L
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(spacing.space10)) {
                                uiState.targetSites.forEachIndexed { index, site ->
                                    TargetSiteItemCard(
                                        record = site,
                                        rank = index + 1,
                                        maxBytes = maxBytes,
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

@Composable
private fun RecentRequestItemCard(
    record: RecentRequestRecord,
    onClick: () -> Unit,
) {
    val spacing = AppTheme.spacing
    val radii = AppTheme.radii
    val colors = AppTheme.colors
    val connection = record.connection
    val destination = remember(connection) { connection.extractDisplayDestination() }
    val totalTraffic = connection.upload + connection.download

    val displayHost = record.displayHost
    val effectiveHost = if (displayHost.isNotBlank()) displayHost else destination
    val sourceAppName = record.sourceAppName
    val sourcePackageName = record.sourcePackageName
    val subHost = record.subHostDetail

    val network = remember(connection.metadata) {
        connection.metadata["network"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: "TCP"
    }
    val protocolColor = when (network) {
        "TCP" -> colors.protocol.tcp
        "UDP" -> colors.protocol.udp
        "HTTP" -> colors.protocol.http
        "HTTPS" -> colors.protocol.https
        else -> colors.protocol.unknown
    }

    val rule = connection.rule
    val rulePayload = connection.rulePayload

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(radii.radius16))
            .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.space16, vertical = spacing.space12),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.space8)) {
            // Top Row: Effective Host + Time & Traffic
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(spacing.space2),
                ) {
                    Text(
                        text = effectiveHost,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
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

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(spacing.space2),
                ) {
                    Text(
                        text = formatBytes(totalTraffic),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(spacing.space4),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "↑${formatBytes(connection.upload)}",
                            style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Text(
                            text = "↓${formatBytes(connection.download)}",
                            style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            // Bottom Badges Row: Status, Network, Outbound Route, Rule, Time
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(spacing.space6),
                    verticalArrangement = Arrangement.spacedBy(spacing.space4),
                ) {
                    // Active Status Chip
                    StatusBadge(
                        text = if (record.isActive) "活跃" else "已结束",
                        isActive = record.isActive,
                    )

                    // Protocol Chip (TCP / UDP)
                    ProtocolChip(network = network, color = protocolColor)

                    // Outbound Route Chains (e.g. "PROXIES → 香港 01" or "DIRECT")
                    val topGroup = record.topLevelGroupName
                    val bottomNode = record.bottomNodeName
                    val routeLabel = when {
                        !topGroup.isNullOrBlank() && !bottomNode.isNullOrBlank() && topGroup != bottomNode ->
                            "$topGroup → $bottomNode"
                        !bottomNode.isNullOrBlank() -> bottomNode
                        !topGroup.isNullOrBlank() -> topGroup
                        else -> null
                    }
                    if (routeLabel != null) {
                        SmallBadge(text = routeLabel, isPrimary = false)
                    }

                    // Rule Tag (e.g. "Google" or "RuleSet")
                    val ruleLabel = when {
                        rulePayload.isNotBlank() -> rulePayload
                        rule.isNotBlank() -> rule
                        else -> null
                    }
                    if (ruleLabel != null) {
                        SmallBadge(text = "🎯 $ruleLabel", isPrimary = true)
                    }
                }

                Text(
                    text = formatConnectionTime(connection.start),
                    style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = spacing.space4),
                )
            }
        }
    }
}

@Composable
private fun TargetSiteItemCard(
    record: TargetSiteRecord,
    rank: Int,
    maxBytes: Long,
) {
    val spacing = AppTheme.spacing
    val radii = AppTheme.radii
    val serviceTag = record.serviceTag
    val connCount = record.connectionCount
    val timeText = formatMillisTime(record.lastSeenAt)
    val progress = (record.totalBytes.toFloat() / maxBytes.toFloat()).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(radii.radius16))
            .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(horizontal = spacing.space16, vertical = spacing.space12),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.space8)) {
            // Row 1: Rank Badge + Domain Name + Service Tag + Total Traffic
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(spacing.space8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RankBadge(rank = rank)

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(spacing.space2),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(spacing.space6),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = record.displayName,
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (!serviceTag.isNullOrBlank()) {
                                SmallBadge(text = serviceTag, isPrimary = true)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(spacing.space12))

                Text(
                    text = formatBytes(record.totalBytes),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }

            // Progress bar showing proportion of total / max site traffic
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(UiDp.dp4)
                    .clip(RoundedCornerShape(radii.full))
                    .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceAtLeast(0.02f))
                        .height(UiDp.dp4)
                        .clip(RoundedCornerShape(radii.full))
                        .background(MiuixTheme.colorScheme.primary),
                )
            }

            // Row 3: Upload/Download breakdown + Connection count + Time
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.space8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "↑ ${formatBytes(record.totalUpload)}",
                        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = "↓ ${formatBytes(record.totalDownload)}",
                        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }

                Text(
                    text = "$connCount 次请求 · $timeText",
                    style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
private fun RankBadge(rank: Int) {
    val radii = AppTheme.radii
    val isTop3 = rank <= 3
    val bgColor = if (isTop3) {
        MiuixTheme.colorScheme.primary.copy(alpha = 0.15f)
    } else {
        MiuixTheme.colorScheme.surface.copy(alpha = 0.6f)
    }
    val textColor = if (isTop3) {
        MiuixTheme.colorScheme.primary
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    }

    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(radii.radius8))
            .background(bgColor),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$rank",
            style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp),
            fontWeight = if (isTop3) FontWeight.Bold else FontWeight.Medium,
            color = textColor,
        )
    }
}

@Composable
private fun ProtocolChip(network: String, color: Color) {
    val radii = AppTheme.radii
    Text(
        text = network,
        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 10.sp),
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(radii.radius8))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun StatusBadge(text: String, isActive: Boolean) {
    val radii = AppTheme.radii
    val colors = AppTheme.colors
    val activeColor = colors.connection.chainActive
    val bgColor = if (isActive) activeColor.copy(alpha = 0.12f) else MiuixTheme.colorScheme.surface.copy(alpha = 0.6f)
    val textColor = if (isActive) activeColor else MiuixTheme.colorScheme.onSurfaceVariantSummary

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(radii.radius8))
            .background(bgColor)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        if (isActive) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(radii.full))
                    .background(activeColor),
            )
        }
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1.copy(fontSize = 10.sp),
            color = textColor,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun SmallBadge(text: String, isPrimary: Boolean) {
    val radii = AppTheme.radii
    val bgColor = if (isPrimary) MiuixTheme.colorScheme.primary.copy(alpha = 0.1f) else MiuixTheme.colorScheme.surface.copy(alpha = 0.6f)
    val textColor = if (isPrimary) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary

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

