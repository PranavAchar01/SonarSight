package com.sixthsense.audio

import com.sixthsense.core.BeltMapper
import com.sixthsense.core.DetectedObj
import com.sixthsense.core.SceneBus
import com.sixthsense.core.SceneState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Turns collision candidates from the SceneState into directional audio pings.
 *
 * Selection: the most-threatening object — highest nearness above
 * [BeltMapper.OBJECT_NEAR_THRESHOLD] with usable confidence. Only one object
 * sounds at a time so the cue stays legible; direction comes from its zone,
 * urgency (rate + pitch + loudness) from its nearness. A sonar-like scheme:
 * distant obstacle ~1.1s cadence, imminent collision ~150ms.
 */
class CollisionAudioController(
    private val bus: SceneBus,
    scope: CoroutineScope,
) {
    private val pinger = SpatialPinger()

    @Volatile
    private var enabled = false

    @Volatile
    private var scene: SceneState? = null

    init {
        scope.launch {
            bus.state.collect { scene = it }
        }
        scope.launch {
            while (true) {
                val threat = if (enabled) scene?.let { pickThreat(it) } else null
                if (threat == null) {
                    delay(IDLE_POLL_MS)
                    continue
                }
                val urgency =
                    ((threat.nearness - BeltMapper.OBJECT_NEAR_THRESHOLD) /
                        (1f - BeltMapper.OBJECT_NEAR_THRESHOLD)).coerceIn(0f, 1f)
                pinger.ping(azimuthOf(threat), urgency)
                delay((MAX_INTERVAL_MS - (MAX_INTERVAL_MS - MIN_INTERVAL_MS) * urgency).toLong())
            }
        }
    }

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun isEnabled(): Boolean = enabled

    private fun pickThreat(s: SceneState): DetectedObj? =
        s.objects
            .filter { it.nearness >= BeltMapper.OBJECT_NEAR_THRESHOLD && it.conf >= MIN_CONF }
            .maxByOrNull { it.nearness }

    private fun azimuthOf(o: DetectedObj): Float = when (o.zone) {
        "left" -> -0.85f
        "right" -> 0.85f
        else -> 0f
    }

    companion object {
        private const val MIN_CONF = 0.35f
        private const val IDLE_POLL_MS = 150L
        private const val MIN_INTERVAL_MS = 150f   // imminent: rapid sonar
        private const val MAX_INTERVAL_MS = 1100f  // just entered path: slow tick
    }
}
