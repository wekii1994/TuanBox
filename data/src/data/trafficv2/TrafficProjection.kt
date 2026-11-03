package com.github.yumeyucca.yumebox.data.trafficv2

enum class TrafficMetric { DOWNLOAD, UPLOAD, TOTAL }
fun Bytes.value(metric: TrafficMetric): Long = when (metric) {
    TrafficMetric.DOWNLOAD -> down
    TrafficMetric.UPLOAD -> up
    TrafficMetric.TOTAL -> Math.addExact(up, down)
}
data class ServiceRow(
    val key: String,
    val label: String,
    val bytes: Bytes,
    val sources: List<AppRef>,
    val lastTrafficAt: Long,
) {
    fun subtitle(): String {
        val names = sources.map { it.label }.distinct()
        if (names.isEmpty() || names.singleOrNull() == label) return ""
        return "来源：" + names.take(2).joinToString("、") + if (names.size > 2) " 等 ${names.size} 个应用" else ""
    }
}
data class ChartBucket(val hourMillis: Long, val bytes: Bytes)
data class TrafficPage(
    val total: Bytes,
    val services: List<ServiceRow>,
    val chart: List<ChartBucket>,
    // Daily legacy totals have no reliable hourly distribution; never invent a midnight spike.
    val legacyWithoutHour: Bytes,
) {
    fun visible(metric: TrafficMetric, limit: Int): Pair<List<ServiceRow>, Bytes> {
        require(limit >= 0)
        val sorted = services.filter { it.bytes.value(metric) > 0 }
            .sortedWith(compareByDescending<ServiceRow> { it.bytes.value(metric) }.thenBy { it.key })
        return sorted.take(limit) to sorted.drop(limit).fold(Bytes()) { a, r -> a + r.bytes }
    }
}

/** One input list produces every displayed number: no independent total/list Flow race. */
fun projectTraffic(rows: List<LedgerDelta>): TrafficPage {
    val total = rows.fold(Bytes()) { a, r -> a + r.bytes }
    val services = rows.groupBy { it.targetKey }.map { (key, group) ->
        val latest = group.maxBy { it.lastTrafficAt }
        ServiceRow(
            key, latest.targetLabel, group.fold(Bytes()) { a, r -> a + r.bytes },
            group.filter { it.packageName != null }.groupBy { it.appKey }.values.map { entries ->
                entries.maxBy { it.lastTrafficAt }.let { AppRef(it.appKey, it.packageName, it.appLabel) }
            }.sortedBy { it.label },
            group.maxOf { it.lastTrafficAt },
        )
    }
    val chart = rows.filter { it.basis != "legacy_daily" }.groupBy { it.hourMillis }
        .map { (hour, group) -> ChartBucket(hour, group.fold(Bytes()) { a, r -> a + r.bytes }) }
        .sortedBy { it.hourMillis }
    return TrafficPage(total, services, chart,
        rows.filter { it.basis == "legacy_daily" }.fold(Bytes()) { a, r -> a + r.bytes })
}
