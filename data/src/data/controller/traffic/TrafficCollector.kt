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

package com.github.yumeyucca.yumebox.data.controller.traffic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.LruCache
import com.github.yumeyucca.yumebox.core.bridge.UnixSocketFactory
import com.github.yumeyucca.yumebox.data.model.traffic.*
import com.github.yumeyucca.yumebox.data.store.room.TrafficDimensionRecordEntity
import com.github.yumeyucca.yumebox.data.store.room.TrafficStatisticsDao
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private data class ActiveBaseline(
    val uid: Int,
    val host: String,
    val nodeName: String,
    val ruleName: String,
    val category: TrafficCategory,
    var lastUpload: Long,
    var lastDownload: Long
)

/**
 * 流量统计核心采集引擎：
 * 兼顾实时速率绘制、全生命周期自适应节流、长休眠中断与后台增量防漏计机制
 */
class TrafficCollector(
    private val context: Context,
    private val trafficDao: TrafficStatisticsDao,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    private val _realtimeSpeed = MutableStateFlow(0L to 0L)
    val realtimeSpeed: StateFlow<Pair<Long, Long>> = _realtimeSpeed.asStateFlow()

    private val _activeUids = MutableStateFlow<Set<Int>>(emptySet())
    val activeUids: StateFlow<Set<Int>> = _activeUids.asStateFlow()

    private val _activeHosts = MutableStateFlow<Set<String>>(emptySet())
    val activeHosts: StateFlow<Set<String>> = _activeHosts.asStateFlow()

    private val _activeNodes = MutableStateFlow<Set<String>>(emptySet())
    val activeNodes: StateFlow<Set<String>> = _activeNodes.asStateFlow()

    val ringBuffer = RateRingBuffer(capacity = 60)

    private val activeBaselines = ConcurrentHashMap<String, ActiveBaseline>()
    private val writeBehindAccumulator = HashMap<AggregationKey, MutableTrafficAccumulator>()
    private val flushLock = Any()

    private val appInfoCache = LruCache<Int, CachedAppInfo>(128)

    private val isScreenOn = AtomicBoolean(true)
    private val isAppForeground = AtomicBoolean(false)
    private val isTrafficPageVisible = AtomicBoolean(false)
    private val isVpnRunning = AtomicBoolean(true)

    private var trafficSamplingJob: Job? = null
    private var connectionSamplingJob: Job? = null
    private var periodicFlushJob: Job? = null
    private var screenReceiver: BroadcastReceiver? = null

    private val socketFile = File(context.cacheDir, "mihomo.sock")
    private val httpClient by lazy {
        HttpClient(OkHttp) {
            engine {
                if (socketFile.exists()) {
                    config { socketFactory(UnixSocketFactory(socketFile.absolutePath)) }
                }
            }
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }
    }

    init {
        registerScreenStateReceiver()
        restartSamplingJobs()
    }

    fun onStart() {
        isAppForeground.set(true)
        restartSamplingJobs()
    }

    fun onStop() {
        isAppForeground.set(false)
        restartSamplingJobs()
        triggerWriteBehindFlush()
    }

    /**
     * 前台可见性切换：
     * 当进入统计页面时，立即落盘并重置调度器，切入 1s 采样与 3s 高频刷盘模式
     */
    fun setTrafficPageVisibility(visible: Boolean) {
        if (isTrafficPageVisible.getAndSet(visible) == visible) return

        if (visible) {
            scope.launch {
                triggerWriteBehindFlush()
            }
        } else {
            _realtimeSpeed.value = 0L to 0L
        }

        restartSamplingJobs()
    }

    /**
     * 统一调度引擎：
     * 根据前后台、浏览状态与屏幕状态瞬时重置休眠 delay
     */
    @Synchronized
    private fun restartSamplingJobs() {
        trafficSamplingJob?.cancel()
        connectionSamplingJob?.cancel()
        periodicFlushJob?.cancel()

        if (isTrafficPageVisible.get() && isScreenOn.get()) {
            // 前台浏览态：1s 速率采样供贝塞尔波形图
            trafficSamplingJob = scope.launch {
                while (isActive) {
                    val traffic = fetchTraffic()
                    _realtimeSpeed.value = traffic.down to traffic.up
                    ringBuffer.push(traffic.down, traffic.up)
                    delay(1000L)
                }
            }

            // 前台浏览态：1s 活跃连接增量采样
            connectionSamplingJob = scope.launch {
                while (isActive) {
                    if (isVpnRunning.get()) {
                        sampleConnectionsDelta()
                    }
                    delay(1000L)
                }
            }

            // 前台浏览态：3s 批量刷盘驱动 Room Flow 顺畅刷新排行榜
            periodicFlushJob = scope.launch {
                while (isActive) {
                    delay(3000L)
                    triggerWriteBehindFlush()
                }
            }
        } else {
            // 后台或非统计页面：挂起速率采样
            _realtimeSpeed.value = 0L to 0L

            // 保持静默连接增量采样：亮屏 10s 一次，息屏 30s 一次，杜绝后台短连接漏记
            val interval = if (isScreenOn.get()) 10_000L else 30_000L
            connectionSamplingJob = scope.launch {
                while (isActive) {
                    delay(interval)
                    if (isVpnRunning.get()) {
                        sampleConnectionsDelta()
                    }
                }
            }

            // 延迟批量刷盘：15 分钟聚合落盘一次
            periodicFlushJob = scope.launch {
                while (isActive) {
                    delay(15 * 60 * 1000L)
                    triggerWriteBehindFlush()
                }
            }
        }
    }

    private suspend fun sampleConnectionsDelta() {
        val payload = fetchConnections()
        val currentConnections = payload.connections
        val currentHour = System.currentTimeMillis() / 3600_000 * 3600_000
        val currentNetwork = resolveCurrentNetworkType()

        val activeIds = HashSet<String>(currentConnections.size)
        val uids = HashSet<Int>()
        val hosts = HashSet<String>()
        val nodes = HashSet<String>()

        synchronized(flushLock) {
            for (conn in currentConnections) {
                activeIds.add(conn.id)
                // 首个元素为物理出口节点或 DIRECT/REJECT
                val exitNodeOrDirect = conn.chains.firstOrNull().orEmpty()
                val category = classifyCategory(conn.rule, exitNodeOrDirect)
                val host = normalizeHost(conn.metadata.host ?: conn.metadata.destinationIP.orEmpty())
                val uid = conn.metadata.uid ?: -1

                if (uid > 0) uids.add(uid)
                if (host.isNotEmpty()) hosts.add(host)
                if (exitNodeOrDirect.isNotEmpty()) nodes.add(exitNodeOrDirect)

                val baseline = activeBaselines[conn.id]
                if (baseline == null) {
                    activeBaselines[conn.id] = ActiveBaseline(
                        uid = uid,
                        host = host,
                        nodeName = exitNodeOrDirect,
                        ruleName = conn.rule,
                        category = category,
                        lastUpload = conn.upload,
                        lastDownload = conn.download
                    )
                    accumulateLocked(currentHour, uid, host, exitNodeOrDirect, conn.rule, currentNetwork, category, conn.upload + conn.download)
                } else {
                    val upDelta = (conn.upload - baseline.lastUpload).coerceAtLeast(0L)
                    val downDelta = (conn.download - baseline.lastDownload).coerceAtLeast(0L)
                    val totalDelta = upDelta + downDelta

                    if (totalDelta > 0L) {
                        baseline.lastUpload = conn.upload
                        baseline.lastDownload = conn.download
                        accumulateLocked(currentHour, baseline.uid, baseline.host, baseline.nodeName, baseline.ruleName, currentNetwork, baseline.category, totalDelta)
                    }
                }
            }

            // 清理已消亡连接
            val iterator = activeBaselines.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (!activeIds.contains(entry.key)) iterator.remove()
            }
        }

        _activeUids.value = uids
        _activeHosts.value = hosts
        _activeNodes.value = nodes
    }

    private fun accumulateLocked(hour: Long, uid: Int, host: String, node: String, rule: String, netType: String, cat: TrafficCategory, bytes: Long) {
        val key = AggregationKey(hour, uid, host, node, rule, "active_profile", netType)
        val accumulator = writeBehindAccumulator.computeIfAbsent(key) { MutableTrafficAccumulator() }
        accumulator.add(cat, bytes)
    }

    private val MULTI_PART_TLDS = setOf(
        "com.cn", "org.cn", "net.cn", "gov.cn", "edu.cn",
        "co.uk", "org.uk", "me.uk",
        "co.jp", "ne.jp", "or.jp",
        "com.tw", "org.tw", "idv.tw",
        "com.hk", "org.hk", "com.sg"
    )

    private fun normalizeHost(rawHost: String): String {
        val host = rawHost.substringBefore(':').trim()
        if (host.isEmpty() || host.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$""")) || host.contains(':')) {
            return host
        }
        val segments = host.split('.').filter { it.isNotEmpty() }
        if (segments.size <= 2) return host.lowercase()

        val lastTwo = "${segments[segments.size - 2]}.${segments.last()}".lowercase()
        return if (MULTI_PART_TLDS.contains(lastTwo) && segments.size >= 3) {
            "${segments[segments.size - 3]}.$lastTwo".lowercase()
        } else {
            lastTwo
        }
    }

    private fun classifyCategory(rule: String, exitNode: String): TrafficCategory {
        val upperRule = rule.uppercase()
        val upperExit = exitNode.uppercase()
        return when {
            upperRule == "REJECT" || upperExit == "REJECT" -> TrafficCategory.REJECT
            upperRule == "DIRECT" || upperExit == "DIRECT" -> TrafficCategory.DIRECT
            else -> TrafficCategory.PROXY
        }
    }

    fun triggerWriteBehindFlush() {
        val entitiesToFlush: List<TrafficDimensionRecordEntity>
        synchronized(flushLock) {
            if (writeBehindAccumulator.isEmpty()) return
            entitiesToFlush = writeBehindAccumulator.map { (key, acc) ->
                TrafficDimensionRecordEntity(
                    timestampHour = key.timestampHour,
                    uid = key.uid,
                    host = key.host,
                    nodeName = key.nodeName,
                    ruleName = key.ruleName,
                    profileId = key.profileId,
                    networkType = key.networkType,
                    proxyBytes = acc.proxyBytes,
                    directBytes = acc.directBytes,
                    rejectBytes = acc.rejectBytes
                )
            }
            writeBehindAccumulator.clear()
        }

        scope.launch {
            try {
                trafficDao.batchFlushAccumulator(entitiesToFlush)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to flush write-behind records")
            }
        }
    }

    private fun registerScreenStateReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        isScreenOn.set(true)
                        restartSamplingJobs()
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        isScreenOn.set(false)
                        restartSamplingJobs()
                        triggerWriteBehindFlush()
                    }
                }
            }
        }
        screenReceiver = receiver
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
    }

    private suspend fun fetchTraffic(): MihomoTrafficPayload = runCatching {
        httpClient.get("http://127.0.0.1/traffic").body<MihomoTrafficPayload>()
    }.getOrDefault(MihomoTrafficPayload(0L, 0L))

    private suspend fun fetchConnections(): FastConnectionsPayload = runCatching {
        httpClient.get("http://127.0.0.1/connections").body<FastConnectionsPayload>()
    }.getOrDefault(FastConnectionsPayload())

    private fun resolveCurrentNetworkType(): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "OTHER"
        val activeNet = cm.activeNetwork ?: return "NONE"
        val caps = cm.getNetworkCapabilities(activeNet) ?: return "OTHER"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            else -> "OTHER"
        }
    }

    fun getAppInfo(uid: Int): CachedAppInfo {
        if (uid < 0) return CachedAppInfo(uid, "system", "系统直连 / 核心进程")
        appInfoCache.get(uid)?.let { return it }

        val pm = context.packageManager
        val packages = pm.getPackagesForUid(uid)
        val info = if (!packages.isNullOrEmpty()) {
            val pkg = packages[0]
            val appName = try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(appInfo).toString()
            } catch (_: Exception) { pkg }
            CachedAppInfo(uid, pkg, appName)
        } else {
            CachedAppInfo(uid, "uid:$uid", "UID $uid")
        }
        appInfoCache.put(uid, info)
        return info
    }

    fun dispose() {
        screenReceiver?.let {
            runCatching { context.unregisterReceiver(it) }
            screenReceiver = null
        }
        trafficSamplingJob?.cancel()
        connectionSamplingJob?.cancel()
        periodicFlushJob?.cancel()
        scope.cancel()
    }

    companion object {
        private const val TAG = "TrafficCollector"
    }
}
