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

package com.github.yumeyucca.yumebox.di

import com.github.yumeyucca.yumebox.common.util.AppLanguageManager
import com.github.yumeyucca.yumebox.BuildInfo
import com.github.yumeyucca.yumebox.analytics.ANALYTICS_ENDPOINT
import com.github.yumeyucca.yumebox.core.domain.ConnectionHistoryManager
import com.github.yumeyucca.yumebox.data.controller.*
import com.github.yumeyucca.yumebox.data.analytics.AnonymousAnalyticsReporter
import com.github.yumeyucca.yumebox.data.store.*
import com.github.yumeyucca.yumebox.data.store.room.TrafficStores
import com.github.yumeyucca.yumebox.data.store.room.createTrafficStores
import com.github.yumeyucca.yumebox.data.trafficv2.*
import com.github.yumeyucca.yumebox.data.update.AppUpdateRepository
import com.github.yumeyucca.yumebox.runtime.api.RuntimeOwner
import com.github.yumeyucca.yumebox.runtime.client.ProfilesRepository
import com.github.yumeyucca.yumebox.runtime.client.ProxyFacade
import com.github.yumeyucca.yumebox.runtime.client.access.RuntimeAccess
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import timber.log.Timber

const val APPLICATION_SCOPE_NAME = "applicationScope"

/** One named MMKV storage: the Koin qualifier and the on-disk id come from the same argument. */
private fun Module.mmkv(id: String) {
    single<MMKV>(named(id)) { get<MMKVProvider>().getMMKV(id) }
}

val appFoundationModule = module {
    single<CoroutineScope>(named(APPLICATION_SCOPE_NAME)) {
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.Default +
                CoroutineExceptionHandler { _, error ->
                    Timber.e(error, "Uncaught failure in the application scope")
                },
        )
    }

    single { MMKVProvider() }
    mmkv("profiles")
    mmkv("settings")
    mmkv("network_settings")
    mmkv("substore")
    mmkv("proxy_display")
    mmkv("traffic_statistics")
    mmkv("profile_links")
    mmkv("service_cache")
    mmkv("override_bindings")
    mmkv("remote_controller")

    single { AppSettingsStore(get<MMKV>(named("settings"))) }
    single { NetworkSettingsStore(get(named("network_settings"))) }
    single { RemoteControllerStore(get(named("remote_controller"))) }
    single { ProfileLinksStore(get(named("profile_links"))) }
    single { FeatureStore(get(named("substore"))) }
    single { ProxyDisplaySettingsStore(get(named("proxy_display"))) }

    single { createTrafficStores(androidApplication()) }
    single { get<TrafficStores>().legacy }
    single { get<TrafficStores>().services }
    single { TrafficUiPreferences(get<MMKV>(named("traffic_statistics"))) }
    single { ConnectionSampleMapper(get()) }
}

val appDataRuntimeModule = module {
    single { AppUpdateRepository() }
    single {
        AnonymousAnalyticsReporter(
            mmkv = get<MMKVProvider>().getMMKV("settings"),
            endpoint = ANALYTICS_ENDPOINT,
            appVersion = BuildInfo.VERSION_NAME,
            versionCode = BuildInfo.VERSION_CODE,
        )
    }
    single { AppSettingsController(get(), applyLanguage = AppLanguageManager::apply) }
    single {
        val proxyFacade = get<ProxyFacade>()
        NetworkSettingsController(
            store = get(),
            isRunning = { proxyFacade.runtimeSnapshot.value.running },
            restartProxy = { mode -> proxyFacade.reloadProxy(mode) },
        )
    }
    single {
        val proxyFacade = get<ProxyFacade>()
        AccessControlController(
            store = get(),
            isRunning = { proxyFacade.isRunning.value },
            resolveActiveMode = {
                proxyFacade.runtimeSnapshot.value.let { snapshot ->
                    snapshot.runMode.takeIf {
                        snapshot.owner != RuntimeOwner.None &&
                            snapshot.owner != RuntimeOwner.RemoteController
                    }
                }
            },
            restartProxy = { mode -> proxyFacade.reloadProxy(mode) },
        )
    }
    single {
        val appContext = androidContext()
        ProvidersController(
            context = appContext,
            queryProvidersAction = {
                RuntimeAccess.connect(appContext)
                RuntimeAccess.core().queryProviders()
            },
            updateProviderAction = { type, name ->
                RuntimeAccess.connect(appContext)
                RuntimeAccess.core().updateProvider(type, name)
            },
        )
    }

    single { ProfileBindingStore(androidContext()) }
    single<ProfileBindingProvider> { get<ProfileBindingStore>() }

    single { OverrideConfigStore(androidContext(), get()) }
    single<OverrideConfigProvider> { get<OverrideConfigStore>() }

    single { OverrideResolver(get(), get()) }
    single { OverrideService(androidContext(), get()) }
    single {
        val profilesRepository = get<ProfilesRepository>()
        ActiveProfileOverrideReloader(
            queryActiveProfile = { profilesRepository.queryActiveProfile() },
            bindingProvider = get(),
            overrideService = get(),
        )
    }

    single { ProxyFacade(androidContext(), get(), get()) }
    single { AppIdentityResolver(androidContext()) }
    single { ProfilesRepository(androidContext()) }
    single {
        val proxyFacade = get<ProxyFacade>()
        val remoteControllerStore = get<RemoteControllerStore>()
        val stores = get<TrafficStores>()
        val appScope = get<CoroutineScope>(named(APPLICATION_SCOPE_NAME))
        val coordinator = TrafficRuntimeCoordinator(
            running = proxyFacade.isRunning,
            stamp = {
                val snapshot = proxyFacade.runtimeSnapshot.value
                val isLocal = !remoteControllerStore.controllerEnabled.value || !remoteControllerStore.controllerAttached.value
                val sessionKey = if (isLocal) {
                    "local:${snapshot.startedAt ?: 0L}"
                } else {
                    "remote:${remoteControllerStore.activeBackendId.value}"
                }
                RuntimeStamp(
                    sessionKey = sessionKey,
                    revision = snapshot.generation,
                    running = snapshot.running,
                    reloading = proxyFacade.isConfigReloading.value,
                    localBackend = isLocal,
                )
            },
            query = { proxyFacade.queryConnections() },
            mapper = get(),
            sink = stores.services,
            scope = appScope,
            monotonicMillis = { android.os.SystemClock.elapsedRealtime() },
            wallMillis = { System.currentTimeMillis() },
            onConnections = { raw ->
                ConnectionHistoryManager.updateConnections(raw.connections)
            },
        )
        proxyFacade.onBeforeStop = {
            runCatching {
                withTimeoutOrNull(3_000L) {
                    coordinator.flushBeforeCoreStop()
                }
            }
        }
        coordinator
    }
}
