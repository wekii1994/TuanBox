package com.github.yumeyucca.yumebox.data.trafficv2

/** revision is a query-consistency fence; sessionKey is a STABLE core/backend identity. */
data class RuntimeStamp(
    val sessionKey: String,
    val revision: Long,
    val running: Boolean,
    val reloading: Boolean,
    val localBackend: Boolean,
)

class ConsistentSampleSource<T>(
    private val stamp: () -> RuntimeStamp,
    private val query: suspend () -> T,
    private val map: (T, RuntimeStamp, Long, Long) -> TrafficSample,
    private val wallMillis: () -> Long,
    private val monotonicMillis: () -> Long,
    private val onValidated: (T, TrafficSample) -> Unit = { _, _ -> },
) {
    suspend fun read(): SampleEnvelope<T> {
        val before = stamp()
        check(before.running && !before.reloading) { "Runtime unavailable" }
        val raw = query()
        val after = stamp()
        check(before == after && after.running && !after.reloading) { "Runtime changed during query" }
        val sample = map(raw, after, wallMillis(), monotonicMillis())
        check(sample.session == after.sessionKey) { "Incorrect session mapping" }
        onValidated(raw, sample)
        return SampleEnvelope(sample, raw)
    }
}
