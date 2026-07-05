package com.sixthsense.debug

import android.content.Context
import com.sixthsense.audio.CollisionAudioController
import com.sixthsense.cloud.CloudAskClient
import com.sixthsense.cloud.CloudVisionClient
import com.sixthsense.cloud.MyPeopleClient
import com.sixthsense.cloud.VoiceCommandRouter
import com.sixthsense.core.MockSceneProducer
import com.sixthsense.core.SceneBus
import com.sixthsense.core.SceneJournal
import com.sixthsense.glasses.GlassesFrameSource
import com.sixthsense.vision.VisionPipeline
import com.sixthsense.voice.LlmEngine
import com.sixthsense.voice.VoiceAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Tiny manual service locator that holds the long-lived app components. Avoids a
 * DI framework on purpose (fewer moving parts for the hackathon). Initialized
 * once from MainActivity; also re-entrant-safe (guarded by @Synchronized on [init])
 * so the debug BroadcastReceiver can call [init] before touching components.
 */
object AppGraph {

    lateinit var sceneBus: SceneBus
        private set
    lateinit var mockSceneProducer: MockSceneProducer
        private set
    lateinit var voiceAgent: VoiceAgent
        private set
    lateinit var llmEngine: LlmEngine
        private set
    lateinit var visionPipeline: VisionPipeline
        private set
    lateinit var glassesSource: GlassesFrameSource
        private set
    lateinit var collisionAudio: CollisionAudioController
        private set
    lateinit var cloudVision: CloudVisionClient
        private set
    lateinit var cloudAsk: CloudAskClient
        private set
    lateinit var voiceRouter: VoiceCommandRouter
        private set
    lateinit var myPeople: MyPeopleClient
        private set
    lateinit var sceneJournal: SceneJournal
        private set

    /** Background scope for producers/streams; survives Activity recreation. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        sceneBus = SceneBus()
        mockSceneProducer = MockSceneProducer(sceneBus, scope)
        llmEngine = LlmEngine(app)
        voiceAgent = VoiceAgent(sceneBus, llmEngine)
        visionPipeline = VisionPipeline(app, sceneBus)
        glassesSource = GlassesFrameSource(visionPipeline)
        collisionAudio = CollisionAudioController(sceneBus, scope)
        cloudVision = CloudVisionClient(visionPipeline)
        cloudAsk = CloudAskClient()
        voiceRouter = VoiceCommandRouter()
        myPeople = MyPeopleClient(app)
        sceneJournal = SceneJournal(sceneBus, scope)
        // Cloud grounding saw a person -> try to match against the enrolled circle.
        cloudVision.onPersonSeen = { myPeople.maybeIdentify(glassesSource.lastFrame) }
        initialized = true
        // Load the on-device Qwen LLM off the main thread (fast no-op if qwen.pte
        // isn't bundled; the voice agent uses rule-based answers until it's ready).
        scope.launch { llmEngine.load() }
    }
}
