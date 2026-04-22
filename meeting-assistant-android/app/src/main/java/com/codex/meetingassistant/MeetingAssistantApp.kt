package com.codex.meetingassistant

import android.app.Application
import com.codex.meetingassistant.data.crypto.CryptoBox
import com.codex.meetingassistant.data.repo.DefaultMeetingRepository
import com.codex.meetingassistant.data.repo.DefaultModelRepository
import com.codex.meetingassistant.data.repo.MeetingRepository
import com.codex.meetingassistant.data.repo.ModelRepository
import com.codex.meetingassistant.data.repo.SettingsRepository
import com.codex.meetingassistant.runtime.audio.AudioRecordCaptureEngine
import com.codex.meetingassistant.runtime.asr.DemoSpeechRecognizer
import com.codex.meetingassistant.runtime.asr.SherpaSpeechRecognizer
import com.codex.meetingassistant.runtime.benchmark.BenchmarkSelector
import com.codex.meetingassistant.runtime.llm.LiteRtModelRuntime
import com.codex.meetingassistant.runtime.llm.ModelDownloadManager
import com.codex.meetingassistant.runtime.llm.ModelPackManager
import com.codex.meetingassistant.runtime.llm.ModelRuntime
import com.codex.meetingassistant.runtime.speaker.DemoSpeakerIdentificationEngine
import com.codex.meetingassistant.runtime.speaker.SherpaSpeakerIdentificationEngine
import com.codex.meetingassistant.runtime.speaker.SpeakerEmbeddingStore
import com.codex.meetingassistant.runtime.speaker.SpeakerEnrollmentRecorder
import com.codex.meetingassistant.service.MeetingSessionCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MeetingAssistantApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(application: Application) {
    val applicationContext = application.applicationContext
    val sessionCoordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val cryptoBox = CryptoBox()
    private val database = DefaultMeetingRepository.buildDatabase(application)
    val settingsRepository = SettingsRepository(application)
    val meetingRepository: MeetingRepository =
        DefaultMeetingRepository(application, database, cryptoBox)
    val modelRepository: ModelRepository =
        DefaultModelRepository(application, database)
    val benchmarkSelector = BenchmarkSelector(application)
    val modelPackManager = ModelPackManager(
        meetingRepository = meetingRepository,
        settingsRepository = settingsRepository,
        benchmarkSelector = benchmarkSelector,
    )
    val modelDownloadManager = ModelDownloadManager(
        context = application,
        repository = modelRepository,
    )
    val modelRuntime: ModelRuntime = LiteRtModelRuntime()
    val speakerEmbeddingStore = SpeakerEmbeddingStore()
    val speakerEnrollmentRecorder = SpeakerEnrollmentRecorder(application)
    val sessionCoordinator = MeetingSessionCoordinator(
        context = application,
        repository = meetingRepository,
        settingsRepository = settingsRepository,
        modelPackManager = modelPackManager,
        modelRuntime = modelRuntime,
        audioCaptureEngine = AudioRecordCaptureEngine(
            context = application,
            scope = sessionCoordinatorScope,
        ),
        speechRecognizer = SherpaSpeechRecognizer(
            context = application,
            scope = sessionCoordinatorScope,
            speakerEmbeddingStore = speakerEmbeddingStore,
            fallback = DemoSpeechRecognizer(scope = sessionCoordinatorScope),
        ),
        speakerIdentificationEngine = SherpaSpeakerIdentificationEngine(
            speakerEmbeddingStore = speakerEmbeddingStore,
            fallback = DemoSpeakerIdentificationEngine(),
        ),
        cryptoBox = cryptoBox,
        scope = sessionCoordinatorScope,
    )
}
