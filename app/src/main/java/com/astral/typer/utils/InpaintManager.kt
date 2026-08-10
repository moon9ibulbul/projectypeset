package com.astral.typer.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import org.opencv.xphoto.Xphoto

/**
 * Manages inpainting operations, providing a robust fallback if OpenCV fails.
 */
class InpaintManager(private val context: Context) {

    enum class Engine {
        OPENCV,
        OPENCV_XPHOTO,
        LAMA,
        MIGAN
    }

    private var currentEngine: Engine = Engine.OPENCV
    private var isOpenCvInitialized = false
    private val lamaProcessor by lazy { LaMaProcessor(context) }
    private val miganProcessor by lazy { MiganProcessor(context) }

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

    fun setEngine(engine: Engine) {
        currentEngine = engine
    }

    /**
     * Inpaints the original bitmap using the provided mask.
     * @param originalBitmap The source image (ARGB_8888 recommended).
     * @param maskBitmap The mask image (where non-transparent pixels indicate areas to remove).
     * @param bounds Optional bounding box for cropped/sub-region inpainting.
     * @return A new Bitmap with the area inpainted, or null if absolutely everything failed.
     */
    suspend fun inpaint(originalBitmap: Bitmap, maskBitmap: Bitmap, bounds: android.graphics.Rect? = null): Bitmap? {
        val rect = bounds ?: android.graphics.Rect(0, 0, originalBitmap.width, originalBitmap.height)

        // Ensure bounds are valid and within bitmap boundaries
        if (rect.left < 0 || rect.top < 0 || rect.right > originalBitmap.width || rect.bottom > originalBitmap.height || rect.isEmpty) {
            return performInpaintOnRegion(originalBitmap, maskBitmap)
        }

        // If bounds cover the whole image, process directly without cropping overhead
        if (rect.left == 0 && rect.top == 0 && rect.width() == originalBitmap.width && rect.height() == originalBitmap.height) {
            return performInpaintOnRegion(originalBitmap, maskBitmap)
        }

        // Crop and process sub-region
        return withContext(Dispatchers.Default) {
            try {
                val croppedOriginal = Bitmap.createBitmap(originalBitmap, rect.left, rect.top, rect.width(), rect.height())

                // If the provided mask is already sized to match the bounds, use it directly.
                // Otherwise, crop the mask to match the sub-region bounds.
                val croppedMask = if (maskBitmap.width == rect.width() && maskBitmap.height == rect.height()) {
                    maskBitmap
                } else {
                    Bitmap.createBitmap(maskBitmap, rect.left, rect.top, rect.width(), rect.height())
                }

                val croppedResult = performInpaintOnRegion(croppedOriginal, croppedMask)

                // Clean up temporary cropped bitmap
                if (croppedOriginal != originalBitmap) {
                    croppedOriginal.recycle()
                }
                if (croppedMask != maskBitmap) {
                    croppedMask.recycle()
                }

                if (croppedResult != null) {
                    // Create a mutable copy of the original and paste the inpainted region
                    val resultBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
                    val canvas = android.graphics.Canvas(resultBitmap)
                    canvas.drawBitmap(croppedResult, rect.left.toFloat(), rect.top.toFloat(), null)
                    croppedResult.recycle()
                    return@withContext resultBitmap
                }
            } catch (e: Exception) {
                Log.e("InpaintManager", "Cropped inpaint failed, falling back to full inpaint", e)
            }

            // Fallback to full inpaint if cropped fails
            performInpaintOnRegion(originalBitmap, maskBitmap)
        }
    }

    private suspend fun performInpaintOnRegion(originalBitmap: Bitmap, maskBitmap: Bitmap): Bitmap? {
        if (currentEngine == Engine.LAMA && lamaProcessor.isModelAvailable()) {
            val result = lamaProcessor.inpaint(originalBitmap, maskBitmap)
            if (result != null) return result
            Log.w("InpaintManager", "LaMa inpaint failed, falling back to OpenCV")
        } else if (currentEngine == Engine.MIGAN && miganProcessor.isModelAvailable()) {
            val result = miganProcessor.inpaint(originalBitmap, maskBitmap)
            if (result != null) return result
            Log.w("InpaintManager", "MIGAN inpaint failed, falling back to OpenCV")
        } else if (currentEngine == Engine.OPENCV_XPHOTO) {
            if (isOpenCvInitialized) {
                val result = inpaintWithOpenCVXPhoto(originalBitmap, maskBitmap)
                if (result != null) return result
                Log.w("InpaintManager", "OpenCV xphoto inpaint failed, falling back to OpenCV Telea")
            }
        }

        // Try OpenCV
        return withContext(Dispatchers.Default) {
            if (isOpenCvInitialized) {
                val result = inpaintWithOpenCV(originalBitmap, maskBitmap)
                if (result != null) {
                    return@withContext result
                }
                Log.w("InpaintManager", "OpenCV inpaint returned null, switching to fallback.")
            }
            // Fallback to simple Kotlin implementation
            inpaintFallback(originalBitmap, maskBitmap)
        }
    }

    private fun inpaintWithOpenCVXPhoto(originalBitmap: Bitmap, maskBitmap: Bitmap): Bitmap? {
        val srcMat = Mat()
        val bgrMat = Mat()
        val maskMat = Mat()
        val grayMask = Mat()
        val invertedMask = Mat()
        val dstMat = Mat()
        val rgbaMat = Mat()
        val outputBitmap = Bitmap.createBitmap(originalBitmap.width, originalBitmap.height, Bitmap.Config.ARGB_8888)

        var scaledMask: Bitmap? = null

        try {
            // 1. Convert Bitmaps to Mats
            Utils.bitmapToMat(originalBitmap, srcMat)

            // Convert Source to BGR (3-channel) - OpenCV Xphoto FSR_BEST expects 3-channel BGR
            Imgproc.cvtColor(srcMat, bgrMat, Imgproc.COLOR_RGBA2BGR)

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
            Imgproc.cvtColor(maskMat, grayMask, Imgproc.COLOR_RGBA2GRAY)

            // Invert the mask. Xphoto.inpaint definition:
            // "mask - mask (CV_8UC1), where non-zero pixels indicate valid image area, while zero pixels indicate area to be inpainted"
            Core.bitwise_not(grayMask, invertedMask)

            // 3. Inpaint
            // We use INPAINT_FSR_BEST for maximum high quality since it operates on cropped/sub-region
            Xphoto.inpaint(bgrMat, invertedMask, dstMat, Xphoto.INPAINT_FSR_BEST)

            // 4. Convert back to RGBA and then to Bitmap
            Imgproc.cvtColor(dstMat, rgbaMat, Imgproc.COLOR_BGR2RGBA)
            Utils.matToBitmap(rgbaMat, outputBitmap)

            return outputBitmap

        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("InpaintManager", "OpenCV xphoto Inpaint failed", e)
            return null
        } finally {
            // Cleanup native resources to prevent memory overhead or leaks
            try { srcMat.release() } catch(e: Exception){}
            try { bgrMat.release() } catch(e: Exception){}
            try { maskMat.release() } catch(e: Exception){}
            try { grayMask.release() } catch(e: Exception){}
            try { invertedMask.release() } catch(e: Exception){}
            try { dstMat.release() } catch(e: Exception){}
            try { rgbaMat.release() } catch(e: Exception){}

            scaledMask?.recycle()
        }
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
