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

@file:Suppress("DuplicatedCode", "FunctionName")

package com.github.yumeyucca.yumebox.screen.connection

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.github.yumeyucca.yumebox.core.locale.YumeTxt
import com.github.yumeyucca.yumebox.core.model.ConnectionInfo
import com.github.yumeyucca.yumebox.data.trafficv2.TrafficRuntimeCoordinator
import com.github.yumeyucca.yumebox.feature.meta.presentation.component.ConnectionCard
import com.github.yumeyucca.yumebox.feature.meta.presentation.viewmodel.ConnectionTab
import com.github.yumeyucca.yumebox.feature.meta.presentation.viewmodel.ConnectionViewModel
import com.github.yumeyucca.yumebox.presentation.component.*
import com.github.yumeyucca.yumebox.presentation.navigation.Route
import com.github.yumeyucca.yumebox.presentation.theme.AppTheme
import com.github.yumeyucca.yumebox.presentation.theme.UiDp
import kotlinx.coroutines.awaitCancellation
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Tab label, listed in [ConnectionTab] order; the segmented row indexes tabs by ordinal. */
private val ConnectionTab.label: String
    get() =
        when (this) {
            ConnectionTab.ACTIVE -> YumeTxt.Connection.Tab.Active
            ConnectionTab.CLOSED -> YumeTxt.Connection.Tab.Closed
        }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConnectionScreen(navigator: Navigator, initialServiceKey: String? = null) {
    val viewModel = koinViewModel<ConnectionViewModel>()
    val coordinator = koinInject<TrafficRuntimeCoordinator>()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(lifecycleOwner, coordinator) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val lease = coordinator.demand.acquire()
            try {
                awaitCancellation()
            } finally {
                lease.close()
            }
        }
    }

    LaunchedEffect(initialServiceKey) {
        if (initialServiceKey != null) {
            viewModel.setServiceKey(initialServiceKey)
        }
    }

    val screen by viewModel.screenState.collectAsState()
    val search = rememberSearchPagerState()
    val scrollBehavior = MiuixScrollBehavior()
    val mainListState = rememberLazyListState()
    val mainLikePadding = rememberStandalonePageMainPadding()

    fun openConnectionDetail(connection: ConnectionInfo) {
        // Collapse search first so the expand gesture never races the detail push.
        search.collapse()
        ConnectionDetailHolder.setup(info = connection)
        navigator.push(
            Route.ConnectionDetail(
                connectionId = connection.id,
                canInterrupt = screen.state.selectedTab == ConnectionTab.ACTIVE,
            )
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ConnectionListScaffold(
            screen = screen,
            search = search,
            scrollBehavior = scrollBehavior,
            listState = mainListState,
            onTabSelected = { viewModel.setTab(it) },
            onOpenConnection = { openConnectionDetail(it) },
            onClearServiceFilter = { viewModel.setServiceKey(null) },
        )

        ConnectionSearchOverlay(
            screen = screen,
            search = search,
            scrollBehavior = scrollBehavior,
            bottomPadding = mainLikePadding.calculateBottomPadding(),
            onQueryChange = viewModel::setSearchQuery,
            onOpenConnection = { openConnectionDetail(it) },
        )
    }
}

/** Collapsed-search chrome plus the connection list itself. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConnectionListScaffold(
    screen: ConnectionViewModel.ConnectionScreenState,
    search: SearchPagerState,
    scrollBehavior: ScrollBehavior,
    listState: LazyListState,
    onTabSelected: (ConnectionTab) -> Unit,
    onOpenConnection: (ConnectionInfo) -> Unit,
    onClearServiceFilter: () -> Unit,
) {
    val spacing = AppTheme.spacing
    val listStartPadding = spacing.screenHorizontal
    val listEndPadding = spacing.screenHorizontal
    val tabs = ConnectionTab.entries.map { it.label }
    val emptyText =
        when {
            screen.state.isLoading -> YumeTxt.Connection.Loading
            screen.state.serviceKey != null && screen.filteredConnections.isEmpty() -> "当前无相关活动连接"
            screen.state.searchQuery.isNotEmpty() -> YumeTxt.Connection.NoResults
            else -> YumeTxt.Connection.Empty
        }
    val dynamicTopPadding by rememberCollapsedTopPadding(scrollBehavior)

    GlassScaffold(
        topBar = {
            search.TopAppBarAnim {
                TopBar(
                    title = YumeTxt.Connection.Title,
                    scrollBehavior = scrollBehavior,
                    bottomContent = {
                        search.CollapsedBar(
                            label = YumeTxt.Connection.SearchHint,
                            topPadding = dynamicTopPadding,
                            startPadding = listStartPadding,
                            endPadding = listEndPadding,
                        )
                    },
                )
            }
        }
    ) { innerPadding ->
        // Keep list during COLLAPSING so it is already under the fading overlay.
        if (search.keepsPageMounted) {
            val combinedInnerPadding = standalonePageInnerPadding(innerPadding)
            ScreenLazyColumn(
                scrollBehavior = scrollBehavior,
                innerPadding = combinedInnerPadding,
                lazyListState = listState,
                contentPadding =
                    PaddingValues(
                        start = listStartPadding,
                        end = listEndPadding,
                        top = combinedInnerPadding.calculateTopPadding(),
                        bottom = combinedInnerPadding.calculateBottomPadding() + spacing.space12,
                    ),
            ) {
                stickySegmentedTabs(
                    key = "__connection_tabs__",
                    tabs = tabs,
                    selectedTabIndex = screen.state.selectedTab.ordinal,
                    onTabSelected = { index -> onTabSelected(ConnectionTab.entries[index]) },
                    tabRowPadding = UiDp.dp0,
                )

                if (screen.state.serviceKey != null) {
                    item(key = "service_filter_banner") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = spacing.space8)
                                .clip(RoundedCornerShape(UiDp.dp8))
                                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
                                .padding(horizontal = spacing.space12, vertical = spacing.space8),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "正在筛选目标服务: ${screen.state.serviceKey}",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                            )
                            IconButton(onClick = onClearServiceFilter, modifier = Modifier.size(UiDp.dp24)) {
                                Icon(
                                    imageVector = MiuixIcons.Close,
                                    contentDescription = "清除筛选",
                                    tint = MiuixTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }

                if (screen.filteredConnections.isEmpty()) {
                    item {
                        ConnectionEmptyText(
                            text = emptyText,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    connectionItems(screen.filteredConnections, onOpenConnection)
                }
            }
        }
    }
}

/** Full-window search page; only meaningful while [search] is expanded. */
@Composable
private fun ConnectionSearchOverlay(
    screen: ConnectionViewModel.ConnectionScreenState,
    search: SearchPagerState,
    scrollBehavior: ScrollBehavior,
    bottomPadding: Dp,
    onQueryChange: (String) -> Unit,
    onOpenConnection: (ConnectionInfo) -> Unit,
) {
    val spacing = AppTheme.spacing
    val listStartPadding = spacing.screenHorizontal
    val listEndPadding = spacing.screenHorizontal
    val dynamicTopPadding by rememberCollapsedTopPadding(scrollBehavior)

    search.SearchPager(
        label = YumeTxt.Connection.SearchHint,
        query = screen.state.searchQuery,
        onQueryChange = onQueryChange,
        hasResults = screen.filteredConnections.isNotEmpty(),
        padding =
            SearchBarPadding(
                top = dynamicTopPadding,
                start = listStartPadding,
                end = listEndPadding,
            ),
        emptyResult = {
            ConnectionEmptyText(
                text = YumeTxt.Connection.NoResults,
                modifier = Modifier.fillMaxSize().padding(bottom = bottomPadding),
            )
        },
    ) {
        search.SearchResults(
            query = screen.state.searchQuery,
            start = listStartPadding,
            end = listEndPadding,
            bottom = bottomPadding,
        ) {
            connectionItems(screen.filteredConnections, onOpenConnection)
        }
    }
}

/** One definition of a connection row's list identity and spacing. */
private fun LazyListScope.connectionItems(
    connections: List<ConnectionInfo>,
    onConnectionClick: (ConnectionInfo) -> Unit,
) {
    items(
        items = connections,
        key = { it.id },
        contentType = { "connection" },
    ) { connection ->
        ConnectionCard(
            connectionInfo = connection,
            onClick = { onConnectionClick(connection) },
            modifier = Modifier.padding(vertical = AppTheme.spacing.space6),
        )
    }
}

/** The slice's one quiet, centered empty-state line. */
@Composable
internal fun ConnectionEmptyText(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.padding(AppTheme.spacing.space32),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}
