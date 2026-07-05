package com.sixthsense

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.gson.GsonBuilder
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.sixthsense.cloud.VoiceCommandRouter
import com.sixthsense.core.SceneState
import com.sixthsense.debug.AppGraph
import com.sixthsense.glasses.GlassesTapTrigger
import com.sixthsense.ui.ZoneBarsView
import com.sixthsense.vision.DetectionOverlayView
import com.sixthsense.vision.VisionStatus
import com.sixthsense.voice.VoiceRecorder
import com.sixthsense.ws.SceneSocket
import java.util.Locale
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Judge-facing operator console. One screen tells the story: live Ray-Ban POV
 * with AR boxes, an obstacle radar driven by metric depth, edge/cloud status
 * chips, and the three big actions (start vision, 3D audio, hold-to-ask).
 * Developer plumbing (mock mode, phone camera, raw SceneState) is tucked into
 * a collapsible section at the bottom.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var sceneView: TextView
    private lateinit var statusView: TextView
    private lateinit var previewView: PreviewView
    private lateinit var overlay: DetectionOverlayView
    private lateinit var glassesStatusView: TextView
    private lateinit var glassesPreview: ImageView
    private lateinit var liveChip: TextView
    private lateinit var pathBanner: TextView
    private lateinit var zoneBars: ZoneBarsView
    private lateinit var srcChip: TextView
    private lateinit var fpsChip: TextView
    private lateinit var latChip: TextView
    private lateinit var detChip: TextView
    private lateinit var startButton: Button
    private lateinit var audioButton: Button
    private lateinit var cloudButton: Button
    private lateinit var askButton: Button
    private lateinit var voiceQuestion: TextView
    private lateinit var voiceAnswer: TextView
    private var socket: SceneSocket? = null
    private var tts: TextToSpeech? = null
    private var glassesRunning = false
    private var tapTrigger: GlassesTapTrigger? = null
    private var handsFreeListening = false
    private val recorder = VoiceRecorder()
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startLiveVision()
        else toast("Camera permission denied — live vision needs the camera.")
    }

    // Android-side permissions the DAT SDK needs before Wearables.initialize.
    private val requestGlassesSetup = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            AppGraph.glassesSource.initialize(this)
            toast(
                if (AppGraph.glassesSource.isRegistered) "Glasses SDK ready."
                else "Approve SonarSight in the Meta AI app, then start glasses vision."
            )
        } else {
            toast("Bluetooth permission denied — glasses session needs it.")
        }
    }

    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) toast("Mic ready — hold the Ask button and speak.")
        else toast("Microphone permission denied — voice questions need it.")
    }

    // Wearable-side permission (glasses camera), granted by the wearer in Meta AI.
    private val requestWearablesCamera = registerForActivityResult(
        Wearables.RequestPermissionContract()
    ) { result ->
        val status = result.getOrDefault(PermissionStatus.Denied)
        if (status == PermissionStatus.Granted) startGlassesVision()
        else toast("Glasses camera permission denied in the Meta AI app.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(this)
        supportActionBar?.hide()
        window.statusBarColor = BG
        window.navigationBarColor = BG
        setContentView(buildUi())
        observeScene()
        observeVisionStatus()
        observeGlassesStatus()
        startDashboardSocket()
        // The vision pipeline owns the frame source; it streams the live frame to the
        // dashboard (only while a dashboard client is connected) and the voice agent
        // forwards each interaction.
        AppGraph.visionPipeline.onFrame = { b64, rot -> socket?.pushFrame(b64, rot) }
        AppGraph.visionPipeline.shouldStreamFrame = { socket?.hasClients() == true }
        AppGraph.voiceAgent.onAnswer = { q, intent, a -> socket?.updateVoice(q, intent, a) }
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.US
        }
        // "Sarah, on your left." — queued so it never cuts off an in-flight answer.
        AppGraph.myPeople.onAnnounce = { line ->
            runOnUiThread { tts?.speak(line, TextToSpeech.QUEUE_ADD, null, "sonarsight-person") }
        }
        // Hands-free "talk to Qwen": glasses touchpad tap (Bluetooth media button)
        // or the capture button (session pause) both open a listening window.
        tapTrigger = GlassesTapTrigger(this).also {
            it.onTap = { runOnUiThread { startHandsFreeAsk() } }
        }
        AppGraph.glassesSource.onWearerTap = { runOnUiThread { startHandsFreeAsk() } }
    }

    /**
     * Tap on the glasses -> earcon -> fixed listening window -> the same
     * router/Q&A flow as hold-to-ask. A second tap ends the window early.
     */
    @SuppressLint("SetTextI18n")
    private fun startHandsFreeAsk() {
        if (handsFreeListening) {
            handsFreeListening = false
            askButton.text = getString(R.string.btn_ask_hold)
            pill(askButton, true, PURPLE)
            recorder.stop()?.let { submitVoiceQuestion(it) }
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val tone = android.media.ToneGenerator(
            android.media.AudioManager.STREAM_MUSIC, 80)
        tone.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 150)
        askButton.postDelayed({
            tone.release()
            if (!recorder.start()) return@postDelayed
            handsFreeListening = true
            askButton.text = "● Listening (glasses)… tap again to finish"
            pill(askButton, true, Color.parseColor("#FF4D5E"))
            askButton.postDelayed({
                if (!handsFreeListening) return@postDelayed
                handsFreeListening = false
                askButton.text = getString(R.string.btn_ask_hold)
                pill(askButton, true, PURPLE)
                recorder.stop()?.let { submitVoiceQuestion(it) }
            }, HANDS_FREE_LISTEN_MS)
        }, 350)
    }

    // ------------------------------------------------------------ UI build --

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != 0) setStroke(dp(1), stroke)
        }

    private fun chip(initial: String): TextView = TextView(this).apply {
        text = initial
        textSize = 11f
        typeface = Typeface.MONOSPACE
        setTextColor(SUB)
        background = rounded(CARD, 8, CARD_STROKE)
        setPadding(dp(10), dp(6), dp(10), dp(6))
    }

    private fun pill(button: Button, on: Boolean, onColor: Int) {
        button.background = rounded(if (on) onColor else CARD, 14, if (on) onColor else CARD_STROKE)
        button.setTextColor(if (on) Color.BLACK else TEXT)
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(SUB)
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.12f
        setPadding(dp(4), dp(14), 0, dp(6))
    }

    @SuppressLint("SetTextI18n")
    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
            setBackgroundColor(BG)
        }
        fun match(height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)

        // -- Header -----------------------------------------------------------
        root.addView(TextView(this).apply {
            text = "SonarSight"
            textSize = 26f
            setTextColor(TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "See with sound · Ray-Ban Meta glasses × Qwen Cloud"
            textSize = 12f
            setTextColor(SUB)
            setPadding(0, 0, 0, dp(10))
        })

        // -- Path banner (the one-glance verdict) ------------------------------
        pathBanner = TextView(this).apply {
            text = "STANDBY — start glasses vision"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(SUB)
            background = rounded(CARD, 12, CARD_STROKE)
            gravity = android.view.Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = match()
        }
        root.addView(pathBanner)

        // -- Live POV card ------------------------------------------------------
        val camCard = FrameLayout(this).apply {
            layoutParams = match(dp(250)).apply { topMargin = dp(10) }
            background = rounded(CARD, 16, CARD_STROKE)
            clipToOutline = true
        }
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        // Glasses POV preview; FIT_XY matches the model's stretch-resize, so the
        // overlay's boxes line up the same way they do over the camera preview.
        glassesPreview = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
            scaleType = ImageView.ScaleType.FIT_XY
        }
        overlay = DetectionOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        liveChip = TextView(this).apply {
            text = "● OFFLINE"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(SUB)
            background = rounded(0xCC0B0F14.toInt(), 8)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(dp(10), dp(10), 0, 0) }
        }
        camCard.addView(previewView)
        camCard.addView(glassesPreview) // glasses POV sits over the camera preview
        camCard.addView(overlay)        // overlay sits on top of both
        camCard.addView(liveChip)
        root.addView(camCard)

        // -- Status chips -------------------------------------------------------
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = match().apply { topMargin = dp(8) }
        }
        srcChip = chip("src —")
        fpsChip = chip("0.0 fps")
        latChip = chip("yolo —")
        detChip = chip("0 obj")
        listOf(srcChip, fpsChip, latChip, detChip).forEach {
            it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(6) }
            it.gravity = android.view.Gravity.CENTER
            chips.addView(it)
        }
        root.addView(chips)

        // -- Obstacle radar (metric depth) ---------------------------------------
        root.addView(sectionLabel("OBSTACLE RADAR — METRIC DEPTH"))
        zoneBars = ZoneBarsView(this).apply { layoutParams = match(dp(96)) }
        root.addView(zoneBars)

        // -- Primary controls -----------------------------------------------------
        root.addView(sectionLabel("CONTROLS"))
        fun bigButton(label: String, height: Int = 52): Button = Button(this).apply {
            text = label
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            stateListAnimator = null
            layoutParams = match(dp(height)).apply { topMargin = dp(8) }
        }
        startButton = bigButton("▶  Start Glasses Vision")
        pill(startButton, true, TEAL)
        startButton.setOnClickListener {
            if (glassesRunning) {
                AppGraph.glassesSource.stop()
                AppGraph.visionPipeline.stop()
                setGlassesRunning(false)
            } else {
                connectGlassesAndStart()
            }
        }
        root.addView(startButton)

        val toggles = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = match().apply { topMargin = dp(8) }
        }
        audioButton = Button(this).apply {
            text = "🔊 3D Audio: OFF"
            textSize = 13f
            isAllCaps = false
            stateListAnimator = null
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) }
            setOnClickListener { toggleCollisionAudio() }
        }
        pill(audioButton, false, GREEN)
        cloudButton = Button(this).apply {
            text = "☁ Qwen Cloud: OFF"
            textSize = 13f
            isAllCaps = false
            stateListAnimator = null
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
            setOnClickListener { toggleCloudVision() }
        }
        pill(cloudButton, false, TEAL)
        toggles.addView(audioButton)
        toggles.addView(cloudButton)
        root.addView(toggles)

        askButton = bigButton(getString(R.string.btn_ask_hold), height = 64)
        pill(askButton, true, PURPLE)
        wireHoldToAsk()
        root.addView(askButton)

        val readButton = bigButton("📖  Read Text Ahead", height = 48)
        pill(readButton, false, AMBER)
        readButton.setOnClickListener {
            speak("Reading.")
            AppGraph.cloudAsk.readText(
                AppGraph.glassesSource.lastFrame,
                onAnswer = ::speakAnswer, onError = ::speakError,
            )
        }
        root.addView(readButton)

        // -- Voice Q&A card ---------------------------------------------------------
        root.addView(sectionLabel("ASK QWEN ABOUT YOUR SURROUNDINGS"))
        val voiceCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, 12, CARD_STROKE)
            setPadding(dp(12), dp(10), dp(12), dp(12))
            layoutParams = match()
        }
        voiceQuestion = TextView(this).apply {
            text = "Hold the mic button and ask anything."
            textSize = 12f
            setTextColor(SUB)
            typeface = Typeface.MONOSPACE
        }
        voiceAnswer = TextView(this).apply {
            text = ""
            textSize = 13f
            setTextColor(TEXT)
            setPadding(0, dp(6), 0, 0)
        }
        voiceCard.addView(voiceQuestion)
        voiceCard.addView(voiceAnswer)
        root.addView(voiceCard)

        // -- Developer tools (collapsed) -----------------------------------------------
        val devToggle = sectionLabel("DEVELOPER TOOLS ▸")
        val devSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
            layoutParams = match()
        }
        devToggle.setOnClickListener {
            val show = devSection.visibility != android.view.View.VISIBLE
            devSection.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
            devToggle.text = if (show) "DEVELOPER TOOLS ▾" else "DEVELOPER TOOLS ▸"
        }
        root.addView(devToggle)

        fun devButton(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            stateListAnimator = null
            layoutParams = match(dp(44)).apply { topMargin = dp(6) }
            setOnClickListener { onClick() }
        }.also { pill(it, false, TEAL) }

        glassesStatusView = TextView(this).apply {
            text = getString(R.string.glasses_idle)
            textSize = 11f
            setTextColor(SUB)
            typeface = Typeface.MONOSPACE
            setPadding(dp(4), dp(6), 0, 0)
        }
        statusView = TextView(this).apply {
            text = getString(R.string.vision_idle)
            textSize = 11f
            setTextColor(SUB)
            typeface = Typeface.MONOSPACE
            setPadding(dp(4), dp(6), 0, 0)
        }
        devSection.addView(devButton(getString(R.string.btn_glasses_setup)) { setupGlasses() })
        devSection.addView(devButton(getString(R.string.btn_start_vision)) { connectCameraAndStart() })
        devSection.addView(devButton(getString(R.string.btn_stop_vision)) {
            AppGraph.visionPipeline.stop()
        })
        devSection.addView(devButton(getString(R.string.btn_mock_on)) {
            AppGraph.mockSceneProducer.setEnabled(true)
        })
        devSection.addView(devButton(getString(R.string.btn_mock_off)) {
            AppGraph.mockSceneProducer.setEnabled(false)
        })
        devSection.addView(devButton("My People: forget everyone") {
            AppGraph.myPeople.clear()
            toast("Enrolled faces deleted.")
        })
        devSection.addView(devButton(getString(R.string.btn_ask)) {
            // Uses the on-device Qwen LLM when ready (falls back to rule-based);
            // generation runs off-thread, so toast the answer when it returns.
            toast(if (AppGraph.llmEngine.isReady) "Asking Qwen…" else "Answering…")
            AppGraph.voiceAgent.askAsync("what's ahead of me?") { answer ->
                Log.i(TAG, "Voice answer: $answer")
                runOnUiThread { toast(answer) }
            }
        })
        devSection.addView(glassesStatusView)
        devSection.addView(statusView)
        sceneView = TextView(this).apply {
            text = getString(R.string.scene_waiting)
            textSize = 10f
            setTextColor(SUB)
            typeface = Typeface.MONOSPACE
            setPadding(dp(4), dp(8), 0, 0)
        }
        devSection.addView(sceneView)
        root.addView(devSection)

        return ScrollView(this).apply {
            setBackgroundColor(BG)
            isVerticalScrollBarEnabled = false
            addView(root)
        }
    }

    // ---------------------------------------------------------- actions ----

    private fun setGlassesRunning(running: Boolean) {
        glassesRunning = running
        startButton.text = if (running) "■  Stop Glasses Vision" else "▶  Start Glasses Vision"
        pill(startButton, true, if (running) Color.parseColor("#FF8A5C") else TEAL)
        liveChip.text = if (running) "● LIVE — RAY-BAN POV" else "● OFFLINE"
        liveChip.setTextColor(if (running) Color.parseColor("#34D399") else SUB)
    }

    private fun connectCameraAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startLiveVision()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startLiveVision() {
        AppGraph.visionPipeline.start(this, previewView)
    }

    /** Step 1: Android BT permissions -> Wearables.initialize -> Meta AI registration. */
    private fun setupGlasses() {
        requestGlassesSetup.launch(
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        )
    }

    /** Step 2: glasses camera permission (via Meta AI) -> stream into the pipeline. */
    private fun connectGlassesAndStart() {
        if (!AppGraph.glassesSource.isRegistered) {
            // First run: do the SDK registration inline so the primary button is
            // the only thing a demo operator ever needs to touch.
            setupGlasses()
            if (!AppGraph.glassesSource.isRegistered) return
        }
        lifecycleScope.launch {
            val status = Wearables.checkPermissionStatus(Permission.CAMERA).getOrNull()
            if (status == PermissionStatus.Granted) startGlassesVision()
            else requestWearablesCamera.launch(Permission.CAMERA)
        }
    }

    private fun startGlassesVision() {
        AppGraph.glassesSource.onFrame = { bmp ->
            AppGraph.cloudVision.submit(bmp)
            runOnUiThread { glassesPreview.setImageBitmap(bmp) }
        }
        AppGraph.glassesSource.start(AppGraph.scope)
        setGlassesRunning(true)
    }

    /** Press-and-hold voice question: record -> Qwen ASR -> Qwen-VL -> spoken answer. */
    @SuppressLint("ClickableViewAccessibility")
    private fun wireHoldToAsk() {
        askButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED
                    ) {
                        requestMic.launch(Manifest.permission.RECORD_AUDIO)
                    } else if (recorder.start()) {
                        askButton.text = getString(R.string.btn_ask_recording)
                        pill(askButton, true, Color.parseColor("#FF4D5E"))
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    askButton.text = getString(R.string.btn_ask_hold)
                    pill(askButton, true, PURPLE)
                    val wav = recorder.stop()
                    if (wav != null) submitVoiceQuestion(wav)
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun submitVoiceQuestion(wav: ByteArray) {
        voiceQuestion.text = "Transcribing…"
        voiceAnswer.text = ""
        AppGraph.cloudAsk.transcribeAsync(wav, onError = ::speakError) { q ->
            runOnUiThread { voiceQuestion.text = "You said: \"$q\"" }
            socket?.updateVoice(q, "cloud", "…")
            // qwen-plus decides: device command (typed tool call) or scene question.
            AppGraph.voiceRouter.route(q) { action -> runOnUiThread { execute(q, action) } }
        }
    }

    /** Execute a routed voice action; every path ends in a spoken confirmation. */
    @SuppressLint("SetTextI18n")
    private fun execute(q: String, action: VoiceCommandRouter.Action) {
        val frame = AppGraph.glassesSource.lastFrame
        when (action) {
            is VoiceCommandRouter.Action.SetPingSensitivity -> {
                AppGraph.collisionAudio.setSensitivity(action.level)
                speak("Ping sensitivity set to ${action.level}.")
            }
            is VoiceCommandRouter.Action.SetCollisionAudio -> {
                AppGraph.collisionAudio.setEnabled(action.enabled)
                audioButton.text = if (action.enabled) "🔊 3D Audio: ON" else "🔊 3D Audio: OFF"
                pill(audioButton, action.enabled, GREEN)
                speak(if (action.enabled) "Collision audio on." else "Collision audio off.")
            }
            is VoiceCommandRouter.Action.SetCloudVision -> {
                if (AppGraph.cloudVision.configured) {
                    AppGraph.cloudVision.enabled = action.enabled
                    cloudButton.text = if (action.enabled) "☁ Qwen Cloud: ON" else "☁ Qwen Cloud: OFF"
                    pill(cloudButton, action.enabled, TEAL)
                    speak(if (action.enabled) "Qwen cloud vision on." else "Cloud vision off — local models.")
                } else speak("No Qwen API key configured.")
            }
            is VoiceCommandRouter.Action.AddHazardWatch -> {
                AppGraph.cloudVision.addWatch(action.labels)
                speak("Now watching for ${action.labels.joinToString(" and ")}.")
            }
            is VoiceCommandRouter.Action.ReadText -> {
                speak("Reading.")
                AppGraph.cloudAsk.readText(frame, onAnswer = ::speakAnswer, onError = ::speakError)
            }
            is VoiceCommandRouter.Action.EnrollFace -> {
                if (AppGraph.myPeople.enroll(frame, action.name)) {
                    speak("Remembered ${action.name}. I'll announce them when I see them. " +
                        "Make sure they're okay with that.")
                } else speak("I need them in view first — face them and try again.")
            }
            is VoiceCommandRouter.Action.FindObject -> {
                speak(AppGraph.sceneJournal.answerFor(action.name))
            }
            is VoiceCommandRouter.Action.AskScene -> {
                AppGraph.cloudAsk.answerScene(q, frame, onAnswer = { a ->
                    socket?.updateVoice("", "cloud", a)
                    speakAnswer(a)
                }, onError = ::speakError)
            }
        }
    }

    private fun speak(msg: String) {
        runOnUiThread {
            voiceAnswer.text = msg
            tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "sonarsight-answer")
        }
    }

    private fun speakAnswer(answer: String) {
        Log.i(TAG, "Answer: $answer")
        speak(answer)
    }

    @SuppressLint("SetTextI18n")
    private fun speakError(msg: String) {
        runOnUiThread {
            voiceQuestion.text = "Couldn't answer"
            voiceAnswer.text = msg
            tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "sonarsight-error")
        }
    }

    @SuppressLint("SetTextI18n")
    private fun toggleCloudVision() {
        if (!AppGraph.cloudVision.configured) {
            toast("No Qwen API key configured (qwen_api_key in local.properties).")
            return
        }
        val enable = !AppGraph.cloudVision.enabled
        AppGraph.cloudVision.enabled = enable
        cloudButton.text = if (enable) "☁ Qwen Cloud: ON" else "☁ Qwen Cloud: OFF"
        pill(cloudButton, enable, TEAL)
        toast(
            if (enable) "Cloud vision on — qwen-vl-max grounding on Qwen Cloud."
            else "Cloud vision off — local model."
        )
    }

    @SuppressLint("SetTextI18n")
    private fun toggleCollisionAudio() {
        val enable = !AppGraph.collisionAudio.isEnabled()
        AppGraph.collisionAudio.setEnabled(enable)
        audioButton.text = if (enable) "🔊 3D Audio: ON" else "🔊 3D Audio: OFF"
        pill(audioButton, enable, GREEN)
        if (enable) toast("3D collision audio on — pings pan toward the obstacle.")
    }

    // -------------------------------------------------------- observers ----

    private fun observeGlassesStatus() {
        lifecycleScope.launch {
            AppGraph.glassesSource.status.collectLatest { glassesStatusView.text = it }
        }
    }

    private fun observeScene() {
        lifecycleScope.launch {
            AppGraph.sceneBus.state.collectLatest { scene ->
                sceneView.text = render(scene)
                overlay.setDetections(scene.objects)
                zoneBars.setZones(scene.depth)
                renderBanner(scene)
            }
        }
    }

    private fun observeVisionStatus() {
        lifecycleScope.launch {
            AppGraph.visionPipeline.status.collectLatest { s ->
                statusView.text = renderStatus(s)
                renderChips(s)
            }
        }
    }

    // -------------------------------------------------------- rendering ----

    @SuppressLint("SetTextI18n")
    private fun renderChips(s: VisionStatus) {
        srcChip.text = if (s.cloudActive) "☁ QWEN VL-MAX" else "⚡ EDGE ${s.backend}"
        srcChip.setTextColor(if (s.cloudActive) TEAL else TEXT)
        fpsChip.text = "%.1f fps".format(s.fps)
        latChip.text = if (s.cloudActive) "rtt %.0fms".format(s.yoloMs) else "yolo %.0fms".format(s.yoloMs)
        detChip.text = "${s.detections} obj"
    }

    @SuppressLint("SetTextI18n")
    private fun renderBanner(s: SceneState) {
        if (s.ts == 0L) return  // no scene yet — keep the STANDBY banner
        val d = s.depth
        val near = 0.55f
        val (text, color) = when {
            d.center >= 0.75f -> "⛔ OBSTACLE AHEAD — STOP" to RED
            d.center >= near -> "⚠ OBSTACLE AHEAD — STEER" to AMBER
            d.left >= near && d.right >= near -> "⚠ TIGHT — BOTH SIDES" to AMBER
            d.left >= near -> "⚠ OBSTACLE LEFT — KEEP RIGHT" to AMBER
            d.right >= near -> "⚠ OBSTACLE RIGHT — KEEP LEFT" to AMBER
            s.pathClear -> "✓ PATH CLEAR" to GREEN
            else -> "PROCEED CAREFULLY" to AMBER
        }
        pathBanner.text = text
        pathBanner.setTextColor(Color.BLACK)
        pathBanner.background = rounded(color, 12)
    }

    private fun renderStatus(s: VisionStatus): String = buildString {
        val src = if (s.cloudActive) "QWEN CLOUD (vl-max)" else "local (${s.backend})"
        append("vision: ${if (s.running) "ON" else "off"}  src=$src\n")
        append("models: depth=${if (s.depthLoaded) "✓" else "—"}  yolo=${if (s.yoloLoaded) "✓" else "—"}  detections=${s.detections}\n")
        append("fps=%.1f  depth=%.0fms  %s=%.0fms\n".format(
            s.fps, s.depthMs, if (s.cloudActive) "cloud-rtt" else "yolo", s.yoloMs))
        append(s.note)
    }

    private fun render(s: SceneState): String {
        val summary = buildString {
            append("mock=${AppGraph.mockSceneProducer.isEnabled()}\n")
            append("zones L/C/R = %.2f / %.2f / %.2f\n".format(s.depth.left, s.depth.center, s.depth.right))
            append("pathClear=${s.pathClear}  conf=%.2f\n".format(s.conf))
            if (s.ocr.present) append("ocr=\"${s.ocr.text}\"\n")
            append("\n")
        }
        return summary + gson.toJson(s)
    }

    private fun startDashboardSocket() {
        socket = SceneSocket(AppGraph.sceneBus).also { it.launch(AppGraph.scope) }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        tapTrigger?.release()
        tts?.shutdown()
        socket?.shutdown()
        // CameraX unbinds with the lifecycle automatically; fully stop the pipeline
        // (close models, free the executor's work) only when the app is finishing.
        if (isFinishing) AppGraph.visionPipeline.stop()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SixthSenseScene"
        private const val HANDS_FREE_LISTEN_MS = 6000L

        private val BG = Color.parseColor("#0B0F14")
        private val CARD = Color.parseColor("#151C24")
        private val CARD_STROKE = Color.parseColor("#26313D")
        private val TEXT = Color.parseColor("#E6EDF3")
        private val SUB = Color.parseColor("#8B98A5")
        private val TEAL = Color.parseColor("#2DD4BF")
        private val PURPLE = Color.parseColor("#A78BFA")
        private val GREEN = Color.parseColor("#34D399")
        private val AMBER = Color.parseColor("#FFC400")
        private val RED = Color.parseColor("#FF4D5E")
    }
}
