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

package com.github.yumeyucca.yumebox.data.analytics

import com.tencent.mmkv.MMKV

/**
 * Anonymous usage analytics reporter.
 * Disabled in TuanBox: prevent all device identification and network telemetry.
 */
class AnonymousAnalyticsReporter(
    private val mmkv: MMKV? = null,
    private val endpoint: String = "",
    private val appVersion: String = "",
    private val versionCode: Int = 0,
) {
    suspend fun reportLaunch() {
        // No-op: Anonymous usage reporting is disabled in TuanBox
    }
}
