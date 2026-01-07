package com.astral.typer.utils

import android.content.Context
import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo

class InpaintManager(private val context: Context) {

    init {
        if (!OpenCVLoader.initDebug()) {
            android.util.Log.e("InpaintManager", "OpenCV initialization failed!")
        } else {
            android.util.Log.d("InpaintManager", "OpenCV initialized successfully.")
        }
    }

    fun inpaint(originalBitmap: Bitmap, maskBitmap: Bitmap): Bitmap? {
        try {
            // 1. Convert Bitmaps to Mats
            val srcMat = Mat()
            Utils.bitmapToMat(originalBitmap, srcMat)

            val maskMat = Mat()
            // Ensure mask is same size as original
            val scaledMask = Bitmap.createScaledBitmap(maskBitmap, originalBitmap.width, originalBitmap.height, false)
            Utils.bitmapToMat(scaledMask, maskMat)

            // 2. Process Mask
            // OpenCV inpaint expects 1-channel 8-bit mask (CV_8UC1)
            // Bitmap to Mat usually creates CV_8UC4 (RGBA)
            val grayMask = Mat()
            Imgproc.cvtColor(maskMat, grayMask, Imgproc.COLOR_RGBA2GRAY)

            // 3. Prepare Output Mat
            val dstMat = Mat()

            // 4. Inpaint
            // Radius 3.0 is standard for Telea
            Photo.inpaint(srcMat, grayMask, dstMat, 5.0, Photo.INPAINT_TELEA)

            // 5. Convert back to Bitmap
            val outputBitmap = Bitmap.createBitmap(originalBitmap.width, originalBitmap.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(dstMat, outputBitmap)

            // Cleanup
            srcMat.release()
            maskMat.release()
            grayMask.release()
            dstMat.release()

            return outputBitmap

        } catch (e: Exception) {
            e.printStackTrace()
            android.util.Log.e("InpaintManager", "Inpaint failed", e)
            return null
        }
    }

    fun close() {
        // No explicit cleanup needed for OpenCV loader
    }
}
