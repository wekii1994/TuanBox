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

@file:Suppress("UnusedSymbol", "FunctionName")

package com.github.yumeyucca.yumebox.feature.meta.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.yumeyucca.yumebox.core.domain.ConnectionHistoryManager
import com.github.yumeyucca.yumebox.core.model.ConnectionInfo
import com.github.yumeyucca.yumebox.core.model.ConnectionSnapshot
import com.github.yumeyucca.yumebox.data.store.RemoteControllerStore
import com.github.yumeyucca.yumebox.data.trafficv2.ConnectionSampleMapper
import com.github.yumeyucca.yumebox.data.trafficv2.TrafficRuntimeCoordinator
import com.github.yumeyucca.yumebox.feature.meta.presentation.util.displayTarget
import com.github.yumeyucca.yumebox.feature.meta.presentation.util.stringOrEmpty
import com.github.yumeyucca.yumebox.runtime.client.access.RuntimeAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

enum class ConnectionSort {
    Time,
    Upload,
    Download,
    Host,
}

enum class ConnectionTab {
    ACTIVE,
    CLOSED,
}

data class ConnectionState(
    val snapshot: ConnectionSnapshot? = null,
    val isLoading: Boolean = true,
    val searchQuery: String = "",
    val sortBy: ConnectionSort = ConnectionSort.Time,
    val selectedTab: ConnectionTab = ConnectionTab.ACTIVE,
    val error: String? = null,
    val serviceKey: String? = null,
)

class ConnectionViewModel(
    private val appContext: Context,
    private val coordinator: TrafficRuntimeCoordinator,
    private val mapper: ConnectionSampleMapper,
    private val remoteControllerStore: RemoteControllerStore,
) : ViewModel() {
    private val _state = MutableStateFlow(ConnectionState())
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    data class ConnectionScreenState(
        val state: ConnectionState = ConnectionState(),
        val filteredConnections: List<ConnectionInfo> = emptyList(),
    )

    /** Derived from [state] alone, so the snapshot and its projection can never disagree. */
    val screenState: StateFlow<ConnectionScreenState> =
        state
            .map { current -> ConnectionScreenState(current, buildFilteredConnections(current)) }
            .flowOn(Dispatchers.Default)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = ConnectionScreenState(),
            )

    init {
        // Collect live snapshot from shared coordinator - no separate polling loop
        viewModelScope.launch {
            coordinator.live.collect { live ->
                _state.update { current ->
                    current.copy(
                        snapshot = live.raw,
                        error = live.error,
                        isLoading = live.raw == null && live.error == null,
                    )
                }
            }
        }
        // When active backend changes, clear serviceKey filter
        viewModelScope.launch {
            remoteControllerStore.activeBackendId.state.collect {
                _state.update { it.copy(serviceKey = null) }
            }
        }
    }

    /** Kept for backwards compatibility; the coordinator drives polling via demand lease. */
    fun startPolling() {
    }

    fun stopPolling() {
    }

    fun setServiceKey(key: String?) {
        _state.update { it.copy(serviceKey = key) }
    }

    fun setSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    fun setSortBy(sort: ConnectionSort) {
        _state.update { it.copy(sortBy = sort) }
    }

    fun setTab(tab: ConnectionTab) {
        _state.update { it.copy(selectedTab = tab) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    suspend fun closeConnection(id: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                RuntimeAccess.connect(appContext)
                RuntimeAccess.core().closeConnection(id)
            }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    Timber.w(error, "Failed to close connection: %s", id)
                    _state.update { it.copy(error = error.message) }
                }
                .getOrDefault(false)
        }

    private fun buildFilteredConnections(currentState: ConnectionState): List<ConnectionInfo> {
        val isLocal = !remoteControllerStore.controllerEnabled.value || !remoteControllerStore.controllerAttached.value
        val connections =
            when (currentState.selectedTab) {
                ConnectionTab.ACTIVE -> {
                    val raw = currentState.snapshot?.connections ?: emptyList()
                    if (currentState.serviceKey != null) {
                        raw.filter { conn ->
                            mapper.resolveServiceKey(conn, isLocal) == currentState.serviceKey
                        }
                    } else {
                        raw
                    }
                }
                ConnectionTab.CLOSED -> {
                    if (currentState.serviceKey != null) {
                        // 筛选为空显示无当前连接，不拿历史关闭记录填充
                        emptyList()
                    } else {
                        ConnectionHistoryManager.getClosedConnections()
                    }
                }
            }

        val filtered =
            if (currentState.searchQuery.isEmpty()) {
                connections
            } else {
                val query = currentState.searchQuery.lowercase()
                connections.filter { conn ->
                    val host = conn.displayTarget().lowercase()
                    val process = conn.metadata.stringOrEmpty("process").lowercase()
                    val chains = conn.chains.joinToString(" ").lowercase()
                    val rule = conn.rule.lowercase()

                    host.contains(query) ||
                            process.contains(query) ||
                            chains.contains(query) ||
                            rule.contains(query)
                }
            }

        val sorted =
            if (currentState.selectedTab == ConnectionTab.ACTIVE) {
                when (currentState.sortBy) {
                    ConnectionSort.Time -> filtered.sortedByDescending { it.start }
                    ConnectionSort.Upload -> filtered.sortedByDescending { it.upload }
                    ConnectionSort.Download -> filtered.sortedByDescending { it.download }
                    ConnectionSort.Host -> filtered.sortedBy { it.displayTarget() }
                }
            } else {
                filtered
            }
        return sorted
    }
}
