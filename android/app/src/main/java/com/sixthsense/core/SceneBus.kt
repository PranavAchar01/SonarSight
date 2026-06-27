package com.sixthsense.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The shared bus carrying the latest [SceneState]. Producers (vision pipeline,
 * mock producer) push; consumers (belt mapper, voice agent, dashboard socket,
 * operator UI) observe. Defaults to a safe "clear path, high confidence" scene
 * so consumers never see an obstacle before any frame has been produced.
 */
class SceneBus {

    private val _state = MutableStateFlow(SAFE_DEFAULT)
    val state: StateFlow<SceneState> = _state

    fun emit(scene: SceneState) {
        _state.value = scene
    }

    companion object {
        val SAFE_DEFAULT = SceneState(
            ts = 0L,
            depth = DepthZones(left = 0f, center = 0f, right = 0f),
            objects = emptyList(),
            pathClear = true,
            ocr = Ocr(),
            conf = 1f,
            belt = listOf(0, 0, 0, 0),
        )
    }
}
