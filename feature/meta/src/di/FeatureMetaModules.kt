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

@file:Suppress("FunctionName")

package com.github.yumeyucca.yumebox.di

import com.github.yumeyucca.yumebox.data.store.room.TrafficStores
import com.github.yumeyucca.yumebox.data.trafficv2.TrafficRuntimeCoordinator
import com.github.yumeyucca.yumebox.feature.meta.presentation.util.CustomRoutingBootstrapper
import com.github.yumeyucca.yumebox.feature.meta.presentation.viewmodel.ConnectionDetailViewModel
import com.github.yumeyucca.yumebox.feature.meta.presentation.viewmodel.ConnectionViewModel
import com.github.yumeyucca.yumebox.feature.meta.presentation.viewmodel.CustomRoutingViewModel
import com.github.yumeyucca.yumebox.feature.meta.presentation.viewmodel.TrafficStatisticsV2ViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val featureMetaViewModelModule = module {
    single { CustomRoutingBootstrapper(get()) }
    viewModel { ConnectionViewModel(androidContext(), get(), get(), get()) }
    viewModel { ConnectionDetailViewModel(androidContext(), get()) }
    viewModel {
        val coordinator = get<TrafficRuntimeCoordinator>()
        TrafficStatisticsV2ViewModel(
            dao = get<TrafficStores>().services,
            clearAll = { coordinator.clearStatistics() },
            preferences = get(),
        )
    }
    viewModel { CustomRoutingViewModel(get(), get(), get()) }
}

val featureMetaModules = listOf(featureMetaViewModelModule)
