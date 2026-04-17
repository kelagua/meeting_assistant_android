package com.codex.meetingassistant.runtime.llm

import com.codex.meetingassistant.data.model.Meeting
import com.codex.meetingassistant.data.model.MeetingStatus
import com.codex.meetingassistant.data.model.ModelPack
import com.codex.meetingassistant.data.model.ModelPackStatus
import com.codex.meetingassistant.data.model.SummaryStage
import com.codex.meetingassistant.data.model.TranscriptSegment
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class HeuristicModelRuntimeTest {
    private val runtime = HeuristicModelRuntime()

    @Test
    fun `generateMeetingSummary extracts decisions and actions`() = runTest {
        val summary = runtime.generateMeetingSummary(
            meeting = meeting(),
            modelPack = pack(),
            segments = listOf(
                segment("1", "Speaker A", "今天决定先做 Android 本地 MVP。"),
                segment("2", "Speaker B", "I will prepare the benchmark selector tomorrow."),
                segment("3", "Speaker C", "风险是桌面拾音会影响 speaker diarization."),
            ),
            chunkSummaries = emptyList(),
            speakerAssignments = emptyList(),
            speakerProfiles = emptyList(),
        )

        assertTrue(summary.decisions.any { it.text.contains("决定") })
        assertTrue(summary.actionItems.any { it.task.contains("benchmark selector") })
        assertTrue(summary.risks.any { it.text.contains("风险") || it.text.contains("diarization") })
    }

    private fun meeting(): Meeting = Meeting(
        id = "m1",
        title = "Weekly Sync",
        agenda = "MVP",
        status = MeetingStatus.LIVE,
        createdAtEpochMs = 1L,
        startedAtEpochMs = 1L,
        endedAtEpochMs = null,
        summariesPaused = false,
    )

    private fun pack(): ModelPack = ModelPack(
        id = "live",
        displayName = "live",
        description = "live",
        stage = SummaryStage.FINAL_SUMMARY,
        quantization = "int4",
        estimatedSizeMb = 1000,
        assetPath = "assets/live",
        status = ModelPackStatus.AVAILABLE,
        minRamGb = 8,
        minScore = 70,
        supportedLanguages = listOf("zh-CN", "en-US"),
    )

    private fun segment(id: String, speaker: String, text: String): TranscriptSegment = TranscriptSegment(
        id = id,
        meetingId = "m1",
        startMs = 0L,
        endMs = 1_000L,
        text = text,
        speakerLabel = speaker,
        speakerConfidence = 0.8f,
        asrConfidence = 0.9f,
        isFinal = true,
        createdAtEpochMs = 1L,
    )
}
