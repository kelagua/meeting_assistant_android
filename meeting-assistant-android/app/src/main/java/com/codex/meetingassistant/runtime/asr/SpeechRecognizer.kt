package com.codex.meetingassistant.runtime.asr

import android.content.Context
import com.codex.meetingassistant.data.model.TranscriptSegment
import com.codex.meetingassistant.runtime.audio.AudioFrame
import com.codex.meetingassistant.runtime.sherpa.SherpaOnnxSupport
import com.codex.meetingassistant.runtime.speaker.SpeakerEmbeddingStore
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

data class RecognitionConfig(
    val meetingId: String,
    val languageHint: String = "zh-CN,en-US",
)

interface SpeechRecognizer {
    val transcriptEvents: SharedFlow<TranscriptSegment>
    val engineLabel: String

    suspend fun start(config: RecognitionConfig)
    suspend fun accept(frame: AudioFrame)
    suspend fun finalizeSegments(): List<TranscriptSegment>
    suspend fun stop()
}

class DemoSpeechRecognizer(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : SpeechRecognizer {
    override val transcriptEvents: SharedFlow<TranscriptSegment> get() = events.asSharedFlow()
    override val engineLabel: String = "demo-scripted"

    private val events = MutableSharedFlow<TranscriptSegment>(extraBufferCapacity = 16)
    private val emittedSegments = mutableListOf<TranscriptSegment>()
    private var scriptJob: Job? = null

    override suspend fun start(config: RecognitionConfig) {
        emittedSegments.clear()
        scriptJob = scope.launch {
            scriptedMeeting().forEachIndexed { index, item ->
                delay(if (index == 0) 1_400 else 2_500)
                val segment = TranscriptSegment(
                    id = item.id,
                    meetingId = config.meetingId,
                    startMs = item.startMs,
                    endMs = item.endMs,
                    text = item.text,
                    speakerLabel = item.speakerLabel,
                    speakerConfidence = item.speakerConfidence,
                    asrConfidence = item.asrConfidence,
                    isFinal = false,
                    createdAtEpochMs = System.currentTimeMillis(),
                )
                emittedSegments.removeAll { it.id == segment.id }
                emittedSegments += segment
                events.emit(segment)
            }
        }
    }

    override suspend fun accept(frame: AudioFrame) = Unit

    override suspend fun finalizeSegments(): List<TranscriptSegment> =
        emittedSegments.map { it.copy(isFinal = true, createdAtEpochMs = System.currentTimeMillis()) }

    override suspend fun stop() {
        scriptJob?.cancelAndJoin()
        scriptJob = null
    }

    private fun scriptedMeeting(): List<DemoUtterance> = listOf(
        DemoUtterance("Speaker A", "Let's confirm the Android offline meeting transcription scope first.", 0, 4_200),
        DemoUtterance("Speaker B", "We should keep everything offline and optimize for flagship devices first.", 4_500, 9_000),
        DemoUtterance("Speaker C", "We should start with enrollment plus anonymous speaker labels instead of fully automatic naming.", 9_300, 14_000),
        DemoUtterance("Speaker A", "This week we should land foreground service, recording, live captions, and the final summary flow.", 14_200, 19_000),
        DemoUtterance("Speaker B", "I will prepare the model pack manager and benchmark selector by tomorrow.", 19_300, 24_000),
        DemoUtterance("Speaker C", "One risk is that overlapping speech from a single device microphone can hurt diarization, so we need manual correction.", 24_200, 30_500),
    )
}

class SherpaSpeechRecognizer(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val speakerEmbeddingStore: SpeakerEmbeddingStore,
    private val fallback: SpeechRecognizer,
) : SpeechRecognizer {
    override val transcriptEvents: SharedFlow<TranscriptSegment> get() = events.asSharedFlow()
    override val engineLabel: String
        get() = currentEngineLabel

    private val events = MutableSharedFlow<TranscriptSegment>(extraBufferCapacity = 32)
    private val finalizedSegments = mutableListOf<TranscriptSegment>()
    private val utteranceAudio = FloatSampleBuffer()

    private var fallbackForwardJob: Job? = null
    private var currentEngineLabel = "sherpa-onnx"
    private var activeMeetingId: String? = null
    private var recognizer: OnlineRecognizer? = null
    private var recognizerStream: OnlineStream? = null
    private var speakerExtractor: SpeakerEmbeddingExtractor? = null
    private var acceptedSamples: Long = 0L
    private var currentUtteranceStartSample: Long? = null
    private var currentSegmentId: String? = null
    private var currentPartialText: String = ""
    private var usingFallback = false

    override suspend fun start(config: RecognitionConfig) {
        stop()
        activeMeetingId = config.meetingId
        finalizedSegments.clear()
        acceptedSamples = 0L
        clearCurrentUtterance()
        speakerEmbeddingStore.reset()

        recognizer = SherpaOnnxSupport.createOnlineRecognizer(context)
        speakerExtractor = SherpaOnnxSupport.createSpeakerExtractor(context)
        recognizerStream = recognizer?.createStream()
        usingFallback = recognizer == null || recognizerStream == null
        currentEngineLabel = if (usingFallback) {
            buildFallbackLabel()
        } else {
            if (speakerExtractor != null) "sherpa-onnx/asr+speaker" else "sherpa-onnx/asr"
        }

        if (usingFallback) {
            startFallback(config)
        }
    }

    override suspend fun accept(frame: AudioFrame) {
        if (usingFallback) {
            fallback.accept(frame)
            return
        }

        val stream = recognizerStream ?: return
        val localRecognizer = recognizer ?: return
        val samples = SherpaOnnxSupport.pcm16ToFloatArray(frame.bytes)
        if (samples.isEmpty()) return

        if (currentUtteranceStartSample == null) {
            currentUtteranceStartSample = acceptedSamples
        }

        utteranceAudio.append(samples)
        acceptedSamples += samples.size
        stream.acceptWaveform(samples = samples, sampleRate = SAMPLE_RATE_HZ)

        while (localRecognizer.isReady(stream)) {
            localRecognizer.decode(stream)
        }

        emitPartialSegmentIfNeeded()

        if (localRecognizer.isEndpoint(stream)) {
            finalizeCurrentUtterance(resetRecognizer = true)
        }
    }

    override suspend fun finalizeSegments(): List<TranscriptSegment> {
        if (usingFallback) {
            return fallback.finalizeSegments()
        }

        val stream = recognizerStream
        val localRecognizer = recognizer
        if (stream != null && localRecognizer != null) {
            stream.inputFinished()
            while (localRecognizer.isReady(stream)) {
                localRecognizer.decode(stream)
            }
            finalizeCurrentUtterance(resetRecognizer = false)
        }
        return finalizedSegments.toList()
    }

    override suspend fun stop() {
        fallbackForwardJob?.cancelAndJoin()
        fallbackForwardJob = null
        fallback.stop()

        recognizerStream?.release()
        recognizerStream = null
        recognizer?.release()
        recognizer = null
        speakerExtractor?.release()
        speakerExtractor = null

        usingFallback = false
        currentEngineLabel = "sherpa-onnx"
        activeMeetingId = null
        acceptedSamples = 0L
        clearCurrentUtterance()
    }

    private suspend fun startFallback(config: RecognitionConfig) {
        fallback.start(config)
        fallbackForwardJob = scope.launch {
            fallback.transcriptEvents.collect { segment ->
                events.emit(segment)
            }
        }
    }

    private suspend fun emitPartialSegmentIfNeeded() {
        val meetingId = activeMeetingId ?: return
        val stream = recognizerStream ?: return
        val localRecognizer = recognizer ?: return
        val result = localRecognizer.getResult(stream)
        val text = result.text.trim()
        if (text.isBlank() || text == currentPartialText) return

        currentPartialText = text
        val segmentId = currentSegmentId ?: UUID.randomUUID().toString().also { currentSegmentId = it }
        val startMs = sampleToMs(currentUtteranceStartSample ?: 0L)
        val endMs = sampleToMs(acceptedSamples)
        events.emit(
            TranscriptSegment(
                id = segmentId,
                meetingId = meetingId,
                startMs = startMs,
                endMs = endMs,
                text = text,
                speakerLabel = PENDING_SPEAKER_LABEL,
                speakerConfidence = 0f,
                asrConfidence = 0.74f,
                isFinal = false,
                createdAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun finalizeCurrentUtterance(resetRecognizer: Boolean) {
        val meetingId = activeMeetingId ?: return
        val stream = recognizerStream ?: return
        val localRecognizer = recognizer ?: return
        val result = localRecognizer.getResult(stream)
        val text = result.text.trim()

        if (text.isNotBlank()) {
            val segmentId = currentSegmentId ?: UUID.randomUUID().toString()
            val utteranceSamples = utteranceAudio.toFloatArray()
            val speakerObservation = identifyAnonymousSpeaker(utteranceSamples)
            val speakerLabel = speakerObservation?.first ?: DEFAULT_SPEAKER_LABEL
            val speakerConfidence = speakerObservation?.second ?: 0.58f
            val finalSegment = TranscriptSegment(
                id = segmentId,
                meetingId = meetingId,
                startMs = sampleToMs(currentUtteranceStartSample ?: 0L),
                endMs = sampleToMs(acceptedSamples),
                text = text,
                speakerLabel = speakerLabel,
                speakerConfidence = speakerConfidence,
                asrConfidence = 0.89f,
                isFinal = true,
                createdAtEpochMs = System.currentTimeMillis(),
            )
            finalizedSegments.removeAll { it.id == segmentId }
            finalizedSegments += finalSegment
            events.emit(finalSegment)
        }

        if (resetRecognizer) {
            localRecognizer.reset(stream)
        }
        clearCurrentUtterance()
    }

    private suspend fun identifyAnonymousSpeaker(samples: FloatArray): Pair<String, Float>? {
        if (samples.isEmpty()) return null
        val extractor = speakerExtractor ?: return null
        val stream = extractor.createStream()
        return try {
            stream.acceptWaveform(samples = samples, sampleRate = SAMPLE_RATE_HZ)
            stream.inputFinished()
            if (!extractor.isReady(stream)) return null
            val embedding = extractor.compute(stream)
            val observation = speakerEmbeddingStore.assignAnonymousSpeaker(embedding)
            speakerEmbeddingStore.rememberSpeakerEmbedding(observation.speakerLabel, observation.embedding)
            observation.speakerLabel to observation.confidence
        } finally {
            stream.release()
        }
    }

    private fun buildFallbackLabel(): String {
        val missingAssets = SherpaOnnxSupport.missingAsrAssets(context.assets)
        return when {
            SherpaOnnxSupport.nativeLoadError != null -> "demo-scripted (sherpa native missing)"
            missingAssets.isNotEmpty() -> "demo-scripted (sherpa assets missing)"
            else -> "demo-scripted (sherpa unavailable)"
        }
    }

    private fun clearCurrentUtterance() {
        utteranceAudio.clear()
        currentUtteranceStartSample = null
        currentSegmentId = null
        currentPartialText = ""
    }

    private fun sampleToMs(sampleIndex: Long): Long = (sampleIndex * 1_000L) / SAMPLE_RATE_HZ

    private companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val DEFAULT_SPEAKER_LABEL = "Speaker A"
        const val PENDING_SPEAKER_LABEL = "Pending Speaker"
    }
}

private data class DemoUtterance(
    val speakerLabel: String,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val speakerConfidence: Float = 0.72f,
    val asrConfidence: Float = 0.88f,
    val id: String = UUID.randomUUID().toString(),
)

private class FloatSampleBuffer {
    private var values = FloatArray(4_096)
    private var size = 0

    fun append(samples: FloatArray) {
        ensureCapacity(size + samples.size)
        samples.copyInto(values, destinationOffset = size)
        size += samples.size
    }

    fun toFloatArray(): FloatArray = values.copyOf(size)

    fun clear() {
        size = 0
    }

    private fun ensureCapacity(requiredSize: Int) {
        if (requiredSize <= values.size) return
        var newSize = values.size
        while (newSize < requiredSize) {
            newSize *= 2
        }
        values = values.copyOf(newSize)
    }
}
