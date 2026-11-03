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
import com.github.yumeyucca.yumebox.data.trafficv2.TrafficRuntimeCoordinator
import com.github.yumeyucca.yumebox.runtime.client.access.RuntimeAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * One connection's detail page: watches that connection from the shared coordinator live state.
 */
class ConnectionDetailViewModel(
    private val appContext: Context,
    private val coordinator: TrafficRuntimeCoordinator,
) : ViewModel() {
    data class ConnectionDetailState(
        val info: ConnectionInfo? = null,
        /** False once the connection has closed: the page freezes its last snapshot. */
        val canInterrupt: Boolean = false,
        val isInterrupting: Boolean = false,
        /** False until the first snapshot answers. */
        val hasLoaded: Boolean = false,
    )

    private val _state = MutableStateFlow(ConnectionDetailState())
    val state: StateFlow<ConnectionDetailState> = _state.asStateFlow()

    /** Emitted once per successful interrupt. */
    private val _closed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val closed: SharedFlow<Unit> = _closed.asSharedFlow()

    private var pollingJob: Job? = null
    private var watchedId: String? = null

    /**
     * Starts watching [connectionId].
     */
    fun start(connectionId: String, canInterrupt: Boolean, staged: ConnectionInfo?) {
        if (watchedId == connectionId && pollingJob?.isActive == true) return
        pollingJob?.cancel()
        watchedId = connectionId
        val initial = staged?.takeIf { it.id == connectionId }
        _state.value =
            ConnectionDetailState(
                info = initial,
                canInterrupt = canInterrupt,
                hasLoaded = initial != null,
            )
        pollingJob =
            viewModelScope.launch {
                coordinator.live.collect { live ->
                    val raw = live.raw
                    if (raw != null) {
                        val active = raw.connections.firstOrNull { it.id == connectionId }
                        if (active != null) {
                            _state.update { it.copy(info = active, canInterrupt = true, hasLoaded = true) }
                        } else {
                            val closed = ConnectionHistoryManager.findClosedConnection(connectionId)
                            _state.update {
                                it.copy(
                                    info = closed ?: it.info,
                                    canInterrupt = false,
                                    hasLoaded = true,
                                )
                            }
                        }
                    } else if (live.error != null) {
                        _state.update { it.copy(hasLoaded = true) }
                    }
                }
            }
    }

    /** Stops watching; called when the page leaves. */
    fun stop() {
        pollingJob?.cancel()
        pollingJob = null
        watchedId = null
    }

    /** Asks the core to drop this connection; the page leaves when the core confirms. */
    fun interrupt() {
        val connection = _state.value.info ?: return
        if (_state.value.isInterrupting) return
        _state.update { it.copy(isInterrupting = true) }
        viewModelScope.launch {
            try {
                if (closeConnection(connection.id)) _closed.tryEmit(Unit)
            } finally {
                _state.update { it.copy(isInterrupting = false) }
            }
        }
    }

    private suspend fun closeConnection(id: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                RuntimeAccess.connect(appContext)
                RuntimeAccess.core().closeConnection(id)
            }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    Timber.w(error, "Failed to close connection: %s", id)
                }
                .getOrDefault(false)
        }
}
