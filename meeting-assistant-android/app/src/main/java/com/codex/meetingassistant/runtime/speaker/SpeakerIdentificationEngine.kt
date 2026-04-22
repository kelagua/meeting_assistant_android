package com.codex.meetingassistant.runtime.speaker

import com.codex.meetingassistant.data.model.SpeakerAssignment
import com.codex.meetingassistant.data.model.SpeakerProfile
import com.codex.meetingassistant.runtime.sherpa.SherpaOnnxSupport

interface SpeakerIdentificationEngine {
    suspend fun match(
        meetingId: String,
        speakerLabel: String,
        profiles: List<SpeakerProfile>,
    ): SpeakerAssignment?
}

class DemoSpeakerIdentificationEngine : SpeakerIdentificationEngine {
    override suspend fun match(
        meetingId: String,
        speakerLabel: String,
        profiles: List<SpeakerProfile>,
    ): SpeakerAssignment? {
        if (profiles.isEmpty()) return null
        val profile = profiles[speakerLabel.last().code % profiles.size]
        return SpeakerAssignment(
            id = "${meetingId}_$speakerLabel",
            meetingId = meetingId,
            speakerLabel = speakerLabel,
            profileId = profile.id,
            confidence = 0.74f,
            lockedByUser = false,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
    }
}

class SherpaSpeakerIdentificationEngine(
    private val speakerEmbeddingStore: SpeakerEmbeddingStore,
    private val fallback: SpeakerIdentificationEngine,
    private val profileSimilarityThreshold: Float = 0.70f,
) : SpeakerIdentificationEngine {
    override suspend fun match(
        meetingId: String,
        speakerLabel: String,
        profiles: List<SpeakerProfile>,
    ): SpeakerAssignment? {
        if (profiles.isEmpty() || speakerLabel == "Pending Speaker") return null

        val observedEmbedding = speakerEmbeddingStore.embeddingFor(speakerLabel)
        if (observedEmbedding == null) {
            return fallback.match(meetingId, speakerLabel, profiles)
        }

        val bestMatch = profiles.mapNotNull { profile ->
            val profileEmbedding = SherpaOnnxSupport.embeddingFromBase64(profile.embeddingBase64) ?: return@mapNotNull null
            val similarity = SherpaOnnxSupport.cosineSimilarity(observedEmbedding, profileEmbedding)
            profile to similarity
        }.maxByOrNull { it.second }

        if (bestMatch == null || bestMatch.second < profileSimilarityThreshold) {
            return null
        }

        return SpeakerAssignment(
            id = "${meetingId}_$speakerLabel",
            meetingId = meetingId,
            speakerLabel = speakerLabel,
            profileId = bestMatch.first.id,
            confidence = bestMatch.second.coerceIn(0f, 1f),
            lockedByUser = false,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
    }
}
