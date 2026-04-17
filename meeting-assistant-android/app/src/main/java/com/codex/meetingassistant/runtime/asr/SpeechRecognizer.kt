package com.codex.meetingassistant.runtime.asr

import com.codex.meetingassistant.data.model.TranscriptSegment
import com.codex.meetingassistant.runtime.audio.AudioFrame
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
    override val transcriptEvents: SharedFlow<TranscriptSegment> get() = _events.asSharedFlow()
    override val engineLabel: String = "demo-scripted"

    private val _events = MutableSharedFlow<TranscriptSegment>(extraBufferCapacity = 16)
    private val emittedSegments = mutableListOf<TranscriptSegment>()
    private var scriptJob: Job? = null

    override suspend fun start(config: RecognitionConfig) {
        emittedSegments.clear()
        scriptJob = scope.launch {
            scriptedMeeting().forEachIndexed { index, item ->
                delay(if (index == 0) 1400 else 2500)
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
                _events.emit(segment)
            }
        }
    }

    override suspend fun accept(frame: AudioFrame) {
        // Demo engine ignores PCM and replays a scripted meeting.
    }

    override suspend fun finalizeSegments(): List<TranscriptSegment> =
        emittedSegments.map { it.copy(isFinal = true, createdAtEpochMs = System.currentTimeMillis()) }

    override suspend fun stop() {
        scriptJob?.cancelAndJoin()
        scriptJob = null
    }

    private fun scriptedMeeting(): List<DemoUtterance> = listOf(
        DemoUtterance("Speaker A", "今天先确认安卓端本地会议转写 MVP 的目标。", 0, 4200),
        DemoUtterance("Speaker B", "We should keep everything offline and optimize for flagship devices first.", 4500, 9000),
        DemoUtterance("Speaker C", "声纹先做注册绑定和匿名分离混合模式，不追求第一次就全自动实名。", 9300, 14000),
        DemoUtterance("Speaker A", "本周先打通前台服务、录音链路、实时字幕和会后总结。", 14200, 19000),
        DemoUtterance("Speaker B", "I will prepare the model pack manager and benchmark selector by tomorrow.", 19300, 24000),
        DemoUtterance("Speaker C", "风险是桌面单机拾音下多人重叠说话会影响 diarization，需要允许手动修正。", 24200, 30500),
    )
}

class SherpaSpeechRecognizer : SpeechRecognizer {
    override val transcriptEvents: SharedFlow<TranscriptSegment> = MutableSharedFlow()
    override val engineLabel: String = "sherpa-onnx-placeholder"

    override suspend fun start(config: RecognitionConfig) {
        // TODO: Wire sherpa-onnx streaming ASR + diarization here.
    }

    override suspend fun accept(frame: AudioFrame) {
        // TODO: Feed PCM frames into sherpa recognizer.
    }

    override suspend fun finalizeSegments(): List<TranscriptSegment> = emptyList()

    override suspend fun stop() = Unit
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
