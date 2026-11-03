package com.github.yumeyucca.yumebox.data.trafficv2

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class Bytes(val up: Long = 0, val down: Long = 0) {
    init { require(up >= 0 && down >= 0) }
    operator fun plus(other: Bytes) = Bytes(Math.addExact(up, other.up), Math.addExact(down, other.down))
    fun minus(other: Bytes) = Bytes(up - other.up, down - other.down)
    val empty get() = up == 0L && down == 0L
}

data class AppRef(val key: String, val packageName: String?, val label: String) {
    companion object { val Unknown = AppRef("unknown", null, "来源应用未知") }
}
data class Target(val key: String, val label: String, val basis: String)
data class ObservedConnection(
    val id: String,
    val startedAt: Long?,
    val bytes: Bytes,
    val app: AppRef,
    val target: Target,
)
data class TrafficSample(
    // Must change on core restart, remote backend switch, or a stable local core-instance identity change.
    val session: String,
    val atMillis: Long,
    val monotonicMillis: Long,
    val totals: Bytes,
    val connections: List<ObservedConnection>,
)
data class LedgerDelta(
    val hourMillis: Long,
    val targetKey: String,
    val targetLabel: String,
    val appKey: String,
    val appLabel: String,
    val packageName: String?,
    val basis: String,
    val bytes: Bytes,
    val lastTrafficAt: Long,
)
data class CoverageGap(val fromMillis: Long, val toMillis: Long, val reason: String)
data class ComputedSample(val deltas: List<LedgerDelta>, val gaps: List<CoverageGap> = emptyList())

/** Pure calculation. Caller serializes accept/clear. This class does no IO and starts no timers. */
class TrafficLedger(private val zone: () -> ZoneId = { ZoneId.systemDefault() }) {
    private var previous: TrafficSample? = null
    fun clear() { previous = null }

    fun accept(sample: TrafficSample): ComputedSample {
        val old = previous
        if (old == null) {
            previous = sample
            return ComputedSample(emptyList()) // Never charge the pre-observation lifetime total.
        }
        if (sample.session == old.session && sample.monotonicMillis <= old.monotonicMillis) {
            return ComputedSample(emptyList()) // Duplicate/stale query cannot move the baseline back.
        }
        if (sample.session != old.session || sample.atMillis < old.atMillis ||
            sample.totals.up < old.totals.up || sample.totals.down < old.totals.down) {
            previous = sample
            return ComputedSample(emptyList(), listOf(CoverageGap(
                minOf(old.atMillis, sample.atMillis), maxOf(old.atMillis, sample.atMillis),
                "runtime_or_counter_reset",
            )))
        }
        val totalDelta = sample.totals.minus(old.totals)
        val prior = old.connections.associateBy { it.id to it.startedAt }
        val candidates = ArrayList<Pair<ObservedConnection, Bytes>>()
        // Deduplicate repeated IDs in one snapshot; the latest representation wins.
        for (connection in sample.connections.associateBy { it.id to it.startedAt }.values) {
            val baseline = prior[connection.id to connection.startedAt]
            val delta = when {
                baseline != null && connection.bytes.up >= baseline.bytes.up &&
                    connection.bytes.down >= baseline.bytes.down -> connection.bytes.minus(baseline.bytes)
                baseline == null && connection.startedAt != null &&
                    connection.startedAt > old.atMillis && connection.startedAt <= sample.atMillis -> connection.bytes
                else -> Bytes() // Unknown first sighting or reset: global residual still preserves total.
            }
            if (!delta.empty) candidates += connection to delta
        }
        val attributed = candidates.fold(Bytes()) { acc, (_, bytes) -> acc + bytes }
        // Reconcile each direction independently; never proportionally rescale app traffic.
        val validUp = attributed.up <= totalDelta.up
        val validDown = attributed.down <= totalDelta.down
        val hour = Instant.ofEpochMilli(sample.atMillis).atZone(zone())
            .truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli()
        val rows = candidates.mapNotNull { (connection, bytes) ->
            val accepted = Bytes(if (validUp) bytes.up else 0, if (validDown) bytes.down else 0)
            if (accepted.empty) null else LedgerDelta(
                hour, connection.target.key, connection.target.label,
                connection.app.key, connection.app.label, connection.app.packageName,
                connection.target.basis, accepted, sample.atMillis,
            )
        }.toMutableList()
        val residual = Bytes(
            totalDelta.up - if (validUp) attributed.up else 0,
            totalDelta.down - if (validDown) attributed.down else 0,
        )
        if (!residual.empty) rows += LedgerDelta(
            hour, "other", "其他流量", "unknown", "来源应用未知", null,
            if (validUp && validDown) "missing_connection" else "inconsistent_sample",
            residual, sample.atMillis,
        )
        check(rows.fold(Bytes()) { acc, row -> acc + row.bytes } == totalDelta)
        // No state advance before all calculations and invariants succeed.
        previous = sample
        return ComputedSample(rows)
    }
}

data class BucketKey(val hour: Long, val target: String, val app: String, val basis: String)

/** Bounded by hours × targets × applications, not by connection count. */
class PendingLedger {
    private val rows = linkedMapOf<BucketKey, LedgerDelta>()
    private val gaps = mutableListOf<CoverageGap>()
    fun add(result: ComputedSample) {
        // Build a complete candidate first so arithmetic errors cannot partially mutate pending data.
        val next = LinkedHashMap(rows)
        for (row in result.deltas) {
            val key = BucketKey(row.hourMillis, row.targetKey, row.appKey, row.basis)
            val old = next[key]
            next[key] = if (old == null) row else row.copy(
                bytes = old.bytes + row.bytes, lastTrafficAt = maxOf(old.lastTrafficAt, row.lastTrafficAt),
            )
        }
        rows.clear(); rows.putAll(next)
        gaps.addAll(result.gaps)
    }
    fun rows(): List<LedgerDelta> = rows.values.toList()
    fun gaps(): List<CoverageGap> = gaps.toList()
    val size get() = rows.size
    val isEmpty get() = rows.isEmpty() && gaps.isEmpty()
    fun clear() { rows.clear(); gaps.clear() }
}
