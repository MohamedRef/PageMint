package com.mohamedref.pagemint.cv

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Thin Android-to-Rust bridge. Perspective correction and document enhancement
 * are implemented by pagemint-core, not the Android UI layer.
 */
object RustProcessor {
    init { System.loadLibrary("pagemint_core") }

    private external fun nativeRectify(
        rgba: ByteArray, width: Int, height: Int,
        corners: FloatArray, paper: Int, safetyBorder: Boolean,
    ): ByteArray?

    private external fun nativeEnhance(rgba: ByteArray, width: Int, height: Int): ByteArray?

    fun rectify(input: Bitmap, corners: List<Corner>, format: PageFormat, safetyBorder: Boolean): Bitmap {
        require(corners.size == 4)
        val quad = FloatArray(8) { index ->
            if (index % 2 == 0) corners[index / 2].x else corners[index / 2].y
        }
        return unpack(nativeRectify(pack(input), input.width, input.height, quad, format.ordinal, safetyBorder)
            ?: error("Rust perspective correction failed; adjust document corners"))
    }

    fun proEnhance(input: Bitmap): Bitmap = unpack(
        nativeEnhance(pack(input), input.width, input.height)
            ?: error("Rust enhancement failed")
    )

    private fun pack(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val argb = IntArray(Math.multiplyExact(width, height))
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)
        val bytes = ByteArray(Math.multiplyExact(argb.size, 4))
        var cursor = 0
        for (color in argb) {
            bytes[cursor++] = ((color ushr 16) and 255).toByte()
            bytes[cursor++] = ((color ushr 8) and 255).toByte()
            bytes[cursor++] = (color and 255).toByte()
            bytes[cursor++] = ((color ushr 24) and 255).toByte()
        }
        return bytes
    }

    private fun unpack(bytes: ByteArray): Bitmap {
        require(bytes.size >= 8)
        val dimensions = ByteBuffer.wrap(bytes, 0, 8).order(ByteOrder.LITTLE_ENDIAN)
        val width = dimensions.int
        val height = dimensions.int
        require(width in 1..4800 && height in 1..4800)
        val count = Math.multiplyExact(width, height)
        require(count <= 12_000_000 && bytes.size == 8 + count * 4)
        val argb = IntArray(count)
        var cursor = 8
        for (i in argb.indices) {
            val red = bytes[cursor++].toInt() and 255
            val green = bytes[cursor++].toInt() and 255
            val blue = bytes[cursor++].toInt() and 255
            val alpha = bytes[cursor++].toInt() and 255
            argb[i] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(argb, 0, width, 0, 0, width, height)
        }
    }
}
