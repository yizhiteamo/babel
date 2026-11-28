package com.babel.platform.game

import android.graphics.Bitmap
import com.babel.domain.game.FramePixels

internal class BitmapFramePixels(private val bitmap: Bitmap) : FramePixels {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height

    override fun getPixels(
        pixels: IntArray,
        offset: Int,
        stride: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        bitmap.getPixels(pixels, offset, stride, x, y, width, height)
    }

    override fun getPixel(x: Int, y: Int): Int = bitmap.getPixel(x, y)
}
