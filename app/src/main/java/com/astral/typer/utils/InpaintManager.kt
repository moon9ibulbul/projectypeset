package com.astral.typer.utils

import android.content.Context
import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

class InpaintManager(private val context: Context) {

    private var isInitialized = false

    init {
        if (OpenCVLoader.initDebug()) {
            isInitialized = true
            android.util.Log.d("InpaintManager", "OpenCV initialized successfully.")
        } else {
            android.util.Log.e("InpaintManager", "OpenCV initialization failed!")
        }
    }

    fun inpaint(originalBitmap: Bitmap, maskBitmap: Bitmap): Bitmap? {
        if (!isInitialized) {
            android.util.Log.e("InpaintManager", "Cannot inpaint: OpenCV not initialized.")
            return null
        }

        val srcMat = Mat()
        val rgbMat = Mat()
        val maskMat = Mat()
        val grayMask = Mat()
        val dstMat = Mat()
        val outputBitmap = Bitmap.createBitmap(originalBitmap.width, originalBitmap.height, Bitmap.Config.ARGB_8888)

        try {
            // 1. Convert Bitmaps to Mats
            // Utils.bitmapToMat creates CV_8UC4 (RGBA) by default
            Utils.bitmapToMat(originalBitmap, srcMat)

            // Convert Source to RGB (Drop Alpha) - OpenCV inpaint expects 8-bit 1-channel or 3-channel
            // Attempting to inpaint 4-channel usually fails or behaves unexpectedly
            Imgproc.cvtColor(srcMat, rgbMat, Imgproc.COLOR_RGBA2RGB)

            // 2. Process Mask
            // Ensure mask is same size as original
            var scaledMask = maskBitmap
            var createdNewMask = false
            if (maskBitmap.width != originalBitmap.width || maskBitmap.height != originalBitmap.height) {
                scaledMask = Bitmap.createScaledBitmap(maskBitmap, originalBitmap.width, originalBitmap.height, false)
                createdNewMask = true
            }

            Utils.bitmapToMat(scaledMask, maskMat)

            // Convert Mask to Grayscale (CV_8UC1)
            // Transparent/Black becomes 0, White becomes 255
            Imgproc.cvtColor(maskMat, grayMask, Imgproc.COLOR_RGBA2GRAY)

            // 3. Inpaint
            // Radius 3.0-5.0 is standard for Telea
            Imgproc.inpaint(rgbMat, grayMask, dstMat, 5.0, Imgproc.INPAINT_TELEA)

            // 4. Convert back to Bitmap
            // dstMat is RGB. Utils.matToBitmap handles conversion to ARGB (adds opaque alpha)
            Utils.matToBitmap(dstMat, outputBitmap)

            if (createdNewMask) {
                scaledMask.recycle()
            }

            return outputBitmap

        } catch (e: Exception) {
            e.printStackTrace()
            android.util.Log.e("InpaintManager", "Inpaint failed", e)
            return null
        } finally {
            // Cleanup native resources
            srcMat.release()
            rgbMat.release()
            maskMat.release()
            grayMask.release()
            dstMat.release()
        }
    }

    fun close() {
        // No explicit cleanup needed for OpenCV loader
    }
}
