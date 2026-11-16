package com.k2fsa.sherpa.onnx

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.text.DateFormat
import java.util.Date
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private val sampleRate = 16_000
    private val modelFile by lazy { File(filesDir, "models/meeting.litertlm") }
    private val meetings = mutableListOf<String>()
    private var recording = false
    private var audioRecord: AudioRecord? = null
    private var qnnRecognizer: OfflineRecognizer? = null
    private var transcript = StringBuilder()
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var nav: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        meetings += getPreferences(MODE_PRIVATE).getStringSet("meetings", emptySet()).orEmpty()
        buildShell()
        showTab(0)
    }

    private fun buildShell() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        root.addView(TextView(this).apply {
            text = "离线会议助手"
            textSize = 22f
            setTextColor(Color.rgb(25, 45, 70))
            setPadding(24, 28, 24, 18)
        })
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
        }
        status = TextView(this).apply {
            setTextColor(Color.DKGRAY)
            setPadding(24, 6, 24, 8)
        }
        nav = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(8, 8, 8, 16)
        }
        listOf("首页", "会议", "模型").forEachIndexed { index, label ->
            nav.addView(Button(this).apply {
                text = label
                setOnClickListener { showTab(index) }
            }, LinearLayout.LayoutParams(0, 56, 1f))
        }
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(status)
        root.addView(nav)
        setContentView(root)
    }

    private fun showTab(index: Int) {
        content.removeAllViews()
        when (index) {
            0 -> showHome()
            1 -> showMeetings()
            else -> showModels()
        }
        status.text = "ASR：sherpa-onnx QNN（Snapdragon SM8750 / HTP v79）"
    }

    private fun showHome() {
        content.addView(TextView(this).apply {
            text = "中文离线识别\\n模型和音频都留在设备本地。"
            textSize = 18f
            setPadding(0, 16, 0, 24)
        })
        content.addView(Button(this).apply {
            text = "创建新会议"
            textSize = 18f
            setOnClickListener { startMeeting() }
        }, LinearLayout.LayoutParams(-1, 64))
        if (meetings.isNotEmpty()) content.addView(TextView(this).apply {
            text = "\\n最近会议：${meetings.last()}"
            setTextColor(Color.DKGRAY)
        })
    }

    private fun startMeeting() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 10)
            return
        }
        transcript = StringBuilder()
        try {
            qnnRecognizer?.release()
            OfflineRecognizer.prependAdspLibraryPath(applicationInfo.nativeLibraryDir)
            qnnRecognizer = OfflineRecognizer(
                assetManager = assets,
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = sampleRate, featureDim = 80, dither = 0.0f),
                    modelConfig = OfflineModelConfig(
                        provider = "qnn",
                        zipformerCtc = OfflineZipformerCtcModelConfig(
                            model = "qnn/zh-zipformer/libmodel.so",
                            qnnConfig = QnnConfig(
                                backendLib = "libQnnHtp.so",
                                systemLib = "libQnnSystem.so",
                            ),
                        ),
                        tokens = "qnn/zh-zipformer/tokens.txt",
                        modelType = "zipformer_ctc",
                    ),
                ),
            )
        } catch (error: Exception) {
            status.text = "QNN 初始化失败：${error.message}"
            return
        }
        content.removeAllViews()
        val output = TextView(this).apply { textSize = 17f }
        content.addView(TextView(this).apply {
            text = "正在录音（每 10 秒识别一次）"
            textSize = 18f
        })
        content.addView(ScrollView(this).apply { addView(output) }, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(Button(this).apply {
            text = "结束会议"
            setOnClickListener { stopRecording(); saveMeeting(); showTab(1) }
        })
        recording = true
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 2)
        audioRecord?.startRecording()
        thread(name = "qnn-asr") {
            val window = FloatArray(sampleRate * 10)
            var size = 0
            val pcm = ShortArray(sampleRate / 5)
            while (recording) {
                val count = audioRecord?.read(pcm, 0, pcm.size) ?: 0
                if (count <= 0) continue
                for (i in 0 until count) if (size < window.size) window[size++] = pcm[i] / 32768f
                if (size == window.size) {
                    val text = decode(window.copyOf())
                    if (text.isNotBlank()) {
                        transcript.appendLine(text)
                        runOnUiThread { output.text = transcript.toString() }
                    }
                    size = 0
                }
            }
        }
    }

    private fun decode(samples: FloatArray): String = runCatching {
        val recognizer = qnnRecognizer ?: return ""
        val stream = recognizer.createStream()
        stream.acceptWaveform(samples, sampleRate)
        recognizer.decode(stream)
        val text = recognizer.getResult(stream).text.trim()
        stream.release()
        text
    }.getOrElse {
        runOnUiThread { status.text = "识别错误：${it.message}" }
        ""
    }

    private fun stopRecording() {
        recording = false
        audioRecord?.runCatching { stop() }
        audioRecord?.release()
        audioRecord = null
        qnnRecognizer?.release()
        qnnRecognizer = null
    }

    private fun saveMeeting() {
        val name = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date())
        meetings += "$name（${transcript.lines().count { it.isNotBlank() }} 段）"
        getPreferences(MODE_PRIVATE).edit().putStringSet("meetings", meetings.toSet()).apply()
    }

    private fun showMeetings() {
        content.addView(TextView(this).apply { text = "会议列表"; textSize = 20f })
        if (meetings.isEmpty()) content.addView(TextView(this).apply { text = "\\n还没有会议，请从首页创建。" })
        else meetings.asReversed().forEach { meeting ->
            content.addView(TextView(this).apply {
                text = "• $meeting"
                textSize = 17f
                setPadding(0, 18, 0, 18)
            })
        }
    }

    private fun showModels() {
        content.addView(TextView(this).apply { text = "LLM 模型管理"; textSize = 20f })
        content.addView(TextView(this).apply {
            text = if (modelFile.exists()) "\\n已安装：${modelFile.length() / 1024 / 1024} MB" else "\\n尚未安装 LiteRT-LM 模型"
            setPadding(0, 16, 0, 16)
        })
        content.addView(Button(this).apply {
            text = "选择 .litertlm 模型"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "application/octet-stream"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }, 20)
            }
        })
        content.addView(Button(this).apply {
            text = "测试 LiteRT"
            isEnabled = modelFile.exists()
            setOnClickListener { testLiteRt() }
        })
    }

    private fun testLiteRt() {
        thread(name = "litert-test") {
            val result = runCatching {
                Engine(EngineConfig(modelPath = modelFile.absolutePath)).use { engine ->
                    engine.initialize()
                    runBlocking {
                        engine.createConversation().use { conversation ->
                            conversation.sendMessageAsync("请只回复：LiteRT 本地模型正常。").toList().joinToString("")
                        }
                    }
                }
            }.getOrElse { "LiteRT 初始化失败：${it.message}" }
            runOnUiThread { status.text = result }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 20 && resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return
            contentResolver.openInputStream(uri)?.use { input ->
                modelFile.parentFile?.mkdirs()
                FileOutputStream(modelFile).use { output -> input.copyTo(output) }
            }
            showTab(2)
        }
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }
}
