package com.codex.meetingassistant.data.repo

import android.content.Context
import androidx.room.Room
import com.codex.meetingassistant.data.crypto.CryptoBox
import com.codex.meetingassistant.data.local.AudioAssetEntity
import com.codex.meetingassistant.data.local.ChunkSummaryEntity
import com.codex.meetingassistant.data.local.MeetingAssistantDatabase
import com.codex.meetingassistant.data.local.MeetingEntity
import com.codex.meetingassistant.data.local.MeetingSummaryEntity
import com.codex.meetingassistant.data.local.ModelPackEntity
import com.codex.meetingassistant.data.local.SpeakerAssignmentEntity
import com.codex.meetingassistant.data.local.SpeakerProfileEntity
import com.codex.meetingassistant.data.local.TranscriptSegmentEntity
import com.codex.meetingassistant.data.model.ActionItem
import com.codex.meetingassistant.data.model.AudioAsset
import com.codex.meetingassistant.data.model.ChunkSummary
import com.codex.meetingassistant.data.model.DeviceProfile
import com.codex.meetingassistant.data.model.ExportArtifact
import com.codex.meetingassistant.data.model.ExportFormat
import com.codex.meetingassistant.data.model.Meeting
import com.codex.meetingassistant.data.model.MeetingDetail
import com.codex.meetingassistant.data.model.MeetingStatus
import com.codex.meetingassistant.data.model.MeetingSummary
import com.codex.meetingassistant.data.model.ModelPack
import com.codex.meetingassistant.data.model.ModelPackStatus
import com.codex.meetingassistant.data.model.SpeakerAssignment
import com.codex.meetingassistant.data.model.SpeakerNote
import com.codex.meetingassistant.data.model.SpeakerProfile
import com.codex.meetingassistant.data.model.SummaryBullet
import com.codex.meetingassistant.data.model.SummaryStage
import com.codex.meetingassistant.data.model.TranscriptSegment
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface MeetingRepository {
    val latestMeetingDetail: Flow<MeetingDetail?>
    val speakerProfiles: Flow<List<SpeakerProfile>>
    val modelPacks: Flow<List<ModelPack>>

    suspend fun seedDefaultModelPacks()
    suspend fun createMeeting(title: String, agenda: String): Meeting
    suspend fun updateMeetingStatus(meetingId: String, status: MeetingStatus, endedAtEpochMs: Long? = null)
    suspend fun setSummariesPaused(meetingId: String, paused: Boolean)
    suspend fun attachAudioAsset(meetingId: String, encryptedFilePath: String, durationMs: Long): AudioAsset
    suspend fun appendTranscriptSegment(segment: TranscriptSegment): TranscriptSegment
    suspend fun renameSpeakerLabel(meetingId: String, oldLabel: String, newLabel: String)
    suspend fun upsertSpeakerProfile(displayName: String, keepsAudioSamples: Boolean): SpeakerProfile
    suspend fun upsertSpeakerAssignment(
        meetingId: String,
        speakerLabel: String,
        profileId: String?,
        confidence: Float,
        lockedByUser: Boolean,
    ): SpeakerAssignment
    suspend fun saveChunkSummary(summary: ChunkSummary)
    suspend fun saveMeetingSummary(summary: MeetingSummary)
    suspend fun getMeetingDetail(meetingId: String): MeetingDetail?
    suspend fun exportMeeting(meetingId: String, format: ExportFormat): ExportArtifact
}

class DefaultMeetingRepository(
    private val context: Context,
    private val database: MeetingAssistantDatabase,
    private val cryptoBox: CryptoBox,
    private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true },
) : MeetingRepository {
    override val speakerProfiles: Flow<List<SpeakerProfile>> =
        database.speakerProfileDao().observeProfiles().mapProfiles()

    override val modelPacks: Flow<List<ModelPack>> =
        database.modelPackDao().observeModelPacks().mapModelPacks()

    override val latestMeetingDetail: Flow<MeetingDetail?> =
        database.meetingDao()
            .observeLatestMeetingByStatuses(
                listOf(
                    MeetingStatus.LIVE.name,
                    MeetingStatus.PROCESSING.name,
                    MeetingStatus.COMPLETED.name,
                    MeetingStatus.READY.name,
                ),
            )
            .flatMapLatest { meeting ->
                if (meeting == null) {
                    flowOf(null)
                } else {
                    observeMeetingDetail(meeting.id)
                }
            }

    override suspend fun seedDefaultModelPacks() {
        if (database.modelPackDao().count() > 0) return

        val defaults = listOf(
            ModelPackEntity(
                id = "live-q4-2b-zh-en",
                displayName = "Live Notes 2B Int4",
                description = "Fast bilingual live summary pack tuned for meeting outlines.",
                stage = SummaryStage.LIVE_NOTES.name,
                quantization = "int4",
                estimatedSizeMb = 1300,
                assetPath = "modelpacks/live-q4-2b-zh-en",
                status = ModelPackStatus.SELECTED.name,
                minRamGb = 8,
                minScore = 78,
                supportedLanguages = listOf("zh-CN", "en-US"),
            ),
            ModelPackEntity(
                id = "final-q4-4b-zh-en",
                displayName = "Final Summary 4B Int4",
                description = "Higher quality post-meeting summarizer with action-item extraction.",
                stage = SummaryStage.FINAL_SUMMARY.name,
                quantization = "int4",
                estimatedSizeMb = 2600,
                assetPath = "modelpacks/final-q4-4b-zh-en",
                status = ModelPackStatus.SELECTED.name,
                minRamGb = 12,
                minScore = 88,
                supportedLanguages = listOf("zh-CN", "en-US"),
            ),
        )
        database.modelPackDao().upsertPacks(defaults)
    }

    override suspend fun createMeeting(title: String, agenda: String): Meeting {
        val now = System.currentTimeMillis()
        val meeting = Meeting(
            id = UUID.randomUUID().toString(),
            title = title.ifBlank { "Untitled Meeting" },
            agenda = agenda,
            status = MeetingStatus.READY,
            createdAtEpochMs = now,
            startedAtEpochMs = now,
            endedAtEpochMs = null,
            summariesPaused = false,
        )
        database.meetingDao().upsertMeeting(meeting.toEntity())
        return meeting
    }

    override suspend fun updateMeetingStatus(meetingId: String, status: MeetingStatus, endedAtEpochMs: Long?) {
        val current = getMeetingDetail(meetingId)?.meeting ?: return
        database.meetingDao().upsertMeeting(
            current.copy(
                status = status,
                endedAtEpochMs = endedAtEpochMs ?: current.endedAtEpochMs,
            ).toEntity(),
        )
    }

    override suspend fun setSummariesPaused(meetingId: String, paused: Boolean) {
        val current = getMeetingDetail(meetingId)?.meeting ?: return
        database.meetingDao().upsertMeeting(current.copy(summariesPaused = paused).toEntity())
    }

    override suspend fun attachAudioAsset(meetingId: String, encryptedFilePath: String, durationMs: Long): AudioAsset {
        val asset = AudioAsset(
            id = UUID.randomUUID().toString(),
            meetingId = meetingId,
            encryptedFilePath = encryptedFilePath,
            format = "pcm16",
            sampleRateHz = 16_000,
            channelCount = 1,
            durationMs = durationMs,
        )
        database.audioAssetDao().upsertAsset(asset.toEntity())
        return asset
    }

    override suspend fun appendTranscriptSegment(segment: TranscriptSegment): TranscriptSegment {
        database.transcriptDao().upsertSegment(segment.toEntity())
        return segment
    }

    override suspend fun renameSpeakerLabel(meetingId: String, oldLabel: String, newLabel: String) {
        val oldCipher = cryptoBox.sealString(oldLabel)
        val newCipher = cryptoBox.sealString(newLabel)
        val now = System.currentTimeMillis()
        database.transcriptDao().renameSpeakerLabel(meetingId, oldCipher, newCipher)
        database.speakerAssignmentDao().renameSpeakerLabel(meetingId, oldCipher, newCipher, now)
    }

    override suspend fun upsertSpeakerProfile(displayName: String, keepsAudioSamples: Boolean): SpeakerProfile {
        val profile = SpeakerProfile(
            id = UUID.randomUUID().toString(),
            displayName = displayName,
            embeddingBase64 = "demo-${displayName.lowercase()}",
            keepsAudioSamples = keepsAudioSamples,
            enrollmentCount = 3,
            createdAtEpochMs = System.currentTimeMillis(),
        )
        database.speakerProfileDao().upsertProfile(profile.toEntity())
        return profile
    }

    override suspend fun upsertSpeakerAssignment(
        meetingId: String,
        speakerLabel: String,
        profileId: String?,
        confidence: Float,
        lockedByUser: Boolean,
    ): SpeakerAssignment {
        val assignment = SpeakerAssignment(
            id = "${meetingId}_$speakerLabel",
            meetingId = meetingId,
            speakerLabel = speakerLabel,
            profileId = profileId,
            confidence = confidence,
            lockedByUser = lockedByUser,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        database.speakerAssignmentDao().upsertAssignment(assignment.toEntity())
        return assignment
    }

    override suspend fun saveChunkSummary(summary: ChunkSummary) {
        database.chunkSummaryDao().upsertChunkSummary(summary.toEntity())
    }

    override suspend fun saveMeetingSummary(summary: MeetingSummary) {
        database.meetingSummaryDao().upsertMeetingSummary(summary.toEntity())
    }

    override suspend fun getMeetingDetail(meetingId: String): MeetingDetail? = observeMeetingDetail(meetingId).first()

    override suspend fun exportMeeting(meetingId: String, format: ExportFormat): ExportArtifact {
        val detail = getMeetingDetail(meetingId) ?: error("Meeting not found: $meetingId")
        val exportsDir = File(
            context.getExternalFilesDir("exports") ?: context.filesDir,
            "exports",
        ).apply { mkdirs() }
        val baseName = "${detail.meeting.title.sanitizeForFileName()}_${meetingId.take(8)}"
        val targetFile = when (format) {
            ExportFormat.MARKDOWN -> File(exportsDir, "$baseName.md")
            ExportFormat.TEXT -> File(exportsDir, "$baseName.txt")
            ExportFormat.JSON -> File(exportsDir, "$baseName.json")
        }
        val content = when (format) {
            ExportFormat.MARKDOWN -> detail.toMarkdown()
            ExportFormat.TEXT -> detail.toPlainText()
            ExportFormat.JSON -> json.encodeToString(detail)
        }
        targetFile.writeText(content)
        return ExportArtifact(format = format, filePath = targetFile.absolutePath)
    }

    private fun observeMeetingDetail(meetingId: String): Flow<MeetingDetail?> =
        combine(
            database.meetingDao().observeMeeting(meetingId),
            database.audioAssetDao().observeAsset(meetingId),
            database.transcriptDao().observeSegments(meetingId),
            database.speakerAssignmentDao().observeAssignments(meetingId),
            database.chunkSummaryDao().observeChunkSummaries(meetingId),
        ) { meeting, audio, segments, assignments, chunks ->
            MeetingDetailCore(
                meeting = meeting,
                audio = audio,
                segments = segments,
                assignments = assignments,
                chunks = chunks,
            )
        }
            .combine(database.meetingSummaryDao().observeMeetingSummary(meetingId)) { core, summary ->
                core to summary
            }
            .combine(database.speakerProfileDao().observeProfiles()) { (core, summary), profiles ->
                val meetingEntity = core.meeting ?: return@combine null
                MeetingDetail(
                    meeting = meetingEntity.toDomain(),
                    audioAsset = core.audio?.toDomain(),
                    transcriptSegments = core.segments.map { it.toDomain() },
                    speakerProfiles = profiles.map { it.toDomain() },
                    speakerAssignments = core.assignments.map { it.toDomain() },
                    chunkSummaries = core.chunks.map { it.toDomain() },
                    meetingSummary = summary?.toDomain(),
                )
            }

    private fun Flow<List<SpeakerProfileEntity>>.mapProfiles(): Flow<List<SpeakerProfile>> =
        map { entities -> entities.map { it.toDomain() } }

    private fun Flow<List<ModelPackEntity>>.mapModelPacks(): Flow<List<ModelPack>> =
        map { entities -> entities.map { it.toDomain() } }

    private fun Meeting.toEntity(): MeetingEntity = MeetingEntity(
        id = id,
        titleCipher = cryptoBox.sealString(title),
        agendaCipher = cryptoBox.sealString(agenda),
        status = status.name,
        createdAtEpochMs = createdAtEpochMs,
        startedAtEpochMs = startedAtEpochMs,
        endedAtEpochMs = endedAtEpochMs,
        summariesPaused = summariesPaused,
    )

    private fun MeetingEntity.toDomain(): Meeting = Meeting(
        id = id,
        title = cryptoBox.openString(titleCipher),
        agenda = cryptoBox.openString(agendaCipher),
        status = MeetingStatus.valueOf(status),
        createdAtEpochMs = createdAtEpochMs,
        startedAtEpochMs = startedAtEpochMs,
        endedAtEpochMs = endedAtEpochMs,
        summariesPaused = summariesPaused,
    )

    private fun AudioAsset.toEntity(): AudioAssetEntity = AudioAssetEntity(
        id = id,
        meetingId = meetingId,
        encryptedFilePathCipher = cryptoBox.sealString(encryptedFilePath),
        format = format,
        sampleRateHz = sampleRateHz,
        channelCount = channelCount,
        durationMs = durationMs,
    )

    private fun AudioAssetEntity.toDomain(): AudioAsset = AudioAsset(
        id = id,
        meetingId = meetingId,
        encryptedFilePath = cryptoBox.openString(encryptedFilePathCipher),
        format = format,
        sampleRateHz = sampleRateHz,
        channelCount = channelCount,
        durationMs = durationMs,
    )

    private fun TranscriptSegment.toEntity(): TranscriptSegmentEntity = TranscriptSegmentEntity(
        id = id,
        meetingId = meetingId,
        startMs = startMs,
        endMs = endMs,
        textCipher = cryptoBox.sealString(text),
        speakerLabelCipher = cryptoBox.sealString(speakerLabel),
        speakerConfidence = speakerConfidence,
        asrConfidence = asrConfidence,
        isFinal = isFinal,
        createdAtEpochMs = createdAtEpochMs,
    )

    private fun TranscriptSegmentEntity.toDomain(): TranscriptSegment = TranscriptSegment(
        id = id,
        meetingId = meetingId,
        startMs = startMs,
        endMs = endMs,
        text = cryptoBox.openString(textCipher),
        speakerLabel = cryptoBox.openString(speakerLabelCipher),
        speakerConfidence = speakerConfidence,
        asrConfidence = asrConfidence,
        isFinal = isFinal,
        createdAtEpochMs = createdAtEpochMs,
    )

    private fun SpeakerProfile.toEntity(): SpeakerProfileEntity = SpeakerProfileEntity(
        id = id,
        displayNameCipher = cryptoBox.sealString(displayName),
        embeddingCipher = cryptoBox.sealString(embeddingBase64),
        keepsAudioSamples = keepsAudioSamples,
        enrollmentCount = enrollmentCount,
        createdAtEpochMs = createdAtEpochMs,
    )

    private fun SpeakerProfileEntity.toDomain(): SpeakerProfile = SpeakerProfile(
        id = id,
        displayName = cryptoBox.openString(displayNameCipher),
        embeddingBase64 = cryptoBox.openString(embeddingCipher),
        keepsAudioSamples = keepsAudioSamples,
        enrollmentCount = enrollmentCount,
        createdAtEpochMs = createdAtEpochMs,
    )

    private fun SpeakerAssignment.toEntity(): SpeakerAssignmentEntity = SpeakerAssignmentEntity(
        id = id,
        meetingId = meetingId,
        speakerLabelCipher = cryptoBox.sealString(speakerLabel),
        profileId = profileId,
        confidence = confidence,
        lockedByUser = lockedByUser,
        updatedAtEpochMs = updatedAtEpochMs,
    )

    private fun SpeakerAssignmentEntity.toDomain(): SpeakerAssignment = SpeakerAssignment(
        id = id,
        meetingId = meetingId,
        speakerLabel = cryptoBox.openString(speakerLabelCipher),
        profileId = profileId,
        confidence = confidence,
        lockedByUser = lockedByUser,
        updatedAtEpochMs = updatedAtEpochMs,
    )

    private fun ChunkSummary.toEntity(): ChunkSummaryEntity = ChunkSummaryEntity(
        id = id,
        meetingId = meetingId,
        startMs = startMs,
        endMs = endMs,
        headlineCipher = cryptoBox.sealString(headline),
        bulletsCipher = cryptoBox.sealString(json.encodeToString(bullets)),
        modelPackId = modelPackId,
        createdAtEpochMs = createdAtEpochMs,
    )

    private fun ChunkSummaryEntity.toDomain(): ChunkSummary = ChunkSummary(
        id = id,
        meetingId = meetingId,
        startMs = startMs,
        endMs = endMs,
        headline = cryptoBox.openString(headlineCipher),
        bullets = json.decodeFromString<List<SummaryBullet>>(cryptoBox.openString(bulletsCipher)),
        modelPackId = modelPackId,
        createdAtEpochMs = createdAtEpochMs,
    )

    private fun MeetingSummary.toEntity(): MeetingSummaryEntity = MeetingSummaryEntity(
        meetingId = meetingId,
        overviewCipher = cryptoBox.sealString(json.encodeToString(overview)),
        decisionsCipher = cryptoBox.sealString(json.encodeToString(decisions)),
        actionItemsCipher = cryptoBox.sealString(json.encodeToString(actionItems)),
        risksCipher = cryptoBox.sealString(json.encodeToString(risks)),
        openQuestionsCipher = cryptoBox.sealString(json.encodeToString(openQuestions)),
        speakerNotesCipher = cryptoBox.sealString(json.encodeToString(speakerNotes)),
        modelPackId = modelPackId,
        generatedAtEpochMs = generatedAtEpochMs,
    )

    private fun MeetingSummaryEntity.toDomain(): MeetingSummary = MeetingSummary(
        meetingId = meetingId,
        overview = json.decodeFromString<SummaryBullet>(cryptoBox.openString(overviewCipher)),
        decisions = json.decodeFromString<List<SummaryBullet>>(cryptoBox.openString(decisionsCipher)),
        actionItems = json.decodeFromString<List<ActionItem>>(cryptoBox.openString(actionItemsCipher)),
        risks = json.decodeFromString<List<SummaryBullet>>(cryptoBox.openString(risksCipher)),
        openQuestions = json.decodeFromString<List<SummaryBullet>>(cryptoBox.openString(openQuestionsCipher)),
        speakerNotes = json.decodeFromString<List<SpeakerNote>>(cryptoBox.openString(speakerNotesCipher)),
        modelPackId = modelPackId,
        generatedAtEpochMs = generatedAtEpochMs,
    )

    private fun ModelPackEntity.toDomain(): ModelPack = ModelPack(
        id = id,
        displayName = displayName,
        description = description,
        stage = SummaryStage.valueOf(stage),
        quantization = quantization,
        estimatedSizeMb = estimatedSizeMb,
        assetPath = assetPath,
        status = ModelPackStatus.valueOf(status),
        minRamGb = minRamGb,
        minScore = minScore,
        supportedLanguages = supportedLanguages,
    )

    private fun MeetingDetail.toMarkdown(): String {
        val builder = StringBuilder()
        builder.appendLine("# ${meeting.title}")
        builder.appendLine()
        builder.appendLine("## Overview")
        builder.appendLine(meetingSummary?.overview?.text ?: "No summary generated yet.")
        builder.appendLine()
        builder.appendLine("## Decisions")
        (meetingSummary?.decisions ?: emptyList()).forEach { builder.appendLine("- ${it.text}") }
        builder.appendLine()
        builder.appendLine("## Action Items")
        (meetingSummary?.actionItems ?: emptyList()).forEach {
            builder.appendLine("- ${it.owner.ifBlank { "Unassigned" }}: ${it.task}${it.dueHint?.let { due -> " ($due)" } ?: ""}")
        }
        builder.appendLine()
        builder.appendLine("## Risks")
        (meetingSummary?.risks ?: emptyList()).forEach { builder.appendLine("- ${it.text}") }
        builder.appendLine()
        builder.appendLine("## Open Questions")
        (meetingSummary?.openQuestions ?: emptyList()).forEach { builder.appendLine("- ${it.text}") }
        builder.appendLine()
        builder.appendLine("## Transcript")
        transcriptSegments.forEach { segment ->
            builder.appendLine("[${segment.startMs}-${segment.endMs}] ${segment.speakerLabel}: ${segment.text}")
        }
        return builder.toString()
    }

    private fun MeetingDetail.toPlainText(): String = buildString {
        appendLine(meeting.title)
        appendLine("Status: ${meeting.status}")
        appendLine()
        appendLine("Overview:")
        appendLine(meetingSummary?.overview?.text ?: "No summary generated.")
        appendLine()
        appendLine("Transcript:")
        transcriptSegments.forEach { segment ->
            appendLine("${segment.speakerLabel} (${segment.startMs}-${segment.endMs}): ${segment.text}")
        }
    }

    private fun String.sanitizeForFileName(): String =
        replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5_-]"), "_").trim('_')

    companion object {
        fun buildDatabase(context: Context): MeetingAssistantDatabase =
            Room.databaseBuilder(
                context,
                MeetingAssistantDatabase::class.java,
                "meeting_assistant.db",
            ).build()
    }
}

private data class MeetingDetailCore(
    val meeting: MeetingEntity?,
    val audio: AudioAssetEntity?,
    val segments: List<TranscriptSegmentEntity>,
    val assignments: List<SpeakerAssignmentEntity>,
    val chunks: List<ChunkSummaryEntity>,
)

data class RuntimeRecommendation(
    val livePack: ModelPack?,
    val finalPack: ModelPack?,
    val deviceProfile: DeviceProfile,
)
