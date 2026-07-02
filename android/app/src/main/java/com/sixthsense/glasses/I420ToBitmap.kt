/*
 * Adapted from Meta's Wearables Device Access Toolkit CameraAccess sample
 * (facebook/meta-wearables-dat-android, samples/CameraAccess). Original code
 * Copyright (c) Meta Platforms, Inc. and affiliates, under the license in that
 * repository's LICENSE file.
 *
 * Changes from the original: allocates a fresh Bitmap per call instead of
 * caching one. SixthSense hands each frame to an async inference executor, so
 * a shared cached bitmap would be overwritten mid-inference by the next frame;
 * the caller drops frames while inference is busy, keeping allocation rate at
 * the model rate (~1-5 fps), not the 24 fps stream rate.
 */
package com.sixthsense.glasses

import android.graphics.Bitmap
import java.nio.ByteBuffer

/** I420 (Y + quarter-res U + V planes) -> upright ARGB_8888 Bitmap, BT.709 limited range. */
internal object I420ToBitmap {

    private val lock = Any()
    private var pixels: IntArray = IntArray(0)
    private var yuvBytes: ByteArray = ByteArray(0)

    fun convert(yuvData: ByteBuffer, width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0 || width % 2 == 1 || height % 2 == 1) return null
        val frameSize = width * height
        val expectedSize = frameSize + (frameSize shr 1)
        if (yuvData.remaining() < expectedSize) return null

        synchronized(lock) {
            if (pixels.size < frameSize) pixels = IntArray(frameSize)
            if (yuvBytes.size < expectedSize) yuvBytes = ByteArray(expectedSize)

            val originalPosition = yuvData.position()
            yuvData.get(yuvBytes, 0, expectedSize)
            yuvData.position(originalPosition)

            convertI420ToArgb(yuvBytes, pixels, width, height)

            return try {
                Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            } catch (_: OutOfMemoryError) {
                null
            }
        }
    }

    // Fixed-point BT.709 limited-range YUV->RGB with branchless clamping.
    private fun convertI420ToArgb(yuvBytes: ByteArray, argbOut: IntArray, width: Int, height: Int) {
        val frameSize = width * height
        val uvPlaneSize = frameSize shr 2
        val uOffset = frameSize
        val vOffset = uOffset + uvPlaneSize

        val coeffVr = 1836 // 1.793 * 1024
        val coeffUg = 218  // 0.213 * 1024
        val coeffVg = 546  // 0.533 * 1024
        val coeffUb = 2163 // 2.112 * 1024

        val halfWidth = width shr 1
        var pixelIndex = 0

        for (row in 0 until height) {
            val uvRowOffset = (row shr 1) * halfWidth
            for (col in 0 until width) {
                val uvIndex = uvRowOffset + (col shr 1)

                val y = (yuvBytes[pixelIndex].toInt() and 0xFF) - 16
                val u = (yuvBytes[uOffset + uvIndex].toInt() and 0xFF) - 128
                val v = (yuvBytes[vOffset + uvIndex].toInt() and 0xFF) - 128

                val yScaled = (y * 1192) shr 10

                val r = yScaled + ((coeffVr * v) shr 10)
                val g = yScaled - ((coeffUg * u + coeffVg * v) shr 10)
                val b = yScaled + ((coeffUb * u) shr 10)

                val rClamped = (r and (r shr 31).inv()) or ((255 - r) shr 31 and 255) and 255
                val gClamped = (g and (g shr 31).inv()) or ((255 - g) shr 31 and 255) and 255
                val bClamped = (b and (b shr 31).inv()) or ((255 - b) shr 31 and 255) and 255

                argbOut[pixelIndex] =
                    0xFF000000.toInt() or (rClamped shl 16) or (gClamped shl 8) or bClamped
                pixelIndex++
            }
        }
    }
}
