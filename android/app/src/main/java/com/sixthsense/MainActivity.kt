package com.sixthsense

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.Gravity
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
import com.sixthsense.core.SceneState
import com.sixthsense.debug.AppGraph
import com.sixthsense.vision.DetectionOverlayView
import com.sixthsense.vision.VisionStatus
import com.sixthsense.ws.SceneSocket
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Operator / developer console — NOT the end-user interface. This screen exists
 * for development and the demo operator (start glasses or camera vision, toggle
 * mock, watch the live SceneState + backend/latency/fps).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var sceneView: TextView
    private lateinit var statusView: TextView
    private lateinit var previewView: PreviewView
    private lateinit var overlay: DetectionOverlayView
    private lateinit var glassesStatusView: TextView
    private lateinit var glassesPreview: ImageView
    private lateinit var audioButton: Button
    private var socket: SceneSocket? = null
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
                else "Approve SixthSense in the Meta AI app, then start glasses vision."
            )
        } else {
            toast("Bluetooth permission denied — glasses session needs it.")
        }
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
    }

    private fun buildUi(): ScrollView {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.title)
            textSize = 24f
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.subtitle)
            textSize = 12f
            setPadding(0, 0, 0, pad)
        })

        // Live POV + AR detection overlay. The overlay draws detection boxes on
        // top of whichever source is active (glasses stream or phone camera).
        val camHeight = (240 * resources.displayMetrics.density).toInt()
        val camContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, camHeight)
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
        camContainer.addView(previewView)
        camContainer.addView(glassesPreview) // glasses POV sits over the camera preview
        camContainer.addView(overlay)   // overlay sits on top of both
        root.addView(camContainer)

        statusView = TextView(this).apply {
            text = getString(R.string.vision_idle)
            textSize = 12f
            setPadding(0, pad / 2, 0, pad / 2)
        }
        root.addView(statusView)

        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setOnClickListener { onClick() }
        }

        // Ray-Ban Meta glasses as the perception input (Meta Wearables DAT).
        glassesStatusView = TextView(this).apply {
            text = getString(R.string.glasses_idle)
            textSize = 12f
            setPadding(0, pad / 2, 0, 0)
        }
        root.addView(glassesStatusView)
        root.addView(button(getString(R.string.btn_glasses_setup)) { setupGlasses() })
        root.addView(button(getString(R.string.btn_glasses_start)) { connectGlassesAndStart() })
        root.addView(button(getString(R.string.btn_glasses_stop)) {
            AppGraph.glassesSource.stop()
            AppGraph.visionPipeline.stop()
        })

        root.addView(button(getString(R.string.btn_start_vision)) { connectCameraAndStart() })
        root.addView(button(getString(R.string.btn_stop_vision)) {
            AppGraph.visionPipeline.stop()
        })
        audioButton = button(getString(R.string.btn_audio_off)) { toggleCollisionAudio() }
        root.addView(audioButton)
        root.addView(button(getString(R.string.btn_mock_on)) {
            AppGraph.mockSceneProducer.setEnabled(true)
        })
        root.addView(button(getString(R.string.btn_mock_off)) {
            AppGraph.mockSceneProducer.setEnabled(false)
        })
        root.addView(button(getString(R.string.btn_ask)) {
            // Uses the on-device Qwen LLM when ready (falls back to rule-based);
            // generation runs off-thread, so toast the answer when it returns.
            toast(if (AppGraph.llmEngine.isReady) "Asking Qwen…" else "Answering…")
            AppGraph.voiceAgent.askAsync("what's ahead of me?") { answer ->
                Log.i(TAG, "Voice answer: $answer")
                runOnUiThread { toast(answer) }
            }
        })

        sceneView = TextView(this).apply {
            text = getString(R.string.scene_waiting)
            textSize = 13f
            setPadding(0, pad, 0, 0)
            gravity = Gravity.START
        }
        root.addView(sceneView)

        return ScrollView(this).apply { addView(root) }
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
            toast("Run Glasses Setup first (and approve in the Meta AI app).")
            return
        }
        lifecycleScope.launch {
            val status = Wearables.checkPermissionStatus(Permission.CAMERA).getOrNull()
            if (status == PermissionStatus.Granted) startGlassesVision()
            else requestWearablesCamera.launch(Permission.CAMERA)
        }
    }

    private fun startGlassesVision() {
        AppGraph.glassesSource.onFrame = { bmp ->
            runOnUiThread { glassesPreview.setImageBitmap(bmp) }
        }
        AppGraph.glassesSource.start(AppGraph.scope)
    }

    private fun toggleCollisionAudio() {
        val enable = !AppGraph.collisionAudio.isEnabled()
        AppGraph.collisionAudio.setEnabled(enable)
        audioButton.text =
            getString(if (enable) R.string.btn_audio_on else R.string.btn_audio_off)
        if (enable) toast("3D collision audio on — pings pan toward the obstacle.")
    }

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
            }
        }
    }

    private fun observeVisionStatus() {
        lifecycleScope.launch {
            AppGraph.visionPipeline.status.collectLatest { s -> statusView.text = renderStatus(s) }
        }
    }

    private fun renderStatus(s: VisionStatus): String = buildString {
        append("vision: ${if (s.running) "ON" else "off"}  backend=${s.backend}\n")
        append("models: depth=${if (s.depthLoaded) "✓" else "—"}  yolo=${if (s.yoloLoaded) "✓" else "—"}  detections=${s.detections}\n")
        append("fps=%.1f  depth=%.0fms  yolo=%.0fms\n".format(s.fps, s.depthMs, s.yoloMs))
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
        socket?.shutdown()
        // CameraX unbinds with the lifecycle automatically; fully stop the pipeline
        // (close models, free the executor's work) only when the app is finishing.
        if (isFinishing) AppGraph.visionPipeline.stop()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SixthSenseScene"
    }
}
