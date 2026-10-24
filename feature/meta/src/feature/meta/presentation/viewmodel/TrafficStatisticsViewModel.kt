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
    val displayHost: String = "",
    val subHostDetail: String? = null,
)

data class TargetSiteRecord(
    val siteKey: String,
    val displayName: String,
    val totalUpload: Long,
    val totalDownload: Long,
    val lastSeenAt: Long,
    val serviceTag: String? = null,
    val connectionCount: Int = 1,
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
            val dest = conn.extractDisplayDestination()
            val parsed = DomainClassifier.parse(dest)
            val port = conn.metadata["destinationPort"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val portSuffix = if (port.isNotBlank() && !parsed.rootDomain.contains(':')) ":$port" else ""

            val displayHost: String
            val subHostDetail: String?

            if (parsed.isCdnSharded) {
                val prefix = dest.substringBefore(".${parsed.rootDomain}")
                displayHost = "${parsed.rootDomain}$portSuffix"
                subHostDetail = if (parsed.serviceTag != null) {
                    "${parsed.serviceTag} · $prefix"
                } else {
                    prefix
                }
            } else {
                displayHost = dest
                subHostDetail = parsed.serviceTag
            }

            RecentRequestRecord(
                connection = conn,
                isActive = conn.id in activeIds,
                topLevelGroupName = chains.firstOrNull(),
                bottomNodeName = chains.lastOrNull() ?: if (chains.isEmpty()) "DIRECT" else null,
                sourceAppName = app.appName,
                sourcePackageName = app.packageName,
                displayHost = displayHost,
                subHostDetail = subHostDetail,
            )
        }

        val targetSites = allConnections
            .mapNotNull { conn ->
                val site = conn.extractTargetSite() ?: return@mapNotNull null
                val parsed = DomainClassifier.parse(site)
                Triple(parsed, conn.upload, conn.download) to parseConnectionStartMillis(conn.start)
            }
            .groupBy { it.first.first.rootDomain }
            .map { (rootDomain, entries) ->
                val totalUp = entries.sumOf { it.first.second }
                val totalDown = entries.sumOf { it.first.third }
                val lastSeen = entries.maxOfOrNull { it.second } ?: 0L
                val firstParsed = entries.first().first.first
                TargetSiteRecord(
                    siteKey = rootDomain,
                    displayName = rootDomain,
                    totalUpload = totalUp,
                    totalDownload = totalDown,
                    lastSeenAt = lastSeen,
                    serviceTag = firstParsed.serviceTag,
                    connectionCount = entries.size,
                )
            }
            .sortedWith(
                compareByDescending<TargetSiteRecord> { it.totalBytes }
                    .thenByDescending { it.lastSeenAt }
            )
            .take(20)

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

object DomainClassifier {
    private val MULTI_PART_TLDS = setOf(
        "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn",
        "co.uk", "org.uk", "me.uk", "ltd.uk", "plc.uk",
        "co.jp", "ne.jp", "or.jp", "go.jp", "ac.jp",
        "co.kr", "ne.kr", "or.kr", "re.kr",
        "com.hk", "org.hk", "edu.hk", "gov.hk", "net.hk",
        "com.tw", "org.tw", "edu.tw", "gov.tw", "net.tw",
        "com.sg", "org.sg", "edu.sg", "gov.sg",
        "com.au", "net.au", "org.au", "edu.au",
        "com.br", "net.br", "org.br",
        "com.mx", "org.mx",
        "com.ru", "net.ru", "org.ru"
    )

    private val KNOWN_SERVICES = mapOf(
        "googlevideo.com" to "YouTube 视频流",
        "youtube.com" to "YouTube",
        "youtu.be" to "YouTube",
        "ytimg.com" to "YouTube 静态资源",
        "googleapis.com" to "Google API",
        "google.com" to "Google",
        "googleusercontent.com" to "Google 资源",
        "gstatic.com" to "Google 静态资源",
        "1e100.net" to "Google 骨干网",
        "gvt1.com" to "Google 视频服务",
        "gvt2.com" to "Google 视频服务",
        "ggpht.com" to "Google 相册",
        "android.com" to "Android 系统",
        "github.com" to "GitHub",
        "githubusercontent.com" to "GitHub 资源",
        "github.io" to "GitHub Pages",
        "twitter.com" to "X (Twitter)",
        "x.com" to "X (Twitter)",
        "twimg.com" to "X 媒体资源",
        "t.co" to "X 短链接",
        "telegram.org" to "Telegram",
        "t.me" to "Telegram",
        "telegram.me" to "Telegram",
        "telesco.pe" to "Telegram 视频",
        "instagram.com" to "Instagram",
        "cdninstagram.com" to "Instagram 媒体",
        "facebook.com" to "Facebook",
        "fbcdn.net" to "Facebook 媒体",
        "messenger.com" to "Messenger",
        "whatsapp.com" to "WhatsApp",
        "whatsapp.net" to "WhatsApp",
        "netflix.com" to "Netflix",
        "nflxvideo.net" to "Netflix 视频",
        "nflximg.net" to "Netflix 媒体",
        "spotify.com" to "Spotify",
        "scdn.co" to "Spotify 媒体",
        "apple.com" to "Apple 服务",
        "icloud.com" to "iCloud",
        "mzstatic.com" to "Apple 媒体",
        "aaplimg.com" to "Apple 资源",
        "openai.com" to "OpenAI / ChatGPT",
        "chatgpt.com" to "ChatGPT",
        "oaistatic.com" to "OpenAI 静态资源",
        "oaiusercontent.com" to "OpenAI 内容",
        "anthropic.com" to "Anthropic",
        "claude.ai" to "Claude AI",
        "microsoft.com" to "Microsoft",
        "live.com" to "Microsoft 账户",
        "office.com" to "Office 365",
        "azure.com" to "Microsoft Azure",
        "bing.com" to "Bing 搜索",
        "msftconnecttest.com" to "Windows 连通性测试",
        "cloudflare.com" to "Cloudflare",
        "workers.dev" to "Cloudflare Workers",
        "bilivideo.com" to "哔哩哔哩视频",
        "bilivideo.cn" to "哔哩哔哩视频",
        "bilibili.com" to "哔哩哔哩",
        "hdslb.com" to "哔哩哔哩静态资源",
        "biliapi.net" to "哔哩哔哩 API",
        "tiktok.com" to "TikTok",
        "tiktokcdn.com" to "TikTok 媒体",
        "byteoversea.com" to "字节海外",
        "ibytedtos.com" to "字节媒体 CDN",
        "douyin.com" to "抖音",
        "douyincdn.com" to "抖音媒体",
        "weibo.com" to "微博",
        "sinaimg.cn" to "微博图片",
        "qq.com" to "腾讯 QQ",
        "qpic.cn" to "腾讯图片 CDN",
        "tencent.com" to "腾讯",
        "alipay.com" to "支付宝",
        "taobao.com" to "淘宝",
        "alicdn.com" to "阿里 CDN",
        "tmall.com" to "天猫",
        "baidu.com" to "百度",
        "bdimg.com" to "百度静态资源"
    )

    data class NormalizedDomain(
        val rawHost: String,
        val rootDomain: String,
        val serviceTag: String?,
        val isIp: Boolean,
        val isCdnSharded: Boolean,
    )

    fun parse(rawHostWithPort: String): NormalizedDomain {
        val host = rawHostWithPort.substringBefore(':').trim()
        if (host.isBlank()) {
            return NormalizedDomain(rawHostWithPort, "未知目标", null, isIp = false, isCdnSharded = false)
        }

        if (isIpv4(host) || isIpv6(host)) {
            val label = if (isIpv6(host)) "IPv6 直连" else "IP 直连"
            return NormalizedDomain(rawHostWithPort, host, label, isIp = true, isCdnSharded = false)
        }

        val parts = host.split('.').filter { it.isNotBlank() }
        if (parts.size <= 2) {
            val root = host.lowercase()
            val tag = KNOWN_SERVICES[root]
            return NormalizedDomain(rawHostWithPort, root, tag, isIp = false, isCdnSharded = false)
        }

        val lastTwo = "${parts[parts.size - 2]}.${parts.last()}".lowercase()
        val rootDomain = if (MULTI_PART_TLDS.contains(lastTwo) && parts.size >= 3) {
            "${parts[parts.size - 3]}.$lastTwo".lowercase()
        } else {
            "${parts[parts.size - 2]}.${parts.last()}".lowercase()
        }

        val serviceTag = KNOWN_SERVICES[rootDomain]
        val isCdnSharded = (host.startsWith("rr", ignoreCase = true) && host.contains("---")) ||
                host.startsWith("mcdn.", ignoreCase = true) ||
                parts[0].length > 16

        return NormalizedDomain(rawHostWithPort, rootDomain, serviceTag, isIp = false, isCdnSharded = isCdnSharded)
    }

    private fun isIpv4(s: String): Boolean =
        s.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$"""))

    private fun isIpv6(s: String): Boolean =
        s.contains(':')
}
