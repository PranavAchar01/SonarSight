package com.sixthsense.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Rolling on-device memory of what the vision tiers have seen: a ring of
 * (timestamp, label, zone, nearness) observations sampled from the SceneBus.
 * Powers "where did I leave my keys?" — recall is a newest-first scan, fully
 * offline, and nothing here ever leaves the phone.
 */
class SceneJournal(bus: SceneBus, scope: CoroutineScope) {

    data class Sighting(val ts: Long, val label: String, val zone: String, val nearness: Float)

    private val ring = ArrayDeque<Sighting>()
    private var lastSampleMs = 0L

    init {
        scope.launch {
            bus.state.collect { scene ->
                val now = System.currentTimeMillis()
                if (scene.ts == 0L || now - lastSampleMs < SAMPLE_GAP_MS) return@collect
                lastSampleMs = now
                synchronized(ring) {
                    for (o in scene.objects) {
                        if (o.conf < MIN_CONF) continue
                        ring.addLast(Sighting(now, o.label.lowercase(), o.zone, o.nearness))
                    }
                    while (ring.size > MAX_ENTRIES) ring.removeFirst()
                }
            }
        }
    }

    /** Newest sighting whose label matches [query] (loose contains, both ways). */
    fun lastSighting(query: String): Sighting? {
        val q = query.trim().lowercase().removeSuffix("s")
        if (q.isBlank()) return null
        synchronized(ring) {
            return ring.lastOrNull { it.label.contains(q) || q.contains(it.label) }
        }
    }

    /** Spoken answer for "where did I last see X". */
    fun answerFor(query: String): String {
        val s = lastSighting(query)
            ?: return "I haven't seen a $query recently."
        val mins = (System.currentTimeMillis() - s.ts) / 60_000L
        val ago = when {
            mins < 1 -> "less than a minute ago"
            mins == 1L -> "about a minute ago"
            else -> "about $mins minutes ago"
        }
        val where = when (s.zone) {
            "left" -> "on your left"
            "right" -> "on your right"
            else -> "ahead of you"
        }
        return "I last saw a ${s.label} $ago, $where."
    }

    companion object {
        private const val SAMPLE_GAP_MS = 8_000L
        private const val MAX_ENTRIES = 900
        private const val MIN_CONF = 0.4f
    }
}
