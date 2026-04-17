package com.codex.meetingassistant.service

import android.content.Context
import com.codex.meetingassistant.data.crypto.CryptoBox
import com.codex.meetingassistant.data.model.ExportFormat
import com.codex.meetingassistant.data.model.Meeting
import com.codex.meetingassistant.data.model.MeetingStatus
import com.codex.meetingassistant.data.model.SummaryStage
import com.codex.meetingassistant.data.repo.MeetingRepository
import com.codex.meetingassistant.data.repo.SettingsRepository
import com.codex.meetingassistant.runtime.audio.AudioCaptureEngine
import com.codex.meetingassistant.runtime.asr.RecognitionConfig
import com.codex.meetingassistant.runtime.asr.SpeechRecognizer
import com.codex.meetingassistant.runtime.llm.ModelPackManager
import com.codex.meetingassistant.runtime.llm.ModelRuntime
import com.codex.meetingassistant.runtime.llm.toDomain
import com.codex.meetingassistant.runtime.speaker.SpeakerIdentificationEngine
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SessionRuntimeState(
    val activeMeetingId: String? = null,
    val recognizerLabel: String = "idle",
    val liveSummariesEnabled: Boolean = true,
    val speakerIdentificationEnabled: Boolean = true,
    val lastExportPath: String? = null,
)

class MeetingSessionCoordinator(
    private val context: Context,
    private val repository: MeetingRepository,
    private val settingsRepository: SettingsRepository,
    private val modelPackManager: ModelPackManager,
    private val modelRuntime: ModelRuntime,
    private val audioCaptureEngine: AudioCaptureEngine,
    private val speechRecognizer: SpeechRecognizer,
    private val speakerIdentificationEngine: SpeakerIdentificationEngine,
    private val cryptoBox: CryptoBox,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    val runtimeState: StateFlow<SessionRuntimeState> get() = _runtimeState.asStateFlow()

    private val _runtimeState = MutableStateFlow(SessionRuntimeState())
    private val sessionMutex = Mutex()
    private var sessionJob: Job? = null
    private var activeMeeting: Meeting? = null
    private var tempAudioFile: File? = null
    private var lastChunkBoundaryMs: Long = 0L

    suspend fun start(meeting: Meeting) = sessionMutex.withLock {
        stopLocked()
        modelPackManager.ensureSeededPacks()
        repository.updateMeetingStatus(meeting.id, MeetingStatus.LIVE)
        activeMeeting = meeting
        lastChunkBoundaryMs = 0L
        tempAudioFile = File(context.cacheDir, "${meeting.id}.pcm")
        audioCaptureEngine.start(tempAudioFile ?: error("Temp file unavailable"))
        speechRecognizer.start(RecognitionConfig(meetingId = meeting.id))

        _runtimeState.value = SessionRuntimeState(
            activeMeetingId = meeting.id,
            recognizerLabel = speechRecognizer.engineLabel,
        )

        sessionJob = scope.launch {
            launch {
                audioCaptureEngine.audioFrames.collect { frame ->
                    speechRecognizer.accept(frame)
                    maybeDegrade()
                }
            }
            launch {
                speechRecognizer.transcriptEvents.collect { segment ->
                    repository.appendTranscriptSegment(segment)
                    maybeMatchSpeaker(segment.speakerLabel)
                    maybeGenerateChunkSummary(meeting.id)
                }
            }
        }
    }

    suspend fun stop() = sessionMutex.withLock {
        stopLocked()
    }

    private suspend fun stopLocked() {
        sessionJob?.cancelAndJoin()
        sessionJob = null

        val meeting = activeMeeting
        val capturedAudio = audioCaptureEngine.stop()
        val finalizedSegments = speechRecognizer.finalizeSegments()
        speechRecognizer.stop()
        finalizedSegments.forEach { repository.appendTranscriptSegment(it) }
        if (meeting != null) {
            repository.updateMeetingStatus(meeting.id, MeetingStatus.PROCESSING)
            capturedAudio?.let { session ->
                val encryptedPath = persistEncryptedAudio(meeting.id, session.tempFile)
                repository.attachAudioAsset(meeting.id, encryptedPath, session.durationMs)
                session.tempFile.delete()
            }
            generateFinalSummary(meeting.id)
            repository.updateMeetingStatus(
                meeting.id,
                MeetingStatus.COMPLETED,
                endedAtEpochMs = System.currentTimeMillis(),
            )
        }
        activeMeeting = null
        tempAudioFile = null
        _runtimeState.value = SessionRuntimeState()
    }

    suspend fun exportLatestMeeting(format: ExportFormat) {
        val meetingId = activeMeeting?.id ?: repository.latestMeetingDetail.first()?.meeting?.id ?: return
        val artifact = repository.exportMeeting(meetingId, format)
        _runtimeState.value = _runtimeState.value.copy(lastExportPath = artifact.filePath)
    }

    private suspend fun maybeMatchSpeaker(speakerLabel: String) {
        if (!_runtimeState.value.speakerIdentificationEnabled) return
        val meetingId = activeMeeting?.id ?: return
        val detail = repository.getMeetingDetail(meetingId) ?: return
        val existingLocked = detail.speakerAssignments.firstOrNull {
            it.speakerLabel == speakerLabel && it.lockedByUser
        }
        if (existingLocked != null) return
        speakerIdentificationEngine.match(
            meetingId = meetingId,
            speakerLabel = speakerLabel,
            profiles = detail.speakerProfiles,
        )?.let { assignment ->
            repository.upsertSpeakerAssignment(
                meetingId = assignment.meetingId,
                speakerLabel = assignment.speakerLabel,
                profileId = assignment.profileId,
                confidence = assignment.confidence,
                lockedByUser = assignment.lockedByUser,
            )
        }
    }

    private suspend fun maybeGenerateChunkSummary(meetingId: String) {
        val detail = repository.getMeetingDetail(meetingId) ?: return
        if (detail.meeting.summariesPaused || !_runtimeState.value.liveSummariesEnabled) return

        val preferences = settingsRepository.preferences.first()
        val pendingSegments = detail.transcriptSegments.filter { it.endMs > lastChunkBoundaryMs }
        if (pendingSegments.isEmpty()) return

        val enoughCharacters = pendingSegments.sumOf { it.text.length } >= preferences.liveSummaryCharacterTarget
        val enoughTime = pendingSegments.last().endMs - lastChunkBoundaryMs >=
            preferences.liveSummaryIntervalMinutes * 60_000L
        if (!enoughCharacters && !enoughTime) return

        val modelPack = modelPackManager.recommendPack(SummaryStage.LIVE_NOTES) ?: return
        val draft = modelRuntime.generateChunkSummary(
            meeting = detail.meeting,
            modelPack = modelPack,
            segments = pendingSegments,
        )
        repository.saveChunkSummary(
            draft.toDomain(
                meetingId = detail.meeting.id,
                startMs = pendingSegments.first().startMs,
                endMs = pendingSegments.last().endMs,
                modelPackId = modelPack.id,
            ),
        )
        lastChunkBoundaryMs = pendingSegments.last().endMs
    }

    private suspend fun generateFinalSummary(meetingId: String) {
        val detail = repository.getMeetingDetail(meetingId) ?: return
        val modelPack = modelPackManager.recommendPack(SummaryStage.FINAL_SUMMARY) ?: return
        val draft = modelRuntime.generateMeetingSummary(
            meeting = detail.meeting,
            modelPack = modelPack,
            segments = detail.transcriptSegments,
            chunkSummaries = detail.chunkSummaries,
            speakerAssignments = detail.speakerAssignments,
            speakerProfiles = detail.speakerProfiles,
        )
        repository.saveMeetingSummary(draft.toDomain(meetingId, modelPack.id))
    }

    private suspend fun maybeDegrade() {
        val metrics = audioCaptureEngine.metrics.value
        when {
            metrics.droppedFrames >= 18 -> {
                _runtimeState.value = _runtimeState.value.copy(
                    liveSummariesEnabled = false,
                    speakerIdentificationEnabled = false,
                )
            }

            metrics.droppedFrames >= 8 -> {
                _runtimeState.value = _runtimeState.value.copy(
                    liveSummariesEnabled = true,
                    speakerIdentificationEnabled = false,
                )
            }
        }
    }

    private fun persistEncryptedAudio(meetingId: String, tempFile: File): String {
        val outputFile = File(context.filesDir, "audio/${meetingId}.enc").apply {
            parentFile?.mkdirs()
        }
        outputFile.writeText(cryptoBox.seal(tempFile.readBytes()))
        return outputFile.absolutePath
    }
}
