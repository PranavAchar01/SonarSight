package com.sixthsense.cloud

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sixthsense.BuildConfig
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * "Ask about my surroundings" voice loop on Qwen Cloud (Model Studio /
 * DashScope, OpenAI-compatible API):
 *
 *   WAV -> qwen3-asr-flash (chat completions with input_audio) -> question
 *   question + current glasses frame -> qwen-vl-max -> extreme-detail answer
 *
 * The caller speaks the answer via TTS, which routes to the glasses' open-ear
 * speakers over Bluetooth. Verified against dashscope-intl on 2026-07-02.
 */
class CloudAskClient {

    private val executor = Executors.newSingleThreadExecutor()
    private val gson = Gson()

    private val configured: Boolean
        get() = BuildConfig.QWEN_API_KEY.isNotBlank()

    fun ask(
        wav: ByteArray,
        frame: Bitmap?,
        onTranscript: (String) -> Unit,
        onAnswer: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!configured) {
            onError("No Qwen API key configured (qwen_api_key in local.properties).")
            return
        }
        executor.execute {
            try {
                val question = transcribe(wav)
                if (question.isBlank()) {
                    onError("Didn't catch that — try again closer to the mic.")
                    return@execute
                }
                onTranscript(question)

                if (frame == null) {
                    onError("No glasses frame yet — start glasses vision first.")
                    return@execute
                }
                val answer = describe(question, frame)
                if (answer.isBlank()) onError("Empty answer from the scene model.")
                else onAnswer(answer)
            } catch (e: Throwable) {
                Log.w(TAG, "ask failed: ${e.message}")
                onError("Cloud ask failed: ${e.message}")
            }
        }
    }

    private fun transcribe(wav: ByteArray): String {
        val audio = JsonObject().apply {
            addProperty("data", "data:audio/wav;base64," +
                Base64.encodeToString(wav, Base64.NO_WRAP))
            addProperty("format", "wav")
        }
        val content = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "input_audio")
                add("input_audio", audio)
            })
        }
        val body = JsonObject().apply {
            addProperty("model", ASR_MODEL)
            add("messages", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    add("content", content)
                })
            })
        }
        return chatCompletion(body)
    }

    private fun describe(question: String, frame: Bitmap): String {
        val userContent = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "image_url")
                add("image_url", JsonObject().apply {
                    addProperty("url", "data:image/jpeg;base64,${frameB64(frame)}")
                })
            })
            add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", question)
            })
        }
        val body = JsonObject().apply {
            addProperty("model", VLM_MODEL)
            addProperty("max_tokens", 350)
            add("messages", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "system")
                    addProperty("content", SYSTEM_PROMPT)
                })
                add(JsonObject().apply {
                    addProperty("role", "user")
                    add("content", userContent)
                })
            })
        }
        return chatCompletion(body)
    }

    private fun chatCompletion(body: JsonObject): String {
        val conn = URL("$BASE_URL/chat/completions").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 60000
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
                .get("content").asString.trim()
        } finally {
            conn.disconnect()
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

    companion object {
        private const val TAG = "SixthSenseScene"
        private const val BASE_URL = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
        private const val ASR_MODEL = "qwen3-asr-flash"
        private const val VLM_MODEL = "qwen-vl-max"
        private const val IMG_WIDTH = 960
        private const val IMG_QUALITY = 80
        private const val SYSTEM_PROMPT =
            "You are SonarSight, the eyes of a blind or low-vision person wearing camera " +
                "glasses. The image is exactly what is in front of them right now. Answer " +
                "their question about their surroundings with extreme, concrete detail: " +
                "name every relevant object, where it is (left/center/right, near/far, " +
                "roughly how many steps away), colors, text you can read, people and what " +
                "they are doing, and any hazards (steps, curbs, obstacles, moving things) " +
                "FIRST. Be direct and specific; never say you cannot see. Speak in second " +
                "person ('to your left...')."
    }
}
