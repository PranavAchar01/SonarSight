package com.sixthsense.cloud

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.sixthsense.BuildConfig
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * "Ask about my surroundings" voice loop against the GPU endpoint:
 * WAV -> /transcribe (Whisper large-v3) -> question + current glasses frame
 * -> /describe (Qwen2.5-VL, extreme-detail prompt) -> answer text.
 *
 * The caller speaks the answer via TTS, which routes to the glasses' open-ear
 * speakers over Bluetooth.
 */
class CloudAskClient {

    private val executor = Executors.newSingleThreadExecutor()
    private val gson = Gson()

    fun ask(
        wav: ByteArray,
        frame: Bitmap?,
        onTranscript: (String) -> Unit,
        onAnswer: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (BuildConfig.CLOUD_VISION_URL.isBlank()) {
            onError("No cloud endpoint configured.")
            return
        }
        executor.execute {
            try {
                val tJson = post("/transcribe", wav, "audio/wav")
                val question = gson.fromJson(tJson, JsonObject::class.java)
                    .get("text")?.asString.orEmpty().trim()
                if (question.isBlank()) {
                    onError("Didn't catch that — try again closer to the mic.")
                    return@execute
                }
                onTranscript(question)

                if (frame == null) {
                    onError("No glasses frame yet — start glasses vision first.")
                    return@execute
                }
                val payload = JsonObject().apply {
                    addProperty("question", question)
                    addProperty("image_b64", frameB64(frame))
                }
                val dJson = post("/describe", payload.toString().toByteArray(), "application/json")
                val parsed = gson.fromJson(dJson, JsonObject::class.java)
                val answer = parsed.get("answer")?.asString.orEmpty().trim()
                if (answer.isBlank()) {
                    onError(parsed.get("error")?.asString ?: "Empty answer from the scene model.")
                } else {
                    onAnswer(answer)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "ask failed: ${e.message}")
                onError("Cloud ask failed: ${e.message}")
            }
        }
    }

    private fun frameB64(src: Bitmap): String {
        val scale = IMG_WIDTH.toFloat() / src.width
        val scaled = Bitmap.createScaledBitmap(
            src, IMG_WIDTH, (src.height * scale).toInt().coerceAtLeast(1), true
        )
        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, IMG_QUALITY, baos)
        if (scaled !== src) scaled.recycle()
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    private fun post(path: String, body: ByteArray, contentType: String): String {
        val conn = URL("${BuildConfig.CLOUD_VISION_URL}$path").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 45000  // VLM generation takes seconds
            conn.setRequestProperty("Content-Type", contentType)
            if (BuildConfig.CLOUD_VISION_KEY.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.CLOUD_VISION_KEY}")
            }
            conn.outputStream.use { it.write(body) }
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "SixthSenseScene"
        private const val IMG_WIDTH = 960   // richer frame for the VLM than for detection
        private const val IMG_QUALITY = 80
    }
}
