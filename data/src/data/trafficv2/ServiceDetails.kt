package com.github.yumeyucca.yumebox.data.trafficv2

data class SourceShare(val app: AppRef, val bytes: Bytes)
data class ServiceDetail(
    val key: String, val label: String, val bytes: Bytes,
    val sources: List<SourceShare>, val lastTrafficAt: Long,
    val reasons: Map<String, Bytes>,
)
fun projectServiceDetail(rows: List<LedgerDelta>, key: String): ServiceDetail? {
    val matching = rows.filter { it.targetKey == key }
    if (matching.isEmpty()) return null
    val newest = matching.maxBy { it.lastTrafficAt }
    return ServiceDetail(key, newest.targetLabel,
        matching.fold(Bytes()) { a, r -> a + r.bytes },
        matching.groupBy { it.appKey }.map { (_, entries) ->
            val latest = entries.maxBy { it.lastTrafficAt }
            SourceShare(AppRef(latest.appKey, latest.packageName, latest.appLabel),
                entries.fold(Bytes()) { a, r -> a + r.bytes })
        }.sortedByDescending { it.bytes.value(TrafficMetric.TOTAL) },
        newest.lastTrafficAt,
        matching.groupBy { it.basis }.mapValues { (_, values) -> values.fold(Bytes()) { a, r -> a + r.bytes } },
    )
}

data class EndpointEvidence(val serviceKey: String, val host: String?, val ip: String?, val lastSeen: Long)

/** Session-only evidence, not historical byte accounting. One sampler writes; UI reads copies. */
class EndpointEvidenceIndex(private val capacity: Int = 512) {
    init { require(capacity > 0) }
    private var session: String? = null
    private val entries = LinkedHashMap<Triple<String, String?, String?>, EndpointEvidence>()
    @Synchronized
    fun update(sessionKey: String, atMillis: Long, observed: List<EndpointEvidence>) {
        if (session != sessionKey) { entries.clear(); session = sessionKey }
        entries.entries.removeAll { atMillis - it.value.lastSeen > 3_600_000L || it.value.lastSeen > atMillis }
        for (entry in observed) {
            if (entry.host.isNullOrBlank() && entry.ip.isNullOrBlank()) continue
            val key = Triple(entry.serviceKey, entry.host, entry.ip)
            entries.remove(key)
            entries[key] = entry.copy(lastSeen = atMillis)
            while (entries.size > capacity) entries.remove(entries.keys.first())
        }
    }
    @Synchronized
    fun forService(key: String, now: Long): List<EndpointEvidence> = entries.values
        .filter { it.serviceKey == key && now - it.lastSeen in 0L..3_600_000L }
        .sortedByDescending { it.lastSeen }
    @Synchronized
    fun clear() { entries.clear(); session = null }
}
