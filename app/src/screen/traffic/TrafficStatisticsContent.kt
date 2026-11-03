package com.github.yumeyucca.yumebox.screen.traffic

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.github.yumeyucca.yumebox.common.util.ByteFormatter
import com.github.yumeyucca.yumebox.data.trafficv2.*
import com.github.yumeyucca.yumebox.feature.meta.presentation.viewmodel.*
import top.yukonga.miuix.kmp.basic.Text

/** Insert inside the existing Scaffold. Reuse the app's chart and bottom sheet. */
@Composable
fun TrafficStatisticsContent(
    state: ServicePageState,
    onRange: (ServiceRange) -> Unit,
    onMetric: (TrafficMetric) -> Unit,
    onService: (String) -> Unit,
    chart: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    status: String? = null,
) {
    var expanded by rememberSaveable(state.selection.range, state.selection.metric) { mutableStateOf(false) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "range") {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf(ServiceRange.TODAY to "今日", ServiceRange.WEEK to "近 7 天").forEach { (range, title) ->
                    Text(title, modifier = Modifier.selectable(state.selection.range == range,
                        role = Role.RadioButton, onClick = { onRange(range) }).padding(12.dp))
                }
            }
        }
        item(key = "overview") { TrafficMetricChoices(state.page.total, state.selection.metric, onMetric) }
        if (!state.loading) item(key = "chart") { chart() }
        val message = state.error ?: status
        if (message != null) item(key = "status") { Text(message) }
        if (state.selection.range == ServiceRange.TODAY && !state.page.legacyWithoutHour.empty) {
            item(key = "legacy_note") { Text("部分旧记录只有每日总量，未分配到小时图表") }
        }
        item(key = "service_header") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("目标服务")
                Text(when (state.selection.metric) {
                    TrafficMetric.DOWNLOAD -> "按下载排序"
                    TrafficMetric.UPLOAD -> "按上传排序"
                    TrafficMetric.TOTAL -> "按合计排序"
                })
            }
        }
        if (state.loading) item(key = "loading") { Text("正在读取统计…") }
        else if (state.page.total.value(state.selection.metric) == 0L) {
            item(key = "empty") { Text("当前时段暂无此项流量") }
        } else trafficServiceItems(state.page, state.selection.metric, expanded,
            onExpand = { expanded = true }, onService = onService)
    }
}

/** Place in the existing scrollable sheet. This composable never starts polling. */
@Composable
fun ServiceDetailContent(detail: ServiceDetail, rangeLabel: String, evidence: List<EndpointEvidence>) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(detail.label)
        Text(rangeLabel)
        Text("下载 ${ByteFormatter.format(detail.bytes.down)} · 上传 ${ByteFormatter.format(detail.bytes.up)} · 合计 ${ByteFormatter.format(detail.bytes.value(TrafficMetric.TOTAL))}")
        Text("来源应用")
        detail.sources.forEach { share ->
            Text("${share.app.label} · 下载 ${ByteFormatter.format(share.bytes.down)} · 上传 ${ByteFormatter.format(share.bytes.up)}")
        }
        if (detail.key == "other") {
            Text("包含缺少目标或连接明细的流量，以及无法恢复服务归属的旧记录。")
        }
        Text("当前会话最近观测到的目标（最多保留 1 小时）")
        if (evidence.isEmpty()) Text("暂无目标记录")
        else evidence.take(20).forEach { entry ->
            Text(listOfNotNull(entry.host, entry.ip).distinct().joinToString(" · "))
        }
        if (evidence.size > 20) Text("另有 ${evidence.size - 20} 个目标，请在连接页查看当前连接")
    }
}
