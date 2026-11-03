package com.github.yumeyucca.yumebox.screen.traffic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.yumeyucca.yumebox.common.util.ByteFormatter
import com.github.yumeyucca.yumebox.data.trafficv2.*
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale

/** Place in existing overview card. Reuse the existing date selector and chart. */
@Composable
fun TrafficMetricChoices(total: Bytes, selected: TrafficMetric, onSelect: (TrafficMetric) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(TrafficMetric.DOWNLOAD to "下载", TrafficMetric.UPLOAD to "上传", TrafficMetric.TOTAL to "合计")
            .forEach { (metric, label) ->
                val chosen = metric == selected
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                        .background(if (chosen) MiuixTheme.colorScheme.primary.copy(alpha = .1f)
                            else MiuixTheme.colorScheme.surface)
                        .selectable(chosen, role = Role.RadioButton, onClick = { onSelect(metric) })
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                ) {
                    Text(label, style = MiuixTheme.textStyles.footnote1)
                    Text(ByteFormatter.format(total.value(metric)),
                        style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium,
                        color = if (chosen) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface)
                }
            }
    }
}

/** Direct children of the existing page's LazyColumn, not one giant nested Column. */
fun LazyListScope.trafficServiceItems(
    page: TrafficPage,
    metric: TrafficMetric,
    expanded: Boolean,
    onExpand: () -> Unit,
    onService: (String) -> Unit,
) {
    val (visible, remainder) = page.visible(metric, if (expanded) Int.MAX_VALUE else 8)
    items(visible, key = { "service:${it.key}" }, contentType = { "service_row" }) { row ->
        ServiceTrafficRow(row, metric, page.total.value(metric)) { onService(row.key) }
    }
    if (remainder.value(metric) > 0) item(key = "service:more", contentType = "service_more") {
        Row(Modifier.fillMaxWidth().clickable(onClick = onExpand).padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("其他 ${page.services.count { it.bytes.value(metric) > 0 } - visible.size} 个目标")
            Text(ByteFormatter.format(remainder.value(metric)))
        }
    }
    // No duplicate "本页合计" footer. "其他流量" participates as a normal service row.
}

@Composable
private fun ServiceTrafficRow(row: ServiceRow, metric: TrafficMetric, total: Long, onClick: () -> Unit) {
    val value = row.bytes.value(metric)
    val fraction = if (total > 0) (value.toDouble() / total).coerceIn(0.0, 1.0) else 0.0
    val percentage = if (value > 0 && fraction < .001) "<0.1%"
        else String.format(Locale.getDefault(), "%.1f%%", fraction * 100)
    Column(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.surface)
        .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(row.label, modifier = Modifier.weight(1f), maxLines = 1,
                overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(ByteFormatter.format(value))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(row.subtitle(), Modifier.weight(1f), style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            Text(percentage, style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(3.dp).background(MiuixTheme.colorScheme.surfaceVariant)) {
            if (fraction > 0) Box(Modifier.fillMaxWidth(fraction.toFloat()).height(3.dp)
                .background(MiuixTheme.colorScheme.primary))
        }
    }
}
