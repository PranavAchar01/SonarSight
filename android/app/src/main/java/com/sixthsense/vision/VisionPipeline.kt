package com.sixthsense.vision

import android.util.Log
import com.sixthsense.core.DepthZones
import com.sixthsense.core.SceneBus
import com.sixthsense.core.SceneState

/**
 * Placeholder for the live on-device vision pipeline.
 *
 * In the starter repo this does NOT run any real model — it only documents the
 * intended flow and emits a trivial safe scene if asked. Do not mistake this for
 * a finished ExecuTorch integration.
 *
 * TODO(vision): wire up the real pipeline, in MVP-ladder order:
 *   - CameraX frame ingestion (ImageAnalysis use case, backpressure-aware).
 *   - Load ExecuTorch `.pte` models from app assets (Module.load).
 *   - Depth-Anything-V2 → inverse-depth map → left/center/right nearness zones.
 *   - YOLO (v8n/v11n) → labels/boxes → map box centers to zones; nearness from
 *     depth inside the box.
 *   - OCR (TrOCR or ML Kit) on demand only ("read that sign").
 *   - Qualcomm QNN backend for NPU acceleration; XNNPACK/CPU fallback per model.
 *   - Per-component latency + active-backend logging for the operator UI.
 */
class VisionPipeline(private val bus: SceneBus) {

    fun start() {
        Log.i(TAG, "VisionPipeline.start() — placeholder; live models not yet wired. " +
            "Use mock mode for now (see MockSceneProducer).")
        // Intentionally no-op in the starter. The real implementation will
        // bind CameraX and emit SceneState onto `bus` at frame rate.
    }

    fun stop() {
        Log.i(TAG, "VisionPipeline.stop()")
    }

    /** Trivial stand-in used only for smoke tests; not the real perception path. */
    fun emitSafePlaceholder() {
        bus.emit(
            SceneState(
                ts = System.currentTimeMillis(),
                depth = DepthZones(0f, 0f, 0f),
                objects = emptyList(),
                pathClear = true,
                conf = 1f,
            )
        )
    }

    companion object {
        private const val TAG = "SixthSenseScene"
    }
}
