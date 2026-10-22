// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0

package com.simplexray.an.ui.screens

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.simplexray.an.R
import com.simplexray.an.core.InstanceStatus
import com.simplexray.an.viewmodel.MainViewModel
import java.io.File
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private const val TAG = "ConfigScreen"

@Composable
fun ConfigScreen(
    onReloadConfig: () -> Unit,
    onEditConfigClick: (File) -> Unit,
    onDeleteConfigClick: (File, () -> Unit) -> Unit,
    mainViewModel: MainViewModel,
    listState: LazyListState,
) {
    val showDeleteDialog = remember { mutableStateOf<File?>(null) }

    val isServiceEnabled by mainViewModel.isServiceEnabled.collectAsState()
    val activeInstances by mainViewModel.activeInstances.collectAsState()

    val files by mainViewModel.configFiles.collectAsState()
    val selectedFile by mainViewModel.selectedConfigFile.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                mainViewModel.refreshConfigFileList()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) { mainViewModel.refreshConfigFileList() }
    PollCoreStats(mainViewModel)

    val hapticFeedback = LocalHapticFeedback.current
    val reorderableLazyListState =
        rememberReorderableLazyListState(listState) { from, to ->
            mainViewModel.moveConfigFile(from.index, to.index)
            hapticFeedback.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }

    Column(modifier = Modifier.fillMaxSize()) {
        if (files.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.no_config_files),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxHeight(),
                contentPadding = PaddingValues(bottom = 10.dp, top = 10.dp),
                state = listState,
            ) {
                items(files, key = { it }) { file ->
                    ReorderableItem(reorderableLazyListState, key = file) {
                        val isSelected = file == selectedFile
                        val instance = activeInstances.find {
                            it.info.configPath == file.absolutePath
                        }

                        Card(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                                    .clip(MaterialTheme.shapes.extraLarge)
                                    .clickable {
                                        mainViewModel.updateSelectedConfigFile(file)
                                        if (isServiceEnabled) {
                                            Log.d(
                                                TAG,
                                                "Config selected while service is running, requesting reload.",
                                            )
                                            onReloadConfig()
                                        }
                                    },
                            colors =
                                CardDefaults.cardColors(
                                    containerColor =
                                        if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                                        else MaterialTheme.colorScheme.surfaceContainerHighest
                                ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        ) {
                            Column(
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .padding(16.dp)
                                        .longPressDraggableHandle()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            file.name.removeSuffix(".json"),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )

                                        Row(
                                            modifier = Modifier.padding(top = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            if (isSelected) {
                                                AssistChip(
                                                    onClick = {},
                                                    label = {
                                                        Text("Primary VPN", fontSize = 11.sp)
                                                    },
                                                    colors =
                                                        AssistChipDefaults.assistChipColors(
                                                            containerColor =
                                                                MaterialTheme.colorScheme
                                                                    .primaryContainer,
                                                            labelColor =
                                                                MaterialTheme.colorScheme
                                                                    .onPrimaryContainer,
                                                        ),
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                            }

                                            val (chipText, chipColor) =
                                                when (instance?.info?.status) {
                                                    null ->
                                                        "Stopped" to
                                                            MaterialTheme.colorScheme.surfaceVariant
                                                    InstanceStatus.STARTING ->
                                                        "Starting" to
                                                            MaterialTheme.colorScheme.surfaceVariant
                                                    InstanceStatus.RUNNING ->
                                                        "Running" to
                                                            MaterialTheme.colorScheme.primary
                                                    InstanceStatus.RESTARTING ->
                                                        "Restarting" to
                                                            MaterialTheme.colorScheme.tertiary
                                                    InstanceStatus.ERROR ->
                                                        "Failed" to MaterialTheme.colorScheme.error
                                                }
                                            AssistChip(
                                                onClick = {},
                                                label = { Text(chipText, fontSize = 11.sp) },
                                                colors =
                                                    AssistChipDefaults.assistChipColors(
                                                        containerColor = chipColor,
                                                        labelColor = contentColorFor(chipColor),
                                                    ),
                                            )
                                        }
                                    }

                                    Switch(
                                        checked = instance?.info?.isActive == true,
                                        onCheckedChange = {
                                            mainViewModel.toggleInstance(file.absolutePath)
                                        },
                                    )

                                    IconButton(onClick = { onEditConfigClick(file) }) {
                                        Icon(
                                            painterResource(R.drawable.edit),
                                            contentDescription = "Edit",
                                        )
                                    }

                                    IconButton(onClick = { showDeleteDialog.value = file }) {
                                        Icon(
                                            painterResource(R.drawable.delete),
                                            contentDescription = "Delete",
                                        )
                                    }
                                }

                                if (instance != null) {
                                    val stats = instance.stats
                                    Text(
                                        text =
                                            if (stats != null)
                                                formatRates(stats.uplinkRate, stats.downlinkRate)
                                            else statusLabel(instance),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = statusColor(instance),
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                    instance.info.errorMessage
                                        ?.takeIf { instance.info.status != InstanceStatus.RUNNING }
                                        ?.let { message ->
                                            Text(
                                                text = message,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error,
                                                maxLines = 3,
                                            )
                                        }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    showDeleteDialog.value?.let { fileToDelete ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog.value = null },
            title = { Text(stringResource(R.string.delete_config)) },
            text = { Text(fileToDelete.name) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog.value = null
                        onDeleteConfigClick(fileToDelete) {
                            mainViewModel.refreshConfigFileList()
                            mainViewModel.updateSelectedConfigFile(null)
                        }
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog.value = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
