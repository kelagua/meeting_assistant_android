package com.codex.meetingassistant.runtime.speaker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.codex.meetingassistant.runtime.sherpa.SherpaOnnxSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class SpeakerEnrollmentRecorder(
    private val context: Context,
) {
    suspend fun recordAndExtractEmbedding(
        displayName: String,
        keepsAudioSamples: Boolean,
        durationMs: Long = 4_000L,
    ): SpeakerEnrollmentResult = withContext(Dispatchers.IO) {
        val extractor = SherpaOnnxSupport.createSpeakerExtractor(context)
        if (extractor == null) {
            return@withContext SpeakerEnrollmentResult(
                embeddingBase64 = fallbackEmbedding(displayName),
                mode = EnrollmentMode.FALLBACK,
                keepsAudioSamples = keepsAudioSamples,
                sampleCount = 0,
                detail = buildFallbackReason(),
            )
        }

        val record = buildRecorder()
        val stream = extractor.createStream()
        try {
            record.startRecording()
            val buffer = ByteArray(BUFFER_BYTES)
            var collectedSamples = 0
            val targetSamples = ((durationMs * SAMPLE_RATE_HZ) / 1_000L).toInt()
            while (collectedSamples < targetSamples) {
                val bytesRead = record.read(buffer, 0, buffer.size)
                if (bytesRead <= 0) continue
                val payload = if (bytesRead == buffer.size) buffer else buffer.copyOf(bytesRead)
                val samples = SherpaOnnxSupport.pcm16ToFloatArray(payload)
                if (samples.isEmpty()) continue
                stream.acceptWaveform(samples, SAMPLE_RATE_HZ)
                collectedSamples += samples.size
            }

            stream.inputFinished()
            if (!extractor.isReady(stream)) {
                return@withContext SpeakerEnrollmentResult(
                    embeddingBase64 = fallbackEmbedding(displayName),
                    mode = EnrollmentMode.FALLBACK,
                    keepsAudioSamples = keepsAudioSamples,
                    sampleCount = collectedSamples,
                    detail = "sherpa-onnx speaker extractor did not receive enough speech frames.",
                )
            }

            val embedding = extractor.compute(stream)
            SpeakerEnrollmentResult(
                embeddingBase64 = SherpaOnnxSupport.embeddingToBase64(embedding),
                mode = EnrollmentMode.SHERPA,
                keepsAudioSamples = keepsAudioSamples,
                sampleCount = collectedSamples,
                detail = "Captured ${(collectedSamples * 1_000L) / SAMPLE_RATE_HZ} ms enrollment audio.",
            )
        } finally {
            runCatching { record.stop() }
            record.release()
            stream.release()
            extractor.release()
        }
    }

    private fun buildRecorder(): AudioRecord {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw IllegalStateException("RECORD_AUDIO permission is required before speaker enrollment.")
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        return AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, BUFFER_BYTES * 2),
        )
    }

    private fun buildFallbackReason(): String {
        val missingAssets = SherpaOnnxSupport.missingSpeakerAssets(context.assets)
        val assetReason = if (missingAssets.isEmpty()) {
            null
        } else {
            "Missing speaker model asset: ${missingAssets.joinToString()}"
        }
        val nativeReason = SherpaOnnxSupport.nativeLoadError?.message
        return listOfNotNull(assetReason, nativeReason).joinToString(" | ").ifBlank {
            "sherpa-onnx speaker runtime is unavailable."
        }
    }

    private fun fallbackEmbedding(displayName: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(displayName.trim().lowercase().toByteArray())
        val floats = FloatArray(digest.size / 4) { index ->
            val start = index * 4
            ByteBuffer.wrap(digest, start, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() / Int.MAX_VALUE.toFloat()
        }
        return SherpaOnnxSupport.embeddingToBase64(floats)
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val BUFFER_BYTES = 3_200
    }
}

data class SpeakerEnrollmentResult(
    val embeddingBase64: String,
    val mode: EnrollmentMode,
    val keepsAudioSamples: Boolean,
    val sampleCount: Int,
    val detail: String,
)

enum class EnrollmentMode {
    SHERPA,
    FALLBACK,
}
