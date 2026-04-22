package com.codex.meetingassistant.data.repo

import android.content.Context
import com.codex.meetingassistant.data.local.DownloadTaskDao
import com.codex.meetingassistant.data.local.DownloadTaskEntity
import com.codex.meetingassistant.data.local.MeetingAssistantDatabase
import com.codex.meetingassistant.data.local.ModelPackDao
import com.codex.meetingassistant.data.local.ModelPackEntity
import com.codex.meetingassistant.data.model.DownloadStatus
import com.codex.meetingassistant.data.model.DownloadTask
import com.codex.meetingassistant.data.model.GalleryModel
import com.codex.meetingassistant.data.model.MeetingSummary
import com.codex.meetingassistant.data.model.ModelPack
import com.codex.meetingassistant.data.model.ModelPackStatus
import com.codex.meetingassistant.data.model.SummaryStage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

interface ModelRepository {
    fun getGalleryModels(): List<GalleryModel>
    val downloadTasks: Flow<List<DownloadTask>>
    fun observeDownloadTask(modelPackId: String): Flow<DownloadTask?>
    suspend fun startDownload(modelPackId: String, downloadUrl: String, totalBytes: Long)
    suspend fun updateDownloadProgress(modelPackId: String, bytesDownloaded: Long, status: DownloadStatus)
    suspend fun completeDownload(modelPackId: String, localFilePath: String)
    suspend fun failDownload(modelPackId: String)
    suspend fun cancelDownload(modelPackId: String)
    suspend fun deleteModel(modelPackId: String)
    suspend fun seedGalleryModels(models: List<GalleryModel>)
    suspend fun updatePackStatus(packId: String, status: ModelPackStatus, localFilePath: String?)
}

class DefaultModelRepository(
    private val context: Context,
    private val database: MeetingAssistantDatabase,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ModelRepository {

    private val galleryModelsCache = mutableMapOf<String, GalleryModel>()

    override fun getGalleryModels(): List<GalleryModel> {
        if (galleryModelsCache.isEmpty()) {
            loadGalleryModelsFromAssets()
        }
        return galleryModelsCache.values.toList()
    }

    private fun loadGalleryModelsFromAssets() {
        try {
            val jsonString = context.assets
                .open("gallery/allowlist.json")
                .bufferedReader()
                .use { it.readText() }

            val allowlist = json.decodeFromString<GalleryAllowlist>(jsonString)
            allowlist.models.forEach { model ->
                galleryModelsCache[model.id] = model
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override val downloadTasks: Flow<List<DownloadTask>> =
        database.downloadTaskDao().observeAllTasks().map { entities ->
            entities.map { it.toDomain() }
        }

    override fun observeDownloadTask(modelPackId: String): Flow<DownloadTask?> =
        database.downloadTaskDao().observeTask(modelPackId).map { it?.toDomain() }

    override suspend fun startDownload(
        modelPackId: String,
        downloadUrl: String,
        totalBytes: Long,
    ) {
        val entity = DownloadTaskEntity(
            modelPackId = modelPackId,
            downloadUrl = downloadUrl,
            totalBytes = totalBytes,
            status = DownloadStatus.PENDING.name,
            startedAt = System.currentTimeMillis(),
        )
        database.downloadTaskDao().upsertTask(entity)
    }

    override suspend fun updateDownloadProgress(
        modelPackId: String,
        bytesDownloaded: Long,
        status: DownloadStatus,
    ) {
        database.downloadTaskDao().updateProgress(modelPackId, bytesDownloaded, status.name)
    }

    override suspend fun completeDownload(modelPackId: String, localFilePath: String) {
        database.downloadTaskDao().upsertTask(
            DownloadTaskEntity(
                modelPackId = modelPackId,
                downloadUrl = "",
                localFilePath = localFilePath,
                totalBytes = 0L,
                bytesDownloaded = 0L,
                status = DownloadStatus.COMPLETED.name,
                startedAt = 0L,
                completedAt = System.currentTimeMillis(),
            ),
        )
        database.modelPackDao().updatePackStatus(
            packId = modelPackId,
            status = ModelPackStatus.AVAILABLE.name,
            localFilePath = localFilePath,
        )
    }

    override suspend fun failDownload(modelPackId: String) {
        database.downloadTaskDao().updateStatus(modelPackId, DownloadStatus.FAILED.name)
        database.modelPackDao().updatePackStatus(
            packId = modelPackId,
            status = ModelPackStatus.MISSING.name,
            localFilePath = null,
        )
    }

    override suspend fun cancelDownload(modelPackId: String) {
        database.downloadTaskDao().updateStatus(modelPackId, DownloadStatus.CANCELLED.name)
    }

    override suspend fun deleteModel(modelPackId: String) {
        database.downloadTaskDao().deleteTask(modelPackId)
        database.modelPackDao().updatePackStatus(
            packId = modelPackId,
            status = ModelPackStatus.MISSING.name,
            localFilePath = null,
        )
    }

    override suspend fun seedGalleryModels(models: List<GalleryModel>) {
        val entities = models.map { it.toEntity() }
        database.modelPackDao().upsertPacks(entities)
    }

    override suspend fun updatePackStatus(
        packId: String,
        status: ModelPackStatus,
        localFilePath: String?,
    ) {
        database.modelPackDao().updatePackStatus(packId, status.name, localFilePath)
    }

    private fun GalleryModel.toEntity(): ModelPackEntity = ModelPackEntity(
        id = id,
        displayName = displayName,
        description = description,
        stage = when (stage) {
            com.codex.meetingassistant.data.model.ModelStage.LIVE_NOTES -> SummaryStage.LIVE_NOTES.name
            com.codex.meetingassistant.data.model.ModelStage.FINAL_SUMMARY -> SummaryStage.FINAL_SUMMARY.name
            com.codex.meetingassistant.data.model.ModelStage.BOTH -> SummaryStage.LIVE_NOTES.name
        },
        quantization = quantization,
        estimatedSizeMb = (sizeInBytes / (1024 * 1024)).toInt(),
        assetPath = "models/$id",
        status = ModelPackStatus.MISSING.name,
        minRamGb = minDeviceMemoryInGb,
        minScore = minDeviceMemoryInGb * 10,
        supportedLanguages = listOf("zh-CN", "en-US"),
        downloadUrl = downloadUrl,
        hfRepoId = hfRepoId,
        modelFile = modelFile,
    )

    private fun DownloadTaskEntity.toDomain(): DownloadTask = DownloadTask(
        modelPackId = modelPackId,
        downloadUrl = downloadUrl,
        localFilePath = localFilePath,
        bytesDownloaded = bytesDownloaded,
        totalBytes = totalBytes,
        status = try {
            DownloadStatus.valueOf(status)
        } catch (e: Exception) {
            DownloadStatus.IDLE
        },
        startedAt = startedAt,
        completedAt = completedAt,
    )
}

@kotlinx.serialization.Serializable
private data class GalleryAllowlist(
    val version: String,
    val generatedAt: String,
    val models: List<GalleryModel>,
)
