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

package com.github.yumeyucca.yumebox.core.domain

import com.github.yumeyucca.yumebox.core.model.ConnectionInfo

object ConnectionHistoryManager {
    private const val MAX_SIZE = 100

    private val closedById = LinkedHashMap<String, ConnectionInfo>()
    private var previousConnections: Map<String, ConnectionInfo> = emptyMap()
    private val lock = Any()

    fun updateConnections(currentConnections: List<ConnectionInfo>) {
        synchronized(lock) {
            val currentMap = currentConnections.associateBy { it.id }
            val currentIds = currentMap.keys

            currentIds.forEach(closedById::remove)
            previousConnections.forEach { (id, connection) ->
                if (id !in currentIds) {
                    closedById.remove(id)
                    closedById[id] = connection
                }
            }

            while (closedById.size > MAX_SIZE) {
                val oldest = closedById.keys.iterator()
                oldest.next()
                oldest.remove()
            }

            previousConnections = currentMap
        }
    }

    fun getClosedConnections(): List<ConnectionInfo> {
        synchronized(lock) {
            return closedById.values.toList().asReversed()
        }
    }

    fun findClosedConnection(id: String): ConnectionInfo? {
        synchronized(lock) {
            return closedById[id]
        }
    }

    fun clear() {
        synchronized(lock) {
            closedById.clear()
            previousConnections = emptyMap()
        }
    }
}
