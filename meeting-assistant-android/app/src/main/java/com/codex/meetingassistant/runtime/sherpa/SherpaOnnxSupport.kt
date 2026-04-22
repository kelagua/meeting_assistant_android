package com.codex.meetingassistant.runtime.sherpa

import android.content.Context
import android.content.res.AssetManager
import android.util.Base64
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

private const val TAG = "SherpaOnnxSupport"

object SherpaOnnxSupport {
    private val sherpaNativeState = lazy {
        runCatching {
            System.loadLibrary("sherpa-onnx-jni")
            NativeState(isLoaded = true, error = null)
        }.getOrElse { error ->
            Log.w(TAG, "sherpa-onnx native library is unavailable", error)
            NativeState(isLoaded = false, error = error)
        }
    }

    val asrAssets = AsrAssetCandidates()
    val speakerAssets = SpeakerAssetCandidates()

    val isNativeLibraryAvailable: Boolean
        get() = sherpaNativeState.value.isLoaded

    val nativeLoadError: Throwable?
        get() = sherpaNativeState.value.error

    fun createOnlineRecognizer(context: Context): OnlineRecognizer? {
        if (!isNativeLibraryAvailable) return null
        val resolved = resolveAsrAssets(context.assets) ?: return null
        return runCatching {
            OnlineRecognizer(
                assetManager = context.assets,
                config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80, dither = 0.0f),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = resolved.encoder,
                            decoder = resolved.decoder,
                            joiner = resolved.joiner,
                        ),
                        tokens = resolved.tokens,
                        numThreads = 2,
                        debug = false,
                        provider = "cpu",
                        modelType = "zipformer",
                    ),
                    decodingMethod = "greedy_search",
                    enableEndpoint = true,
                    maxActivePaths = 4,
                ),
            )
        }.onFailure {
            Log.e(TAG, "Failed to initialize sherpa-onnx online recognizer", it)
        }.getOrNull()
    }

    fun createSpeakerExtractor(context: Context): SpeakerEmbeddingExtractor? {
        if (!isNativeLibraryAvailable) return null
        val model = resolveExistingAsset(context.assets, speakerAssets.modelCandidates) ?: return null
        return runCatching {
            SpeakerEmbeddingExtractor(
                assetManager = context.assets,
                config = SpeakerEmbeddingExtractorConfig(
                    model = model,
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                ),
            )
        }.onFailure {
            Log.e(TAG, "Failed to initialize sherpa-onnx speaker extractor", it)
        }.getOrNull()
    }

    fun missingAsrAssets(assetManager: AssetManager): List<String> =
        asrAssets.requiredGroups.mapNotNull { group ->
            if (resolveExistingAsset(assetManager, group) == null) group.first() else null
        }

    fun missingSpeakerAssets(assetManager: AssetManager): List<String> =
        if (resolveExistingAsset(assetManager, speakerAssets.modelCandidates) == null) {
            listOf(speakerAssets.modelCandidates.first())
        } else {
            emptyList()
        }

    fun pcm16ToFloatArray(bytes: ByteArray): FloatArray {
        if (bytes.isEmpty()) return FloatArray(0)
        val sampleCount = bytes.size / 2
        val samples = FloatArray(sampleCount)
        var sampleIndex = 0
        var byteIndex = 0
        while (byteIndex + 1 < bytes.size) {
            val sample = ((bytes[byteIndex + 1].toInt() shl 8) or (bytes[byteIndex].toInt() and 0xFF)).toShort()
            samples[sampleIndex++] = sample / Short.MAX_VALUE.toFloat()
            byteIndex += 2
        }
        return if (sampleIndex == sampleCount) samples else samples.copyOf(sampleIndex)
    }

    fun embeddingToBase64(embedding: FloatArray): String {
        if (embedding.isEmpty()) return ""
        val buffer = ByteBuffer.allocate(embedding.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        embedding.forEach { value -> buffer.putFloat(value) }
        return Base64.encodeToString(buffer.array(), Base64.NO_WRAP)
    }

    fun embeddingFromBase64(embeddingBase64: String): FloatArray? {
        if (embeddingBase64.isBlank()) return null
        val raw = runCatching { Base64.decode(embeddingBase64, Base64.DEFAULT) }.getOrNull() ?: return null
        if (raw.isEmpty() || raw.size % Float.SIZE_BYTES != 0) return null
        return runCatching {
            val byteBuffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            FloatArray(raw.size / Float.SIZE_BYTES) { byteBuffer.getFloat() }
        }.getOrNull()
    }

    fun cosineSimilarity(left: FloatArray, right: FloatArray): Float {
        if (left.isEmpty() || right.isEmpty() || left.size != right.size) return 0f
        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        for (index in left.indices) {
            dot += left[index] * right[index]
            leftNorm += left[index] * left[index]
            rightNorm += right[index] * right[index]
        }
        val denominator = sqrt(leftNorm) * sqrt(rightNorm)
        if (denominator == 0.0) return 0f
        return (dot / denominator).toFloat()
    }

    private fun resolveAsrAssets(assetManager: AssetManager): ResolvedAsrAssets? {
        val encoder = resolveExistingAsset(assetManager, asrAssets.encoderCandidates) ?: return null
        val decoder = resolveExistingAsset(assetManager, asrAssets.decoderCandidates) ?: return null
        val joiner = resolveExistingAsset(assetManager, asrAssets.joinerCandidates) ?: return null
        val tokens = resolveExistingAsset(assetManager, asrAssets.tokensCandidates) ?: return null
        return ResolvedAsrAssets(
            encoder = encoder,
            decoder = decoder,
            joiner = joiner,
            tokens = tokens,
        )
    }

    private fun resolveExistingAsset(assetManager: AssetManager, candidates: List<String>): String? =
        candidates.firstOrNull { assetExists(assetManager, it) }

    private fun assetExists(assetManager: AssetManager, path: String): Boolean =
        runCatching {
            assetManager.open(path).close()
            true
        }.getOrDefault(false)
}

data class AsrAssetCandidates(
    val modelDir: String = "sherpa/asr/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20",
    val encoderCandidates: List<String> = listOf(
        "$modelDir/encoder-epoch-99-avg-1.onnx",
        "$modelDir/encoder-epoch-99-avg-1.int8.onnx",
    ),
    val decoderCandidates: List<String> = listOf(
        "$modelDir/decoder-epoch-99-avg-1.onnx",
        "$modelDir/decoder-epoch-99-avg-1.int8.onnx",
    ),
    val joinerCandidates: List<String> = listOf(
        "$modelDir/joiner-epoch-99-avg-1.onnx",
        "$modelDir/joiner-epoch-99-avg-1.int8.onnx",
    ),
    val tokensCandidates: List<String> = listOf("$modelDir/tokens.txt"),
) {
    val requiredGroups: List<List<String>> = listOf(
        encoderCandidates,
        decoderCandidates,
        joinerCandidates,
        tokensCandidates,
    )
}

data class SpeakerAssetCandidates(
    val modelCandidates: List<String> = listOf(
        "sherpa/speaker/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx",
        "sherpa/speaker/3dspeaker_speech_eres2net_large_sv_zh-cn_3dspeaker_16k.onnx",
        "3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx",
        "3dspeaker_speech_eres2net_large_sv_zh-cn_3dspeaker_16k.onnx",
    ),
)

private data class NativeState(
    val isLoaded: Boolean,
    val error: Throwable?,
)

private data class ResolvedAsrAssets(
    val encoder: String,
    val decoder: String,
    val joiner: String,
    val tokens: String,
)
