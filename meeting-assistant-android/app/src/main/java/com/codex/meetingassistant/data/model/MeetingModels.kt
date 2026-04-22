package com.codex.meetingassistant.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class MeetingStatus {
    DRAFT,
    READY,
    LIVE,
    PROCESSING,
    COMPLETED,
    FAILED
}

@Serializable
enum class SummaryStage {
    LIVE_NOTES,
    FINAL_SUMMARY
}

@Serializable
enum class ExportFormat {
    MARKDOWN,
    TEXT,
    JSON
}

@Serializable
enum class ModelPackStatus {
    AVAILABLE,
    SELECTED,
    MISSING,
    DOWNLOADING
}

@Serializable
data class Meeting(
    val id: String,
    val title: String,
    val agenda: String,
    val status: MeetingStatus,
    val createdAtEpochMs: Long,
    val startedAtEpochMs: Long?,
    val endedAtEpochMs: Long?,
    val summariesPaused: Boolean,
)

@Serializable
data class AudioAsset(
    val id: String,
    val meetingId: String,
    val encryptedFilePath: String,
    val format: String,
    val sampleRateHz: Int,
    val channelCount: Int,
    val durationMs: Long,
)

@Serializable
data class TranscriptSegment(
    val id: String,
    val meetingId: String,
    @SerialName("start_ms")
    val startMs: Long,
    @SerialName("end_ms")
    val endMs: Long,
    val text: String,
    @SerialName("speaker_label")
    val speakerLabel: String,
    @SerialName("speaker_confidence")
    val speakerConfidence: Float,
    @SerialName("asr_confidence")
    val asrConfidence: Float,
    @SerialName("is_final")
    val isFinal: Boolean,
    val createdAtEpochMs: Long,
)

@Serializable
data class SpeakerProfile(
    val id: String,
    val displayName: String,
    val embeddingBase64: String,
    val keepsAudioSamples: Boolean,
    val enrollmentCount: Int,
    val createdAtEpochMs: Long,
)

@Serializable
data class SpeakerAssignment(
    val id: String,
    val meetingId: String,
    val speakerLabel: String,
    val profileId: String?,
    val confidence: Float,
    val lockedByUser: Boolean,
    val updatedAtEpochMs: Long,
)

@Serializable
data class SummaryBullet(
    val text: String,
    val anchorSegmentIds: List<String>,
)

@Serializable
data class ActionItem(
    val owner: String,
    val task: String,
    val dueHint: String?,
    val anchorSegmentIds: List<String>,
)

@Serializable
data class SpeakerNote(
    val speakerLabel: String,
    val displayName: String?,
    val note: String,
    val anchorSegmentIds: List<String>,
)

@Serializable
data class ChunkSummary(
    val id: String,
    val meetingId: String,
    val startMs: Long,
    val endMs: Long,
    val headline: String,
    val bullets: List<SummaryBullet>,
    val modelPackId: String,
    val createdAtEpochMs: Long,
)

@Serializable
data class MeetingSummary(
    val meetingId: String,
    val overview: SummaryBullet,
    val decisions: List<SummaryBullet>,
    @SerialName("action_items")
    val actionItems: List<ActionItem>,
    val risks: List<SummaryBullet>,
    @SerialName("open_questions")
    val openQuestions: List<SummaryBullet>,
    @SerialName("speaker_notes")
    val speakerNotes: List<SpeakerNote>,
    val modelPackId: String,
    val generatedAtEpochMs: Long,
)

@Serializable
data class ModelPack(
    val id: String,
    val displayName: String,
    val description: String,
    val stage: SummaryStage,
    val quantization: String,
    val estimatedSizeMb: Int,
    val assetPath: String,
    val status: ModelPackStatus,
    val minRamGb: Int,
    val minScore: Int,
    val supportedLanguages: List<String>,
    val downloadUrl: String? = null,
    val localFilePath: String? = null,
    val hfRepoId: String? = null,
    val modelFile: String? = null,
)

@Serializable
data class DeviceProfile(
    val ramGb: Int,
    val cpuCores: Int,
    val abi: String,
    val score: Int,
)

@Serializable
data class MeetingDetail(
    val meeting: Meeting,
    val audioAsset: AudioAsset?,
    val transcriptSegments: List<TranscriptSegment>,
    val speakerProfiles: List<SpeakerProfile>,
    val speakerAssignments: List<SpeakerAssignment>,
    val chunkSummaries: List<ChunkSummary>,
    val meetingSummary: MeetingSummary?,
)

@Serializable
data class MeetingPreferences(
    val liveSummaryIntervalMinutes: Int = 3,
    val liveSummaryCharacterTarget: Int = 650,
    val selectedLivePackId: String? = null,
    val selectedFinalPackId: String? = null,
)

data class ExportArtifact(
    val format: ExportFormat,
    val filePath: String,
)

// ========== Model Market ==========

@Serializable
enum class DownloadStatus {
    IDLE,
    PENDING,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

@Serializable
enum class ModelStage {
    LIVE_NOTES,
    FINAL_SUMMARY,
    BOTH,
}

@Serializable
data class ModelConfig(
    val topK: Int = 64,
    val topP: Float = 0.95f,
    val temperature: Float = 1.0f,
    val maxTokens: Int = 4096,
    val maxContextLength: Int? = null,
    val accelerators: String = "gpu,cpu",
    val visionAccelerator: String? = null,
)

@Serializable
data class GalleryModel(
    val id: String,
    val displayName: String,
    val description: String,
    val modelFile: String,
    val hfRepoId: String,
    val sizeInBytes: Long,
    val sizeLabel: String,
    val minDeviceMemoryInGb: Int,
    val stage: ModelStage,
    val quantization: String,
    val taskTypes: List<String>,
    val defaultConfig: ModelConfig? = null,
) {
    companion object {
        // 官方源，稳定性最高
        private const val HF_OFFICIAL = "https://huggingface.co"
        // 国内镜像（被某些 VPN 拦截时可用作备选）
        private const val HF_MIRROR = "https://hf-mirror.com"

        fun buildDownloadUrl(hfRepoId: String, modelFile: String, useMirror: Boolean = false): String {
            val base = if (useMirror) HF_MIRROR else HF_OFFICIAL
            return "$base/$hfRepoId/resolve/main/$modelFile"
        }
    }

    val downloadUrl: String
        get() = buildDownloadUrl(hfRepoId, modelFile)
}

data class DownloadTask(
    val modelPackId: String,
    val downloadUrl: String,
    val localFilePath: String? = null,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val status: DownloadStatus = DownloadStatus.IDLE,
    val startedAt: Long = 0L,
    val completedAt: Long? = null,
) {
    val progressPercent: Float
        get() = if (totalBytes > 0) (bytesDownloaded.toFloat() / totalBytes * 100f) else 0f
}
