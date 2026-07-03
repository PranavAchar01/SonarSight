package com.sixthsense.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Push-to-talk recorder: 16 kHz mono PCM16 -> WAV bytes (what Whisper wants).
 * Caller is responsible for the RECORD_AUDIO runtime permission.
 */
class VoiceRecorder {

    private var record: AudioRecord? = null
    private var buffer: ByteArrayOutputStream? = null
    private var thread: Thread? = null

    @Volatile
    private var recording = false

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (recording) return true
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, CHANNEL, ENCODING,
            maxOf(minBuf, SAMPLE_RATE),  // >= 0.5s of headroom
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord init failed")
            rec.release()
            return false
        }
        record = rec
        buffer = ByteArrayOutputStream()
        recording = true
        rec.startRecording()
        thread = Thread {
            val chunk = ByteArray(4096)
            while (recording) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n > 0) buffer?.write(chunk, 0, n)
            }
        }.also { it.start() }
        return true
    }

    /** Stop and return a complete WAV file (or null if nothing was captured). */
    fun stop(): ByteArray? {
        if (!recording) return null
        recording = false
        thread?.join(500)
        thread = null
        record?.let { runCatching { it.stop() }; it.release() }
        record = null
        val pcm = buffer?.toByteArray() ?: return null
        buffer = null
        if (pcm.size < SAMPLE_RATE / 2) return null  // <0.25s: accidental tap
        return wavOf(pcm)
    }

    private fun wavOf(pcm: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        val byteRate = SAMPLE_RATE * 2
        header.put("RIFF".toByteArray())
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)               // PCM chunk size
        header.putShort(1)              // PCM format
        header.putShort(1)              // mono
        header.putInt(SAMPLE_RATE)
        header.putInt(byteRate)
        header.putShort(2)              // block align
        header.putShort(16)             // bits per sample
        header.put("data".toByteArray())
        header.putInt(pcm.size)
        return header.array() + pcm
    }

    companion object {
        private const val TAG = "SixthSenseMCP"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
