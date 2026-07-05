package com.sixthsense.glasses

import android.app.Activity
import android.util.Log
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addStream
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.core.types.RegistrationState
import com.sixthsense.vision.VisionPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Ray-Ban Meta glasses frame source (Meta Wearables DAT). Replaces the chest
 * camera as the perception input: opens a device session, adds a video stream,
 * decodes I420 frames to bitmaps, and feeds [VisionPipeline.submitExternalFrame]
 * — everything downstream (SceneState -> BeltMapper -> belt, dashboard, voice)
 * is unchanged.
 *
 * Session/stream lifecycle mirrors Meta's CameraAccess sample: a wearer tap
 * pauses the session (PAUSED) and the SDK resumes the same stream on the next
 * tap, so PAUSED->STARTED must NOT recreate the stream.
 */
class GlassesFrameSource(private val pipeline: VisionPipeline) {

    private val _status = MutableStateFlow("glasses: idle")
    val status: StateFlow<String> = _status.asStateFlow()

    /** Operator preview sink, fed at ~[PREVIEW_FPS] independent of inference rate. */
    var onFrame: ((android.graphics.Bitmap) -> Unit)? = null
    private var lastPreviewMs = 0L

    /**
     * Wearer pressed the glasses' capture button (the SDK reports it as a
     * session PAUSE). Wired to the hands-free "talk to Qwen" flow; the stream
     * itself resumes on the wearer's next tap, and [lastFrame] stays cached so
     * the VLM still has a view to answer about.
     */
    var onWearerTap: (() -> Unit)? = null

    /** Most recent decoded POV frame — what the voice agent's VLM looks at. */
    @Volatile
    var lastFrame: android.graphics.Bitmap? = null
        private set

    private var session: DeviceSession? = null
    private var stream: Stream? = null
    private val jobs = mutableListOf<Job>()
    private var previousSessionState: DeviceSessionState? = null

    @Volatile
    var isStreaming = false
        private set

    /** One-time SDK init + (if needed) registration handoff to the Meta AI app. */
    fun initialize(activity: Activity) {
        Wearables.initialize(activity)
        if (Wearables.registrationState.value != RegistrationState.REGISTERED) {
            Wearables.startRegistration(activity)
        }
    }

    val isRegistered: Boolean
        get() = runCatching {
            Wearables.registrationState.value == RegistrationState.REGISTERED
        }.getOrDefault(false)

    /**
     * Open the session and start streaming into the pipeline. Wearables CAMERA
     * permission must already be granted (MainActivity owns the request contract).
     */
    @Synchronized
    fun start(scope: CoroutineScope) {
        if (isStreaming) return
        isStreaming = true
        previousSessionState = null
        _status.value = "glasses: creating session…"
        pipeline.startExternal()

        Wearables.createSession(AutoDeviceSelector())
            .onSuccess { created ->
                session = created
                jobs += scope.launch {
                    created.errors.collect { onSessionError(it) }
                }
                jobs += scope.launch {
                    created.state.collect { state -> onSessionState(state, scope) }
                }
                created.start()
            }
            .onFailure { error, _ ->
                Log.e(TAG, "createSession failed: ${error.description}")
                _status.value = "glasses: session failed — ${error.description}"
                isStreaming = false
            }
    }

    private fun onSessionState(state: DeviceSessionState, scope: CoroutineScope) {
        val prev = previousSessionState
        previousSessionState = state
        Log.i(TAG, "session state: $prev -> $state")
        when (state) {
            DeviceSessionState.STARTED -> {
                if (prev == DeviceSessionState.PAUSED && stream != null) {
                    // Wearer tap resume: the SDK revives the existing stream.
                    _status.value = "glasses: streaming (resumed)"
                    return
                }
                addStream(scope)
            }
            DeviceSessionState.PAUSED -> {
                // Wearer tap pause: keep the stream for the SDK to resume.
                _status.value = "glasses: paused (tap glasses to resume)"
                if (prev == DeviceSessionState.STARTED) onWearerTap?.invoke()
            }
            else -> _status.value = "glasses: session $state"
        }
    }

    private fun addStream(scope: CoroutineScope) {
        val current = session ?: return
        stream?.stop()
        stream = null
        current.addStream(StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 24))
            .onSuccess { added ->
                stream = added
                jobs += scope.launch {
                    added.videoStream.collect { frame ->
                        // Preview and inference are decoupled: the preview refreshes at
                        // a steady ~PREVIEW_FPS while inference grabs the latest frame
                        // whenever it is free. Frames needed by neither are dropped
                        // before the I420 decode, keeping CPU for the models.
                        val now = System.currentTimeMillis()
                        val wantPreview =
                            onFrame != null && now - lastPreviewMs >= 1000L / PREVIEW_FPS
                        val wantInfer = pipeline.canAcceptExternalFrame()
                        if (!wantPreview && !wantInfer) return@collect
                        val bmp = I420ToBitmap.convert(frame.buffer, frame.width, frame.height)
                            ?: return@collect
                        lastFrame = bmp
                        if (wantPreview) {
                            lastPreviewMs = now
                            onFrame?.invoke(bmp)
                        }
                        if (wantInfer) pipeline.submitExternalFrame(bmp)
                    }
                }
                jobs += scope.launch {
                    added.state.collect { s ->
                        Log.i(TAG, "stream state: $s")
                        if (s == StreamState.STREAMING) _status.value = "glasses: streaming"
                        if (s == StreamState.CLOSED) {
                            _status.value = "glasses: stream closed"
                            stop()
                        }
                    }
                }
                jobs += scope.launch {
                    added.errorStream.collect { err ->
                        Log.w(TAG, "stream error: ${err.description}")
                        if (err != StreamError.STREAM_ERROR) {
                            _status.value = "glasses: error — ${err.description}"
                            stop()
                        }
                    }
                }
                added.start()
            }
            .onFailure { error, _ ->
                Log.e(TAG, "addStream failed: ${error.description}")
                _status.value = "glasses: stream failed — ${error.description}"
            }
    }

    private fun onSessionError(error: DeviceSessionError) {
        Log.e(TAG, "session error: ${error.description}")
        _status.value = when (error) {
            DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED ->
                "glasses: DAT app on glasses needs update (Meta AI app > App info)"
            else -> "glasses: error — ${error.description}"
        }
        stop()
    }

    @Synchronized
    fun stop() {
        if (!isStreaming && session == null) return
        isStreaming = false
        jobs.forEach { it.cancel() }
        jobs.clear()
        stream?.stop()
        stream = null
        session?.stop()
        session = null
        previousSessionState = null
        if (!_status.value.startsWith("glasses: error") &&
            !_status.value.startsWith("glasses: DAT")
        ) {
            _status.value = "glasses: stopped"
        }
        Log.i(TAG, "GlassesFrameSource stopped")
    }

    companion object {
        private const val TAG = "SixthSenseMCP"
        private const val PREVIEW_FPS = 12
    }
}
