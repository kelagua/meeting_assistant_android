package com.codex.meetingassistant.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.ModelTraining
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codex.meetingassistant.R
import com.codex.meetingassistant.data.model.DownloadStatus
import com.codex.meetingassistant.data.model.DownloadTask
import com.codex.meetingassistant.data.model.GalleryModel

data class ModelMarketUiState(
    val galleryModels: List<GalleryModel> = emptyList(),
    val downloadTasks: Map<String, DownloadTask> = emptyMap(),
    val deviceMemoryGb: Int = 0,
    val isLoading: Boolean = false,
)

@Composable
fun ModelMarketScreen(
    uiState: ModelMarketUiState,
    onDownload: (GalleryModel) -> Unit,
    onCancel: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val incompatibleModels = uiState.galleryModels.filter { it.minDeviceMemoryInGb > uiState.deviceMemoryGb }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HeroCard(
            title = stringResource(R.string.models_hero_title),
            body = stringResource(R.string.models_hero_body),
        )

        if (incompatibleModels.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        text = stringResource(R.string.device_ram_warning, uiState.deviceMemoryGb),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 300.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            items(uiState.galleryModels) { model ->
                ModelCard(
                    model = model,
                    downloadTask = uiState.downloadTasks[model.id],
                    deviceMemoryGb = uiState.deviceMemoryGb,
                    onDownload = { onDownload(model) },
                    onCancel = { onCancel(model.id) },
                    onDelete = { onDelete(model.id) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelCard(
    model: GalleryModel,
    downloadTask: DownloadTask?,
    deviceMemoryGb: Int,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val isCompatible = model.minDeviceMemoryInGb <= deviceMemoryGb
    val animatedProgress by animateFloatAsState(
        targetValue = (downloadTask?.progressPercent?.div(100f) ?: 0f).coerceIn(0f, 1f),
        label = "model-download-progress",
    )
    val status = downloadTask?.status

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = model.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = model.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Icon(
                    imageVector = Icons.Outlined.ModelTraining,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(onClick = { }, label = { Text(model.sizeLabel, maxLines = 1) })
                AssistChip(onClick = { }, label = { Text("${model.minDeviceMemoryInGb}GB+", maxLines = 1) })
                AssistChip(onClick = { }, label = { Text(model.quantization, maxLines = 1) })
                AssistChip(
                    onClick = { },
                    label = { Text(model.stage.name.replace('_', ' ').lowercase(), maxLines = 1) },
                )
            }

            when (status) {
                DownloadStatus.DOWNLOADING, DownloadStatus.PENDING -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(
                                R.string.downloading_progress,
                                (downloadTask.bytesDownloaded).div(1024 * 1024).toInt(),
                                (downloadTask.totalBytes).div(1024 * 1024).toInt(),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        DownloadProgressBar(progress = animatedProgress)
                    }
                }

                DownloadStatus.COMPLETED -> {
                    Text(
                        text = stringResource(R.string.downloaded_ready),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                DownloadStatus.FAILED -> {
                    Text(
                        text = stringResource(R.string.download_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                DownloadStatus.CANCELLED -> {
                    Text(
                        text = stringResource(R.string.download_cancelled),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> Unit
            }

            when (status) {
                DownloadStatus.DOWNLOADING, DownloadStatus.PENDING -> {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.cancel_download))
                    }
                }

                DownloadStatus.COMPLETED -> {
                    FilledTonalButton(
                        onClick = onDelete,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.Delete, contentDescription = null)
                        Text(stringResource(R.string.delete_local_model))
                    }
                }

                DownloadStatus.FAILED -> {
                    Button(
                        onClick = onDownload,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.CloudDownload, contentDescription = null)
                        Text(stringResource(R.string.retry_download))
                    }
                }

                else -> {
                    if (isCompatible) {
                        Button(
                            onClick = onDownload,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.CloudDownload, contentDescription = null)
                            Text(stringResource(R.string.download_model))
                        }
                    } else {
                        OutlinedButton(
                            onClick = { },
                            enabled = false,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.needs_more_ram))
                        }
                    }
                }
            }

            if (status == DownloadStatus.COMPLETED) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DownloadDone,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.model_ready_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadProgressBar(progress: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(10.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}
