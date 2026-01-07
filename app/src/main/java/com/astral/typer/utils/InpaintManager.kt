package com.astral.typer.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo

/**
 * Manages inpainting operations, providing a robust fallback if OpenCV fails.
 */
class InpaintManager(private val context: Context) {

    private var isOpenCvInitialized = false
    private val tfliteHelper: TfliteInpaintHelper by lazy { TfliteInpaintHelper(context) }

    init {
        try {
            if (OpenCVLoader.initDebug()) {
                isOpenCvInitialized = true
                Log.d("InpaintManager", "OpenCV initialized successfully.")
            } else {
                Log.e("InpaintManager", "OpenCV initialization failed! Will use fallback.")
            }
        } catch (e: Throwable) {
            Log.e("InpaintManager", "Error initializing OpenCV", e)
        }
    }

    /**
     * Inpaints the original bitmap using the provided mask.
     * @param originalBitmap The source image (ARGB_8888 recommended).
     * @param maskBitmap The mask image (where non-transparent pixels indicate areas to remove).
     * @param useTflite If true, uses TensorFlow Lite model instead of OpenCV.
     * @return A new Bitmap with the area inpainted, or null if absolutely everything failed.
     */
    fun inpaint(originalBitmap: Bitmap, maskBitmap: Bitmap, useTflite: Boolean = false): Bitmap? {
        if (useTflite) {
            Log.d("InpaintManager", "Using TFLite engine")
            return tfliteHelper.inpaint(originalBitmap, maskBitmap) ?: inpaintFallback(originalBitmap, maskBitmap)
        }

        // Try OpenCV first
        if (isOpenCvInitialized) {
            val result = inpaintWithOpenCV(originalBitmap, maskBitmap)
            if (result != null) {
                return result
            }
            Log.w("InpaintManager", "OpenCV inpaint returned null, switching to fallback.")
        }

        // Fallback to simple Kotlin implementation
        return inpaintFallback(originalBitmap, maskBitmap)
    }

    private fun inpaintWithOpenCV(originalBitmap: Bitmap, maskBitmap: Bitmap): Bitmap? {
        val srcMat = Mat()
        val rgbMat = Mat()
        val maskMat = Mat()
        val grayMask = Mat()
        val dstMat = Mat()
        val outputBitmap = Bitmap.createBitmap(originalBitmap.width, originalBitmap.height, Bitmap.Config.ARGB_8888)

        var scaledMask: Bitmap? = null

        try {
            // 1. Convert Bitmaps to Mats
            Utils.bitmapToMat(originalBitmap, srcMat)

            // Convert Source to RGB (Drop Alpha) - OpenCV inpaint expects 8-bit 1-channel or 3-channel
            // Attempting to inpaint 4-channel usually fails or behaves unexpectedly
            Imgproc.cvtColor(srcMat, rgbMat, Imgproc.COLOR_RGBA2RGB)

            // 2. Process Mask
            // Ensure mask is same size as original
            val maskToUse = if (maskBitmap.width != originalBitmap.width || maskBitmap.height != originalBitmap.height) {
                scaledMask = Bitmap.createScaledBitmap(maskBitmap, originalBitmap.width, originalBitmap.height, false)
                scaledMask
            } else {
                maskBitmap
            }

            Utils.bitmapToMat(maskToUse, maskMat)

            // Convert Mask to Grayscale (CV_8UC1)
            // Transparent (0) -> Black (0), White (255) -> White (255)
            // Pixels > 0 are inpainted
            Imgproc.cvtColor(maskMat, grayMask, Imgproc.COLOR_RGBA2GRAY)

            // 3. Inpaint
            // Radius 5.0 is standard for Telea
            Photo.inpaint(rgbMat, grayMask, dstMat, 5.0, Photo.INPAINT_TELEA)

            // 4. Convert back to Bitmap
            // dstMat is RGB. Utils.matToBitmap handles conversion to ARGB (adds opaque alpha)
            Utils.matToBitmap(dstMat, outputBitmap)

            return outputBitmap

        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("InpaintManager", "OpenCV Inpaint failed", e)
            return null
        } finally {
            // Cleanup native resources
            try { srcMat.release() } catch(e:Exception){}
            try { rgbMat.release() } catch(e:Exception){}
            try { maskMat.release() } catch(e:Exception){}
            try { grayMask.release() } catch(e:Exception){}
            try { dstMat.release() } catch(e:Exception){}

            scaledMask?.recycle()
        }
    }

    /**
     * A simple "Push-Pull" or "Diffusion" based inpainting fallback.
     * It iteratively fills the masked area with average colors from the boundary.
     * This is slower and lower quality than Telea, but works without native libs.
     */
    private fun inpaintFallback(original: Bitmap, mask: Bitmap): Bitmap {
        Log.d("InpaintManager", "Starting Fallback Inpainting...")

        // Working on a mutable copy
        val result = original.copy(Bitmap.Config.ARGB_8888, true)
        val w = result.width
        val h = result.height

        // Access pixels
        val pixels = IntArray(w * h)
        result.getPixels(pixels, 0, w, 0, 0, w, h)

        val maskPixels = IntArray(w * h)
        // Ensure mask is scaled if needed
        if (mask.width != w || mask.height != h) {
             val scaled = Bitmap.createScaledBitmap(mask, w, h, false)
             scaled.getPixels(maskPixels, 0, w, 0, 0, w, h)
             scaled.recycle()
        } else {
             mask.getPixels(maskPixels, 0, w, 0, 0, w, h)
        }

        // Identify mask boolean array for speed
        // Mask pixel != 0 is "hole"
        val hole = BooleanArray(w * h)
        var holeCount = 0
        for (i in pixels.indices) {
            // Check alpha or brightness of mask
            // Assuming mask is white on transparent/black
            if (Color.alpha(maskPixels[i]) > 0 && (Color.red(maskPixels[i]) > 10 || maskPixels[i] != 0)) {
                hole[i] = true
                holeCount++
                // Clear the pixel in result initially (optional)
                // pixels[i] = Color.TRANSPARENT
            }
        }

        if (holeCount == 0) return result // Nothing to do

        Log.d("InpaintManager", "Fallback: Identified $holeCount pixels to fix.")

        // Simple Iterative Diffusion (Pyramid-like)
        // Repeat N times: for every hole pixel, set it to average of non-hole neighbors
        // To make it converge, we update 'hole' status or use two buffers?
        // Simple approximation: Just smooth it repeatedly.

        // Pass 1: Fill holes with nearest valid pixel (Voronoi-ish) or just generic color
        // to have a starting point. simpler: Skip this, just run diffusion.

        val iterations = 10
        val tempPixels = pixels.clone()

        for (iter in 0 until iterations) {
            var changes = 0
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val idx = y * w + x
                    if (hole[idx]) {
                        // Gather neighbors
                        var rSum = 0
                        var gSum = 0
                        var bSum = 0
                        var count = 0

                        // Check 4 neighbors
                        // Manually unrolled for performance to avoid alloc

                        // Left
                        if (x > 0) {
                            val nIdx = idx - 1
                            val p = tempPixels[nIdx]
                            rSum += Color.red(p)
                            gSum += Color.green(p)
                            bSum += Color.blue(p)
                            count++
                        }
                        // Right
                        if (x < w - 1) {
                            val nIdx = idx + 1
                            val p = tempPixels[nIdx]
                            rSum += Color.red(p)
                            gSum += Color.green(p)
                            bSum += Color.blue(p)
                            count++
                        }
                        // Top
                        if (y > 0) {
                            val nIdx = idx - w
                            val p = tempPixels[nIdx]
                            rSum += Color.red(p)
                            gSum += Color.green(p)
                            bSum += Color.blue(p)
                            count++
                        }
                        // Bottom
                        if (y < h - 1) {
                            val nIdx = idx + w
                            val p = tempPixels[nIdx]
                            rSum += Color.red(p)
                            gSum += Color.green(p)
                            bSum += Color.blue(p)
                            count++
                        }

                        if (count > 0) {
                            val newCol = Color.rgb(rSum / count, gSum / count, bSum / count)
                            pixels[idx] = newCol // Write to current buffer
                            changes++
                        }
                    }
                }
            }
            // Update temp buffer for next pass
            System.arraycopy(pixels, 0, tempPixels, 0, pixels.size)
        }

        // Apply back
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        return result
    }

    fun close() {
        // No explicit cleanup needed
    }
}
