package com.github.yumeyucca.yumebox.data.trafficv2

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LedgerBatch(
    val writer: String, val sequence: Long,
    val rows: List<LedgerDelta>, val gaps: List<CoverageGap>,
)
interface LedgerSink {
    /** Atomic and idempotent for (writer, sequence). */
    suspend fun persist(batch: LedgerBatch)
    /** Clear new and legacy statistics together. Leave the active batch deduplication cursor intact. */
    suspend fun clearStatistics()
}
data class SampleEnvelope<T>(val sample: TrafficSample, val raw: T)
data class LiveTraffic<T>(val raw: T? = null, val capturedAt: Long? = null, val error: String? = null)

class RealtimeDemand {
    private val users = MutableStateFlow(0)
    val enabled: Flow<Boolean> = users.map { it > 0 }.distinctUntilChanged()
    fun acquire(): AutoCloseable {
        users.update { it + 1 }
        val released = AtomicBoolean(false)
        return AutoCloseable { if (released.compareAndSet(false, true)) users.update { it - 1 } }
    }
}

/**
 * The only statistics query loop. 15s normally; 2s only for a visible connection diagnosis page.
 * Stats screen subscribes to the ledger and NEVER acquires realtime demand.
 * The supplied scope belongs to the existing runtime/application service, not a screen ViewModel.
 */
class SharedTrafficSampler<T>(
    private val running: Flow<Boolean>,
    private val realtime: Flow<Boolean>,
    private val read: suspend () -> SampleEnvelope<T>,
    private val sink: LedgerSink,
    private val scope: CoroutineScope,
    private val monotonicMillis: () -> Long,
    private val ledger: TrafficLedger = TrafficLedger(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Mutex()
    private val pending = PendingLedger()
    private var inFlight: LedgerBatch? = null
    private val writer = UUID.randomUUID().toString()
    private var sequence = 0L
    private var lastFlushAt = Long.MIN_VALUE
    private val fast = realtime.stateIn(scope, SharingStarted.Eagerly, false)
    private val _live = MutableStateFlow(LiveTraffic<T>())
    val live: StateFlow<LiveTraffic<T>> = _live.asStateFlow()

    val job: Job = scope.launch(dispatcher) {
        running.distinctUntilChanged().collectLatest { active ->
            if (!active) {
                _live.update { it.copy(raw = null) }
                return@collectLatest
            }
            lock.withLock { ledger.clear() }
            var failures = 0
            try {
                while (currentCoroutineContext().isActive) {
                    try {
                        lock.withLock {
                            // Resolve an uncertain previous commit before accepting any more samples.
                            if (inFlight != null) flushLocked()
                            val envelope = withTimeoutOrNull(10_000L) { read() }
                                ?: throw IllegalStateException("内核查询超时")
                            pending.add(ledger.accept(envelope.sample))
                            _live.value = LiveTraffic(envelope.raw, envelope.sample.atMillis)
                            val now = monotonicMillis()
                            if (lastFlushAt == Long.MIN_VALUE || now - lastFlushAt >= 15_000L || pending.size >= 4096) {
                                flushLocked()
                                lastFlushAt = now
                            }
                        }
                        failures = 0
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        failures = (failures + 1).coerceAtMost(4)
                        _live.update { it.copy(error = "统计暂未更新，正在重试") }
                    }
                    val wasFast = fast.value
                    val delayMillis = if (failures > 0) {
                        (15_000L shl (failures - 1)).coerceAtMost(60_000L)
                    } else if (wasFast) 2_000L else 15_000L
                    // A foreground demand change can wake this existing loop; it creates no second loop.
                    withTimeoutOrNull(delayMillis) { fast.first { it != wasFast } }
                }
            } finally {
                // Best effort on normal service stop; no new query on a stopped core.
                withContext(NonCancellable + dispatcher) {
                    withTimeoutOrNull(3_000L) {
                        lock.withLock {
                            try { flushLocked() } catch (_: Exception) {
                                _live.update { it.copy(error = "部分统计尚未保存") }
                            }
                            ledger.clear()
                        }
                    }
                }
            }
        }
    }

    private suspend fun flushLocked() {
        if (inFlight == null && !pending.isEmpty) {
            inFlight = LedgerBatch(writer, sequence + 1L, pending.rows(), pending.gaps())
            pending.clear() // Frozen batch owns these bytes until persistence is acknowledged.
        }
        val batch = inFlight ?: return
        sink.persist(batch)
        sequence = batch.sequence
        inFlight = null
    }

    /** Invoke before the owner shuts down the core, while query still works. Never blocks stop forever. */
    suspend fun flushBeforeCoreStop() = withContext(dispatcher) {
        withTimeout(3_000L) {
            lock.withLock {
                if (inFlight != null) flushLocked()
                val envelope = read()
                pending.add(ledger.accept(envelope.sample))
                _live.value = LiveTraffic(envelope.raw, envelope.sample.atMillis)
                flushLocked()
                lastFlushAt = monotonicMillis()
            }
        }
    }

    suspend fun clearAll() = withContext(dispatcher) {
        lock.withLock {
            // If a prior commit succeeded but its response was lost, retry first, then delete.
            flushLocked()
            sink.clearStatistics()
            pending.clear()
            inFlight = null
            ledger.clear()
            lastFlushAt = Long.MIN_VALUE
        }
    }
}
