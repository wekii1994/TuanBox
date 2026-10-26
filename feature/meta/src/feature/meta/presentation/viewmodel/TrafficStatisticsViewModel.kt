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

@Immutable
data class RecentRequestRecord(
    val connection: ConnectionInfo,
    val isActive: Boolean,
    val exitNode: String,
    val ruleLabel: String,
    val sourceAppName: String?,
    val serviceTag: String?,
    val displayHost: String,
    val subDetail: String?,
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

@Immutable
data class TrafficStatisticsUiState(
    val selectedTimeRange: StatisticsTimeRange = StatisticsTimeRange.TODAY,
    val summary: DailyTrafficSummary = DailyTrafficSummary.empty,
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

private data class ConnectionDetails(
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
    private val dailyTotalsFlow: Flow<List<DailyTrafficSummary>> =
        selectedTimeRange.flatMapLatest { range ->
            trafficStatisticsStore.getDailyTotalsFlow(range)
        }

    private val connectionDetailsFlow: Flow<ConnectionDetails> =
        liveConnectionsFlow
            .map { (activeConns, closedConns) ->
                buildConnectionDetails(activeConns, closedConns)
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    val uiState: StateFlow<TrafficStatisticsUiState> =
        combine(
            combine(selectedTimeRange, selectedBarIndex, dailyTotalsFlow) { range, barIndex, dailyTotals ->
                Triple(range, barIndex, dailyTotals)
            },
            connectionDetailsFlow,
            trafficStatisticsStore.getDailyTotalsFlow(StatisticsTimeRange.WEEK),
            trafficStatisticsStore.getTodayHourlyTotalsFlow(),
        ) { (range, barIndex, dailyTotals), connectionDetails, weekDailyTotals, hourlyTotals ->
            buildUiState(
                range = range,
                selectedBarIndex = barIndex,
                dailyTotals = dailyTotals,
                weekDailyTotals = weekDailyTotals,
                hourlyTotals = hourlyTotals,
                recentRequests = connectionDetails.recentRequests,
                targetSites = connectionDetails.targetSites,
            )
        }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                TrafficStatisticsUiState(),
            )

    fun setTimeRange(range: StatisticsTimeRange) {
        selectedTimeRange.value = range
        selectedBarIndex.value = -1
    }

    fun selectBar(index: Int) {
        selectedBarIndex.value = if (selectedBarIndex.value == index) -1 else index
    }

    fun clearAllStatistics() {
        viewModelScope.launch {
            trafficStatisticsStore.clearAll()
            ConnectionHistoryManager.clear()
        }
    }

    private fun buildConnectionDetails(
        activeConns: List<ConnectionInfo>,
        closedConns: List<ConnectionInfo>,
    ): ConnectionDetails {
        val activeIds = activeConns.map(ConnectionInfo::id).toSet()
        val allWithTimestamp = (activeConns + closedConns.filterNot { it.id in activeIds })
            .map { conn -> conn to parseConnectionStartMillis(conn.start) }
            .sortedByDescending { it.second }

        val recentRequests = allWithTimestamp.take(30).map { (conn, _) ->
            val resolvedApp = appIdentityResolver.resolve(conn.metadata)
            val hasValidApp = resolvedApp.packageName != null &&
                    resolvedApp.appName.isNotBlank() &&
                    resolvedApp.appName != "未知应用" &&
                    resolvedApp.appKey != AppIdentityResolver.UNKNOWN_APP_KEY

            val sourceAppName = if (hasValidApp) resolvedApp.appName else null
            val dest = conn.extractDisplayDestination()
            val parsed = DomainClassifier.parse(dest)
            val port = conn.metadata["destinationPort"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
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
                val destIp = conn.metadata["destinationIP"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                subDetail = if (destIp.isNotBlank() && destIp != dest) destIp else null
            }

            val rawExit = conn.chains.lastOrNull()?.trim() ?: if (conn.chains.isEmpty()) "直连" else null
            val exitNode = when {
                rawExit.isNullOrBlank() || rawExit.equals("DIRECT", ignoreCase = true) -> "直连"
                rawExit.contains('|') -> rawExit.substringAfterLast('|').trim()
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
                serviceTag = parsed.brandEntity.serviceTag ?: parsed.serviceTag,
                displayHost = displayHost,
                subDetail = subDetail,
            )
        }

        val targetSites = allWithTimestamp
            .mapNotNull { (conn, startMillis) ->
                val site = conn.extractTargetSite() ?: return@mapNotNull null
                val parsed = DomainClassifier.parse(site)
                Triple(parsed, conn.upload, conn.download) to startMillis
            }
            .groupBy { it.first.first.brandEntity.entityKey }
            .map { (entityKey, entries) ->
                val totalUp = entries.sumOf { it.first.second }
                val totalDown = entries.sumOf { it.first.third }
                val lastSeen = entries.maxOfOrNull { it.second } ?: 0L
                val firstParsed = entries.first().first.first
                val brand = firstParsed.brandEntity

                TargetSiteRecord(
                    siteKey = entityKey,
                    displayName = brand.displayName,
                    primaryDomain = brand.primaryDomain,
                    totalUpload = totalUp,
                    totalDownload = totalDown,
                    lastSeenAt = lastSeen,
                    serviceTag = brand.serviceTag,
                    connectionCount = entries.size,
                    isIp = brand.isIp,
                )
            }
            .sortedWith(
                compareByDescending<TargetSiteRecord> { it.totalBytes }
                    .thenByDescending { it.lastSeenAt }
            )
            .take(20)

        return ConnectionDetails(recentRequests, targetSites)
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
            summary = DailyTrafficSummary(
                dateMillis = range.days.toLong(),
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
        private const val HOURS_PER_DAY = 24
        private val LABELED_HOURS = setOf(0, 6, 12, 18, 23)
    }
}

fun parseConnectionStartMillis(isoString: String): Long =
    runCatching {
        OffsetDateTime.parse(isoString).toInstant().toEpochMilli()
    }.getOrDefault(0L)

fun ConnectionInfo.extractDisplayDestination(): String {
    val host = metadata["host"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (host.isNotBlank()) return host
    val destIp = metadata["destinationIP"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (destIp.isNotBlank()) {
        val port = metadata["destinationPort"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        return if (destIp.count { it == ':' } >= 2) {
            if (port.isNotBlank()) "[$destIp]:$port" else "[$destIp]"
        } else {
            if (port.isNotBlank()) "$destIp:$port" else destIp
        }
    }
    return "未知目标"
}

fun ConnectionInfo.extractTargetSite(): String? {
    val host = metadata["host"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (host.isNotBlank()) return host
    val destIp = metadata["destinationIP"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (destIp.isNotBlank()) return destIp
    return null
}

data class BrandEntity(
    val entityKey: String,
    val displayName: String,
    val primaryDomain: String,
    val serviceTag: String? = null,
    val isIp: Boolean = false,
)

data class NormalizedDomain(
    val rawHost: String,
    val rootDomain: String,
    val brandEntity: BrandEntity,
    val serviceTag: String?,
    val isIp: Boolean,
    val isCdnSharded: Boolean,
)

object DomainClassifier {
    private val MULTI_PART_TLDS = setOf(
        "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn",
        "co.uk", "org.uk", "me.uk", "ltd.uk", "plc.uk",
        "co.jp", "ne.jp", "or.jp", "go.jp",
        "com.hk", "org.hk", "edu.hk",
        "com.tw", "org.tw", "edu.tw",
        "com.sg", "org.sg",
        "com.au", "net.au", "org.au",
    )

    private val IPV4_REGEX = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")

    private val BRAND_MAP = linkedMapOf<String, BrandEntity>()

    init {
        fun registerBrand(
            entityKey: String,
            displayName: String,
            primaryDomain: String,
            serviceTag: String?,
            vararg domains: String,
        ) {
            val entity = BrandEntity(entityKey, displayName, primaryDomain, serviceTag, isIp = false)
            for (domain in domains) {
                BRAND_MAP[domain.lowercase()] = entity
            }
        }

        registerBrand(
            "bilibili", "哔哩哔哩", "bilibili.com", "视频 / 社区",
            "bilibili.com", "biliapi.com", "biliapi.net", "hdslb.com", "bilivideo.com", "bilivideo.cn"
        )
        registerBrand(
            "tencent", "腾讯服务", "qq.com", "社交 / 腾讯服务",
            "qq.com", "tencent.com", "gdtimg.com", "gtimg.com", "gtimg.cn", "qpic.cn", "qlogo.cn",
            "weixin.qq.com", "tenpay.com", "wechat.com", "foxmail.com"
        )
        registerBrand(
            "bytedance", "字节跳动", "douyin.com", "抖音 / 媒体 CDN",
            "douyin.com", "douyincdn.com", "byteoversea.com", "ibytedtos.com", "pstatp.com",
            "snssdk.com", "tiktok.com", "tiktokcdn.com", "toutiao.com"
        )
        registerBrand(
            "alibaba", "阿里服务", "taobao.com", "电商 / 云计算",
            "taobao.com", "tmall.com", "alipay.com", "alicdn.com", "aliyun.com", "tbcdn.cn", "alibaba.com"
        )
        registerBrand(
            "baidu", "百度", "baidu.com", "搜索 / 地图",
            "baidu.com", "bdimg.com", "baidupcs.com", "bcebos.com"
        )
        registerBrand(
            "google", "Google 服务", "google.com", "Google 核心",
            "google.com", "googleapis.com", "gstatic.com", "googleusercontent.com", "1e100.net",
            "gvt1.com", "gvt2.com", "ggpht.com", "android.com"
        )
        registerBrand(
            "youtube", "YouTube", "youtube.com", "流媒体视频",
            "youtube.com", "youtu.be", "ytimg.com", "googlevideo.com"
        )
        registerBrand(
            "apple", "Apple 服务", "apple.com", "Apple / iCloud",
            "apple.com", "icloud.com", "mzstatic.com", "aaplimg.com", "apple-dns.net", "itunes.com"
        )
        registerBrand(
            "microsoft", "Microsoft", "microsoft.com", "Windows / 办公",
            "microsoft.com", "office.com", "live.com", "azure.com", "bing.com",
            "msftconnecttest.com", "windows.com", "microsoftonline.com"
        )
        registerBrand(
            "telegram", "Telegram", "telegram.org", "即时通讯",
            "telegram.org", "t.me", "telegram.me", "telesco.pe"
        )
        registerBrand(
            "openai", "OpenAI / ChatGPT", "openai.com", "AI 大模型",
            "openai.com", "chatgpt.com", "oaistatic.com", "oaiusercontent.com"
        )
        registerBrand(
            "twitter", "X (Twitter)", "x.com", "社交网络",
            "x.com", "twitter.com", "twimg.com", "t.co"
        )
        registerBrand(
            "meta", "Meta (Instagram/FB)", "instagram.com", "社交网络",
            "facebook.com", "fbcdn.net", "instagram.com", "cdninstagram.com",
            "whatsapp.com", "whatsapp.net", "messenger.com"
        )
        registerBrand(
            "github", "GitHub", "github.com", "代码托管",
            "github.com", "githubusercontent.com", "github.io", "githubassets.com"
        )
        registerBrand(
            "cloudflare", "Cloudflare", "cloudflare.com", "网络加速 / CDN",
            "cloudflare.com", "workers.dev", "cloudflare-dns.com"
        )
        registerBrand(
            "netflix", "Netflix", "netflix.com", "影视流媒体",
            "netflix.com", "nflxvideo.net", "nflximg.net", "nflxso.net"
        )
        registerBrand(
            "spotify", "Spotify", "spotify.com", "音乐流媒体",
            "spotify.com", "scdn.co", "spoti.fi"
        )
        registerBrand(
            "weibo", "微博", "weibo.com", "社交媒体",
            "weibo.com", "sinaimg.cn", "weibo.cn", "sina.com.cn"
        )
        registerBrand(
            "heytap", "欢太 (OPPO)", "heytapmobi.com", "系统生态",
            "heytapmobi.com", "oppo.com", "coloros.com"
        )
        registerBrand(
            "xiaomi", "小米服务", "xiaomi.com", "MIUI / 澎湃",
            "xiaomi.com", "mi.com", "miui.com", "xiaomi.net"
        )
        registerBrand(
            "vivo", "vivo 服务", "vivo.com", "OriginOS",
            "vivo.com", "vivo.com.cn"
        )
        registerBrand(
            "huawei", "华为服务", "huawei.com", "HarmonyOS",
            "huawei.com", "hicloud.com", "dbankcdn.com", "honor.com"
        )
    }

    fun parse(rawHostWithPort: String): NormalizedDomain {
        val trimmed = rawHostWithPort.trim()
        val host: String = when {
            trimmed.startsWith('[') -> trimmed.substringAfter('[').substringBefore(']')
            trimmed.count { it == ':' } >= 2 -> trimmed
            else -> trimmed.substringBefore(':').trim()
        }

        if (host.isBlank()) {
            val emptyEntity = BrandEntity("category:unknown", "未知目标", "未知", isIp = false)
            return NormalizedDomain(rawHostWithPort, "未知目标", emptyEntity, null, isIp = false, isCdnSharded = false)
        }

        if (isIpv4(host) || isIpv6(host)) {
            val isPrivate = isPrivateIp(host)
            val ipEntity = if (isPrivate) {
                BrandEntity(
                    entityKey = "category:lan_ip",
                    displayName = "局域网 / 私有 IP",
                    primaryDomain = "私有 IP 地址",
                    serviceTag = "局域网",
                    isIp = true,
                )
            } else {
                BrandEntity(
                    entityKey = "category:direct_ip",
                    displayName = "IP 直接连接 (CDN/直连流)",
                    primaryDomain = "公网 IP 直连",
                    serviceTag = "流媒体/直连",
                    isIp = true,
                )
            }
            return NormalizedDomain(rawHostWithPort, host, ipEntity, ipEntity.serviceTag, isIp = true, isCdnSharded = false)
        }

        val parts = host.split('.').filter { it.isNotBlank() }
        if (parts.size <= 2) {
            val root = host.lowercase()
            val brand = BRAND_MAP[root] ?: BrandEntity(root, root, root, null, isIp = false)
            return NormalizedDomain(rawHostWithPort, root, brand, brand.serviceTag, isIp = false, isCdnSharded = false)
        }

        val lastTwo = "${parts[parts.size - 2]}.${parts.last()}".lowercase()
        val rootDomain = if (MULTI_PART_TLDS.contains(lastTwo) && parts.size >= 3) {
            "${parts[parts.size - 3]}.$lastTwo".lowercase()
        } else {
            "${parts[parts.size - 2]}.${parts.last()}".lowercase()
        }

        val lowerHost = host.lowercase()
        val brand = BRAND_MAP[lowerHost]
            ?: BRAND_MAP[rootDomain]
            ?: BrandEntity(rootDomain, rootDomain, rootDomain, null, isIp = false)
        val isCdnSharded = (host.startsWith("rr", ignoreCase = true) && host.contains("---")) ||
                host.startsWith("mcdn.", ignoreCase = true) ||
                parts[0].length > 16

        return NormalizedDomain(rawHostWithPort, rootDomain, brand, brand.serviceTag, isIp = false, isCdnSharded = isCdnSharded)
    }

    private fun isIpv4(s: String): Boolean {
        if (!IPV4_REGEX.matches(s)) return false
        return s.split('.').all { part ->
            part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }

    private fun isIpv6(s: String): Boolean =
        s.count { it == ':' } >= 2

    private fun isPrivateIp(ip: String): Boolean {
        if (isIpv6(ip)) {
            val lower = ip.lowercase()
            if (lower == "::1" || lower == "0:0:0:0:0:0:0:1") return true
            val firstHextet = lower.substringBefore(':').padStart(4, '0')
            return firstHextet.startsWith("fc") ||
                firstHextet.startsWith("fd") ||
                firstHextet.startsWith("fe8") ||
                firstHextet.startsWith("fe9") ||
                firstHextet.startsWith("fea") ||
                firstHextet.startsWith("feb")
        }
        val parts = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        val (a, b) = parts
        return when {
            a == 10 -> true
            a == 127 -> true
            a == 172 && b in 16..31 -> true
            a == 192 && b == 168 -> true
            a == 169 && b == 254 -> true
            else -> false
        }
    }
}
