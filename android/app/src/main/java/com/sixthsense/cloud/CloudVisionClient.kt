package com.sixthsense.cloud

import android.graphics.Bitmap
import android.util.Log
import com.google.gson.Gson
import com.sixthsense.BuildConfig
import com.sixthsense.core.BoundingBox
import com.sixthsense.core.DetectedObj
import com.sixthsense.vision.VisionPipeline
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Cloud detection tier: ships glasses frames to the GPU endpoint (YOLO11x,
 * see cloud/server.py) and feeds the detections back into [VisionPipeline],
 * where fresh cloud results take precedence over the on-device int8 model.
 *
 * Single-flight: one request in the air at a time; frames arriving while a
 * request is pending are dropped, so the effective rate self-tunes to the
 * network + GPU round trip. Failures flip the pipeline back to the local
 * model automatically (freshness window) — that IS the degradation story.
 */
class CloudVisionClient(private val pipeline: VisionPipeline) {

    private val executor = Executors.newSingleThreadExecutor()
    private val gson = Gson()

    @Volatile
    private var inFlight = false

    @Volatile
    var enabled = false

    private val configured: Boolean
        get() = BuildConfig.CLOUD_VISION_URL.isNotBlank()

    fun submit(bitmap: Bitmap) {
        if (!enabled || !configured || inFlight) return
        inFlight = true
        executor.execute {
            try {
                val t0 = System.currentTimeMillis()
                val response = post(encodeJpeg(bitmap))
                val rtt = System.currentTimeMillis() - t0
                val parsed = gson.fromJson(response, CloudResult::class.java)
                pipeline.submitCloudDetections(parsed.dets.map { it.toDetectedObj() }, rtt)
            } catch (e: Throwable) {
                Log.w(TAG, "cloud detect failed: ${e.message}")
                pipeline.noteCloudFailure()
            } finally {
                inFlight = false
            }
        }
    }

    private fun encodeJpeg(src: Bitmap): ByteArray {
        val scale = UPLOAD_WIDTH.toFloat() / src.width
        val scaled = Bitmap.createScaledBitmap(
            src, UPLOAD_WIDTH, (src.height * scale).roundToInt().coerceAtLeast(1), true
        )
        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, UPLOAD_QUALITY, baos)
        if (scaled !== src) scaled.recycle()
        return baos.toByteArray()
    }

    private fun post(jpeg: ByteArray): String {
        val conn = URL("${BuildConfig.CLOUD_VISION_URL}/detect").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 3000
            conn.readTimeout = 4000
            conn.setRequestProperty("Content-Type", "image/jpeg")
            if (BuildConfig.CLOUD_VISION_KEY.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.CLOUD_VISION_KEY}")
            }
            conn.outputStream.use { it.write(jpeg) }
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private data class CloudResult(val ms: Double = 0.0, val dets: List<CloudDet> = emptyList())

    private data class CloudDet(
        val label: String = "object",
        val conf: Float = 0f,
        val x1: Float = 0f,
        val y1: Float = 0f,
        val x2: Float = 0f,
        val y2: Float = 0f,
    ) {
        /** Same zone/nearness math as the on-device path (Decoders.kt). */
        fun toDetectedObj(): DetectedObj {
            val cx = (x1 + x2) * 0.5f
            val area = (x2 - x1).coerceAtLeast(0f) * (y2 - y1).coerceAtLeast(0f)
            return DetectedObj(
                label = label,
                zone = when {
                    cx < 1f / 3f -> "left"
                    cx < 2f / 3f -> "center"
                    else -> "right"
                },
                nearness = (area * AREA_GAIN).coerceIn(0f, 1f),
                conf = conf,
                box = BoundingBox(x1, y1, x2, y2),
            )
        }
    }

    companion object {
        private const val TAG = "SixthSenseScene"
        private const val UPLOAD_WIDTH = 640
        private const val UPLOAD_QUALITY = 65
        private const val AREA_GAIN = 3.0f  // mirrors SceneAssembler.AREA_GAIN
    }
}
