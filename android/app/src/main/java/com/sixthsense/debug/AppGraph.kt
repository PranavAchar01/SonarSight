package com.sixthsense.debug

import android.content.Context
import com.sixthsense.ble.BeltClient
import com.sixthsense.core.MockSceneProducer
import com.sixthsense.core.SceneBus
import com.sixthsense.voice.VoiceAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Tiny manual service locator that holds the long-lived app components. Avoids a
 * DI framework on purpose (fewer moving parts for the hackathon). Initialized
 * once from MainActivity; also re-entrant-safe so the debug BroadcastReceiver can
 * call [init] before touching components.
 */
object AppGraph {

    lateinit var sceneBus: SceneBus
        private set
    lateinit var mockSceneProducer: MockSceneProducer
        private set
    lateinit var beltClient: BeltClient
        private set
    lateinit var voiceAgent: VoiceAgent
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
        beltClient = BeltClient(app)
        mockSceneProducer = MockSceneProducer(sceneBus, scope)
        voiceAgent = VoiceAgent(sceneBus)
        initialized = true
    }
}
