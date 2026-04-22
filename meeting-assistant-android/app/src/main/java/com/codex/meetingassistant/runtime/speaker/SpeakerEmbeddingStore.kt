package com.codex.meetingassistant.runtime.speaker

import com.codex.meetingassistant.runtime.sherpa.SherpaOnnxSupport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SpeakerEmbeddingStore(
    private val anonymousSpeakerThreshold: Float = 0.72f,
) {
    private val mutex = Mutex()
    private val anonymousSpeakers = linkedMapOf<String, SpeakerEmbeddingSnapshot>()

    suspend fun reset() = mutex.withLock {
        anonymousSpeakers.clear()
    }

    suspend fun assignAnonymousSpeaker(embedding: FloatArray): SpeakerEmbeddingObservation = mutex.withLock {
        val existing = anonymousSpeakers.maxByOrNull { (_, snapshot) ->
            SherpaOnnxSupport.cosineSimilarity(snapshot.embedding, embedding)
        }

        if (existing != null) {
            val similarity = SherpaOnnxSupport.cosineSimilarity(existing.value.embedding, embedding)
            if (similarity >= anonymousSpeakerThreshold) {
                val updated = existing.value.merge(embedding)
                anonymousSpeakers[existing.key] = updated
                return@withLock SpeakerEmbeddingObservation(
                    speakerLabel = existing.key,
                    confidence = similarity.coerceIn(0f, 1f),
                    embedding = updated.embedding,
                )
            }
        }

        val label = generateSpeakerLabel(anonymousSpeakers.size)
        anonymousSpeakers[label] = SpeakerEmbeddingSnapshot(embedding = embedding.copyOf(), observationCount = 1)
        SpeakerEmbeddingObservation(
            speakerLabel = label,
            confidence = 0.62f,
            embedding = embedding.copyOf(),
        )
    }

    suspend fun rememberSpeakerEmbedding(speakerLabel: String, embedding: FloatArray) = mutex.withLock {
        val existing = anonymousSpeakers[speakerLabel]
        anonymousSpeakers[speakerLabel] = existing?.merge(embedding) ?: SpeakerEmbeddingSnapshot(
            embedding = embedding.copyOf(),
            observationCount = 1,
        )
    }

    suspend fun embeddingFor(speakerLabel: String): FloatArray? = mutex.withLock {
        anonymousSpeakers[speakerLabel]?.embedding?.copyOf()
    }

    private fun generateSpeakerLabel(index: Int): String {
        var value = index
        val builder = StringBuilder()
        do {
            builder.append(('A'.code + (value % 26)).toChar())
            value = value / 26 - 1
        } while (value >= 0)
        return "Speaker ${builder.reverse()}"
    }
}

data class SpeakerEmbeddingObservation(
    val speakerLabel: String,
    val confidence: Float,
    val embedding: FloatArray,
)

private data class SpeakerEmbeddingSnapshot(
    val embedding: FloatArray,
    val observationCount: Int,
) {
    fun merge(newEmbedding: FloatArray): SpeakerEmbeddingSnapshot {
        if (newEmbedding.size != embedding.size) {
            return copy(embedding = newEmbedding.copyOf(), observationCount = 1)
        }
        val totalCount = observationCount + 1
        val merged = FloatArray(embedding.size) { index ->
            ((embedding[index] * observationCount) + newEmbedding[index]) / totalCount
        }
        return SpeakerEmbeddingSnapshot(
            embedding = merged,
            observationCount = totalCount,
        )
    }
}
