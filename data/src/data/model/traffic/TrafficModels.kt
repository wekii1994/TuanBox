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

package com.github.yumeyucca.yumebox.data.model.traffic

import android.graphics.Bitmap
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicInteger

/**
 * 流量统计四维透视枚举
 */
enum class TrafficDimension(val displayName: String) {
    APP("应用"),
    NODE("节点"),
    DOMAIN("域名"),
    RULE("规则")
}

/**
 * 分流类型枚举（与分流效能看板对齐）
 */
enum class TrafficCategory {
    PROXY,   // 代理流量（电光蓝）
    DIRECT,  // 直连流量（翠绿）
    REJECT   // 拦截流量（暗灰）
}

/**
 * 网络接入类型
 */
enum class NetworkAccessType {
    WIFI,
    CELLULAR,
    OTHER
}

/**
 * L1 实时速率载荷（对标 Mihomo /traffic 接口）
 */
@Serializable
data class MihomoTrafficPayload(
    @SerialName("up") val up: Long = 0L,
    @SerialName("down") val down: Long = 0L
)

/**
 * L1/L2 裁剪版 /connections DTO
 * 仅反序列化必须字段，极大减少超大 JSON 的反序列化耗时与临时对象逃逸
 */
@Serializable
data class FastConnectionsPayload(
    @SerialName("downloadTotal") val downloadTotal: Long = 0L,
    @SerialName("uploadTotal") val uploadTotal: Long = 0L,
    @SerialName("connections") val connections: List<FastConnectionItem> = emptyList()
)

@Serializable
data class FastConnectionItem(
    val id: String = "",
    val metadata: FastMetadata = FastMetadata(),
    @SerialName("upload") val upload: Long = 0L,
    @SerialName("download") val download: Long = 0L,
    val chains: List<String> = emptyList(),
    val rule: String = "",
    val rulePayload: String = ""
)

@Serializable
data class FastMetadata(
    val uid: Int? = null,
    val host: String? = null,
    val destinationIP: String? = null,
    val network: String? = null,
    val process: String? = null
)

/**
 * 应用 UID 缓存模型（缓存于 LruCache 中，避免频繁跨进程 Binder 调用 PackageManager）
 */
data class CachedAppInfo(
    val uid: Int,
    val packageName: String,
    val appName: String,
    val compressedIcon: Bitmap? = null
)

/**
 * L2 短期趋势层：定长无锁环形缓冲区 (Fixed-Size RingBuffer)
 * 【核心性能优化】：
 * 1. 内部持有容量 60 的 LongArray，表示过去 60 秒的下行与上行速率。
 * 2. 避免 List<Long> 或 ArrayList 的装箱拆箱开销与节点对象频繁创建，零 GC 分配。
 * 3. 读写操作完全在固定内存区间，提供只读镜像快照用于 Compose Canvas 绘制。
 */
class RateRingBuffer(val capacity: Int = 60) {
    private val downRates = LongArray(capacity)
    private val upRates = LongArray(capacity)
    private val head = AtomicInteger(0)
    private var size = 0

    @Synchronized
    fun push(downRate: Long, upRate: Long) {
        val index = head.getAndIncrement() % capacity
        downRates[index] = downRate
        upRates[index] = upRate
        if (size < capacity) size++
    }

    /**
     * 获取按时间正序排列的速率历史镜像（供 Canvas 贝塞尔曲线平滑绘制）
     * 复用预分配数组拷贝，避免对象逃逸
     */
    @Synchronized
    fun exportSnapshot(outDown: LongArray, outUp: LongArray): Int {
        val currentHead = head.get()
        val count = size
        val startOffset = if (count < capacity) 0 else (currentHead % capacity)
        for (i in 0 until count) {
            val idx = (startOffset + i) % capacity
            outDown[i] = downRates[idx]
            outUp[i] = upRates[idx]
        }
        return count
    }
}

/**
 * 内存累加器聚合键（用于 L3 延迟批量刷盘前的内存哈希聚合）
 */
data class AggregationKey(
    val timestampHour: Long,
    val uid: Int,
    val host: String,
    val nodeName: String,
    val ruleName: String,
    val profileId: String,
    val networkType: String
)

/**
 * 内存瞬时累加值
 */
class MutableTrafficAccumulator {
    var proxyBytes: Long = 0L
    var directBytes: Long = 0L
    var rejectBytes: Long = 0L

    fun add(category: TrafficCategory, bytes: Long) {
        when (category) {
            TrafficCategory.PROXY -> proxyBytes += bytes
            TrafficCategory.DIRECT -> directBytes += bytes
            TrafficCategory.REJECT -> rejectBytes += bytes
        }
    }
}

/**
 * 复合排行榜展示条目（供 Compose UI 使用）
 */
data class TrafficRankItem(
    val identifier: String,
    val displayName: String,
    val totalBytes: Long,
    val proxyBytes: Long,
    val directBytes: Long,
    val rejectBytes: Long,
    val hasActiveConnections: Boolean
)
