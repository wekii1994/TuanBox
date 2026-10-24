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

package com.github.yumeyucca.yumebox.data.model

import com.github.yumeyucca.yumebox.core.model.OverrideInternalConstants
import com.github.yumeyucca.yumebox.core.locale.YumeTxt

/**
 * APK-bundled override templates (from override-hub). Content lives under
 * `assets/overrides/builtin/` and is materialized into the configs dir on demand so the runtime
 * override chain can resolve a real file path.
 */
class BuiltInOverrideDefinition(
    val id: String,
    val assetPath: String,
    val contentType: OverrideContentType,
    private val nameProvider: () -> String,
    val description: String? = null,
) {
    val name: String
        get() = nameProvider()
}

object BuiltInOverrideCatalog {
    private const val ASSET_DIR = "overrides/builtin"

    val all: List<BuiltInOverrideDefinition> =
        listOf(
            BuiltInOverrideDefinition(
                id = "${OverrideInternalConstants.BUILTIN_OVERRIDE_PREFIX}zashboard",
                assetPath = "$ASSET_DIR/zashboard.yaml",
                contentType = OverrideContentType.Yaml,
                nameProvider = { "zashboard" },
                description = "YAML",
            ),
        )

    fun find(id: String): BuiltInOverrideDefinition? = all.firstOrNull { it.id == id }

    fun isBuiltIn(id: String): Boolean = OverrideInternalConstants.isBuiltInOverrideId(id)
}
