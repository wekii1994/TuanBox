package com.github.yumeyucca.yumebox.data.trafficv2

import com.github.yumeyucca.yumebox.core.model.ConnectionInfo
import com.github.yumeyucca.yumebox.core.model.ConnectionSnapshot
import com.github.yumeyucca.yumebox.data.controller.AppIdentityResolver
import kotlinx.serialization.json.*
import java.time.OffsetDateTime

/** Called by the sole sampler on Dispatchers.IO, never by a Composable. */
class ConnectionSampleMapper(private val identities: AppIdentityResolver) {
    private val hosts = object : LinkedHashMap<String, RecognizedHost?>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RecognizedHost?>?) = size > 1024
    }
    private val starts = object : LinkedHashMap<String, Long?>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long?>?) = size > 2048
    }
    private val resolver = ServiceResolver({ host ->
        if (hosts.containsKey(host)) hosts[host] else {
            val parsed = OfflineDomainClassifier.parse(host)
            val recognized = if (parsed.isIp || parsed.brandEntity.entityKey == "category:unknown") null else {
                val entity = parsed.brandEntity
                val key = if (entity.entityKey.startsWith("domain:")) entity.entityKey else "service:${entity.entityKey}"
                RecognizedHost(key, entity.displayName)
            }
            hosts[host] = recognized
            recognized
        }
    })

    fun resolveTarget(connection: ConnectionInfo, localBackend: Boolean): Target {
        val app = if (localBackend) {
            val resolved = runCatching { identities.resolve(connection.metadata) }.getOrNull()
            if (resolved?.packageName == null) AppRef.Unknown
            else AppRef(resolved.appKey, resolved.packageName, resolved.appName)
        } else AppRef.Unknown
        val host = (connection.metadata["host"] as? JsonPrimitive)?.contentOrNull?.trim()
        return resolver.resolve(host, app)
    }

    fun resolveServiceKey(connection: ConnectionInfo, localBackend: Boolean): String =
        resolveTarget(connection, localBackend).key

    fun evidence(snapshot: ConnectionSnapshot, sample: TrafficSample): List<EndpointEvidence> =
        snapshot.connections.zip(sample.connections).map { (raw, mapped) ->
            fun field(key: String) = (raw.metadata[key] as? JsonPrimitive)?.contentOrNull
                ?.trim()?.takeIf { it.isNotEmpty() }
            EndpointEvidence(mapped.target.key, field("host"), field("destinationIP"), sample.atMillis)
        }

    fun map(
        snapshot: ConnectionSnapshot, session: String, atMillis: Long,
        monotonicMillis: Long, localBackend: Boolean,
    ): TrafficSample = TrafficSample(
        session, atMillis, monotonicMillis,
        Bytes(snapshot.uploadTotal, snapshot.downloadTotal),
        snapshot.connections.map { connection ->
            // Remote UIDs must never be resolved against the local phone's installed packages.
            val app = if (localBackend) {
                val resolved = runCatching { identities.resolve(connection.metadata) }.getOrNull()
                if (resolved?.packageName == null) AppRef.Unknown
                else AppRef(resolved.appKey, resolved.packageName, resolved.appName)
            } else AppRef.Unknown
            val host = (connection.metadata["host"] as? JsonPrimitive)?.contentOrNull?.trim()
            val start = if (starts.containsKey(connection.start)) starts[connection.start] else {
                runCatching { OffsetDateTime.parse(connection.start).toInstant().toEpochMilli() }.getOrNull()
                    .also { starts[connection.start] = it }
            }
            ObservedConnection(connection.id, start, Bytes(connection.upload, connection.download),
                app, resolver.resolve(host, app))
        },
    )
}
