package com.github.yumeyucca.yumebox.data.trafficv2

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class ChartPoint(val startMillis: Long, val label: String, val bytes: Bytes)

/** Includes legacy daily rows only in day-scale charts. Missing hours/days are zero-filled. */
fun trafficChartPoints(
    rows: List<LedgerDelta>, firstDate: LocalDate, days: Int, zone: ZoneId,
    daily: Boolean,
): List<ChartPoint> {
    require(days in 1..366)
    val from = firstDate.atStartOfDay(zone)
    val until = firstDate.plusDays(days.toLong()).atStartOfDay(zone)
    val included = rows.filter { it.hourMillis >= from.toInstant().toEpochMilli() &&
        it.hourMillis < until.toInstant().toEpochMilli() && (daily || it.basis != "legacy_daily") }
    fun bucket(time: Long) = Instant.ofEpochMilli(time).atZone(zone).let {
        if (daily) it.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        else it.withMinute(0).withSecond(0).withNano(0).toInstant().toEpochMilli()
    }
    val values = included.groupBy { bucket(it.hourMillis) }
        .mapValues { (_, group) -> group.fold(Bytes()) { a, row -> a + row.bytes } }
    val result = mutableListOf<ChartPoint>()
    var current = from
    while (current < until) {
        val time = current.toInstant().toEpochMilli()
        val label = if (daily) "${current.monthValue}/${current.dayOfMonth}" else "${current.hour}:00"
        result += ChartPoint(time, label, values[time] ?: Bytes())
        current = if (daily) current.plusDays(1) else current.plusHours(1)
    }
    return result
}
