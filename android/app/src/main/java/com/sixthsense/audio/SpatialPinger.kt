package com.sixthsense.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Synthesizes short stereo "ping" tones with constant-power panning, so a
 * collision candidate is *heard* in the direction it is seen. Plays through
 * the active audio route — with Ray-Ban Meta glasses connected that is their
 * open-ear speakers, which keeps ambient hearing free (critical for blind and
 * low-vision users).
 *
 * Direction  -> stereo pan (left/right gain, constant power)
 * Proximity  -> pitch (750->1100 Hz), loudness, and (via the controller) rate
 */
class SpatialPinger {

    /**
     * @param azimuth -1 = hard left, 0 = center, +1 = hard right
     * @param urgency 0..1, from just-entered-path to imminent
     */
    fun ping(azimuth: Float, urgency: Float) {
        try {
            val track = buildTrack(azimuth.coerceIn(-1f, 1f), urgency.coerceIn(0f, 1f))
            track.setNotificationMarkerPosition(track.bufferSizeInFrames)
            track.setPlaybackPositionUpdateListener(
                object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(t: AudioTrack) {
                        t.release()
                    }
                    override fun onPeriodicNotification(t: AudioTrack) = Unit
                }
            )
            track.play()
        } catch (e: Throwable) {
            Log.w(TAG, "ping failed: ${e.message}")
        }
    }

    private fun buildTrack(azimuth: Float, urgency: Float): AudioTrack {
        val freq = 750.0 + 350.0 * urgency
        val samples = SAMPLE_RATE * PING_MS / 1000
        // Constant-power pan: equal perceived loudness at any azimuth.
        val panAngle = (azimuth + 1f) * (PI.toFloat() / 4f)
        val volume = 0.35f + 0.65f * urgency
        val leftGain = cos(panAngle) * volume
        val rightGain = sin(panAngle) * volume

        val pcm = ShortArray(samples * 2)
        for (i in 0 until samples) {
            // Sine ping with a fast-attack / exponential-ish decay envelope.
            val t = i.toDouble() / SAMPLE_RATE
            val attack = min(1.0, i / (SAMPLE_RATE * 0.004))
            val decay = 1.0 - i.toDouble() / samples
            val s = sin(2.0 * PI * freq * t) * attack * decay * decay
            val v = (s * Short.MAX_VALUE).toInt()
            pcm[2 * i] = (v * leftGain).toInt().coerceIn(-32768, 32767).toShort()
            pcm[2 * i + 1] = (v * rightGain).toInt().coerceIn(-32768, 32767).toShort()
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        track.write(pcm, 0, pcm.size)
        return track
    }

    companion object {
        private const val TAG = "SixthSenseScene"
        private const val SAMPLE_RATE = 44100
        private const val PING_MS = 90
    }
}
