package com.codex.meetingassistant.runtime.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

data class AudioFrame(
    val bytes: ByteArray,
    val timestampEpochMs: Long,
    val rms: Double,
)

data class AudioCaptureMetrics(
    val droppedFrames: Int = 0,
    val ringBufferFillRatio: Float = 0f,
)

data class CapturedAudioSession(
    val tempFile: File,
    val durationMs: Long,
)

class CircularAudioBuffer(private val capacityBytes: Int) {
    private val buffer = ByteArray(capacityBytes)
    private var writeIndex = 0
    private var totalWritten = 0

    @Synchronized
    fun append(frame: ByteArray) {
        frame.forEach { byte ->
            buffer[writeIndex] = byte
            writeIndex = (writeIndex + 1) % capacityBytes
            totalWritten++
        }
    }

    @Synchronized
    fun fillRatio(): Float = (totalWritten.coerceAtMost(capacityBytes)).toFloat() / capacityBytes

    @Synchronized
    fun snapshot(): ByteArray {
        val size = totalWritten.coerceAtMost(capacityBytes)
        val output = ByteArray(size)
        val start = if (totalWritten < capacityBytes) 0 else writeIndex
        for (index in 0 until size) {
            output[index] = buffer[(start + index) % capacityBytes]
        }
        return output
    }
}

interface AudioCaptureEngine {
    val audioFrames: SharedFlow<AudioFrame>
    val metrics: StateFlow<AudioCaptureMetrics>

    suspend fun start(tempFile: File)
    suspend fun stop(): CapturedAudioSession?
}

class AudioRecordCaptureEngine(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val ringBuffer: CircularAudioBuffer = CircularAudioBuffer(capacityBytes = 16_000 * 2 * 90),
) : AudioCaptureEngine {
    override val audioFrames: SharedFlow<AudioFrame> get() = _audioFrames.asSharedFlow()
    override val metrics: StateFlow<AudioCaptureMetrics> get() = _metrics.asStateFlow()

    private val _audioFrames = MutableSharedFlow<AudioFrame>(
        extraBufferCapacity = 24,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val _metrics = MutableStateFlow(AudioCaptureMetrics())

    private var recorder: AudioRecord? = null
    private var captureJob: Job? = null
    private var tempFile: File? = null
    private var startElapsedMs: Long = 0L

    override suspend fun start(tempFile: File) {
        if (captureJob != null) return
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw IllegalStateException(
                "RECORD_AUDIO permission is required before starting audio capture.",
            )
        }

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBufferSize, FRAME_BYTES * 2),
            )
        } catch (securityException: SecurityException) {
            throw IllegalStateException(
                "RECORD_AUDIO permission is required before starting audio capture.",
                securityException,
            )
        }
        this.tempFile = tempFile
        recorder = record
        startElapsedMs = SystemClock.elapsedRealtime()
        try {
            record.startRecording()
        } catch (securityException: SecurityException) {
            record.release()
            recorder = null
            this.tempFile = null
            throw IllegalStateException(
                "Audio capture could not start because RECORD_AUDIO permission was denied.",
                securityException,
            )
        }

        captureJob = scope.launch {
            FileOutputStream(tempFile).use { output ->
                while (isActive) {
                    val localRecorder = recorder ?: break
                    val buffer = ByteArray(FRAME_BYTES)
                    val bytesRead = localRecorder.read(buffer, 0, buffer.size)
                    if (bytesRead <= 0) continue
                    val payload = if (bytesRead == buffer.size) buffer else buffer.copyOf(bytesRead)
                    ringBuffer.append(payload)
                    output.write(payload)
                    val frame = AudioFrame(
                        bytes = payload,
                        timestampEpochMs = System.currentTimeMillis(),
                        rms = payload.calculateRms(),
                    )
                    if (!_audioFrames.tryEmit(frame)) {
                        _metrics.value = _metrics.value.copy(droppedFrames = _metrics.value.droppedFrames + 1)
                    }
                    _metrics.value = _metrics.value.copy(ringBufferFillRatio = ringBuffer.fillRatio())
                }
            }
        }
    }

    override suspend fun stop(): CapturedAudioSession? {
        val file = tempFile ?: return null
        runCatching { recorder?.stop() }
        captureJob?.cancelAndJoin()
        captureJob = null
        recorder?.release()
        recorder = null
        tempFile = null
        val durationMs = SystemClock.elapsedRealtime() - startElapsedMs
        return CapturedAudioSession(file, durationMs)
    }

    private fun ByteArray.calculateRms(): Double {
        if (isEmpty()) return 0.0
        var sum = 0.0
        var sampleCount = 0
        for (index in indices step 2) {
            if (index + 1 >= size) break
            val sample = ((this[index + 1].toInt() shl 8) or (this[index].toInt() and 0xFF)).toShort()
            sum += sample * sample
            sampleCount++
        }
        return if (sampleCount == 0) 0.0 else sqrt(sum / sampleCount)
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_BYTES = 3200
    }
}
