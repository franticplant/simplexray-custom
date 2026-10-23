// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.simplexray.an.R
import com.simplexray.an.common.formatBytes
import com.simplexray.an.common.formatNumber
import com.simplexray.an.common.formatUptime
import com.simplexray.an.core.InstanceStatus
import com.simplexray.an.viewmodel.InstanceState
import com.simplexray.an.viewmodel.MainViewModel
import kotlinx.coroutines.delay

/** Polls instance state and stats every second while the calling screen is resumed. */
@Composable
fun PollCoreStats(mainViewModel: MainViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                mainViewModel.updateCoreStats()
                delay(1000)
            }
        }
    }
}

@Composable
fun DashboardScreen(mainViewModel: MainViewModel) {
    val coreStats by mainViewModel.coreStatsState.collectAsState()
    val activeInstances by mainViewModel.activeInstances.collectAsState()
    PollCoreStats(mainViewModel)

    val runningCount = activeInstances.count { it.info.status == InstanceStatus.RUNNING }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp),
        contentPadding = PaddingValues(bottom = 16.dp, top = 16.dp),
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraLarge),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.dashboard_global_stats),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )

                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    pluralStringResource(
                                        R.plurals.dashboard_active_instances,
                                        runningCount,
                                        runningCount,
                                    ),
                                    fontSize = 12.sp,
                                )
                            },
                            colors =
                                AssistChipDefaults.assistChipColors(
                                    containerColor =
                                        if (runningCount > 0) Color(0xFF2E7D32)
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                    labelColor =
                                        if (runningCount > 0) Color.White
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    StatRow(
                        label = stringResource(R.string.stats_speed),
                        value = formatRates(coreStats.uplinkRate, coreStats.downlinkRate),
                    )
                    StatRow(
                        label = stringResource(id = R.string.stats_uplink),
                        value = formatBytes(coreStats.uplink),
                    )
                    StatRow(
                        label = stringResource(id = R.string.stats_downlink),
                        value = formatBytes(coreStats.downlink),
                    )
                    StatRow(
                        label = stringResource(id = R.string.stats_num_goroutine),
                        value = formatNumber(coreStats.numGoroutine.toLong()),
                    )
                    StatRow(
                        label = stringResource(id = R.string.stats_alloc),
                        value = formatBytes(coreStats.alloc),
                    )
                    StatRow(
                        label = stringResource(id = R.string.stats_uptime),
                        value = formatUptime(coreStats.uptime),
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        item {
            Text(
                text = stringResource(R.string.dashboard_running_instances),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        if (activeInstances.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                ) {
                    Text(
                        text = stringResource(R.string.dashboard_no_instances),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(activeInstances, key = { it.info.configPath }) { instance ->
                InstanceCard(
                    instance = instance,
                    onStop = {
                        mainViewModel.stopInstanceConfig(instance.info.configPath)
                    },
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
fun InstanceCard(instance: InstanceState, onStop: () -> Unit) {
    val info = instance.info
    val stats = instance.stats
    Card(
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text =
                            if (info.isPrimaryVpn)
                                stringResource(R.string.instance_name_vpn, info.configName)
                            else info.configName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = statusLabel(instance),
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor(instance),
                    )
                }

                OutlinedButton(onClick = onStop) {
                    Text(stringResource(R.string.stop), fontSize = 12.sp)
                }
            }

            info.errorMessage
                ?.takeIf { info.status != InstanceStatus.RUNNING }
                ?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

            if (stats != null) {
                Spacer(modifier = Modifier.height(8.dp))
                StatRow(
                    label = stringResource(R.string.stats_speed),
                    value = formatRates(stats.uplinkRate, stats.downlinkRate),
                )
                StatRow(
                    label = stringResource(R.string.stats_traffic),
                    value = "↑ ${formatBytes(stats.uplink)}  ↓ ${formatBytes(stats.downlink)}",
                )
                StatRow(
                    label = stringResource(R.string.stats_num_goroutine),
                    value = formatNumber(stats.numGoroutine.toLong()),
                )
                StatRow(
                    label = stringResource(R.string.stats_alloc),
                    value = formatBytes(stats.alloc),
                )
                StatRow(
                    label = stringResource(R.string.stats_uptime),
                    value = formatUptime(stats.uptime),
                )
                Text(
                    text = stringResource(R.string.instance_api, info.apiAddress, info.apiPort),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** What an instance is doing, in a few words. */
@Composable
fun statusLabel(instance: InstanceState): String {
    val info = instance.info
    return when (info.status) {
        InstanceStatus.STARTING -> stringResource(R.string.status_starting_detail)
        InstanceStatus.RUNNING ->
            when {
                instance.stats == null -> stringResource(R.string.status_running_no_stats)
                info.restarts > 0 ->
                    pluralStringResource(
                        R.plurals.status_running_restarted,
                        info.restarts,
                        info.restarts,
                    )
                else -> stringResource(R.string.status_running)
            }
        InstanceStatus.RESTARTING ->
            stringResource(R.string.status_restarting_detail, info.restarts)
        InstanceStatus.ERROR -> stringResource(R.string.status_failed)
    }
}

@Composable
fun statusColor(instance: InstanceState): Color =
    when (instance.info.status) {
        InstanceStatus.RUNNING ->
            if (instance.stats != null) Color(0xFF2E7D32)
            else MaterialTheme.colorScheme.onSurfaceVariant
        InstanceStatus.STARTING -> MaterialTheme.colorScheme.onSurfaceVariant
        InstanceStatus.RESTARTING -> MaterialTheme.colorScheme.tertiary
        InstanceStatus.ERROR -> MaterialTheme.colorScheme.error
    }

fun formatRates(uplinkRate: Long, downlinkRate: Long): String =
    "↑ ${formatBytes(uplinkRate)}/s  ↓ ${formatBytes(downlinkRate)}/s"

@Composable
fun StatRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
        )
    }
}
