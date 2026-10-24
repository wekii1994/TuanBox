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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.yumeyucca.yumebox.core.domain.ConnectionHistoryManager
import com.github.yumeyucca.yumebox.core.model.ConnectionInfo
import com.github.yumeyucca.yumebox.data.controller.AppIdentityResolver
import com.github.yumeyucca.yumebox.data.model.*
import com.github.yumeyucca.yumebox.data.store.TrafficStatisticsStore
import com.github.yumeyucca.yumebox.presentation.component.BarChartItem
import com.github.yumeyucca.yumebox.runtime.client.access.RuntimeAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import com.github.yumeyucca.yumebox.core.locale.YumeTxt
import java.time.OffsetDateTime
import java.util.*

data class RecentRequestRecord(
    val connection: ConnectionInfo,
    val isActive: Boolean,
    val topLevelGroupName: String?,
    val bottomNodeName: String?,
    val sourceAppName: String,
    val sourcePackageName: String?,
)

data class TargetSiteRecord(
    val siteKey: String,
    val displayName: String,
    val totalUpload: Long,
    val totalDownload: Long,
    val lastSeenAt: Long,
) {
    val totalBytes: Long
        get() = totalUpload + totalDownload
}

data class TrafficStatisticsUiState(
    val selectedTimeRange: StatisticsTimeRange = StatisticsTimeRange.TODAY,
    val summary: DailyTrafficSummary = DailyTrafficSummary.empty,
    val topApps: List<AppTrafficUsage> = emptyList(),
    val chartBars: List<BarChartItem> = emptyList(),
    val chartAverage: Long = 0L,
    val todayTotal: Long = 0L,
    val weekTotal: Long = 0L,
    val yesterdayTotal: Long = 0L,
    val trafficDifference: Long = 0L,
    val selectedBarIndex: Int = -1,
    val todayTimeContext: String = "",
    val recentRequests: List<RecentRequestRecord> = emptyList(),
    val targetSites: List<TargetSiteRecord> = emptyList(),
)

class TrafficStatisticsViewModel(
    private val trafficStatisticsStore: TrafficStatisticsStore,
    private val context: Context,
) : ViewModel() {
    private val appIdentityResolver = AppIdentityResolver(context)
    private val selectedTimeRange = MutableStateFlow(StatisticsTimeRange.TODAY)
    private val selectedBarIndex = MutableStateFlow(-1)

    private val liveConnectionsFlow: Flow<Pair<List<ConnectionInfo>, List<ConnectionInfo>>> =
        flow {
            while (true) {
                val active =
                    runCatching {
                        RuntimeAccess.connect(context)
                        val snapshot = RuntimeAccess.core().queryConnections()
                        ConnectionHistoryManager.updateConnections(snapshot.connections)
                        snapshot.connections
                    }
                        .getOrDefault(emptyList())
                val closed = ConnectionHistoryManager.getClosedConnections()
                emit(Pair(active, closed))
                delay(2000L)
            }
        }.flowOn(Dispatchers.IO)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<TrafficStatisticsUiState> =
        combine(
            selectedTimeRange,
            selectedBarIndex,
            liveConnectionsFlow,
            trafficStatisticsStore.getDailyTotalsFlow(StatisticsTimeRange.WEEK),
            trafficStatisticsStore.getTodayHourlyTotalsFlow(),
        ) { range, barIndex, (activeConns, closedConns), weekDailyTotals, hourlyTotals ->
            Triple(range, barIndex, Pair(activeConns, closedConns)) to Pair(weekDailyTotals, hourlyTotals)
        }.flatMapLatest { (firstPart, secondPart) ->
            val (range, barIndex, connectionsPair) = firstPart
            val (weekDailyTotals, hourlyTotals) = secondPart
            val (activeConns, closedConns) = connectionsPair

            trafficStatisticsStore.getAppUsagesFlow(range).map { topApps ->
                buildUiState(
                    range = range,
                    barIndex = barIndex,
                    topApps = topApps,
                    weekDailyTotals = weekDailyTotals,
                    hourlyTotals = hourlyTotals,
                    activeConns = activeConns,
                    closedConns = closedConns,
                )
            }
        }.distinctUntilChanged().stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            TrafficStatisticsUiState(),
        )

    fun setTimeRange(range: StatisticsTimeRange) {
        selectedTimeRange.value = range
        selectedBarIndex.value = -1
    }

    fun setSelectedBarIndex(index: Int) {
        selectedBarIndex.value = index
    }

    fun clearAllStatistics() {
        viewModelScope.launch { trafficStatisticsStore.clearAll() }
    }

    private fun buildUiState(
        range: StatisticsTimeRange,
        barIndex: Int,
        topApps: List<AppTrafficUsage>,
        weekDailyTotals: List<DailyTrafficSummary>,
        hourlyTotals: List<HourlyTrafficSummary>,
        activeConns: List<ConnectionInfo>,
        closedConns: List<ConnectionInfo>,
    ): TrafficStatisticsUiState {
        val totalUpload = topApps.sumOf(AppTrafficUsage::totalUpload)
        val totalDownload = topApps.sumOf(AppTrafficUsage::totalDownload)
        val summary =
            DailyTrafficSummary(
                dateMillis = range.days.toLong(),
                totalUpload = totalUpload,
                totalDownload = totalDownload,
            )
        val bars =
            when (range) {
                StatisticsTimeRange.TODAY -> buildTodayBars(hourlyTotals)
                StatisticsTimeRange.WEEK -> buildWeekBars(weekDailyTotals)
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

        val todayTotal = weekDailyTotals.firstOrNull { it.dateMillis == todayKey }?.total ?: (totalUpload + totalDownload)
        val yesterdayTotal = weekDailyTotals.firstOrNull { it.dateMillis == yesterdayKey }?.total ?: 0L
        val trafficDiff = todayTotal - yesterdayTotal
        val weekTotal = weekDailyTotals.sumOf(DailyTrafficSummary::total)

        val activeIds = activeConns.map(ConnectionInfo::id).toSet()
        val allConnections = (activeConns + closedConns.filterNot { it.id in activeIds })
            .sortedByDescending { parseConnectionStartMillis(it.start) }

        val recentRequests = allConnections.take(30).map { conn ->
            val app = appIdentityResolver.resolve(conn.metadata)
            val chains = conn.chains.map(String::trim).filter(String::isNotEmpty)
            RecentRequestRecord(
                connection = conn,
                isActive = conn.id in activeIds,
                topLevelGroupName = chains.firstOrNull(),
                bottomNodeName = chains.lastOrNull() ?: if (chains.isEmpty()) "DIRECT" else null,
                sourceAppName = app.appName,
                sourcePackageName = app.packageName,
            )
        }

        val targetSites = allConnections
            .mapNotNull { conn ->
                val site = conn.extractTargetSite() ?: return@mapNotNull null
                Triple(site, conn.upload, conn.download) to parseConnectionStartMillis(conn.start)
            }
            .groupBy { it.first.first }
            .map { (siteKey, entries) ->
                val totalUp = entries.sumOf { it.first.second }
                val totalDown = entries.sumOf { it.first.third }
                val lastSeen = entries.maxOfOrNull { it.second } ?: 0L
                TargetSiteRecord(
                    siteKey = siteKey,
                    displayName = siteKey,
                    totalUpload = totalUp,
                    totalDownload = totalDown,
                    lastSeenAt = lastSeen,
                )
            }
            .sortedWith(
                compareByDescending<TargetSiteRecord> { it.totalBytes }
                    .thenByDescending { it.lastSeenAt }
            )
            .take(15)

        return TrafficStatisticsUiState(
            selectedTimeRange = range,
            summary = summary,
            topApps = mergeSystemTraffic(topApps),
            chartBars = bars,
            chartAverage = average,
            todayTotal = todayTotal,
            weekTotal = weekTotal,
            yesterdayTotal = yesterdayTotal,
            trafficDifference = trafficDiff,
            selectedBarIndex = barIndex,
            todayTimeContext = getTodayTimeContext(),
            recentRequests = recentRequests,
            targetSites = targetSites,
        )
    }

    private fun getTodayTimeContext(): String {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        val timeLabel = String.format(Locale.ROOT, "%02d:%02d", hour, minute)
        val tz = calendar.timeZone.id
        val period =
            when (hour) {
                in 0..5 -> "凌晨"
                in 6..11 -> "上午"
                in 12..13 -> "中午"
                in 14..17 -> "下午"
                else -> "晚上"
            }
        return "$timeLabel $tz · $period"
    }

    private fun parseConnectionStartMillis(start: String): Long {
        if (start.isBlank()) return 0L
        return runCatching { OffsetDateTime.parse(start).toInstant().toEpochMilli() }
            .getOrDefault(0L)
    }

    private fun mergeSystemTraffic(apps: List<AppTrafficUsage>): List<AppTrafficUsage> {
        val systemApps = apps.filter {
            it.appKey == AppIdentityResolver.UNKNOWN_APP_KEY ||
                    it.appKey == TrafficStatisticsBuckets.UNATTRIBUTED_APP_KEY
        }
        if (systemApps.isEmpty()) return apps

        val regularApps = apps.filterNot {
            it.appKey == AppIdentityResolver.UNKNOWN_APP_KEY ||
                    it.appKey == TrafficStatisticsBuckets.UNATTRIBUTED_APP_KEY
        }
        val systemTraffic =
            AppTrafficUsage(
                appKey = TrafficStatisticsBuckets.UNATTRIBUTED_APP_KEY,
                packageName = ANDROID_SYSTEM_PACKAGE,
                appName = YumeTxt.TrafficStatistics.Section.SystemTraffic,
                totalUpload = systemApps.sumOf(AppTrafficUsage::totalUpload),
                totalDownload = systemApps.sumOf(AppTrafficUsage::totalDownload),
                lastActiveAt = systemApps.maxOfOrNull(AppTrafficUsage::lastActiveAt) ?: 0L,
            )
        return regularApps + systemTraffic
    }

    private fun buildTodayBars(hourlyTotals: List<HourlyTrafficSummary>): List<BarChartItem> {
        val totalsByHour = hourlyTotals.associateBy { it.hourStartMillis }
        val start = startOfDay(System.currentTimeMillis())
        val calendar = Calendar.getInstance().apply { timeInMillis = start }
        return (0 until HOURS_PER_DAY).map { hour ->
            calendar.set(Calendar.HOUR_OF_DAY, hour)
            BarChartItem(
                label = if (hour in LABELED_HOURS) "%02d:00".format(hour) else "",
                value = totalsByHour[calendar.timeInMillis]?.total ?: 0L,
            )
        }
    }

    private fun buildWeekBars(dailyTotals: List<DailyTrafficSummary>): List<BarChartItem> {
        val byDay = dailyTotals.associateBy { it.dateMillis }
        val today =
            Calendar.getInstance().apply { timeInMillis = startOfDay(System.currentTimeMillis()) }
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

    companion object {
        private const val ANDROID_SYSTEM_PACKAGE = "android"
        private const val HOURS_PER_DAY = 24
        private val LABELED_HOURS = setOf(0, 6, 12, 18, 23)
    }
}

fun ConnectionInfo.extractDisplayDestination(): String {
    val host = metadata["host"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val destIp = metadata["destinationIP"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val destPort = metadata["destinationPort"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val target = host.ifBlank { destIp }
    return if (destPort.isNotBlank() && !target.contains(":")) "$target:$destPort" else target.ifBlank { "Unknown" }
}

fun ConnectionInfo.extractTargetSite(): String? {
    val host = metadata["host"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (host.isNotBlank()) return host
    val destIp = metadata["destinationIP"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (destIp.isNotBlank()) return destIp
    return null
}
