package com.babel.domain.game

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Read-only access to packed ARGB pixels of a captured game window frame,
 * without bringing `android.graphics.Bitmap` into `:domain` or copying a full
 * high-resolution frame into a single heap array.
 */
interface FramePixels {
    val width: Int
    val height: Int

    fun getPixels(
        pixels: IntArray,
        offset: Int,
        stride: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    )

    fun getPixel(x: Int, y: Int): Int
}

/** Pure in-memory [FramePixels] backed by a row-major ARGB [IntArray]. */
class ArrayFramePixels(
    override val width: Int,
    override val height: Int,
    private val data: IntArray,
) : FramePixels {
    init {
        require(width > 0 && height > 0)
        require(data.size >= width * height)
    }

    override fun getPixels(
        pixels: IntArray,
        offset: Int,
        stride: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        require(x >= 0 && y >= 0 && width >= 0 && height >= 0)
        require(x + width <= this.width && y + height <= this.height)
        require(abs(stride) >= width)
        for (row in 0 until height) {
            data.copyInto(
                destination = pixels,
                destinationOffset = offset + row * stride,
                startIndex = (y + row) * this.width + x,
                endIndex = (y + row) * this.width + x + width,
            )
        }
    }

    override fun getPixel(x: Int, y: Int): Int {
        require(x in 0 until width && y in 0 until height)
        return data[y * width + x]
    }
}

/** Packed ARGB channel arithmetic and perceptual brightness/distance helpers. */
object GamePixels {

    fun red(pixel: Int): Int = (pixel shr 16) and 0xFF

    fun green(pixel: Int): Int = (pixel shr 8) and 0xFF

    fun blue(pixel: Int): Int = pixel and 0xFF

    fun rgb(red: Int, green: Int, blue: Int): Int =
        (0xFF shl 24) or ((red and 0xFF) shl 16) or ((green and 0xFF) shl 8) or (blue and 0xFF)

    fun luma(pixel: Int): Double =
        (red(pixel) * 299 + green(pixel) * 587 + blue(pixel) * 114) / 1000.0

    fun blend(a: Int, b: Int, t: Float): Int = rgb(
        (red(a) + (red(b) - red(a)) * t).toInt(),
        (green(a) + (green(b) - green(a)) * t).toInt(),
        (blue(a) + (blue(b) - blue(a)) * t).toInt(),
    )

    fun mean(pixels: List<Int>): Int =
        rgb(pixels.sumOf { red(it) } / pixels.size, pixels.sumOf { green(it) } / pixels.size, pixels.sumOf { blue(it) } / pixels.size)

    fun colourDistance(a: Int, b: Int): Double {
        val dr = (red(a) - red(b)).toDouble()
        val dg = (green(a) - green(b)).toDouble()
        val db = (blue(a) - blue(b)).toDouble()
        return sqrt(dr * dr + dg * dg + db * db)
    }
}
