package com.sixthsense.cloud

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sixthsense.BuildConfig
import com.sixthsense.core.BeltMapper
import com.sixthsense.core.BoundingBox
import com.sixthsense.core.DepthZones
import com.sixthsense.core.DetectedObj
import com.sixthsense.vision.VisionPipeline
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Cloud detection tier on Qwen Cloud (Model Studio / DashScope): glasses frames
 * go to qwen-vl-max, whose Qwen2.5-VL grounding outputs labeled bounding boxes
 * for everything in the scene — open-vocabulary, far beyond the 80 COCO classes
 * the on-device nano model knows. The same call also scores per-zone surface
 * proximity (left/center/right), which catches what NO box detector can: a
 * featureless painted wall, glass, a pillar filling the frame. Close zones with
 * no covering detection become synthetic "obstacle" objects, so the collision
 * pinger fires toward them exactly like toward a detected object. Detections
 * feed back into [VisionPipeline], where fresh cloud results take precedence
 * over the local int8 model.
 *
 * Single-flight: one request in the air at a time; frames arriving while a
 * request is pending are dropped, so the effective rate self-tunes to the
 * network + model round trip (a VLM pass is seconds, not milliseconds — the
 * pipeline's freshness window adapts to the measured RTT). Failures flip the
 * pipeline back to the local model automatically — that IS the degradation story.
 */
class CloudVisionClient(private val pipeline: VisionPipeline) {

    private val executor = Executors.newSingleThreadExecutor()
    private val gson = Gson()

    @Volatile
    private var inFlight = false

    @Volatile
    var enabled = false

    // Voice-added hazard watchlist ("watch out for bikes and dogs") — injected
    // into the grounding prompt so the VLM prioritizes them.
    @Volatile
    private var watchlist: List<String> = emptyList()

    /** Fires (off-main) when the latest grounding round saw a person in frame. */
    @Volatile
    var onPersonSeen: (() -> Unit)? = null

    fun addWatch(labels: List<String>) {
        watchlist = (watchlist + labels.map { it.trim().lowercase() })
            .filter { it.isNotBlank() }.distinct().takeLast(8)
    }

    val configured: Boolean
        get() = BuildConfig.QWEN_API_KEY.isNotBlank()

    fun submit(bitmap: Bitmap) {
        if (!enabled || !configured || inFlight) return
        inFlight = true
        // Scale on the caller's thread while the bitmap is guaranteed alive; the
        // glasses source recycles/reuses frames once onFrame returns.
        val upload = scaleForUpload(bitmap)
        executor.execute {
            try {
                val t0 = System.currentTimeMillis()
                val answer = ground(upload)
                val rtt = System.currentTimeMillis() - t0
                val (objects, zones) = parseScene(answer, upload.width, upload.height)
                pipeline.submitCloudDetections(withSurfaceObstacles(objects, zones), zones, rtt)
                if (objects.any { PERSON_WORDS.any(it.label::contains) }) onPersonSeen?.invoke()
            } catch (e: Throwable) {
                Log.w(TAG, "cloud detect failed: ${e.message}")
                pipeline.noteCloudFailure()
            } finally {
                upload.recycle()
                inFlight = false
            }
        }
    }

    private fun scaleForUpload(src: Bitmap): Bitmap {
        val scale = UPLOAD_WIDTH.toFloat() / src.width
        return Bitmap.createScaledBitmap(
            src, UPLOAD_WIDTH, (src.height * scale).roundToInt().coerceAtLeast(1), true
        )
    }

    /** One qwen-vl-max grounding call: image in, JSON detection list out. */
    private fun ground(upload: Bitmap): String {
        val baos = ByteArrayOutputStream()
        upload.compress(Bitmap.CompressFormat.JPEG, UPLOAD_QUALITY, baos)
        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

        val watch = watchlist
        val prompt = if (watch.isEmpty()) GROUNDING_PROMPT
        else GROUNDING_PROMPT + " The user especially wants warnings about: " +
            watch.joinToString(", ") + " — always include these if visible."
        val userContent = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "image_url")
                add("image_url", JsonObject().apply {
                    addProperty("url", "data:image/jpeg;base64,$b64")
                })
            })
            add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", prompt)
            })
        }
        val body = JsonObject().apply {
            addProperty("model", VLM_MODEL)
            addProperty("max_tokens", 600)
            add("messages", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    add("content", userContent)
                })
            })
        }

        val conn = URL("$BASE_URL/chat/completions").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 30000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.QWEN_API_KEY}")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode != 200) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(200)
                error("HTTP ${conn.responseCode}: $err")
            }
            val json = conn.inputStream.bufferedReader().use { it.readText() }
            gson.fromJson(json, JsonObject::class.java)
                .getAsJsonArray("choices")[0].asJsonObject
                .getAsJsonObject("message")
                .get("content").asString
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Parse the model's JSON into [DetectedObj]s + per-zone surface proximity.
     * Qwen2.5-VL grounding emits pixel coordinates of the input image, but hedge
     * against the two other conventions it has used (normalized 0–1, and the
     * legacy 0–1000 scale) by checking the response's coordinate range.
     */
    private fun parseScene(
        answer: String,
        imgW: Int,
        imgH: Int,
    ): Pair<List<DetectedObj>, DepthZones?> {
        val start = answer.indexOf('{')
        val end = answer.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList<DetectedObj>() to null
        val root = gson.fromJson(answer.substring(start, end + 1), JsonObject::class.java)

        val zones = runCatching {
            val z = root.getAsJsonObject("zones")
            DepthZones(
                left = z.get("left").asFloat.coerceIn(0f, 1f),
                center = z.get("center").asFloat.coerceIn(0f, 1f),
                right = z.get("right").asFloat.coerceIn(0f, 1f),
            )
        }.getOrNull()

        val arr = root.getAsJsonArray("objects") ?: return emptyList<DetectedObj>() to zones
        return parseDetections(arr, imgW, imgH) to zones
    }

    private fun parseDetections(arr: JsonArray, imgW: Int, imgH: Int): List<DetectedObj> {
        data class RawDet(val label: String, val c: FloatArray)
        val raw = arr.mapNotNull { el ->
            runCatching {
                val o = el.asJsonObject
                val bbox = o.getAsJsonArray("bbox_2d")
                RawDet(
                    label = o.get("label").asString.lowercase(),
                    c = FloatArray(4) { i -> bbox[i].asFloat },
                )
            }.getOrNull()
        }
        if (raw.isEmpty()) return emptyList()

        val maxCoord = raw.maxOf { d -> d.c.max() }
        val (sx, sy) = when {
            maxCoord <= 1.5f -> 1f to 1f                                  // already normalized
            maxCoord > maxOf(imgW, imgH) * 1.2f -> 1000f to 1000f         // legacy 0–1000 scale
            else -> imgW.toFloat() to imgH.toFloat()                      // input-image pixels
        }

        return raw.take(MAX_DETECTIONS).map { d ->
            val x1 = (d.c[0] / sx).coerceIn(0f, 1f)
            val y1 = (d.c[1] / sy).coerceIn(0f, 1f)
            val x2 = (d.c[2] / sx).coerceIn(0f, 1f)
            val y2 = (d.c[3] / sy).coerceIn(0f, 1f)
            val cx = (x1 + x2) * 0.5f
            val area = (x2 - x1).coerceAtLeast(0f) * (y2 - y1).coerceAtLeast(0f)
            DetectedObj(
                label = d.label,
                zone = when {
                    cx < 1f / 3f -> "left"
                    cx < 2f / 3f -> "center"
                    else -> "right"
                },
                nearness = (area * AREA_GAIN).coerceIn(0f, 1f),
                conf = CLOUD_CONF,
                box = BoundingBox(x1, y1, x2, y2),
            )
        }
    }

    /**
     * A zone the VLM scored as near but that no detection covers is exactly the
     * "blank wall" case — surface proximity without an object to box. Represent
     * it as a synthetic obstacle spanning that third of the view so the collision
     * pinger, overlay, and belt treat it like any detected object.
     */
    private fun withSurfaceObstacles(
        objects: List<DetectedObj>,
        zones: DepthZones?,
    ): List<DetectedObj> {
        if (zones == null) return objects
        val synthetic = listOf(
            Triple("left", zones.left, 0f to 1f / 3f),
            Triple("center", zones.center, 1f / 3f to 2f / 3f),
            Triple("right", zones.right, 2f / 3f to 1f),
        ).mapNotNull { (zone, proximity, xr) ->
            if (proximity < BeltMapper.OBJECT_NEAR_THRESHOLD) return@mapNotNull null
            val covered = objects.any { it.zone == zone && it.nearness >= proximity - 0.1f }
            if (covered) return@mapNotNull null
            DetectedObj(
                label = "obstacle",
                zone = zone,
                nearness = proximity,
                conf = CLOUD_CONF,
                box = BoundingBox(xr.first, 0.1f, xr.second, 1f),
            )
        }
        return objects + synthetic
    }

    companion object {
        private const val TAG = "SixthSenseScene"
        private const val BASE_URL = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
        private const val VLM_MODEL = "qwen-vl-max"
        private const val UPLOAD_WIDTH = 640
        private const val UPLOAD_QUALITY = 65
        private const val AREA_GAIN = 3.0f  // mirrors SceneAssembler.AREA_GAIN
        private const val MAX_DETECTIONS = 25
        private val PERSON_WORDS = listOf("person", "man", "woman", "people", "pedestrian")
        // Grounding gives no per-box score; the VLM only names what it actually
        // sees, so treat every box as high-confidence downstream.
        private const val CLOUD_CONF = 0.9f
        private const val GROUNDING_PROMPT =
            "You are the vision system of navigation glasses for a blind person. " +
                "Analyze this image and do two things.\n" +
                "1. Detect every distinct visible object: people, vehicles, furniture, " +
                "doors, stairs, poles, animals, signs — anything a walking person could " +
                "collide with or care about.\n" +
                "2. Judge obstacle proximity for the LEFT, CENTER and RIGHT thirds of the " +
                "view. Consider ALL physical surfaces, including featureless ones a " +
                "detector would miss: blank or painted walls, glass, fences, pillars, " +
                "closed doors. IMPORTANT: if a third of the view is filled by a uniform, " +
                "textureless expanse with no visible floor, horizon, or scene depth, that " +
                "is almost always a wall or large surface at very close range — score it " +
                "0.9-1.0, never 0. Score each third: 1.0 = touching or within one step, " +
                "0.7 = about 2-3 steps away, 0.5 = about 4-5 steps, 0.2 = several meters " +
                "of clearance, 0.0 = confirmed open walkable space.\n" +
                "Reply with ONLY minified JSON, no prose and no markdown fences, exactly: " +
                "{\"objects\":[{\"label\":\"<short name>\",\"bbox_2d\":[x1,y1,x2,y2]}]," +
                "\"zones\":{\"left\":0.0,\"center\":0.0,\"right\":0.0}} " +
                "bbox_2d uses pixel coordinates of this image."
    }
}
