package com.sixthsense

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
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
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.lifecycleScope
import com.google.gson.GsonBuilder
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.sixthsense.cloud.VoiceCommandRouter
import com.sixthsense.core.DetectedObj
import com.sixthsense.core.SceneBus
import com.sixthsense.core.SceneState
import com.sixthsense.debug.AppGraph
import com.sixthsense.glasses.GlassesTapTrigger
import com.sixthsense.ui.DirectionCheckpoint
import com.sixthsense.ui.DirectionCondition
import com.sixthsense.ui.DirectionRailView
import com.sixthsense.ui.RailCondition
import com.sixthsense.ui.SystemCheckpoint
import com.sixthsense.ui.SystemRailView
import com.sixthsense.vision.DetectionOverlayView
import com.sixthsense.vision.VisionStatus
import com.sixthsense.voice.VoiceRecorder
import com.sixthsense.ws.SceneSocket
import java.util.Locale
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Trail Line is the end-user navigation surface. It presents the existing SceneState as
 * stable route checkpoints while keeping camera, model telemetry, and developer tools in
 * the collapsed Scene Details zone. Perception, voice, cloud, glasses, and audio behavior
 * remain owned by the same AppGraph components and callbacks as before.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var bodyTypeface: Typeface
    private lateinit var routeTypeface: Typeface

    private lateinit var sceneView: TextView
    private lateinit var statusView: TextView
    private lateinit var previewView: PreviewView
    private lateinit var overlay: DetectionOverlayView
    private lateinit var glassesStatusView: TextView
    private lateinit var glassesPreview: ImageView
    private lateinit var liveChip: TextView
    private lateinit var srcChip: TextView
    private lateinit var fpsChip: TextView
    private lateinit var latChip: TextView
    private lateinit var detChip: TextView
    private lateinit var startButton: Button
    private lateinit var audioButton: Button
    private lateinit var cloudButton: Button
    private lateinit var askButton: Button
    private lateinit var readButton: Button
    private lateinit var findButton: Button
    private lateinit var voiceQuestion: TextView
    private lateinit var voiceAnswer: TextView
    private lateinit var voicePanel: LinearLayout
    private lateinit var navModeView: TextView
    private lateinit var headerStateView: TextView
    private lateinit var headerStatePanel: LinearLayout
    private lateinit var systemRail: SystemRailView
    private lateinit var trailSection: LinearLayout
    private lateinit var lastConfirmedView: TextView
    private lateinit var nowView: TextView
    private lateinit var nowDetailView: TextView
    private lateinit var nextActionView: TextView
    private lateinit var directionRail: DirectionRailView
    private lateinit var objectsView: TextView

    private var socket: SceneSocket? = null
    private var tts: TextToSpeech? = null
    private var glassesRunning = false
    private var tapTrigger: GlassesTapTrigger? = null
    private var handsFreeListening = false
    private var cloudDegraded = false
    private var cloudEnabledAt = 0L
    private val recorder = VoiceRecorder()
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private var latestScene: SceneState = SceneBus.SAFE_DEFAULT
    private var latestVision = VisionStatus()
    private var latestGlassesStatus = "glasses: idle"
    private var currentNowTitle = ""
    private var currentNowDetail = ""
    private var lastConfirmed = "No prior checkpoint"
    private var interactionState = InteractionState.IDLE

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startLiveVision()
        else toast("Camera permission denied — live vision needs the camera.")
    }

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
        renderInterface()
    }

    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) toast("Microphone ready — hold the Ask button and speak.")
        else toast("Microphone permission denied — voice questions need it.")
    }

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
        bodyTypeface = ResourcesCompat.getFont(this, R.font.atkinson_hyperlegible_next)
            ?: Typeface.DEFAULT
        routeTypeface = ResourcesCompat.getFont(this, R.font.barlow_semi_condensed_semibold)
            ?: Typeface.DEFAULT_BOLD
        supportActionBar?.hide()
        window.statusBarColor = BG
        window.navigationBarColor = BG
        setContentView(buildUi())
        observeScene()
        observeVisionStatus()
        observeGlassesStatus()
        startDashboardSocket()

        AppGraph.visionPipeline.onFrame = { b64, rot -> socket?.pushFrame(b64, rot) }
        AppGraph.visionPipeline.shouldStreamFrame = { socket?.hasClients() == true }
        AppGraph.voiceAgent.onAnswer = { q, intent, a -> socket?.updateVoice(q, intent, a) }
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.US
        }
        AppGraph.myPeople.onAnnounce = { line ->
            runOnUiThread { tts?.speak(line, TextToSpeech.QUEUE_ADD, null, "sonarsight-person") }
        }
        tapTrigger = GlassesTapTrigger(this).also {
            it.onTap = { runOnUiThread { startHandsFreeAsk() } }
        }
        AppGraph.glassesSource.onWearerTap = { runOnUiThread { startHandsFreeAsk() } }
        setInteractionState(
            if (AppGraph.cloudVision.configured) InteractionState.IDLE else InteractionState.DISABLED
        )
        renderInterface()
    }

    // ------------------------------------------------------------ UI build --

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int, stroke: Int = 0, strokeWidth: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != 0) setStroke(dp(strokeWidth), stroke)
        }

    private fun oval(color: Int, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        if (stroke != 0) setStroke(dp(2), stroke)
    }

    private fun buttonSelector(
        normal: Int,
        pressed: Int,
        disabled: Int,
        stroke: Int = 0,
    ): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(-android.R.attr.state_enabled), rounded(disabled, 6, DIVIDER))
        addState(intArrayOf(android.R.attr.state_pressed), rounded(pressed, 6, stroke))
        addState(intArrayOf(), rounded(normal, 6, stroke))
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(SECONDARY)
        typeface = routeTypeface
        letterSpacing = 0.1f
        setPadding(dp(2), dp(18), 0, dp(8))
    }

    private fun chip(initial: String): TextView = TextView(this).apply {
        text = initial
        textSize = 13f
        typeface = routeTypeface
        setTextColor(SECONDARY)
        background = rounded(SURFACE, 4, DIVIDER)
        gravity = Gravity.CENTER
        minHeight = dp(48)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        maxLines = 2
    }

    private fun actionButton(label: String, height: Int, iconRes: Int): Button = Button(this).apply {
        text = label
        textSize = 18f
        typeface = Typeface.create(bodyTypeface, Typeface.BOLD)
        isAllCaps = false
        stateListAnimator = null
        minHeight = dp(height)
        gravity = Gravity.CENTER
        includeFontPadding = false
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setButtonIcon(this, iconRes, BG)
    }

    private fun detailButton(label: String, height: Int = 48): Button = Button(this).apply {
        text = label
        textSize = 15f
        typeface = bodyTypeface
        isAllCaps = false
        stateListAnimator = null
        minHeight = dp(height.coerceAtLeast(48))
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = buttonSelector(RAISED, DIVIDER, SURFACE, DIVIDER)
        setTextColor(PRIMARY)
    }

    private fun setButtonIcon(button: Button, resId: Int, tint: Int) {
        val icon = ContextCompat.getDrawable(this, resId)?.mutate() ?: return
        DrawableCompat.setTint(icon, tint)
        icon.setBounds(0, 0, dp(22), dp(22))
        button.setCompoundDrawablesRelative(icon, null, null, null)
        button.compoundDrawablePadding = dp(10)
    }

    private data class CheckpointViews(
        val root: LinearLayout,
        val value: TextView,
        val detail: TextView,
    )

    private fun checkpointRow(
        label: String,
        initial: String,
        dominant: Boolean,
        markerColor: Int,
    ): CheckpointViews {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = dp(if (dominant) 126 else 74)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val marker = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.MATCH_PARENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        marker.addView(View(this).apply {
            setBackgroundColor(DIVIDER)
            layoutParams = FrameLayout.LayoutParams(dp(2), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL)
        })
        marker.addView(View(this).apply {
            background = if (dominant) rounded(markerColor, 2) else oval(SURFACE, markerColor)
            layoutParams = FrameLayout.LayoutParams(dp(if (dominant) 15 else 11), dp(if (dominant) 15 else 11), Gravity.CENTER)
        })

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        content.addView(TextView(this).apply {
            text = label
            textSize = 13f
            typeface = routeTypeface
            setTextColor(if (dominant) ROUTE_BLUE else SECONDARY)
            letterSpacing = 0.1f
        })
        val value = TextView(this).apply {
            text = initial
            textSize = if (dominant) 36f else if (label == "NEXT ACTION") 25f else 17f
            typeface = Typeface.create(bodyTypeface, Typeface.BOLD)
            setTextColor(PRIMARY)
            maxLines = if (dominant) 2 else 3
            setPadding(0, dp(if (dominant) 4 else 2), 0, 0)
        }
        val detail = TextView(this).apply {
            text = ""
            textSize = 17f
            typeface = bodyTypeface
            setTextColor(SECONDARY)
            maxLines = 3
            visibility = View.GONE
            setPadding(0, dp(4), 0, 0)
        }
        content.addView(value)
        content.addView(detail)
        row.addView(marker)
        row.addView(content)
        return CheckpointViews(row, value, detail)
    }

    @SuppressLint("SetTextI18n")
    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(28))
            setBackgroundColor(BG)
        }
        fun match(height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)

        // Stable header: route identity, navigation mode, and exactly one system state.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(68)
        }
        header.addView(TextView(this).apply {
            text = "SS 01"
            textSize = 17f
            typeface = routeTypeface
            setTextColor(BG)
            gravity = Gravity.CENTER
            background = rounded(ROUTE_BLUE, 4)
            contentDescription = "Route SS 01"
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(56))
        })
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@MainActivity).apply {
                text = "SonarSight"
                textSize = 24f
                typeface = Typeface.create(bodyTypeface, Typeface.BOLD)
                setTextColor(PRIMARY)
                maxLines = 2
            })
            navModeView = TextView(this@MainActivity).apply {
                text = "GUIDANCE MODE"
                textSize = 13f
                typeface = routeTypeface
                setTextColor(SECONDARY)
                letterSpacing = 0.08f
                maxLines = 2
            }
            addView(navModeView)
        })
        headerStatePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = rounded(RAISED, 4, TRAIL_GREEN)
            layoutParams = LinearLayout.LayoutParams(dp(118), ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(TextView(this@MainActivity).apply {
                text = "SYSTEM STATE"
                textSize = 11f
                typeface = routeTypeface
                setTextColor(SECONDARY)
                letterSpacing = 0.08f
            })
            headerStateView = TextView(this@MainActivity).apply {
                text = "● READY"
                textSize = 15f
                typeface = routeTypeface
                setTextColor(TRAIL_GREEN)
                maxLines = 2
            }
            addView(headerStateView)
        }
        header.addView(headerStatePanel)
        root.addView(header, match())

        root.addView(sectionLabel("SYSTEM RAIL"))
        systemRail = SystemRailView(this).apply {
            background = rounded(SURFACE, 4, DIVIDER)
            setPadding(dp(4), 0, dp(4), 0)
        }
        root.addView(systemRail, match())

        root.addView(sectionLabel("TRAIL LINE / ROUTE SS 01"))
        trailSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(SURFACE, 6, DIVIDER)
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val last = checkpointRow("LAST CONFIRMED", lastConfirmed, false, TRAIL_GREEN)
        val now = checkpointRow("NOW", "READY TO NAVIGATE", true, ROUTE_BLUE)
        val next = checkpointRow("NEXT ACTION", "START VISION WHEN READY", false, AMBER)
        lastConfirmedView = last.value
        nowView = now.value
        nowDetailView = now.detail
        nextActionView = next.value
        trailSection.addView(last.root)
        trailSection.addView(View(this).apply { setBackgroundColor(DIVIDER) }, match(dp(1)))
        trailSection.addView(now.root)
        trailSection.addView(View(this).apply { setBackgroundColor(DIVIDER) }, match(dp(1)))
        trailSection.addView(next.root)
        root.addView(trailSection, match())

        root.addView(sectionLabel("DIRECTION RAIL"))
        directionRail = DirectionRailView(this)
        root.addView(directionRail, match())

        root.addView(sectionLabel("PRIMARY ACTIONS"))
        askButton = actionButton("HOLD TO ASK", 72, R.drawable.ic_trail_mic).apply {
            layoutParams = match()
            contentDescription = "Hold to ask about your surroundings. With TalkBack, double tap to start a timed listening window."
            setOnClickListener { startHandsFreeAsk() }
        }
        wireHoldToAsk()
        root.addView(askButton)

        val secondaryActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = match().apply { topMargin = dp(8) }
        }
        readButton = actionButton("READ TEXT", 56, R.drawable.ic_trail_read).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
            contentDescription = "Read visible text aloud"
            setOnClickListener {
                showVoicePanel()
                voiceQuestion.text = "READING TEXT"
                voiceAnswer.text = ""
                setInteractionState(InteractionState.PROCESSING, "READING TEXT")
                speak("Reading.", markSuccess = false)
                AppGraph.cloudAsk.readText(
                    AppGraph.glassesSource.lastFrame,
                    onAnswer = ::speakAnswer,
                    onError = ::speakError,
                )
            }
        }
        findButton = actionButton("FIND", 56, R.drawable.ic_trail_find).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) }
            contentDescription = "Find an object from recent scene memory. Double tap, then say what to find."
            setOnClickListener {
                showVoicePanel()
                voiceQuestion.text = "SAY WHAT YOU WANT TO FIND"
                voiceAnswer.text = ""
                startHandsFreeAsk()
            }
        }
        secondaryActions.addView(readButton)
        secondaryActions.addView(findButton)
        root.addView(secondaryActions)

        voicePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(SURFACE, 4, DIVIDER)
            setPadding(dp(14), dp(12), dp(14), dp(14))
            visibility = View.GONE
            layoutParams = match().apply { topMargin = dp(10) }
        }
        voicePanel.addView(TextView(this).apply {
            text = "VOICE RESPONSE"
            textSize = 13f
            typeface = routeTypeface
            letterSpacing = 0.08f
            setTextColor(ROUTE_BLUE)
        })
        voiceQuestion = TextView(this).apply {
            text = ""
            textSize = 15f
            typeface = bodyTypeface
            setTextColor(SECONDARY)
            setPadding(0, dp(6), 0, 0)
        }
        voiceAnswer = TextView(this).apply {
            text = ""
            textSize = 17f
            typeface = bodyTypeface
            setTextColor(PRIMARY)
            setPadding(0, dp(5), 0, 0)
        }
        voicePanel.addView(voiceQuestion)
        voicePanel.addView(voiceAnswer)
        root.addView(voicePanel)

        // Scene Details starts collapsed. Every former operator/developer control remains here.
        val detailsToggle = detailButton("SCENE DETAILS  +", 56).apply {
            typeface = routeTypeface
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            layoutParams = match(dp(56)).apply { topMargin = dp(18) }
            contentDescription = "Scene details, collapsed"
        }
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = rounded(SURFACE, 4, DIVIDER)
            setPadding(dp(10), dp(10), dp(10), dp(14))
            layoutParams = match()
        }
        detailsToggle.setOnClickListener {
            val show = details.visibility != View.VISIBLE
            details.visibility = if (show) View.VISIBLE else View.GONE
            detailsToggle.text = if (show) "SCENE DETAILS  −" else "SCENE DETAILS  +"
            detailsToggle.contentDescription = "Scene details, ${if (show) "expanded" else "collapsed"}"
        }
        root.addView(detailsToggle)

        details.addView(sectionLabel("LIVE SCENE PREVIEW"))
        val cameraZone = FrameLayout(this).apply {
            layoutParams = match(dp(230))
            background = rounded(BG, 4, DIVIDER)
            clipToOutline = true
            contentDescription = "Live scene preview with detected object outlines"
        }
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        glassesPreview = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.FIT_XY
        }
        overlay = DetectionOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        liveChip = TextView(this).apply {
            text = "PREVIEW OFFLINE"
            textSize = 12f
            typeface = routeTypeface
            setTextColor(SECONDARY)
            background = rounded(0xE60C1210.toInt(), 4, DIVIDER)
            setPadding(dp(9), dp(5), dp(9), dp(5))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(10), dp(10), 0, 0)
            }
        }
        cameraZone.addView(previewView)
        cameraZone.addView(glassesPreview)
        cameraZone.addView(overlay)
        cameraZone.addView(liveChip)
        details.addView(cameraZone)

        details.addView(sectionLabel("DETECTED OBJECTS"))
        objectsView = TextView(this).apply {
            text = "No labeled objects"
            textSize = 17f
            typeface = bodyTypeface
            setTextColor(PRIMARY)
            background = rounded(RAISED, 4, DIVIDER)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        details.addView(objectsView, match())

        details.addView(sectionLabel("SYSTEM CONTROLS"))
        startButton = detailButton("START GLASSES VISION", 56).apply {
            layoutParams = match()
            typeface = Typeface.create(bodyTypeface, Typeface.BOLD)
            setButtonIcon(this, R.drawable.ic_trail_glasses, BG)
            setOnClickListener {
                if (glassesRunning) {
                    AppGraph.glassesSource.stop()
                    AppGraph.visionPipeline.stop()
                    setGlassesRunning(false)
                } else {
                    connectGlassesAndStart()
                }
            }
        }
        details.addView(startButton)

        val toggles = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = match().apply { topMargin = dp(8) }
        }
        audioButton = detailButton("3D AUDIO — DISABLED").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
            setOnClickListener { toggleCollisionAudio() }
        }
        cloudButton = detailButton("CLOUD VISION — DISABLED").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) }
            setOnClickListener { toggleCloudVision() }
        }
        toggles.addView(audioButton)
        toggles.addView(cloudButton)
        details.addView(toggles)

        details.addView(sectionLabel("TECHNICAL INFORMATION"))
        srcChip = chip("SOURCE —")
        fpsChip = chip("0.0 FPS")
        latChip = chip("YOLO —")
        detChip = chip("0 OBJECTS")
        fun chipRow(first: TextView, second: TextView) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = match().apply { topMargin = dp(4) }
            first.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(2) }
            second.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(2) }
            addView(first)
            addView(second)
        }
        details.addView(chipRow(srcChip, fpsChip))
        details.addView(chipRow(latChip, detChip))

        val diagnosticsToggle = detailButton("DIAGNOSTICS  +", 52).apply {
            typeface = routeTypeface
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = match(dp(52)).apply { topMargin = dp(12) }
            contentDescription = "Diagnostics, collapsed"
        }
        val diagnostics = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = match()
        }
        diagnosticsToggle.setOnClickListener {
            val show = diagnostics.visibility != View.VISIBLE
            diagnostics.visibility = if (show) View.VISIBLE else View.GONE
            diagnosticsToggle.text = if (show) "DIAGNOSTICS  −" else "DIAGNOSTICS  +"
            diagnosticsToggle.contentDescription = "Diagnostics, ${if (show) "expanded" else "collapsed"}"
        }
        details.addView(diagnosticsToggle)

        fun diagnosticButton(label: String, onClick: () -> Unit) = detailButton(label).apply {
            layoutParams = match().apply { topMargin = dp(5) }
            setOnClickListener { onClick() }
        }
        diagnostics.addView(diagnosticButton(getString(R.string.btn_glasses_setup)) { setupGlasses() })
        diagnostics.addView(diagnosticButton(getString(R.string.btn_start_vision)) { connectCameraAndStart() })
        diagnostics.addView(diagnosticButton(getString(R.string.btn_stop_vision)) { AppGraph.visionPipeline.stop() })
        diagnostics.addView(diagnosticButton(getString(R.string.btn_mock_on)) { AppGraph.mockSceneProducer.setEnabled(true) })
        diagnostics.addView(diagnosticButton(getString(R.string.btn_mock_off)) { AppGraph.mockSceneProducer.setEnabled(false) })
        diagnostics.addView(diagnosticButton("My People — forget everyone") {
            AppGraph.myPeople.clear()
            toast("Enrolled faces deleted.")
        })
        diagnostics.addView(diagnosticButton(getString(R.string.btn_ask)) {
            toast(if (AppGraph.llmEngine.isReady) "Asking Qwen…" else "Answering…")
            AppGraph.voiceAgent.askAsync("what's ahead of me?") { answer ->
                Log.i(TAG, "Voice answer: $answer")
                runOnUiThread { toast(answer) }
            }
        })
        glassesStatusView = TextView(this).apply {
            text = getString(R.string.glasses_idle)
            textSize = 13f
            setTextColor(SECONDARY)
            typeface = bodyTypeface
            setPadding(dp(4), dp(10), dp(4), 0)
        }
        statusView = TextView(this).apply {
            text = getString(R.string.vision_idle)
            textSize = 13f
            setTextColor(SECONDARY)
            typeface = bodyTypeface
            setPadding(dp(4), dp(8), dp(4), 0)
        }
        sceneView = TextView(this).apply {
            text = getString(R.string.scene_waiting)
            textSize = 12f
            setTextColor(SECONDARY)
            typeface = Typeface.MONOSPACE
            setPadding(dp(4), dp(8), dp(4), 0)
        }
        diagnostics.addView(glassesStatusView)
        diagnostics.addView(statusView)
        diagnostics.addView(sceneView)
        details.addView(diagnostics)
        root.addView(details)

        return ScrollView(this).apply {
            setBackgroundColor(BG)
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            addView(root)
        }
    }

    // ---------------------------------------------------------- actions ----

    private fun setGlassesRunning(running: Boolean) {
        glassesRunning = running
        liveChip.text = if (running) "LIVE — GLASSES POV" else "PREVIEW OFFLINE"
        liveChip.setTextColor(if (running) TRAIL_GREEN else SECONDARY)
        updateSystemControls()
        renderInterface()
    }

    private fun connectCameraAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startLiveVision()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startLiveVision() {
        AppGraph.visionPipeline.start(this, previewView)
    }

    private fun setupGlasses() {
        requestGlassesSetup.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
    }

    private fun connectGlassesAndStart() {
        if (!AppGraph.glassesSource.isRegistered) {
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

    /** Glasses tap or TalkBack click: fixed listening window; a second tap submits early. */
    private fun startHandsFreeAsk() {
        if (handsFreeListening) {
            handsFreeListening = false
            val wav = recorder.stop()
            if (wav != null) submitVoiceQuestion(wav) else setInteractionState(InteractionState.IDLE)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 80)
        tone.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 150)
        askButton.postDelayed({
            tone.release()
            if (!recorder.start()) {
                setInteractionState(InteractionState.ERROR, "MICROPHONE ERROR")
                return@postDelayed
            }
            handsFreeListening = true
            setInteractionState(InteractionState.LISTENING, "LISTENING — TAP TO FINISH")
            askButton.postDelayed({
                if (!handsFreeListening) return@postDelayed
                handsFreeListening = false
                val wav = recorder.stop()
                if (wav != null) submitVoiceQuestion(wav) else setInteractionState(InteractionState.IDLE)
            }, HANDS_FREE_LISTEN_MS)
        }, 350)
    }

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
                        setInteractionState(InteractionState.LISTENING, "LISTENING — RELEASE TO SEND")
                    } else {
                        setInteractionState(InteractionState.ERROR, "MICROPHONE ERROR")
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val wav = recorder.stop()
                    if (wav != null) submitVoiceQuestion(wav)
                    else if (!handsFreeListening) setInteractionState(InteractionState.IDLE)
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun submitVoiceQuestion(wav: ByteArray) {
        showVoicePanel()
        setInteractionState(InteractionState.PROCESSING, "PROCESSING QUESTION")
        voiceQuestion.text = "TRANSCRIBING"
        voiceAnswer.text = ""
        AppGraph.cloudAsk.transcribeAsync(wav, onError = ::speakError) { question ->
            runOnUiThread { voiceQuestion.text = "YOU SAID  “$question”" }
            socket?.updateVoice(question, "cloud", "…")
            AppGraph.voiceRouter.route(question) { action -> runOnUiThread { execute(question, action) } }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun execute(question: String, action: VoiceCommandRouter.Action) {
        val frame = AppGraph.glassesSource.lastFrame
        when (action) {
            is VoiceCommandRouter.Action.SetPingSensitivity -> {
                AppGraph.collisionAudio.setSensitivity(action.level)
                speak("Ping sensitivity set to ${action.level}.")
            }
            is VoiceCommandRouter.Action.SetCollisionAudio -> {
                AppGraph.collisionAudio.setEnabled(action.enabled)
                updateSystemControls()
                renderInterface()
                speak(if (action.enabled) "Collision audio on." else "Collision audio off.")
            }
            is VoiceCommandRouter.Action.SetCloudVision -> {
                if (AppGraph.cloudVision.configured) {
                    AppGraph.cloudVision.enabled = action.enabled
                    if (action.enabled) cloudEnabledAt = System.currentTimeMillis()
                    updateSystemControls()
                    renderInterface()
                    speak(if (action.enabled) "Qwen cloud vision on." else "Cloud vision off — local models.")
                } else speak("No Qwen API key configured.")
            }
            is VoiceCommandRouter.Action.AddHazardWatch -> {
                AppGraph.cloudVision.addWatch(action.labels)
                speak("Now watching for ${action.labels.joinToString(" and ")}.")
            }
            is VoiceCommandRouter.Action.ReadText -> {
                setInteractionState(InteractionState.PROCESSING, "READING TEXT")
                speak("Reading.", markSuccess = false)
                AppGraph.cloudAsk.readText(frame, onAnswer = ::speakAnswer, onError = ::speakError)
            }
            is VoiceCommandRouter.Action.EnrollFace -> {
                if (AppGraph.myPeople.enroll(frame, action.name)) {
                    speak("Remembered ${action.name}. I'll announce them when I see them. Make sure they're okay with that.")
                } else speak("I need them in view first — face them and try again.")
            }
            is VoiceCommandRouter.Action.FindObject -> speak(AppGraph.sceneJournal.answerFor(action.name))
            is VoiceCommandRouter.Action.AskScene -> {
                AppGraph.cloudAsk.answerScene(question, frame, onAnswer = { answer ->
                    socket?.updateVoice("", "cloud", answer)
                    speakAnswer(answer)
                }, onError = ::speakError)
            }
        }
    }

    private fun showVoicePanel() {
        voicePanel.visibility = View.VISIBLE
    }

    private fun speak(message: String, markSuccess: Boolean = true) {
        runOnUiThread {
            showVoicePanel()
            voiceAnswer.text = message
            if (markSuccess) setInteractionState(InteractionState.SUCCESS, "ACTION COMPLETE")
            tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "sonarsight-answer")
        }
    }

    private fun speakAnswer(answer: String) {
        Log.i(TAG, "Answer: $answer")
        speak(answer)
    }

    @SuppressLint("SetTextI18n")
    private fun speakError(message: String) {
        runOnUiThread {
            showVoicePanel()
            voiceQuestion.text = "ACTION COULD NOT COMPLETE"
            voiceAnswer.text = message
            setInteractionState(InteractionState.ERROR, "ACTION NEEDED")
            tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "sonarsight-error")
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
        if (enable) cloudEnabledAt = System.currentTimeMillis()
        updateSystemControls()
        renderInterface()
        toast(if (enable) "Cloud vision on — qwen-vl-max grounding on Qwen Cloud." else "Cloud vision off — local model.")
    }

    private fun toggleCollisionAudio() {
        val enable = !AppGraph.collisionAudio.isEnabled()
        AppGraph.collisionAudio.setEnabled(enable)
        updateSystemControls()
        renderInterface()
        if (enable) toast("3D collision audio on — pings pan toward the obstacle.")
    }

    // -------------------------------------------------------- observers ----

    private fun observeGlassesStatus() {
        lifecycleScope.launch {
            AppGraph.glassesSource.status.collectLatest {
                latestGlassesStatus = it
                glassesStatusView.text = it
                if (!AppGraph.glassesSource.isStreaming && glassesRunning &&
                    (it.contains("stopped") || it.contains("error") || it.contains("closed"))
                ) setGlassesRunning(false) else renderInterface()
            }
        }
    }

    private fun observeScene() {
        lifecycleScope.launch {
            AppGraph.sceneBus.state.collectLatest { scene ->
                latestScene = scene
                sceneView.text = render(scene)
                overlay.setDetections(scene.objects)
                renderInterface()
            }
        }
    }

    private fun observeVisionStatus() {
        lifecycleScope.launch {
            AppGraph.visionPipeline.status.collectLatest { status ->
                latestVision = status
                statusView.text = renderStatus(status)
                renderChips(status)
                renderInterface()
            }
        }
    }

    // -------------------------------------------------------- rendering ----

    private data class Guidance(
        val state: String,
        val now: String,
        val detail: String,
        val next: String,
        val color: Int,
    )

    private fun renderInterface() {
        val guidance = guidanceFor(latestScene)
        renderGuidance(guidance)
        renderDirections(latestScene)
        renderObjects(latestScene)
        renderHeader(guidance)
        renderSystemRail()
        updateSystemControls()
    }

    private fun guidanceFor(scene: SceneState): Guidance {
        if (scene.ts == 0L) {
            return Guidance("READY", "READY TO NAVIGATE", "Vision is standing by", "START VISION WHEN READY", TRAIL_GREEN)
        }
        val left = zoneNearness(scene, "left", scene.depth.left)
        val ahead = zoneNearness(scene, "center", scene.depth.center)
        val right = zoneNearness(scene, "right", scene.depth.right)
        val aheadObject = objectFor(scene, "center")?.label?.displayLabel() ?: "Obstacle"
        val leftObject = objectFor(scene, "left")?.label?.displayLabel() ?: "Obstacle"
        val rightObject = objectFor(scene, "right")?.label?.displayLabel() ?: "Obstacle"

        if (scene.conf < 0.4f) {
            return Guidance("REACQUIRING", "PAUSE", "Route position is uncertain", "WAIT FOR ROUTE", AMBER)
        }
        if (scene.depth.curbAhead || scene.depth.stepDown) {
            return Guidance("ATTENTION", "STOP — STEP AHEAD", distancePhrase(ahead), "STEP CAREFULLY", CORAL)
        }
        if (ahead >= 0.75f) {
            return Guidance(
                "ATTENTION", "STOP", "$aheadObject directly ahead · ${distancePhrase(ahead).lowercase()}",
                if (left <= right) "MOVE LEFT WHEN CLEAR" else "MOVE RIGHT WHEN CLEAR", CORAL,
            )
        }
        if (ahead >= 0.55f) {
            return Guidance(
                "ATTENTION", "$aheadObject AHEAD", distancePhrase(ahead),
                if (left <= right) "MOVE SLIGHTLY LEFT" else "MOVE SLIGHTLY RIGHT", AMBER,
            )
        }
        if (left >= 0.55f && right >= 0.55f) {
            return Guidance("ATTENTION", "PASSAGE NARROWS", "Obstacles on both sides", "CONTINUE SLOWLY AHEAD", AMBER)
        }
        if (left >= 0.55f) {
            return Guidance("ATTENTION", "MOVE RIGHT", "$leftObject on left · ${distancePhrase(left).lowercase()}", "KEEP RIGHT UNTIL CLEAR", AMBER)
        }
        if (right >= 0.55f) {
            return Guidance("ATTENTION", "MOVE LEFT", "$rightObject on right · ${distancePhrase(right).lowercase()}", "KEEP LEFT UNTIL CLEAR", AMBER)
        }
        if (scene.pathClear) {
            return Guidance("TRACKING", "PATH CLEAR", "Route ahead is open", "CONTINUE AHEAD", TRAIL_GREEN)
        }
        return Guidance("TRACKING", "PROCEED CAREFULLY", "Route is being checked", "CONTINUE SLOWLY", AMBER)
    }

    private fun renderGuidance(guidance: Guidance) {
        if (guidance.now != currentNowTitle || guidance.detail != currentNowDetail) {
            if (currentNowTitle.isNotBlank() && latestScene.ts != 0L && currentNowTitle != "READY TO NAVIGATE") {
                lastConfirmed = if (currentNowDetail.isBlank()) currentNowTitle else "$currentNowTitle — $currentNowDetail"
            }
            currentNowTitle = guidance.now
            currentNowDetail = guidance.detail
            nowView.alpha = 0.65f
            nowView.translationY = dp(4).toFloat()
            nowView.animate().alpha(1f).translationY(0f).setDuration(160L).start()
        }
        lastConfirmedView.text = lastConfirmed
        nowView.text = guidance.now
        nowView.setTextColor(guidance.color)
        nowDetailView.text = guidance.detail
        nowDetailView.visibility = if (guidance.detail.isBlank()) View.GONE else View.VISIBLE
        nextActionView.text = guidance.next
        trailSection.contentDescription =
            "Trail line. Last confirmed: $lastConfirmed. Now: ${guidance.now}. ${guidance.detail}. Next action: ${guidance.next}."
    }

    private fun renderDirections(scene: SceneState) {
        val headings = listOf("LEFT", "AHEAD", "RIGHT")
        val zones = listOf("left", "center", "right")
        val depths = listOf(scene.depth.left, scene.depth.center, scene.depth.right)
        val values = zones.indices.map { index ->
            val zone = zones[index]
            val near = zoneNearness(scene, zone, depths[index])
            val obj = objectFor(scene, zone)
            val uncertain = scene.ts == 0L || scene.conf < 0.4f
            val curb = zone == "center" && (scene.depth.curbAhead || scene.depth.stepDown)
            val condition = when {
                uncertain -> DirectionCondition.UNKNOWN
                curb || near >= 0.75f -> DirectionCondition.STOP
                near >= 0.45f -> DirectionCondition.WATCH
                else -> DirectionCondition.OPEN
            }
            DirectionCheckpoint(
                heading = headings[index],
                objectLabel = when {
                    uncertain -> "Checking"
                    curb -> "Step"
                    obj != null -> obj.label.displayLabel()
                    near >= 0.45f -> "Obstacle"
                    else -> "Clear"
                },
                distance = when {
                    uncertain -> "—"
                    near < 0.2f -> "OPEN"
                    else -> distanceLabel(near)
                },
                condition = condition,
            )
        }
        directionRail.setCheckpoints(values)
    }

    private fun renderObjects(scene: SceneState) {
        objectsView.text = if (scene.objects.isEmpty()) {
            "No labeled objects"
        } else {
            scene.objects.sortedByDescending { it.nearness }.take(8).joinToString("\n") { obj ->
                "${obj.zone.uppercase()}  ${obj.label.displayLabel()} · ${distancePhrase(obj.nearness).lowercase()}"
            }
        }
    }

    private fun renderHeader(guidance: Guidance) {
        navModeView.text = when {
            AppGraph.mockSceneProducer.isEnabled() -> "MOCK NAVIGATION"
            AppGraph.glassesSource.isStreaming || glassesRunning -> "GLASSES NAVIGATION"
            latestVision.running -> "PHONE CAMERA NAVIGATION"
            else -> "GUIDANCE MODE"
        }
        val state = when (interactionState) {
            InteractionState.LISTENING -> "LISTENING"
            InteractionState.ERROR -> "ACTION NEEDED"
            InteractionState.PROCESSING -> "TRACKING"
            else -> guidance.state
        }
        val color = when (state) {
            "ATTENTION", "REACQUIRING" -> AMBER
            "ACTION NEEDED" -> CORAL
            "LISTENING" -> ROUTE_BLUE
            "TRACKING" -> ROUTE_BLUE
            else -> TRAIL_GREEN
        }
        val symbol = when (state) {
            "ATTENTION", "REACQUIRING" -> "▲"
            "ACTION NEEDED" -> "■"
            else -> "●"
        }
        headerStateView.text = "$symbol $state"
        headerStateView.setTextColor(color)
        headerStatePanel.background = rounded(RAISED, 4, color)
        headerStatePanel.contentDescription = "System state, ${state.lowercase()}"
    }

    private fun renderSystemRail() {
        val glasses = when {
            AppGraph.glassesSource.isStreaming -> SystemCheckpoint("GLASSES", "CONNECTED", RailCondition.CONNECTED)
            AppGraph.glassesSource.isRegistered -> SystemCheckpoint("GLASSES", "AVAILABLE", RailCondition.AVAILABLE)
            latestGlassesStatus.contains("error") -> SystemCheckpoint("GLASSES", "UNAVAILABLE", RailCondition.UNAVAILABLE)
            else -> SystemCheckpoint("GLASSES", "SETUP NEEDED", RailCondition.UNAVAILABLE)
        }
        val vision = when {
            !latestVision.running -> SystemCheckpoint("VISION", "DISABLED", RailCondition.DISABLED)
            cloudDegraded -> SystemCheckpoint("VISION", "DEGRADED", RailCondition.DEGRADED)
            latestVision.cloudActive || latestVision.depthLoaded || latestVision.yoloLoaded ->
                SystemCheckpoint("VISION", "ACTIVE", RailCondition.ACTIVE)
            else -> SystemCheckpoint("VISION", "DEGRADED", RailCondition.DEGRADED)
        }
        val audio = if (AppGraph.collisionAudio.isEnabled()) {
            SystemCheckpoint("AUDIO", "ACTIVE", RailCondition.ACTIVE)
        } else {
            SystemCheckpoint("AUDIO", "DISABLED", RailCondition.DISABLED)
        }
        systemRail.setCheckpoints(listOf(glasses, vision, audio))
    }

    private fun updateSystemControls() {
        if (!::startButton.isInitialized) return
        startButton.text = if (glassesRunning) "STOP GLASSES VISION" else "START GLASSES VISION"
        styleButton(startButton, if (glassesRunning) CORAL else ROUTE_BLUE, BG, if (glassesRunning) CORAL else ROUTE_BLUE)
        setButtonIcon(startButton, R.drawable.ic_trail_glasses, BG)

        val audioEnabled = AppGraph.collisionAudio.isEnabled()
        audioButton.text = "3D AUDIO — ${if (audioEnabled) "ACTIVE" else "DISABLED"}"
        styleButton(audioButton, if (audioEnabled) TRAIL_GREEN else RAISED, if (audioEnabled) BG else PRIMARY,
            if (audioEnabled) TRAIL_GREEN else DIVIDER)

        val cloudEnabled = AppGraph.cloudVision.enabled
        cloudButton.text = when {
            cloudDegraded -> "CLOUD — EDGE FALLBACK"
            cloudEnabled -> "CLOUD VISION — ACTIVE"
            else -> "CLOUD VISION — DISABLED"
        }
        val cloudColor = when {
            cloudDegraded -> AMBER
            cloudEnabled -> ROUTE_BLUE
            else -> RAISED
        }
        styleButton(cloudButton, cloudColor, if (cloudEnabled || cloudDegraded) BG else PRIMARY,
            if (cloudEnabled || cloudDegraded) cloudColor else DIVIDER)
    }

    private fun styleButton(button: Button, fill: Int, text: Int, stroke: Int) {
        button.background = buttonSelector(fill, mix(fill, PRIMARY, 0.16f), SURFACE, stroke)
        button.setTextColor(text)
    }

    private fun setInteractionState(state: InteractionState, label: String? = null) {
        if (!::askButton.isInitialized) return
        askButton.removeCallbacks(resetInteractionRunnable)
        interactionState = if (state == InteractionState.IDLE && !AppGraph.cloudVision.configured) {
            InteractionState.DISABLED
        } else state

        val (text, fill, textColor, enabled) = when (interactionState) {
            InteractionState.IDLE -> Quad(label ?: "HOLD TO ASK", ROUTE_BLUE, BG, true)
            InteractionState.LISTENING -> Quad(label ?: "LISTENING — RELEASE TO SEND", AMBER, BG, true)
            InteractionState.PROCESSING -> Quad(label ?: "PROCESSING", RAISED, PRIMARY, false)
            InteractionState.SUCCESS -> Quad(label ?: "ACTION COMPLETE", TRAIL_GREEN, BG, false)
            InteractionState.ERROR -> Quad(label ?: "ACTION NEEDED", CORAL, BG, false)
            InteractionState.DISABLED -> Quad("HOLD TO ASK — UNAVAILABLE", SURFACE, SECONDARY, false)
        }
        askButton.text = text
        askButton.isEnabled = enabled
        askButton.background = buttonSelector(fill, mix(fill, PRIMARY, 0.16f), SURFACE,
            if (interactionState == InteractionState.PROCESSING) ROUTE_BLUE else fill)
        askButton.setTextColor(textColor)
        setButtonIcon(askButton, R.drawable.ic_trail_mic, textColor)

        val cloudActionsEnabled = AppGraph.cloudVision.configured && interactionState != InteractionState.PROCESSING
        readButton.isEnabled = cloudActionsEnabled
        findButton.isEnabled = cloudActionsEnabled
        styleSecondaryAction(readButton, cloudActionsEnabled)
        styleSecondaryAction(findButton, cloudActionsEnabled)

        askButton.stateDescription = when (interactionState) {
            InteractionState.IDLE -> "Idle"
            InteractionState.LISTENING -> "Listening"
            InteractionState.PROCESSING -> "Processing"
            InteractionState.SUCCESS -> "Success"
            InteractionState.ERROR -> "Error"
            InteractionState.DISABLED -> "Disabled"
        }
        if (interactionState == InteractionState.SUCCESS) askButton.postDelayed(resetInteractionRunnable, 1600L)
        if (interactionState == InteractionState.ERROR) askButton.postDelayed(resetInteractionRunnable, 2400L)
        if (::trailSection.isInitialized) renderInterface()
    }

    private fun styleSecondaryAction(button: Button, enabled: Boolean) {
        val fill = if (enabled) RAISED else SURFACE
        val text = if (enabled) PRIMARY else SECONDARY
        button.background = buttonSelector(fill, DIVIDER, SURFACE, if (enabled) ROUTE_BLUE else DIVIDER)
        button.setTextColor(text)
        val icon = if (button === readButton) R.drawable.ic_trail_read else R.drawable.ic_trail_find
        setButtonIcon(button, icon, text)
        button.stateDescription = if (enabled) "Idle" else "Disabled"
    }

    private val resetInteractionRunnable = Runnable { setInteractionState(InteractionState.IDLE) }

    private fun renderChips(status: VisionStatus) {
        srcChip.text = if (status.cloudActive) "CLOUD / QWEN" else "EDGE / ${status.backend.uppercase()}"
        srcChip.setTextColor(if (status.cloudActive) ROUTE_BLUE else PRIMARY)
        fpsChip.text = "%.1f FPS".format(status.fps)
        latChip.text = if (status.cloudActive) "RTT %.0f MS".format(status.yoloMs) else "YOLO %.0f MS".format(status.yoloMs)
        detChip.text = "${status.detections} OBJECTS"
        renderCloudHealth(status)
    }

    private fun renderCloudHealth(status: VisionStatus) {
        if (!AppGraph.cloudVision.enabled) {
            cloudDegraded = false
            return
        }
        if (System.currentTimeMillis() - cloudEnabledAt < CLOUD_ENABLE_GRACE_MS) return
        val degraded = status.running && AppGraph.visionPipeline.cloudResultAgeMs() > CLOUD_DEGRADED_AFTER_MS
        if (degraded == cloudDegraded) return
        cloudDegraded = degraded
        updateSystemControls()
        renderSystemRail()
        toast(if (degraded) "Cloud unreachable — edge AI took over" else "Qwen Cloud restored")
    }

    private fun objectFor(scene: SceneState, zone: String): DetectedObj? =
        scene.objects.filter { it.zone == zone }.maxByOrNull { it.nearness }

    private fun zoneNearness(scene: SceneState, zone: String, depth: Float): Float =
        maxOf(depth, objectFor(scene, zone)?.nearness ?: 0f)

    private fun distanceLabel(nearness: Float): String = when {
        nearness >= 0.85f -> "< 1 M"
        nearness >= 0.65f -> "~ 1 M"
        nearness >= 0.4f -> "~ 2 M"
        else -> "2 M+"
    }

    private fun distancePhrase(nearness: Float): String = when {
        nearness >= 0.85f -> "Less than 1 m away"
        nearness >= 0.65f -> "Approximately 1 m away"
        nearness >= 0.4f -> "Approximately 2 m away"
        else -> "More than 2 m away"
    }

    private fun String.displayLabel(): String = trim().ifBlank { "Object" }.replaceFirstChar(Char::uppercase)

    private fun mix(a: Int, b: Int, amount: Float): Int {
        fun channel(shift: Int) = (((a shr shift) and 0xff) * (1f - amount) + ((b shr shift) and 0xff) * amount).toInt()
        return Color.rgb(channel(16), channel(8), channel(0))
    }

    private fun renderStatus(status: VisionStatus): String = buildString {
        val source = if (status.cloudActive) "QWEN CLOUD (vl-max)" else "local (${status.backend})"
        append("vision: ${if (status.running) "ON" else "off"}  src=$source\n")
        append("models: depth=${if (status.depthLoaded) "yes" else "—"}  yolo=${if (status.yoloLoaded) "yes" else "—"}  detections=${status.detections}\n")
        append("fps=%.1f  depth=%.0fms  %s=%.0fms\n".format(
            status.fps, status.depthMs, if (status.cloudActive) "cloud-rtt" else "yolo", status.yoloMs))
        append(status.note)
    }

    private fun render(scene: SceneState): String {
        val summary = buildString {
            append("mock=${AppGraph.mockSceneProducer.isEnabled()}\n")
            append("zones L/C/R = %.2f / %.2f / %.2f\n".format(scene.depth.left, scene.depth.center, scene.depth.right))
            append("pathClear=${scene.pathClear}  conf=%.2f\n".format(scene.conf))
            if (scene.ocr.present) append("ocr=\"${scene.ocr.text}\"\n")
            append("\n")
        }
        return summary + gson.toJson(scene)
    }

    private fun startDashboardSocket() {
        socket = SceneSocket(AppGraph.sceneBus).also { it.launch(AppGraph.scope) }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        tapTrigger?.release()
        tts?.shutdown()
        socket?.shutdown()
        if (isFinishing) AppGraph.visionPipeline.stop()
        super.onDestroy()
    }

    private enum class InteractionState { IDLE, LISTENING, PROCESSING, SUCCESS, ERROR, DISABLED }
    private data class Quad(val text: String, val fill: Int, val textColor: Int, val enabled: Boolean)

    companion object {
        private const val TAG = "SixthSenseScene"
        private const val HANDS_FREE_LISTEN_MS = 6000L
        private const val CLOUD_DEGRADED_AFTER_MS = 20_000L
        private const val CLOUD_ENABLE_GRACE_MS = 35_000L

        private val BG = Color.parseColor("#0C1210")
        private val SURFACE = Color.parseColor("#121A17")
        private val RAISED = Color.parseColor("#18211E")
        private val PRIMARY = Color.parseColor("#F4F1E8")
        private val SECONDARY = Color.parseColor("#B7C2BC")
        private val DIVIDER = Color.parseColor("#2A3531")
        private val ROUTE_BLUE = Color.parseColor("#62B5E5")
        private val TRAIL_GREEN = Color.parseColor("#A8C68F")
        private val AMBER = Color.parseColor("#F3C45B")
        private val CORAL = Color.parseColor("#FF766B")
    }
}
