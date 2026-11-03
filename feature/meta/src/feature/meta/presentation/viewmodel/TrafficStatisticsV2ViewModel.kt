package com.github.yumeyucca.yumebox.feature.meta.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.yumeyucca.yumebox.data.trafficv2.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.time.LocalDate
import java.time.ZoneId

enum class ServiceRange { TODAY, WEEK }
data class ServiceSelection(val range: ServiceRange, val metric: TrafficMetric)
data class ServicePageState(
    val selection: ServiceSelection = ServiceSelection(ServiceRange.TODAY, TrafficMetric.DOWNLOAD),
    val page: TrafficPage = projectTraffic(emptyList()),
    val loading: Boolean = true,
    val error: String? = null,
    val rows: List<LedgerDelta> = emptyList(),
)

/** UI must use collectAsStateWithLifecycle. No core query loop lives in this ViewModel. */
@OptIn(ExperimentalCoroutinesApi::class)
class TrafficStatisticsV2ViewModel(
    private val dao: ServiceTrafficDao,
    private val clearAll: suspend () -> Unit,
    private val preferences: TrafficUiPreferences,
    private val saved: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private val range = saved.getStateFlow("traffic.range", preferences.range)
    private val metric = saved.getStateFlow("traffic.metric", preferences.metric)
    private val notices = Channel<String>(Channel.BUFFERED)
    val messages = notices.receiveAsFlow()
    private var clearing = false

    private val window = flow {
        while (currentCoroutineContext().isActive) {
            val zone = ZoneId.systemDefault()
            emit(LocalDate.now(zone) to zone)
            delay(30_000L) // Exists only while the subscribed UI is active (+ stop timeout).
        }
    }.distinctUntilChanged()

    private val storedPage = combine(range, window) { raw, (date, zone) ->
        val selected = ServiceRange.entries.firstOrNull { it.name == raw } ?: ServiceRange.TODAY
        val from = date.minusDays(if (selected == ServiceRange.WEEK) 6 else 0).atStartOfDay(zone).toInstant().toEpochMilli()
        val until = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        Triple(selected, from, until)
    }.flatMapLatest { (selected, from, until) ->
        var last = ServicePageState(selection = ServiceSelection(selected, TrafficMetric.DOWNLOAD))
        dao.observe(from, until)
            .map { rows -> ServicePageState(
                selection = ServiceSelection(selected, TrafficMetric.DOWNLOAD),
                page = projectTraffic(rows.map { it.delta() }), loading = false,
                rows = rows.map { it.delta() },
            ).also { last = it } }
            .onStart { emit(last) }
            .retryWhen { cause, attempt ->
                if (cause is CancellationException) throw cause
                emit(last.copy(loading = false, error = "统计读取失败，正在重试"))
                delay(minOf(30_000L, 2_000L * (attempt.coerceAtMost(14) + 1)))
                true
            }
    }.flowOn(Dispatchers.Default)

    val state = combine(storedPage, metric) { page, rawMetric ->
        page.copy(selection = page.selection.copy(metric =
            TrafficMetric.entries.firstOrNull { it.name == rawMetric } ?: TrafficMetric.DOWNLOAD))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ServicePageState())

    fun selectRange(value: ServiceRange) { saved["traffic.range"] = value.name; preferences.range = value.name }
    fun selectMetric(value: TrafficMetric) { saved["traffic.metric"] = value.name; preferences.metric = value.name }
    fun clearStatistics() {
        if (clearing) return
        clearing = true
        viewModelScope.launch {
            try {
                clearAll()
                notices.send("已清空全部统计")
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { notices.send("清空失败，请重试")
            } finally { clearing = false }
        }
    }
}
