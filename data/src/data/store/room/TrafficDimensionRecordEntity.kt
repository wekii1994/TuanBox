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

package com.github.yumeyucca.yumebox.data.store.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * L3 历史持久层 Entity
 * 按照小时对齐的细粒度流量记录表，包含覆盖应用、域名、节点、规则四维索引
 */
@Entity(
    tableName = "traffic_dimension_records",
    indices = [
        Index(value = ["timestamp_hour", "uid"]),
        Index(value = ["timestamp_hour", "host"]),
        Index(value = ["timestamp_hour", "node_name"]),
        Index(value = ["timestamp_hour", "rule_name"])
    ]
)
data class TrafficDimensionRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "timestamp_hour") val timestampHour: Long,
    @ColumnInfo(name = "uid") val uid: Int,
    @ColumnInfo(name = "host") val host: String,
    @ColumnInfo(name = "node_name") val nodeName: String,
    @ColumnInfo(name = "rule_name") val ruleName: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "network_type") val networkType: String,
    @ColumnInfo(name = "proxy_bytes") val proxyBytes: Long,
    @ColumnInfo(name = "direct_bytes") val directBytes: Long,
    @ColumnInfo(name = "reject_bytes") val rejectBytes: Long
)

/**
 * 复合统计返回对象 DTO
 */
data class DimensionTrafficStat(
    val dimensionKey: String,
    val totalBytes: Long,
    val proxyBytes: Long,
    val directBytes: Long,
    val rejectBytes: Long
)

data class GlobalEfficiencyStat(
    val totalProxyBytes: Long,
    val totalDirectBytes: Long,
    val totalRejectBytes: Long
)

data class HourlyTrendPoint(
    val hourTimestamp: Long,
    val totalBytes: Long
)
