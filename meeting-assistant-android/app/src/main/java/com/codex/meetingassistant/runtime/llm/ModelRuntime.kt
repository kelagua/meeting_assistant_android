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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

data class ModelRuntimeState(
    val backendLabel: String = "heuristic fallback",
    val detail: String = "Rules-based summarization is ready.",
    val lastError: String? = null,
)

interface ModelRuntime {
    val runtimeState: StateFlow<ModelRuntimeState>

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
    private val _runtimeState = MutableStateFlow(
        ModelRuntimeState(
            backendLabel = "heuristic fallback",
            detail = "Rules-based summarization is active.",
        ),
    )

    override val runtimeState: StateFlow<ModelRuntimeState> = _runtimeState.asStateFlow()

    override suspend fun generateChunkSummary(
        meeting: Meeting,
        modelPack: ModelPack,
        segments: List<TranscriptSegment>,
    ): ChunkSummaryDraft {
        _runtimeState.value = _runtimeState.value.copy(
            backendLabel = "heuristic fallback",
            detail = "Using rules-based live notes for ${meeting.title}.",
            lastError = null,
        )

        val headline = segments.firstOrNull()?.text?.take(32)?.ifBlank { meeting.title }
            ?: "Meeting progress update"
        val bullets = segments
            .chunked(2)
            .take(3)
            .map { chunk ->
                SummaryBullet(
                    text = chunk.joinToString(" ") { it.text }.take(96),
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
        _runtimeState.value = _runtimeState.value.copy(
            backendLabel = "heuristic fallback",
            detail = "Using rules-based final summary for ${meeting.title}.",
            lastError = null,
        )

        val nonEmptySegments = segments.filter { it.text.isNotBlank() }
        val overviewText = chunkSummaries.lastOrNull()?.headline
            ?: nonEmptySegments.takeLast(5).joinToString(" ") { it.text }.take(180)
                .ifBlank { "No final summary generated." }

        val decisions = buildBullets(nonEmptySegments, listOf("决定", "确认", "agree", "approved"))
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
        .map { segment -> SummaryBullet(text = segment.text.take(140), anchorSegmentIds = listOf(segment.id)) }

    private fun fallbackBullets(chunkSummaries: List<ChunkSummary>): List<SummaryBullet> =
        chunkSummaries.takeLast(3).flatMap { it.bullets }.take(3)

    private fun buildActionItems(
        segments: List<TranscriptSegment>,
        assignments: List<SpeakerAssignment>,
        profiles: List<SpeakerProfile>,
    ): List<ActionItem> {
        val profileNames = profiles.associateBy({ it.id }, { it.displayName })
        val speakerOwners = assignments.associateBy({ it.speakerLabel }, { it.profileId?.let(profileNames::get) })
        val keywords = listOf("负责", "跟进", "提交", "安排", "will", "need to", "action")
        return segments
            .filter { segment -> keywords.any { keyword -> segment.text.contains(keyword, ignoreCase = true) } }
            .take(4)
            .map { segment ->
                ActionItem(
                    owner = speakerOwners[segment.speakerLabel] ?: segment.speakerLabel,
                    task = segment.text.take(120),
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
                    note = speakerSegments.takeLast(2).joinToString(" ") { it.text }.take(140),
                    anchorSegmentIds = speakerSegments.takeLast(2).map { it.id },
                )
            }
    }

    private fun extractDueHint(text: String): String? {
        val dueHints = listOf("今天", "明天", "下周", "today", "tomorrow", "next week")
        return dueHints.firstOrNull { text.contains(it, ignoreCase = true) }
    }
}

/**
 * LiteRT-backed summary runtime.
 *
 * Falls back to [HeuristicModelRuntime] whenever no local model is available or LiteRT fails.
 */
class LiteRtModelRuntime : ModelRuntime {
    private val heuristicFallback = HeuristicModelRuntime()
    private val _runtimeState = MutableStateFlow(
        ModelRuntimeState(
            backendLabel = "litert standby",
            detail = "Download a local model to enable LiteRT summaries.",
        ),
    )

    override val runtimeState: StateFlow<ModelRuntimeState> = _runtimeState.asStateFlow()

    private var cachedEngine: com.google.ai.edge.litertlm.Engine? = null
    private var currentModelPath: String? = null
    private var lastInitializationError: Throwable? = null

    override suspend fun generateChunkSummary(
        meeting: Meeting,
        modelPack: ModelPack,
        segments: List<TranscriptSegment>,
    ): ChunkSummaryDraft {
        val localPath = modelPack.localFilePath
        if (localPath.isNullOrBlank()) {
            setFallbackState("No local live-summary model is ready for ${modelPack.displayName}.")
            return heuristicFallback.generateChunkSummary(meeting, modelPack, segments)
        }

        return try {
            val engine = getOrCreateEngine(localPath)
            if (engine == null) {
                val reason = lastInitializationError?.message ?: "LiteRT engine initialization failed."
                setFallbackState("LiteRT could not initialize ${modelPack.displayName}.", reason)
                return heuristicFallback.generateChunkSummary(meeting, modelPack, segments)
            }

            _runtimeState.value = ModelRuntimeState(
                backendLabel = "litert active",
                detail = "Using ${modelPack.displayName} for live notes.",
            )

            val prompt = buildChunkSummaryPrompt(meeting, segments)
            val result = collectModelResponse(engine, prompt)
            parseChunkSummaryResult(result, segments)
        } catch (error: Exception) {
            setFallbackState("LiteRT live summary failed for ${modelPack.displayName}.", error.message)
            heuristicFallback.generateChunkSummary(meeting, modelPack, segments)
        }
    }

    override suspend fun generateMeetingSummary(
        meeting: Meeting,
        modelPack: ModelPack,
        segments: List<TranscriptSegment>,
        chunkSummaries: List<ChunkSummary>,
        speakerAssignments: List<SpeakerAssignment>,
        speakerProfiles: List<SpeakerProfile>,
    ): MeetingSummaryDraft {
        val localPath = modelPack.localFilePath
        if (localPath.isNullOrBlank()) {
            setFallbackState("No local final-summary model is ready for ${modelPack.displayName}.")
            return heuristicFallback.generateMeetingSummary(
                meeting = meeting,
                modelPack = modelPack,
                segments = segments,
                chunkSummaries = chunkSummaries,
                speakerAssignments = speakerAssignments,
                speakerProfiles = speakerProfiles,
            )
        }

        return try {
            val engine = getOrCreateEngine(localPath)
            if (engine == null) {
                val reason = lastInitializationError?.message ?: "LiteRT engine initialization failed."
                setFallbackState("LiteRT could not initialize ${modelPack.displayName}.", reason)
                return heuristicFallback.generateMeetingSummary(
                    meeting = meeting,
                    modelPack = modelPack,
                    segments = segments,
                    chunkSummaries = chunkSummaries,
                    speakerAssignments = speakerAssignments,
                    speakerProfiles = speakerProfiles,
                )
            }

            _runtimeState.value = ModelRuntimeState(
                backendLabel = "litert active",
                detail = "Using ${modelPack.displayName} for the final summary.",
            )

            val prompt = buildMeetingSummaryPrompt(
                meeting = meeting,
                segments = segments,
                chunkSummaries = chunkSummaries,
                speakerAssignments = speakerAssignments,
                speakerProfiles = speakerProfiles,
            )
            val result = collectModelResponse(engine, prompt)
            parseMeetingSummaryResult(result, segments)
        } catch (error: Exception) {
            setFallbackState("LiteRT final summary failed for ${modelPack.displayName}.", error.message)
            heuristicFallback.generateMeetingSummary(
                meeting = meeting,
                modelPack = modelPack,
                segments = segments,
                chunkSummaries = chunkSummaries,
                speakerAssignments = speakerAssignments,
                speakerProfiles = speakerProfiles,
            )
        }
    }

    private suspend fun collectModelResponse(
        engine: com.google.ai.edge.litertlm.Engine,
        prompt: String,
    ): String = buildString {
        engine.createConversation().use { conversation ->
            conversation.sendMessageAsync(prompt).collect { token ->
                append(token)
            }
        }
    }

    private fun getOrCreateEngine(modelPath: String): com.google.ai.edge.litertlm.Engine? {
        if (currentModelPath == modelPath && cachedEngine != null) {
            return cachedEngine
        }

        cachedEngine?.close()
        cachedEngine = null
        currentModelPath = null
        lastInitializationError = null

        return try {
            val config = com.google.ai.edge.litertlm.EngineConfig(modelPath = modelPath)
            val engine = com.google.ai.edge.litertlm.Engine(config)
            engine.initialize()
            cachedEngine = engine
            currentModelPath = modelPath
            engine
        } catch (error: Exception) {
            lastInitializationError = error
            null
        }
    }

    private fun setFallbackState(detail: String, error: String? = null) {
        _runtimeState.value = ModelRuntimeState(
            backendLabel = "heuristic fallback",
            detail = detail,
            lastError = error,
        )
    }

    private fun buildChunkSummaryPrompt(
        meeting: Meeting,
        segments: List<TranscriptSegment>,
    ): String {
        val transcript = segments.joinToString("\n") { "[${it.speakerLabel}] ${it.text}" }
        return """
            You are summarizing an offline meeting transcript.
            Meeting title: ${meeting.title}
            Agenda: ${meeting.agenda.ifBlank { "(not provided)" }}

            Transcript:
            $transcript

            Respond with plain text using this exact format:
            HEADLINE: one short headline
            BULLET: concise point 1
            BULLET: concise point 2
            BULLET: concise point 3
            Keep it short and factual.
        """.trimIndent()
    }

    private fun buildMeetingSummaryPrompt(
        meeting: Meeting,
        segments: List<TranscriptSegment>,
        chunkSummaries: List<ChunkSummary>,
        speakerAssignments: List<SpeakerAssignment>,
        speakerProfiles: List<SpeakerProfile>,
    ): String {
        val transcript = segments.joinToString("\n") { "[${it.speakerLabel}] ${it.text}" }
        val chunkText = chunkSummaries.joinToString("\n") { "- ${it.headline}" }
        val speakerHints = speakerAssignments.joinToString("\n") { assignment ->
            val displayName = speakerProfiles.firstOrNull { it.id == assignment.profileId }?.displayName
            if (displayName.isNullOrBlank()) {
                "${assignment.speakerLabel}: unresolved"
            } else {
                "${assignment.speakerLabel}: $displayName"
            }
        }

        return """
            You are generating a structured meeting summary for an offline Android assistant.
            Meeting title: ${meeting.title}
            Agenda: ${meeting.agenda.ifBlank { "(not provided)" }}

            Speaker hints:
            ${speakerHints.ifBlank { "(none)" }}

            Live chunk summaries:
            ${chunkText.ifBlank { "(none)" }}

            Transcript:
            $transcript

            Respond in plain text using these prefixes only:
            OVERVIEW: one short overview sentence
            DECISION: one decision per line
            ACTION: owner | task | due(optional)
            RISK: one risk per line
            QUESTION: one open question per line
            SPEAKER: speaker name or label | note
            Keep every line concise.
        """.trimIndent()
    }

    private fun parseChunkSummaryResult(
        result: String,
        segments: List<TranscriptSegment>,
    ): ChunkSummaryDraft {
        val lines = result.lines().map(String::trim).filter(String::isNotEmpty)
        val headline = lines
            .firstOrNull { it.startsWith("HEADLINE:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.take(60)
            ?: lines.firstOrNull()?.take(60)
            ?: "Meeting progress update"

        val bulletLines = lines
            .filter { it.startsWith("BULLET:", ignoreCase = true) }
            .map { it.substringAfter(':').trim() }
            .filter(String::isNotEmpty)
            .take(5)

        val bullets = bulletLines.mapIndexed { index, text ->
            SummaryBullet(
                text = text.take(140),
                anchorSegmentIds = segments.getOrNull(index * 2)?.let { listOf(it.id) } ?: emptyList(),
            )
        }.ifEmpty {
            listOf(
                SummaryBullet(
                    text = result.take(140).ifBlank { "No summary generated." },
                    anchorSegmentIds = segments.take(2).map { it.id },
                ),
            )
        }

        return ChunkSummaryDraft(
            headline = headline,
            bullets = bullets,
        )
    }

    private fun parseMeetingSummaryResult(
        result: String,
        segments: List<TranscriptSegment>,
    ): MeetingSummaryDraft {
        val lines = result.lines().map(String::trim).filter(String::isNotEmpty)

        fun values(prefix: String): List<String> = lines
            .filter { it.startsWith(prefix, ignoreCase = true) }
            .map { it.substringAfter(':').trim() }
            .filter(String::isNotEmpty)

        val overviewText = values("OVERVIEW").firstOrNull()
            ?: lines.firstOrNull()
            ?: "No summary generated."
        val decisions = values("DECISION").take(4).map { SummaryBullet(it.take(140), emptyList()) }
        val risks = values("RISK").take(4).map { SummaryBullet(it.take(140), emptyList()) }
        val questions = values("QUESTION").take(4).map { SummaryBullet(it.take(140), emptyList()) }
        val actionItems = values("ACTION").take(4).map { line ->
            val parts = line.split('|').map { it.trim() }
            ActionItem(
                owner = parts.getOrNull(0).orEmpty().ifBlank { "Unassigned" },
                task = parts.getOrNull(1).orEmpty().ifBlank { line.take(120) },
                dueHint = parts.getOrNull(2)?.ifBlank { null },
                anchorSegmentIds = emptyList(),
            )
        }
        val speakerNotes = values("SPEAKER").take(5).map { line ->
            val parts = line.split('|').map { it.trim() }
            val label = parts.getOrNull(0).orEmpty().ifBlank { "Speaker" }
            SpeakerNote(
                speakerLabel = label,
                displayName = if (label.startsWith("Speaker ")) null else label,
                note = parts.getOrNull(1).orEmpty().ifBlank { line.take(140) },
                anchorSegmentIds = emptyList(),
            )
        }

        return MeetingSummaryDraft(
            overview = SummaryBullet(overviewText.take(200), segments.takeLast(3).map { it.id }),
            decisions = decisions,
            actionItems = actionItems,
            risks = risks,
            openQuestions = questions,
            speakerNotes = speakerNotes,
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
