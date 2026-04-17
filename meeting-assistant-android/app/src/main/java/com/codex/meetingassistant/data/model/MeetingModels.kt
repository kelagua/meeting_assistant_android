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
