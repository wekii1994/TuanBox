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

package com.github.yumeyucca.yumebox.runtime.service.session

import android.content.Context
import kotlinx.coroutines.CoroutineScope

/**
 * Historical connection tracker. The sole sampler in TrafficRuntimeCoordinator now feeds
 * ConnectionHistoryManager on every validated sample, so independent polling here is retired.
 */
internal class ConnectionTracker(
    @Suppress("UnusedPrivateProperty") private val context: Context,
    @Suppress("UnusedPrivateProperty") private val scope: CoroutineScope,
) {
    fun start() {
        // SharedTrafficSampler feeds ConnectionHistoryManager directly.
    }

    fun stop() {
        // No-op
    }
}
