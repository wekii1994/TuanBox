package com.github.yumeyucca.yumebox.data.trafficv2

import com.github.yumeyucca.yumebox.core.model.ConnectionSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/** Instantiate exactly once in the process that owns collection, using its service scope. */
class TrafficRuntimeCoordinator(
    running: Flow<Boolean>,
    stamp: () -> RuntimeStamp,
    query: suspend () -> ConnectionSnapshot,
    mapper: ConnectionSampleMapper,
    sink: LedgerSink,
    scope: CoroutineScope,
    monotonicMillis: () -> Long,
    wallMillis: () -> Long = System::currentTimeMillis,
    onConnections: (ConnectionSnapshot) -> Unit = {},
) {
    val demand = RealtimeDemand()
    val evidence = EndpointEvidenceIndex()
    private val source = ConsistentSampleSource(
        stamp, query,
        map = { raw, identity, wall, mono ->
            mapper.map(raw, identity.sessionKey, wall, mono, identity.localBackend)
        }, wallMillis, monotonicMillis,
        onValidated = { raw, sample ->
            evidence.update(sample.session, sample.atMillis, mapper.evidence(raw, sample))
            onConnections(raw)
        },
    )
    val sampler = SharedTrafficSampler(running, demand.enabled, source::read,
        sink, scope, monotonicMillis)
    val live get() = sampler.live
    suspend fun flushBeforeCoreStop() = sampler.flushBeforeCoreStop()
    suspend fun clearStatistics() {
        sampler.clearAll()
        evidence.clear()
    }
}
