package com.sixthsense.cloud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sixthsense.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * "My People" — consent-based person recognition for the wearer's own circle.
 *
 * Enrollment is explicit ("remember this face as Sarah" while the person is in
 * view, with their consent); the reference photo lives ONLY in app-private
 * storage on the phone. At runtime, when the vision tier reports a person in
 * frame, the current view plus the enrolled references go to qwen-vl-max with
 * a match-or-none prompt, and a match is announced once per person per
 * cooldown ("Sarah, on your left"). One tap of clear() forgets everyone.
 *
 * This is deliberately NOT open-set identification: it can only name people
 * the wearer enrolled, and reference photos never leave the device except to
 * the DashScope call that does the match.
 */
class MyPeopleClient(context: Context) {

    private val dir = File(context.filesDir, "people").apply { mkdirs() }
    private val executor = Executors.newSingleThreadExecutor()
    private val gson = Gson()

    @Volatile private var inFlight = false
    @Volatile private var lastIdentifyMs = 0L
    private val lastAnnounced = HashMap<String, Long>()

    /** Spoken announcement sink ("Sarah, on your left, a few steps away"). */
    @Volatile var onAnnounce: ((String) -> Unit)? = null

    fun names(): List<String> =
        dir.listFiles { f -> f.extension == "jpg" }?.map { it.nameWithoutExtension }.orEmpty()

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
        lastAnnounced.clear()
    }

    /** Save the current glasses frame as [name]'s reference photo. */
    fun enroll(frame: Bitmap?, name: String): Boolean {
        if (frame == null) return false
        val safe = name.trim().replace(Regex("[^A-Za-z0-9 _-]"), "").ifBlank { return false }
        val scaled = scaleTo(frame, 640)
        File(dir, "$safe.jpg").outputStream().use {
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, it)
        }
        if (scaled !== frame) scaled.recycle()
        Log.i(TAG, "enrolled '$safe' (${names().size} people)")
        return true
    }

    /**
     * Called when a person is detected in frame. Single-flight + a global
     * cooldown keeps this to at most one qwen-vl-max match per [IDENTIFY_GAP_MS].
     */
    fun maybeIdentify(frame: Bitmap?) {
        if (frame == null || inFlight || BuildConfig.QWEN_API_KEY.isBlank()) return
        val people = names()
        if (people.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastIdentifyMs < IDENTIFY_GAP_MS) return
        lastIdentifyMs = now
        inFlight = true
        val live = scaleTo(frame, 640)
        executor.execute {
            try {
                val (match, zone) = match(people.take(MAX_REFS), live)
                if (match != null && shouldAnnounce(match)) {
                    val where = when (zone) {
                        "left" -> "on your left"
                        "right" -> "on your right"
                        else -> "ahead of you"
                    }
                    onAnnounce?.invoke("$match, $where.")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "identify failed: ${e.message}")
            } finally {
                live.recycle()
                inFlight = false
            }
        }
    }

    private fun shouldAnnounce(name: String): Boolean {
        val now = System.currentTimeMillis()
        val last = lastAnnounced[name] ?: 0L
        if (now - last < ANNOUNCE_GAP_MS) return false
        lastAnnounced[name] = now
        return true
    }

    private fun match(people: List<String>, live: Bitmap): Pair<String?, String> {
        val content = JsonArray()
        for (n in people) {
            val ref = BitmapFactory.decodeFile(File(dir, "$n.jpg").absolutePath) ?: continue
            content.add(imagePart(ref))
            ref.recycle()
        }
        content.add(imagePart(live))
        content.add(JsonObject().apply {
            addProperty("type", "text")
            addProperty("text",
                "The first ${people.size} image(s) are enrolled people, in this order: " +
                    people.joinToString(", ") + ". The LAST image is the live camera view. " +
                    "If one of the enrolled people is clearly visible in the live view, " +
                    "reply ONLY {\"match\":\"<their name>\",\"zone\":\"left|center|right\"}. " +
                    "If you are not confident, reply ONLY {\"match\":\"none\"}.")
        })
        val body = JsonObject().apply {
            addProperty("model", VLM_MODEL)
            addProperty("max_tokens", 60)
            add("messages", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    add("content", content)
                })
            })
        }
        val conn = URL("$BASE_URL/chat/completions").openConnection() as HttpURLConnection
        val answer = try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 30000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.QWEN_API_KEY}")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            gson.fromJson(
                conn.inputStream.bufferedReader().use { it.readText() }, JsonObject::class.java
            ).getAsJsonArray("choices")[0].asJsonObject
                .getAsJsonObject("message").get("content").asString
        } finally {
            conn.disconnect()
        }
        val s = answer.indexOf('{'); val e = answer.lastIndexOf('}')
        if (s < 0 || e <= s) return null to "center"
        val obj = gson.fromJson(answer.substring(s, e + 1), JsonObject::class.java)
        val m = obj.get("match")?.asString ?: "none"
        val matched = people.firstOrNull { it.equals(m, ignoreCase = true) }
        return matched to (obj.get("zone")?.asString ?: "center")
    }

    private fun imagePart(bmp: Bitmap): JsonObject {
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, baos)
        return JsonObject().apply {
            addProperty("type", "image_url")
            add("image_url", JsonObject().apply {
                addProperty("url", "data:image/jpeg;base64," +
                    Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP))
            })
        }
    }

    private fun scaleTo(src: Bitmap, width: Int): Bitmap {
        if (src.width <= width) return src
        val scale = width.toFloat() / src.width
        return Bitmap.createScaledBitmap(
            src, width, (src.height * scale).roundToInt().coerceAtLeast(1), true)
    }

    companion object {
        private const val TAG = "SixthSenseScene"
        private const val BASE_URL = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
        private const val VLM_MODEL = "qwen-vl-max"
        private const val MAX_REFS = 4
        private const val IDENTIFY_GAP_MS = 20_000L
        private const val ANNOUNCE_GAP_MS = 60_000L
    }
}
