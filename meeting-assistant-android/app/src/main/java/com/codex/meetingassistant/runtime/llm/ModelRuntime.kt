package com.codex.meetingassistant.runtime.llm

import com.codex.meetingassistant.data.model.ActionItem
import com.codex.meetingassistant.data.model.ChunkSummary
import com.codex.meetingassistant.data.model.Meeting
import com.codex.meetingassistant.data.model.MeetingSummary
import com.codex.meetingassistant.data.model.ModelPack
import com.codex.meetingassistant.data.model.SpeakerAssignment
import com.codex.meetingassistant.data.model.SpeakerNote
import com.codex.meetingassistant.data.model.SpeakerProfile
import com.codex.meetingassistant.data.model.SummaryBullet
import com.codex.meetingassistant.data.model.TranscriptSegment
import java.util.UUID

data class ChunkSummaryDraft(
    val headline: String,
    val bullets: List<SummaryBullet>,
)

data class MeetingSummaryDraft(
    val overview: SummaryBullet,
    val decisions: List<SummaryBullet>,
    val actionItems: List<ActionItem>,
    val risks: List<SummaryBullet>,
    val openQuestions: List<SummaryBullet>,
    val speakerNotes: List<SpeakerNote>,
)

interface ModelRuntime {
    suspend fun generateChunkSummary(
        meeting: Meeting,
        modelPack: ModelPack,
        segments: List<TranscriptSegment>,
    ): ChunkSummaryDraft

    suspend fun generateMeetingSummary(
        meeting: Meeting,
        modelPack: ModelPack,
        segments: List<TranscriptSegment>,
        chunkSummaries: List<ChunkSummary>,
        speakerAssignments: List<SpeakerAssignment>,
        speakerProfiles: List<SpeakerProfile>,
    ): MeetingSummaryDraft
}

class HeuristicModelRuntime : ModelRuntime {
    override suspend fun generateChunkSummary(
        meeting: Meeting,
        modelPack: ModelPack,
        segments: List<TranscriptSegment>,
    ): ChunkSummaryDraft {
        val headline = segments.firstOrNull()?.text?.take(24)?.ifBlank { meeting.title }
            ?: "Meeting progress update"
        val bullets = segments
            .chunked(2)
            .take(3)
            .map { chunk ->
                SummaryBullet(
                    text = chunk.joinToString(" ") { it.text }.take(72),
                    anchorSegmentIds = chunk.map { it.id },
                )
            }

        return ChunkSummaryDraft(
            headline = headline,
            bullets = bullets.ifEmpty {
                listOf(SummaryBullet("No spoken content captured yet.", anchorSegmentIds = emptyList()))
            },
        )
    }

    override suspend fun generateMeetingSummary(
        meeting: Meeting,
        modelPack: ModelPack,
        segments: List<TranscriptSegment>,
        chunkSummaries: List<ChunkSummary>,
        speakerAssignments: List<SpeakerAssignment>,
        speakerProfiles: List<SpeakerProfile>,
    ): MeetingSummaryDraft {
        val nonEmptySegments = segments.filter { it.text.isNotBlank() }
        val overviewText = chunkSummaries.lastOrNull()?.headline
            ?: nonEmptySegments.takeLast(5).joinToString(" ") { it.text }.take(140)
                .ifBlank { "No final summary generated." }

        val decisions = buildBullets(nonEmptySegments, listOf("决定", "确定", "agree", "approved"))
            .ifEmpty { fallbackBullets(chunkSummaries) }
        val risks = buildBullets(nonEmptySegments, listOf("风险", "阻塞", "risk", "blocker"))
        val openQuestions = buildBullets(nonEmptySegments, listOf("待确认", "问题", "question", "follow up"))
        val actionItems = buildActionItems(nonEmptySegments, speakerAssignments, speakerProfiles)
        val speakerNotes = buildSpeakerNotes(nonEmptySegments, speakerAssignments, speakerProfiles)

        return MeetingSummaryDraft(
            overview = SummaryBullet(overviewText, nonEmptySegments.takeLast(5).map { it.id }),
            decisions = decisions,
            actionItems = actionItems,
            risks = risks,
            openQuestions = openQuestions,
            speakerNotes = speakerNotes,
        )
    }

    private fun buildBullets(
        segments: List<TranscriptSegment>,
        keywords: List<String>,
    ): List<SummaryBullet> = segments
        .filter { segment -> keywords.any { keyword -> segment.text.contains(keyword, ignoreCase = true) } }
        .take(4)
        .map { segment -> SummaryBullet(text = segment.text.take(120), anchorSegmentIds = listOf(segment.id)) }

    private fun fallbackBullets(chunkSummaries: List<ChunkSummary>): List<SummaryBullet> =
        chunkSummaries.takeLast(3).flatMap { it.bullets }.take(3)

    private fun buildActionItems(
        segments: List<TranscriptSegment>,
        assignments: List<SpeakerAssignment>,
        profiles: List<SpeakerProfile>,
    ): List<ActionItem> {
        val profileNames = profiles.associateBy({ it.id }, { it.displayName })
        val speakerOwners = assignments.associateBy({ it.speakerLabel }, { it.profileId?.let(profileNames::get) })
        val candidates = segments.filter {
            listOf("负责", "跟进", "提交", "安排", "will", "need to", "action").any { keyword ->
                it.text.contains(keyword, ignoreCase = true)
            }
        }
        return candidates.take(4).map { segment ->
            ActionItem(
                owner = speakerOwners[segment.speakerLabel] ?: segment.speakerLabel,
                task = segment.text.take(100),
                dueHint = extractDueHint(segment.text),
                anchorSegmentIds = listOf(segment.id),
            )
        }
    }

    private fun buildSpeakerNotes(
        segments: List<TranscriptSegment>,
        assignments: List<SpeakerAssignment>,
        profiles: List<SpeakerProfile>,
    ): List<SpeakerNote> {
        val profileNames = profiles.associateBy({ it.id }, { it.displayName })
        return segments
            .groupBy { it.speakerLabel }
            .entries
            .take(5)
            .map { (speakerLabel, speakerSegments) ->
                val assignment = assignments.firstOrNull { it.speakerLabel == speakerLabel }
                SpeakerNote(
                    speakerLabel = speakerLabel,
                    displayName = assignment?.profileId?.let(profileNames::get),
                    note = speakerSegments.takeLast(2).joinToString(" ") { it.text }.take(120),
                    anchorSegmentIds = speakerSegments.takeLast(2).map { it.id },
                )
            }
    }

    private fun extractDueHint(text: String): String? {
        val dueHints = listOf("今天", "明天", "下周", "today", "tomorrow", "next week")
        return dueHints.firstOrNull { text.contains(it, ignoreCase = true) }
    }
}

class LiteRtModelRuntime(
    private val fallback: ModelRuntime = HeuristicModelRuntime(),
) : ModelRuntime {
    override suspend fun generateChunkSummary(
        meeting: Meeting,
        modelPack: ModelPack,
        segments: List<TranscriptSegment>,
    ): ChunkSummaryDraft {
        // TODO: Replace this fallback with LiteRT/MediaPipe GenAI integration.
        return fallback.generateChunkSummary(meeting, modelPack, segments)
    }

    override suspend fun generateMeetingSummary(
        meeting: Meeting,
        modelPack: ModelPack,
        segments: List<TranscriptSegment>,
        chunkSummaries: List<ChunkSummary>,
        speakerAssignments: List<SpeakerAssignment>,
        speakerProfiles: List<SpeakerProfile>,
    ): MeetingSummaryDraft {
        // TODO: Replace this fallback with LiteRT/MediaPipe GenAI integration.
        return fallback.generateMeetingSummary(
            meeting = meeting,
            modelPack = modelPack,
            segments = segments,
            chunkSummaries = chunkSummaries,
            speakerAssignments = speakerAssignments,
            speakerProfiles = speakerProfiles,
        )
    }
}

fun ChunkSummaryDraft.toDomain(
    meetingId: String,
    startMs: Long,
    endMs: Long,
    modelPackId: String,
): ChunkSummary = ChunkSummary(
    id = UUID.randomUUID().toString(),
    meetingId = meetingId,
    startMs = startMs,
    endMs = endMs,
    headline = headline,
    bullets = bullets,
    modelPackId = modelPackId,
    createdAtEpochMs = System.currentTimeMillis(),
)

fun MeetingSummaryDraft.toDomain(
    meetingId: String,
    modelPackId: String,
): MeetingSummary = MeetingSummary(
    meetingId = meetingId,
    overview = overview,
    decisions = decisions,
    actionItems = actionItems,
    risks = risks,
    openQuestions = openQuestions,
    speakerNotes = speakerNotes,
    modelPackId = modelPackId,
    generatedAtEpochMs = System.currentTimeMillis(),
)
