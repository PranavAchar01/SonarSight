package com.sixthsense.core

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Emits a scripted [SceneState] sequence onto the [SceneBus] at ~6 Hz when
 * enabled. This is an engineering safety net, not a fake demo: the belt, voice
 * agent, and dashboard run against the exact same contract as live mode.
 *
 * Scene order: clear → obstacle left → obstacle center → obstacle right →
 * curb ahead → exit-sign OCR → low-confidence caution → (loop).
 */
class MockSceneProducer(
    private val bus: SceneBus,
    private val scope: CoroutineScope,
) {
    @Volatile
    private var enabled = false
    private var job: Job? = null

    fun isEnabled(): Boolean = enabled

    fun setEnabled(value: Boolean) {
        if (value == enabled) return
        enabled = value
        Log.i(TAG, "Mock mode -> $enabled")
        if (enabled) start() else stop()
    }

    private fun start() {
        job?.cancel()
        job = scope.launch {
            var tick = 0
            while (isActive && enabled) {
                val template = SCRIPT[(tick / DWELL_FRAMES) % SCRIPT.size]
                val scene = template.copy(
                    ts = System.currentTimeMillis(),
                    belt = BeltMapper.packetAsInts(template),
                )
                bus.emit(scene)
                tick++
                delay(PERIOD_MS)
            }
        }
    }

    private fun stop() {
        job?.cancel()
        job = null
        bus.emit(SceneBus.SAFE_DEFAULT.copy(ts = System.currentTimeMillis()))
    }

    companion object {
        private const val TAG = "SixthSenseScene"
        private const val PERIOD_MS = 160L          // ~6 Hz
        private const val DWELL_FRAMES = 12         // ~2 s per scripted scene

        private val SCRIPT: List<SceneState> = listOf(
            // clear path
            SceneState(
                ts = 0, depth = DepthZones(0.1f, 0.1f, 0.1f),
                objects = emptyList(), pathClear = true, conf = 0.95f,
            ),
            // obstacle left
            SceneState(
                ts = 0, depth = DepthZones(0.85f, 0.2f, 0.1f),
                objects = listOf(DetectedObj("chair", "left", 0.85f, 0.82f)),
                pathClear = false, conf = 0.9f,
            ),
            // obstacle center
            SceneState(
                ts = 0, depth = DepthZones(0.2f, 0.9f, 0.2f),
                objects = listOf(DetectedObj("person", "center", 0.9f, 0.93f)),
                pathClear = false, conf = 0.92f,
            ),
            // obstacle right
            SceneState(
                ts = 0, depth = DepthZones(0.1f, 0.2f, 0.88f),
                objects = listOf(DetectedObj("pole", "right", 0.88f, 0.8f)),
                pathClear = false, conf = 0.9f,
            ),
            // curb ahead
            SceneState(
                ts = 0, depth = DepthZones(0.3f, 0.6f, 0.3f, curbAhead = true),
                objects = emptyList(), pathClear = false, conf = 0.85f,
            ),
            // exit sign OCR
            SceneState(
                ts = 0, depth = DepthZones(0.15f, 0.15f, 0.2f),
                objects = listOf(DetectedObj("sign", "center", 0.4f, 0.7f)),
                pathClear = true, ocr = Ocr(present = true, text = "EXIT"), conf = 0.88f,
            ),
            // low-confidence caution
            SceneState(
                ts = 0, depth = DepthZones(0.5f, 0.5f, 0.5f),
                objects = emptyList(), pathClear = false, conf = 0.25f,
            ),
        )
    }
}
