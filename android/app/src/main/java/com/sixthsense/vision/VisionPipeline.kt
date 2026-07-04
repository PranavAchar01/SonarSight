package com.sixthsense.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.sixthsense.BuildConfig
import com.sixthsense.core.BeltMapper
import com.sixthsense.core.DepthZones
import com.sixthsense.core.DetectedObj
import com.sixthsense.core.SceneBus
import com.sixthsense.core.SceneState
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Operator-facing status of the live on-device pipeline. */
data class VisionStatus(
    val running: Boolean = false,
    val depthLoaded: Boolean = false,
    val yoloLoaded: Boolean = false,
    val backend: String = BuildConfig.EXECUTORCH_BACKEND,
    val depthMs: Double = 0.0,
    val yoloMs: Double = 0.0,
    val fps: Double = 0.0,
    val detections: Int = 0,
    val cloudActive: Boolean = false,
    val note: String = "idle",
)

/**
 * The live, fully ON-DEVICE perception pipeline. CameraX frames are run through
 * ExecuTorch `.pte` models (Depth-Anything-V2 + YOLOv11n) entirely on the phone —
 * no network, airplane-mode capable — and the result is published as [SceneState]
 * on the [SceneBus] that the belt mapper, voice agent, dashboard, and phone-haptics
 * test mode all consume.
 *
 * Backend is baked into the `.pte` at export time (XNNPACK CPU now, Qualcomm
 * QNN/Hexagon NPU as a drop-in later — see docs); this code is identical for both.
 *
 * Degrades gracefully: with no `.pte` in assets it stays idle and logs that mock
 * mode should be used (it never emits a confidently-wrong scene). Depth gates
 * emission — protecting MVP rung 1 (depth -> belt); YOLO is additive.
 */
class VisionPipeline(
    private val context: Context,
    private val bus: SceneBus,
) {
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    // Metric depth runs concurrently with YOLO on the glasses feed (its ~1s pass
    // must not stall the ~5fps detection loop), so it owns its own executor.
    private val depthExecutor = Executors.newSingleThreadExecutor()
    private val depthConv = FrameToTensor(DEPTH_SIZE, Norm.IMAGENET)
    private val yoloConv = FrameToTensor(YOLO_SIZE, Norm.SCALE_0_1)

    @Volatile private var depthModule: EtModule? = null
    @Volatile private var yoloModule: EtModule? = null
    @Volatile private var running = false
    @Volatile private var externalBusy = false
    @Volatile private var depthBusy = false
    @Volatile private var lastDepthPassMs = 0L
    @Volatile private var modelsRequested = false
    private var lastZones = DepthZones(0f, 0f, 0f)
    private var lastDepthData: FloatArray? = null
    private var lastDw = 0
    private var lastDh = 0

    // Cloud detection tier (qwen-vl-max grounding on Qwen Cloud). Fresh results
    // outrank local YOLO; staleness flips back to on-device — automatic degradation.
    @Volatile private var cloudObjects: List<DetectedObj>? = null
    @Volatile private var cloudZones: DepthZones? = null
    @Volatile private var cloudTs = 0L
    @Volatile private var cloudRttMs = 0L

    /**
     * Called by CloudVisionClient with detections mapped to the SceneState
     * contract, plus optional VLM-judged surface proximity zones — the glasses
     * loop runs no depth model, so this is its only source of wall awareness.
     */
    fun submitCloudDetections(objects: List<DetectedObj>, zones: DepthZones?, rttMs: Long) {
        cloudObjects = objects
        cloudZones = zones
        cloudTs = System.currentTimeMillis()
        cloudRttMs = rttMs
    }

    fun noteCloudFailure() {
        cloudTs = 0L  // immediately stale -> local model takes over
    }
    @Volatile private var loggedYolo = false

    private var cameraProvider: ProcessCameraProvider? = null
    private var lastFrameNs = 0L
    private var emaFps = 0.0
    private var lastFrameEmitMs = 0L

    /** Dashboard frame sink: a downscaled base64 JPEG of the live camera + its rotation. */
    var onFrame: ((String, Int) -> Unit)? = null

    /** Only encode/stream the dashboard frame while a dashboard client is connected. */
    var shouldStreamFrame: () -> Boolean = { false }

    private val _status = MutableStateFlow(VisionStatus())
    val status: StateFlow<VisionStatus> = _status.asStateFlow()

    /**
     * Start live vision. Loads models off the main thread, then binds CameraX
     * [Preview] (optional, for the operator) + [ImageAnalysis] to [owner]'s lifecycle.
     */
    @Synchronized
    fun start(owner: LifecycleOwner, previewView: PreviewView?) {
        running = true
        _status.value = _status.value.copy(running = true, note = "loading models…")
        // Load once on the analysis thread; queued analyze() calls run after, so
        // models are ready by the first inference (no null-module race). bindCamera
        // unbinds first, so re-tapping Start (e.g. after rotation) safely rebinds.
        if (!modelsRequested) {
            modelsRequested = true
            analysisExecutor.execute { loadModels() }
        }
        bindCamera(owner, previewView)
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        modelsRequested = false
        ContextCompat.getMainExecutor(context).execute {
            runCatching { cameraProvider?.unbindAll() }
        }
        // Each module is closed on the executor that runs its forwards, so the
        // close is serialized strictly after any in-flight/queued inference — no
        // half-destroyed-module access is possible.
        analysisExecutor.execute {
            yoloModule?.close(); yoloModule = null
        }
        depthExecutor.execute {
            depthModule?.close(); depthModule = null
        }
        _status.value = VisionStatus(note = "stopped")
        Log.i(TAG, "VisionPipeline stopped.")
    }

    fun shutdown() {
        stop()
        analysisExecutor.shutdown()
        depthExecutor.shutdown()
        runCatching {
            if (!analysisExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                analysisExecutor.shutdownNow()
            }
            if (!depthExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                depthExecutor.shutdownNow()
            }
        }
    }

    private fun loadModels() {
        depthModule = EtModule.tryLoad(context, DEPTH_ASSETS)
        yoloModule = EtModule.tryLoad(context, YOLO_ASSETS)
        val backend = BuildConfig.EXECUTORCH_BACKEND
        val note = when {
            depthModule == null && yoloModule == null ->
                "No models in assets/models — add depth.pte / yolo.pte, or use Mock mode."
            depthModule == null ->
                "YOLO only ($backend) — detection drives haptics; nearness from box size."
            yoloModule == null ->
                "Depth only ($backend) — belt works; no object labels."
            else -> "Depth + YOLO loaded on $backend."
        }
        _status.value = _status.value.copy(
            depthLoaded = depthModule != null,
            yoloLoaded = yoloModule != null,
            note = note,
        )
        Log.i(TAG, note)
    }

    private fun bindCamera(owner: LifecycleOwner, previewView: PreviewView?) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                val preview = previewView?.let { pv ->
                    Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, ::analyze) }

                provider.unbindAll()
                val useCases = listOfNotNull(preview, analysis).toTypedArray()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, *useCases)
                Log.i(TAG, "CameraX bound (preview=${preview != null}).")
            } catch (e: Throwable) {
                Log.e(TAG, "CameraX bind failed: ${e.message}", e)
                _status.value = _status.value.copy(note = "camera bind failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * External-frame mode for the Ray-Ban Meta glasses feed: loads models without
     * binding CameraX. Frames arrive via [submitExternalFrame]; everything
     * downstream (SceneState, belt, dashboard, voice) is identical to camera mode.
     */
    @Synchronized
    fun startExternal() {
        running = true
        _status.value = _status.value.copy(running = true, note = "glasses feed: loading models…")
        if (!modelsRequested) {
            modelsRequested = true
            analysisExecutor.execute { loadModels() }
        }
    }

    /** True when a submitted frame would actually be processed (lets the source skip decode work). */
    fun canAcceptExternalFrame(): Boolean = running && !externalBusy

    /**
     * Feed one upright ARGB frame from the glasses. Keep-only-latest: while an
     * inference is in flight new frames are dropped, so the effective rate is the
     * model rate regardless of the 24fps stream.
     */
    fun submitExternalFrame(bitmap: Bitmap) {
        if (!running || externalBusy) return
        externalBusy = true
        maybeRunMetricDepth(bitmap)
        analysisExecutor.execute {
            try {
                maybeStreamExternalFrame(bitmap)
                // Depth runs concurrently on its own executor (see
                // maybeRunMetricDepth); this loop stays YOLO-only so boxes keep
                // their ~5fps cadence. Object nearness comes from box size.
                runModels(null, { yoloConv.toTensor(bitmap) })
            } catch (e: Throwable) {
                Log.w(TAG, "external frame error: ${e.message}")
            } finally {
                externalBusy = false
            }
        }
    }

    /**
     * Metric depth (Depth-Anything-V2-Metric-Indoor, 252x252, meters out) at its
     * own ~1Hz cadence, concurrent with YOLO. This is what catches the obstacles
     * detection can't: a featureless wall, a closed door, glass — geometry says
     * "surface at 0.5m" no matter what it looks like. Updates [lastZones], which
     * every subsequent SceneState emission picks up (pathClear, belt, pings).
     */
    private fun maybeRunMetricDepth(bitmap: Bitmap) {
        if (depthModule == null || depthBusy) return
        val now = System.currentTimeMillis()
        if (now - lastDepthPassMs < DEPTH_MIN_INTERVAL_MS) return
        depthBusy = true
        depthExecutor.execute {
            try {
                val depth = depthModule ?: return@execute
                val t0 = System.nanoTime()
                val out = depth.forward(depthConv.toTensor(bitmap))
                val ms = (System.nanoTime() - t0) / 1_000_000.0
                val (dw, dh) = depthDims(out)
                lastZones = DepthDecoder.toZones(out.data, dw, dh)
                _status.value = _status.value.copy(depthMs = ms)
            } catch (e: Throwable) {
                Log.w(TAG, "metric depth error: ${e.message}")
            } finally {
                lastDepthPassMs = System.currentTimeMillis()
                depthBusy = false
            }
        }
    }

    /** Runs on [analysisExecutor], never the main thread. */
    private fun analyze(image: ImageProxy) {
        try {
            // Stream the raw camera frame to the dashboard even before models load.
            maybeStreamFrame(image)
            runModels({ depthConv.toTensor(image) }, { yoloConv.toTensor(image) })
        } catch (e: Throwable) {
            Log.w(TAG, "analyze error: ${e.message}")
        } finally {
            image.close() // mandatory or KEEP_ONLY_LATEST stalls
        }
    }

    /**
     * Shared inference core; tensor conversion is deferred so each source pays only
     * for loaded models. A null [depthTensor] skips depth and reuses the cached
     * zones/depth map from the last depth pass (external-mode cadence).
     */
    private fun runModels(
        depthTensor: (() -> org.pytorch.executorch.Tensor)?,
        yoloTensor: () -> org.pytorch.executorch.Tensor,
    ) {
        val depth = if (depthTensor != null) depthModule else null
        val yolo = yoloModule
        if (depthModule == null && yolo == null) return  // nothing loaded -> emit nothing (safe)

        // Depth -> zones + a depth map for object nearness; cached between passes.
        var zones = lastZones
        var depthData: FloatArray? = lastDepthData
        var dw = lastDw; var dh = lastDh
        var depthMs = 0.0
        if (depth != null && depthTensor != null) {
            val t0 = System.nanoTime()
            val depthOut = depth.forward(depthTensor())
            depthMs = (System.nanoTime() - t0) / 1_000_000.0
            val dims = depthDims(depthOut)
            dw = dims.first; dh = dims.second
            depthData = depthOut.data
            zones = DepthDecoder.toZones(depthOut.data, dw, dh)
            lastZones = zones; lastDepthData = depthData; lastDw = dw; lastDh = dh
        }

        // Cloud tier first: a fresh qwen-vl-max grounding result beats the local
        // nano model. A VLM round trip is seconds, so "fresh" scales with the
        // measured RTT (a result is valid roughly until the next one lands —
        // single-flight means that's ~one RTT away). Stale/failed -> local resumes.
        val cloud = cloudObjects
        val freshWindow = (cloudRttMs * 2).coerceIn(CLOUD_FRESH_MIN_MS, CLOUD_FRESH_MAX_MS)
        val cloudFresh = cloud != null && System.currentTimeMillis() - cloudTs < freshWindow

        // YOLO (if present) -> objects. Nearness from depth when available, else
        // from box size — so object detection runs and drives haptics standalone.
        var objects: List<DetectedObj> = emptyList()
        var yoloMs = 0.0
        if (cloudFresh && cloud != null) {
            objects = cloud
            yoloMs = cloudRttMs.toDouble()
            // Merge VLM surface proximity with local metric depth conservatively:
            // whichever tier says a zone is nearer wins.
            cloudZones?.let { cz ->
                zones = DepthZones(
                    left = maxOf(zones.left, cz.left),
                    center = maxOf(zones.center, cz.center),
                    right = maxOf(zones.right, cz.right),
                    curbAhead = zones.curbAhead,
                    stepDown = zones.stepDown,
                )
            }
        } else if (yolo != null) {
            val t1 = System.nanoTime()
            val yoloOut = yolo.forward(yoloTensor())
            yoloMs = (System.nanoTime() - t1) / 1_000_000.0
            // One-time diagnostic to debug detection: confirms the output shape and
            // that scores look sane (if max≈0, the input scaling/model is wrong).
            if (!loggedYolo) {
                loggedYolo = true
                val expected = YoloDecoder.ATTRS * YoloDecoder.ANCHORS_640
                val maxScore = yoloOut.data.let { d ->
                    var m = 0f; val start = 4 * YoloDecoder.ANCHORS_640
                    var i = start; while (i < d.size) { if (d[i] > m) m = d[i]; i++ }; m
                }
                Log.i(TAG, "YOLO out size=${yoloOut.data.size} (expect $expected) maxScore=$maxScore")
            }
            val dets = YoloDecoder.decode(yoloOut.data, inputSize = YOLO_SIZE)
            objects = if (depthData != null)
                SceneAssembler.toDetectedObjects(dets, depthData, dw, dh, YOLO_SIZE)
            else
                SceneAssembler.toDetectedObjectsNoDepth(dets, YOLO_SIZE)
        }

        // Path is clear only if neither depth nor a centered near object blocks it.
        val centerObjNear = objects.any {
            it.zone == "center" && it.nearness >= BeltMapper.OBJECT_NEAR_THRESHOLD
        }
        val pathClear =
            !zones.curbAhead && zones.center < BeltMapper.NEAR_THRESHOLD && !centerObjNear

        val base = SceneState(
            ts = System.currentTimeMillis(),
            depth = zones,
            objects = objects,
            pathClear = pathClear,
            conf = LIVE_CONF,
        )
        bus.emit(base.copy(belt = BeltMapper.packetAsInts(base)))
        updateStatus(depthMs, yoloMs, objects.size, cloudFresh)
    }

    /** Throttled: encode the current RGBA frame to a small JPEG for the dashboard. */
    private fun maybeStreamFrame(image: ImageProxy) {
        val sink = onFrame ?: return
        if (!shouldStreamFrame()) return
        val now = System.currentTimeMillis()
        if (now - lastFrameEmitMs < FRAME_MIN_INTERVAL_MS) return
        lastFrameEmitMs = now
        val b64 = encodeJpegBase64(image) ?: return
        sink(b64, image.imageInfo.rotationDegrees)
    }

    /** [maybeStreamFrame] for the glasses feed; frames are already upright (rotation 0). */
    private fun maybeStreamExternalFrame(bmp: Bitmap) {
        val sink = onFrame ?: return
        if (!shouldStreamFrame()) return
        val now = System.currentTimeMillis()
        if (now - lastFrameEmitMs < FRAME_MIN_INTERVAL_MS) return
        lastFrameEmitMs = now
        val b64 = encodeBitmapJpegBase64(bmp) ?: return
        sink(b64, 0)
    }

    private fun encodeBitmapJpegBase64(src: Bitmap): String? = try {
        val scale = FRAME_WIDTH.toFloat() / src.width
        val scaled = Bitmap.createScaledBitmap(
            src, FRAME_WIDTH, (src.height * scale).roundToInt().coerceAtLeast(1), true
        )
        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, FRAME_QUALITY, baos)
        if (scaled !== src) scaled.recycle()
        Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    } catch (e: Throwable) {
        Log.w(TAG, "frame encode error: ${e.message}")
        null
    }

    /** RGBA_8888 ImageProxy -> downscaled JPEG -> base64 (reuses the pipeline's camera). */
    private fun encodeJpegBase64(image: ImageProxy): String? {
        return try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * image.width
            val bmpW = image.width + rowPadding / pixelStride
            val full = Bitmap.createBitmap(bmpW, image.height, Bitmap.Config.ARGB_8888)
            plane.buffer.rewind()
            full.copyPixelsFromBuffer(plane.buffer)
            val cropped = if (bmpW != image.width) {
                Bitmap.createBitmap(full, 0, 0, image.width, image.height).also { full.recycle() }
            } else full
            val scale = FRAME_WIDTH.toFloat() / image.width
            val scaled = Bitmap.createScaledBitmap(
                cropped, FRAME_WIDTH, (image.height * scale).roundToInt().coerceAtLeast(1), true
            )
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, FRAME_QUALITY, baos)
            if (scaled !== cropped) cropped.recycle()
            scaled.recycle()
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        } catch (e: Throwable) {
            Log.w(TAG, "frame encode error: ${e.message}")
            null
        }
    }

    private fun updateStatus(
        depthMs: Double,
        yoloMs: Double,
        detections: Int,
        cloudActive: Boolean = false,
    ) {
        val now = System.nanoTime()
        if (lastFrameNs != 0L) {
            val inst = 1_000_000_000.0 / (now - lastFrameNs).coerceAtLeast(1)
            emaFps = if (emaFps == 0.0) inst else 0.8 * emaFps + 0.2 * inst
        }
        lastFrameNs = now
        _status.value = _status.value.copy(
            depthMs = depthMs,
            yoloMs = yoloMs,
            fps = (emaFps * 10).roundToInt() / 10.0,
            detections = detections,
            cloudActive = cloudActive,
        )
    }

    /**
     * Derive (width, height) of the depth map from the output shape's last two dims.
     * For a [..,H,W] tensor that is (W, H) = (s[last], s[last-1]); the depth map is
     * square (518x518) so order only matters for indexing, which uses row*w+col.
     */
    private fun depthDims(out: EtModule.Out): Pair<Int, Int> {
        val s = out.shape
        return if (s.size >= 2) s[s.size - 1].toInt() to s[s.size - 2].toInt()
        else {
            val side = sqrt(out.data.size.toDouble()).roundToInt()
            side to side
        }
    }

    companion object {
        private const val TAG = "SixthSenseScene"
        private const val DEPTH_SIZE = 252
        private const val DEPTH_MIN_INTERVAL_MS = 900L
        private const val YOLO_SIZE = 640
        private const val LIVE_CONF = 0.85f
        private const val FRAME_WIDTH = 480
        private const val FRAME_QUALITY = 50
        private const val FRAME_MIN_INTERVAL_MS = 125L   // ~8 fps to the dashboard
        private const val CLOUD_FRESH_MIN_MS = 900L      // cloud validity floor (fast RTT)
        private const val CLOUD_FRESH_MAX_MS = 8000L     // cap so stale boxes can't linger

        // Candidate asset names (metric depth only — the relative model's per-frame
        // normalization can't register a wall; see scripts/export_depth_metric.py).
        private val DEPTH_ASSETS = listOf(
            "models/depth_metric.pte",
        )
        private val YOLO_ASSETS = listOf(
            "models/yolo.pte",
            "models/yolo11n.pte",
            "models/yolov11n.pte",
        )
    }
}
