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

package com.github.yumeyucca.yumebox.feature.meta.presentation.viewmodel

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.yumeyucca.yumebox.core.domain.ConnectionHistoryManager
import com.github.yumeyucca.yumebox.core.model.ConnectionInfo
import com.github.yumeyucca.yumebox.data.controller.AppIdentityResolver
import com.github.yumeyucca.yumebox.data.model.*
import com.github.yumeyucca.yumebox.data.store.TrafficStatisticsStore
import com.github.yumeyucca.yumebox.presentation.component.BarChartItem
import com.github.yumeyucca.yumebox.runtime.client.access.RuntimeAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import com.github.yumeyucca.yumebox.data.controller.AppTrafficStatisticsCollector
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import com.github.yumeyucca.yumebox.core.locale.YumeTxt
import java.time.OffsetDateTime
import java.util.*

data class RecentRequestRecord(
    val connection: ConnectionInfo,
    val isActive: Boolean,
    val exitNode: String,
    val ruleLabel: String,
    val sourceAppName: String?,
    val sourcePackageName: String?,
    val serviceTag: String?,
    val displayHost: String,
    val subDetail: String?,
    val startMillis: Long = 0L,
)

@Immutable
data class TargetSiteRecord(
    val siteKey: String,
    val displayName: String,
    val primaryDomain: String,
    val totalUpload: Long,
    val totalDownload: Long,
    val lastSeenAt: Long,
    val serviceTag: String? = null,
    val connectionCount: Int = 1,
    val isIp: Boolean = false,
) {
    val totalBytes: Long
        get() = totalUpload + totalDownload
}

data class TrafficStatisticsUiState(
    val selectedTimeRange: StatisticsTimeRange = StatisticsTimeRange.TODAY,
    val summary: TrafficTotals = TrafficTotals.empty,
    val topApps: List<AppTrafficUsage> = emptyList(),
    val chartBars: List<BarChartItem> = emptyList(),
    val chartAverage: Long = 0L,
    val todayTotal: Long = 0L,
    val weekTotal: Long = 0L,
    val yesterdayTotal: Long = 0L,
    val trafficDifference: Long = 0L,
    val selectedBarIndex: Int = -1,
    val todayTimeContext: String = "",
    val connectionError: String? = null,
    val recentRequests: List<RecentRequestRecord> = emptyList(),
    val targetSites: List<TargetSiteRecord> = emptyList(),
)

/**
 * Transfer totals of the selected [StatisticsTimeRange]. The range is the period, so this type
 * carries no timestamp at all: a millis-named field must never hold a day count.
 */
@Immutable
data class TrafficTotals(
    val totalUpload: Long = 0L,
    val totalDownload: Long = 0L,
) {
    val total: Long
        get() = totalUpload + totalDownload

    companion object {
        val empty = TrafficTotals()
    }
}

private data class ConnectionDetails(
    val error: String? = null,
    val recentRequests: List<RecentRequestRecord> = emptyList(),
    val targetSites: List<TargetSiteRecord> = emptyList(),
)

private data class ParsedConnection(
    val connection: ConnectionInfo,
    val startMillis: Long,
    val displayDestination: String,
    val normalizedDomain: NormalizedDomain,
)

class TrafficStatisticsViewModel(
    private val trafficStatisticsStore: TrafficStatisticsStore,
    context: Context,
    private val statisticsCollector: AppTrafficStatisticsCollector,
) : ViewModel() {
    private val context = context.applicationContext
    private val appIdentityResolver = AppIdentityResolver(this.context)
    private val selectedTimeRange = MutableStateFlow(StatisticsTimeRange.TODAY)
    // These caches are confined to the one sequential connectionDetailsFlow collector.
    private val parsedHosts = object : LinkedHashMap<String, NormalizedDomain>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, NormalizedDomain>?): Boolean = size > 1024
    }
    private val parsedStarts = object : LinkedHashMap<String, Long>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > 1024
    }
    private val refreshTrigger = MutableStateFlow(0L)

    private val messages = Channel<String>(Channel.BUFFERED)
    val notices = messages.receiveAsFlow()
    private var isClearing = false

    private data class LiveConnections(
        val active: List<ConnectionInfo> = emptyList(),
        val closed: List<ConnectionInfo> = emptyList(),
        val error: String? = null,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val liveConnectionsFlow = refreshTrigger.flatMapLatest {
        resilientPolling(
            initial = LiveConnections(closed = ConnectionHistoryManager.getClosedConnections()),
            query = {
                RuntimeAccess.connect(context)
                val active = RuntimeAccess.core().queryConnections().connections
                ConnectionHistoryManager.updateConnections(active)
                LiveConnections(active, ConnectionHistoryManager.getClosedConnections())
            },
            stale = { it.copy(error = "连接数据暂不可用，正在重试；以下为最近取得的快照") },
        )
    }.flowOn(Dispatchers.IO)

    // Recreate date-bound DAO subscriptions at midnight and after a timezone change.
    private val calendarWindow = flow {
        while (currentCoroutineContext().isActive) {
            emit(startOfDay(System.currentTimeMillis()) to TimeZone.getDefault().id)
            delay(30_000L)
        }
    }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val storedTotals = calendarWindow.flatMapLatest {
        combine(
            trafficStatisticsStore.getDailyTotalsFlow(StatisticsTimeRange.WEEK),
            trafficStatisticsStore.getTodayHourlyTotalsFlow(),
        ) { days, hours -> days to hours }
    }

    private val connectionDetailsFlow = liveConnectionsFlow
        .conflate()
        .map { snapshot ->
            buildConnectionDetails(snapshot.active, snapshot.closed).copy(error = snapshot.error)
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    val overviewState: StateFlow<TrafficStatisticsUiState> = combine(
        selectedTimeRange, storedTotals,
    ) { range, (days, hours) ->
        buildUiState(range, -1, days, days, hours, emptyList(), emptyList())
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TrafficStatisticsUiState())

    val uiState: StateFlow<TrafficStatisticsUiState> = combine(
        overviewState, connectionDetailsFlow,
    ) { overview, details ->
        overview.copy(
            recentRequests = details.recentRequests,
            targetSites = details.targetSites,
            connectionError = details.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TrafficStatisticsUiState())

    fun setTimeRange(range: StatisticsTimeRange) { selectedTimeRange.value = range }

    fun clearAllStatistics() {
        if (isClearing) return
        isClearing = true
        viewModelScope.launch {
            try {
                statisticsCollector.clearStatistics()
                ConnectionHistoryManager.clear()
                refreshTrigger.update { it + 1L }
                messages.send("已清空保存的统计与关闭记录；活跃连接仍会显示")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                messages.send("清空失败，请重试")
            } finally {
                isClearing = false
            }
        }
    }

    private suspend fun buildConnectionDetails(
        activeConns: List<ConnectionInfo>,
        closedConns: List<ConnectionInfo>,
    ): ConnectionDetails {
        val activeIds = activeConns.map(ConnectionInfo::id).toSet()
        val allWithTimestamp = (activeConns + closedConns.filterNot { it.id in activeIds })
            .map { conn -> conn to parsedStarts.getOrPut(conn.start) { parseConnectionStartMillis(conn.start) } }
            .sortedWith(compareByDescending<Pair<ConnectionInfo, Long>> { it.second }.thenBy { it.first.id })

        val parsedConnections = allWithTimestamp.map { (conn, startMillis) ->
            val dest = conn.extractDisplayDestination()
            ParsedConnection(
                connection = conn,
                startMillis = startMillis,
                displayDestination = dest,
                normalizedDomain = parsedHosts.getOrPut(dest) { DomainClassifier.parse(dest) },
            )
        }

        val recentRequests = parsedConnections.take(30).map { parsedConn ->
            val conn = parsedConn.connection
            val resolvedApp = withContext(Dispatchers.IO) {
                try {
                    appIdentityResolver.resolve(conn.metadata)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    com.github.yumeyucca.yumebox.data.controller.AppIdentity(
                        appKey = AppIdentityResolver.UNKNOWN_APP_KEY, appName = "未知应用",
                    )
                }
            }
            val hasValidApp = resolvedApp.packageName != null &&
                    resolvedApp.appName.isNotBlank() &&
                    resolvedApp.appName != "未知应用" &&
                    resolvedApp.appKey != AppIdentityResolver.UNKNOWN_APP_KEY

            val sourceAppName = if (hasValidApp) resolvedApp.appName else null
            val dest = parsedConn.displayDestination
            val parsed = parsedConn.normalizedDomain
            val port = (conn.metadata["destinationPort"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val portSuffix = if (port.isNotBlank() && !dest.contains(':')) ":$port" else ""

            val displayHost: String
            val subDetail: String?

            if (parsed.isCdnSharded) {
                val hostOnly = dest.substringBefore(':')
                val suffix = ".${parsed.rootDomain}"
                val prefix = if (hostOnly.endsWith(suffix, ignoreCase = true)) {
                    hostOnly.dropLast(suffix.length)
                } else {
                    hostOnly
                }
                displayHost = "${parsed.rootDomain}$portSuffix"
                subDetail = prefix
            } else {
                displayHost = if (portSuffix.isNotBlank() && !dest.endsWith(portSuffix)) "$dest$portSuffix" else dest
                val destIp = (conn.metadata["destinationIP"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                subDetail = if (destIp.isNotBlank() && destIp != dest) destIp else null
            }

            val rawExit = conn.chains.lastOrNull()?.trim()
            val exitNode = when {
                rawExit.isNullOrBlank() -> "未知出站"
                rawExit.equals("DIRECT", ignoreCase = true) -> "直连"
                else -> rawExit
            }

            val rawRule = conn.rulePayload.takeIf(String::isNotBlank) ?: conn.rule.takeIf(String::isNotBlank) ?: "MATCH"
            val ruleLabel = rawRule.replace("RuleSet(", "").replace("GeoSite(", "").replace(")", "").trim()

            RecentRequestRecord(
                connection = conn,
                isActive = conn.id in activeIds,
                exitNode = exitNode,
                ruleLabel = ruleLabel,
                sourceAppName = sourceAppName,
                sourcePackageName = resolvedApp.packageName,
                serviceTag = parsed.brandEntity.serviceTag ?: parsed.serviceTag,
                displayHost = displayHost,
                subDetail = subDetail,
                startMillis = parsedConn.startMillis,
            )
        }

        // One accumulator per service; avoid per-connection Triple/Pair and group lists.
        data class SiteAccumulator(
            val brand: BrandEntity,
            var upload: Long = 0L,
            var download: Long = 0L,
            var lastStartedAt: Long = 0L,
            var count: Int = 0,
        )
        val sites = linkedMapOf<String, SiteAccumulator>()
        for (parsed in parsedConnections) {
            val conn = parsed.connection
            if (conn.extractTargetSite() == null) continue
            val brand = parsed.normalizedDomain.brandEntity
            val entry = sites.getOrPut(brand.entityKey) { SiteAccumulator(brand) }
            entry.upload += conn.upload
            entry.download += conn.download
            entry.lastStartedAt = maxOf(entry.lastStartedAt, parsed.startMillis)
            entry.count++
        }
        val targetSites = sites.map { (key, entry) ->
            TargetSiteRecord(
                siteKey = key,
                displayName = entry.brand.displayName,
                primaryDomain = entry.brand.primaryDomain,
                totalUpload = entry.upload,
                totalDownload = entry.download,
                lastSeenAt = entry.lastStartedAt,
                serviceTag = entry.brand.serviceTag,
                connectionCount = entry.count,
                isIp = entry.brand.isIp,
            )
        }.sortedWith(
            compareByDescending<TargetSiteRecord> { it.totalBytes }
                .thenByDescending { it.lastSeenAt }
                .thenBy { it.siteKey }
        ).take(20)
        return ConnectionDetails(recentRequests = recentRequests, targetSites = targetSites)
    }

    private fun buildUiState(
        range: StatisticsTimeRange,
        selectedBarIndex: Int,
        dailyTotals: List<DailyTrafficSummary>,
        weekDailyTotals: List<DailyTrafficSummary>,
        hourlyTotals: List<HourlyTrafficSummary>,
        recentRequests: List<RecentRequestRecord>,
        targetSites: List<TargetSiteRecord>,
    ): TrafficStatisticsUiState {
        val totalUpload = when (range) {
            StatisticsTimeRange.TODAY -> hourlyTotals.sumOf(HourlyTrafficSummary::totalUpload)
            StatisticsTimeRange.WEEK -> dailyTotals.sumOf(DailyTrafficSummary::totalUpload)
        }
        val totalDownload = when (range) {
            StatisticsTimeRange.TODAY -> hourlyTotals.sumOf(HourlyTrafficSummary::totalDownload)
            StatisticsTimeRange.WEEK -> dailyTotals.sumOf(DailyTrafficSummary::totalDownload)
        }

        val bars = when (range) {
            StatisticsTimeRange.TODAY -> buildTodayBars(hourlyTotals)
            StatisticsTimeRange.WEEK -> buildWeekBars(dailyTotals)
        }
        val average =
            if (range == StatisticsTimeRange.WEEK && bars.isNotEmpty()) {
                bars.sumOf(BarChartItem::value) / bars.size
            } else {
                0L
            }

        val todayKey = startOfDay(System.currentTimeMillis())
        val calYesterday = Calendar.getInstance().apply {
            timeInMillis = todayKey
            add(Calendar.DAY_OF_YEAR, -1)
        }
        val yesterdayKey = calYesterday.timeInMillis

        val hourlyTotalSum = hourlyTotals.sumOf(HourlyTrafficSummary::total)
        val todayTotal = if (hourlyTotals.isNotEmpty() || range == StatisticsTimeRange.TODAY) {
            hourlyTotalSum
        } else {
            weekDailyTotals.firstOrNull { it.dateMillis == todayKey }?.total ?: hourlyTotalSum
        }
        val yesterdayTotal = weekDailyTotals.firstOrNull { it.dateMillis == yesterdayKey }?.total ?: 0L
        val trafficDiff = todayTotal - yesterdayTotal
        val weekTotal = weekDailyTotals.sumOf(DailyTrafficSummary::total)

        return TrafficStatisticsUiState(
            selectedTimeRange = range,
            summary = TrafficTotals(
                totalUpload = totalUpload,
                totalDownload = totalDownload,
            ),
            chartBars = bars,
            chartAverage = average,
            todayTotal = todayTotal,
            weekTotal = weekTotal,
            yesterdayTotal = yesterdayTotal,
            trafficDifference = trafficDiff,
            selectedBarIndex = selectedBarIndex,
            todayTimeContext = buildTodayTimeContext(),
            recentRequests = recentRequests,
            targetSites = targetSites,
        )
    }

    private fun buildTodayBars(hourlyTotals: List<HourlyTrafficSummary>): List<BarChartItem> {
        val totalsByHour = hourlyTotals.associateBy { it.hourStartMillis }
        val start = startOfDay(System.currentTimeMillis())
        val calendar = Calendar.getInstance().apply { timeInMillis = start }
        val end = (calendar.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
        return buildList {
            while (calendar.timeInMillis < end) {
                val hour = calendar.get(Calendar.HOUR_OF_DAY)
                add(BarChartItem(
                    label = if (hour in LABELED_HOURS) "%02d:00".format(hour) else "",
                    value = totalsByHour[calendar.timeInMillis]?.total ?: 0L,
                ))
                calendar.add(Calendar.HOUR_OF_DAY, 1)
            }
        }
    }

    private fun buildWeekBars(dailyTotals: List<DailyTrafficSummary>): List<BarChartItem> {
        val byDay = dailyTotals.associateBy { it.dateMillis }
        val today = Calendar.getInstance().apply { timeInMillis = startOfDay(System.currentTimeMillis()) }
        return (6 downTo 0).map { daysAgo ->
            val day = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -daysAgo) }
            val dayStart = day.timeInMillis
            val total = byDay[dayStart]?.total ?: 0L
            BarChartItem(
                label = weekdayLabel(dayStart, isToday = daysAgo == 0),
                value = total,
                isHighlighted = daysAgo == 0,
            )
        }
    }

    private fun weekdayLabel(dayStartMillis: Long, isToday: Boolean): String {
        if (isToday) return YumeTxt.TrafficStatistics.Weekday.Today
        val calendar = Calendar.getInstance().apply { timeInMillis = dayStartMillis }
        return when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> YumeTxt.TrafficStatistics.Weekday.Mon
            Calendar.TUESDAY -> YumeTxt.TrafficStatistics.Weekday.Tue
            Calendar.WEDNESDAY -> YumeTxt.TrafficStatistics.Weekday.Wed
            Calendar.THURSDAY -> YumeTxt.TrafficStatistics.Weekday.Thu
            Calendar.FRIDAY -> YumeTxt.TrafficStatistics.Weekday.Fri
            Calendar.SATURDAY -> YumeTxt.TrafficStatistics.Weekday.Sat
            else -> YumeTxt.TrafficStatistics.Weekday.Sun
        }
    }

    private fun startOfDay(timestamp: Long): Long {
        val calendar = Calendar.getInstance().apply { timeInMillis = timestamp }
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private fun buildTodayTimeContext(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 0..5 -> "凌晨时段"
            in 6..11 -> "今日上午"
            in 12..13 -> "今日中午"
            in 14..17 -> "今日下午"
            else -> "今日晚间"
        }
    }

    companion object {
        private val LABELED_HOURS = setOf(0, 6, 12, 18, 23)
    }
}

fun parseConnectionStartMillis(isoString: String): Long =
    runCatching {
        OffsetDateTime.parse(isoString).toInstant().toEpochMilli()
    }.getOrDefault(0L)

fun ConnectionInfo.extractDisplayDestination(): String {
    val host = (metadata["host"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    if (host.isNotBlank()) return host
    val destIp = (metadata["destinationIP"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    if (destIp.isNotBlank()) {
        val port = (metadata["destinationPort"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        return if (destIp.count { it == ':' } >= 2) {
            if (port.isNotBlank()) "[$destIp]:$port" else "[$destIp]"
        } else {
            if (port.isNotBlank()) "$destIp:$port" else destIp
        }
    }
    return "未知目标"
}

fun ConnectionInfo.extractTargetSite(): String? {
    val host = (metadata["host"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    if (host.isNotBlank()) return host
    val destIp = (metadata["destinationIP"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    if (destIp.isNotBlank()) return destIp
    return null
}
