// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.simplexray.an.R
import com.simplexray.an.common.formatBytes
import com.simplexray.an.telemetry.Bucket
import com.simplexray.an.telemetry.ClassTotals

/** The colours of the traffic classes, the same in every chart and legend. */
data class ClassColors(val proxy: Color, val direct: Color, val block: Color, val other: Color)

@Composable
fun classColors() =
    ClassColors(
        proxy = MaterialTheme.colorScheme.primary,
        direct = MaterialTheme.colorScheme.tertiary,
        block = MaterialTheme.colorScheme.error,
        other = MaterialTheme.colorScheme.outline,
    )

/**
 * Stacked bars of traffic per bucket, by class. Tapping a bar shows its figures above the chart;
 * [label] names a bucket by its start (an hour, a day).
 */
@Composable
fun TrafficBars(
    buckets: List<Bucket>,
    label: (Long) -> String,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 160.dp,
) {
    val colors = classColors()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val highlight = MaterialTheme.colorScheme.onSurface
    var selected by remember(buckets) { mutableIntStateOf(-1) }
    val max = buckets.maxOfOrNull { it.bytes.total }?.takeIf { it > 0 } ?: 1L
    val summary =
        stringResource(
            R.string.chart_traffic_summary,
            formatBytes(buckets.sumOf { it.bytes.total }),
        )

    Column(modifier) {
        val shown = buckets.getOrNull(selected)
        Text(
            text =
                if (shown == null) stringResource(R.string.chart_peak, formatBytes(max))
                else "${label(shown.start)} · ${breakdown(shown.bytes)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
        Spacer(Modifier.height(6.dp))
        Canvas(
            Modifier.fillMaxWidth()
                .height(height)
                .semantics { contentDescription = summary }
                .pointerInput(buckets) {
                    detectTapGestures { offset ->
                        if (buckets.isEmpty()) return@detectTapGestures
                        val i = (offset.x / (size.width.toFloat() / buckets.size)).toInt()
                        selected = if (i == selected) -1 else i.coerceIn(0, buckets.size - 1)
                    }
                }
        ) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
            listOf(0f, 0.5f, 1f).forEach { f ->
                val y = size.height * f
                drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f, pathEffect = dash)
            }
            if (buckets.isEmpty()) return@Canvas
            val slot = size.width / buckets.size
            // Few buckets would make slabs; keep bars bar-shaped.
            val bar = (slot * 0.7f).coerceIn(1f, 16.dp.toPx())
            buckets.forEachIndexed { i, b ->
                val x = i * slot + (slot - bar) / 2
                var top = size.height
                listOf(
                        b.bytes.proxy to colors.proxy,
                        b.bytes.direct to colors.direct,
                        b.bytes.block to colors.block,
                        b.bytes.other to colors.other,
                    )
                    .forEach { (bytes, color) ->
                        if (bytes <= 0) return@forEach
                        val h = (bytes.toFloat() / max * size.height).coerceAtLeast(1.5f)
                        top -= h
                        drawRoundRect(
                            color,
                            Offset(x, top),
                            Size(bar, h),
                            CornerRadius(minOf(bar / 6, 3f)),
                        )
                    }
                if (i == selected) {
                    drawRect(
                        highlight.copy(alpha = 0.08f),
                        Offset(i * slot, 0f),
                        Size(slot, size.height),
                    )
                }
            }
        }
        if (buckets.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(buckets.first(), buckets[buckets.size / 2], buckets.last()).forEach {
                    Text(
                        label(it.start),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** "1.2 GB: proxy 1 GB, direct 200 MB", leaving out empty classes. */
@Composable
fun breakdown(bytes: ClassTotals): String {
    val parts =
        listOf(
                bytes.proxy to stringResource(R.string.traffic_proxy),
                bytes.direct to stringResource(R.string.traffic_direct),
                bytes.block to stringResource(R.string.traffic_block),
                bytes.other to stringResource(R.string.traffic_other),
            )
            .filter { it.first > 0 }
            .joinToString(", ") { "${it.second} ${formatBytes(it.first)}" }
    return if (parts.isEmpty()) formatBytes(0) else "${formatBytes(bytes.total)}: $parts"
}

/** The classes and their colours; with [bytes], each one's total. */
@Composable
fun ClassLegend(modifier: Modifier = Modifier, bytes: ClassTotals? = null) {
    val colors = classColors()
    val entries =
        listOf(
            Triple(R.string.traffic_proxy, colors.proxy, bytes?.proxy),
            Triple(R.string.traffic_direct, colors.direct, bytes?.direct),
            Triple(R.string.traffic_block, colors.block, bytes?.block),
            Triple(R.string.traffic_other, colors.other, bytes?.other),
        )
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        entries
            .filter { bytes == null || (it.third ?: 0) > 0 || it.first == R.string.traffic_proxy }
            .forEach { (name, color, value) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(color, CircleShape))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(name) + (value?.let { " " + formatBytes(it) } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
    }
}

/** Upload and download rates over the last samples, newest on the right. */
@Composable
fun ThroughputLines(
    samples: List<Pair<Long, Long>>,
    capacity: Int,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 72.dp,
) {
    val up = MaterialTheme.colorScheme.tertiary
    val down = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val max = samples.maxOfOrNull { maxOf(it.first, it.second) }?.takeIf { it > 0 } ?: 1L
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(height).padding(vertical = 2.dp)) {
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1f)
            if (samples.size < 2) return@Canvas
            val step = size.width / (capacity - 1).coerceAtLeast(1)
            val start = size.width - (samples.size - 1) * step
            fun path(value: (Pair<Long, Long>) -> Long) =
                Path().apply {
                    samples.forEachIndexed { i, s ->
                        val x = start + i * step
                        val y = size.height - value(s).toFloat() / max * size.height
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                }
            drawPath(path { it.second }, down, style = Stroke(3f, cap = StrokeCap.Round))
            drawPath(path { it.first }, up, style = Stroke(2f, cap = StrokeCap.Round))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LegendDot(down, stringResource(R.string.chart_download))
            LegendDot(up, stringResource(R.string.chart_upload))
            Text(
                stringResource(R.string.chart_peak, formatBytes(max) + "/s"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelSmall)
    }
}

/** A thin bar split by class, for a row in a list. */
@Composable
fun ClassBar(bytes: ClassTotals, max: Long, modifier: Modifier = Modifier) {
    val colors = classColors()
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier.fillMaxWidth().height(6.dp)) {
        drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2))
        if (max <= 0) return@Canvas
        var x = 0f
        listOf(
                bytes.proxy to colors.proxy,
                bytes.direct to colors.direct,
                bytes.block to colors.block,
                bytes.other to colors.other,
            )
            .forEach { (b, c) ->
                if (b <= 0) return@forEach
                val w = b.toFloat() / max * size.width
                drawRect(c, Offset(x, 0f), Size(w, size.height))
                x += w
            }
    }
}
