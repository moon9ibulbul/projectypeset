package com.astral.typer.utils

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Helper class for TFLite Inpainting.
 * Based on: https://github.com/helloyeseul/inpainting_tflite_demo
 */
class TfliteInpaintHelper(private val context: Context) {

    private var interpreter: Interpreter? = null
    private val MODEL_NAME = "inpaint_model.tflite" // 256x256 input
    private val IMG_SIZE = 256
    private val NUM_BYTES_PER_CHANNEL = 4 // Float32

    private var isInitialized = false

    init {
        try {
            val options = Interpreter.Options()
            val modelFile = FileUtil.loadMappedFile(context, MODEL_NAME)
            interpreter = Interpreter(modelFile, options)
            isInitialized = true
            Log.d("TfliteInpaint", "TFLite model loaded successfully.")
        } catch (e: Exception) {
            Log.e("TfliteInpaint", "Error loading TFLite model", e)
        }
    }

    fun inpaint(originalBitmap: Bitmap, maskBitmap: Bitmap): Bitmap? {
        if (!isInitialized || interpreter == null) {
             Log.e("TfliteInpaint", "Interpreter not initialized")
             return null
        }

        try {
            // 1. Preprocess
            // Resize to 256x256
            val scaledOriginal = Bitmap.createScaledBitmap(originalBitmap, IMG_SIZE, IMG_SIZE, true)
            val scaledMask = Bitmap.createScaledBitmap(maskBitmap, IMG_SIZE, IMG_SIZE, true)

            // Prepare Inputs
            // Input 0: Image [1, 256, 256, 3] (Float32, normalized 0..1 or -1..1?)
            // Input 1: Mask [1, 256, 256, 1] (Float32)

            // Checking model specs from typical TFLite inpainting (e.g. DeepFill v2 or similar)
            // Usually inputs are [Image, Mask].
            // Image: RGB, normalized to [0, 1] or [-1, 1]. Let's assume [0, 1] usually.
            // Mask: 1 channel, 0 or 1.

            val imgInput = convertBitmapToByteBuffer(scaledOriginal)
            val maskInput = convertMaskToByteBuffer(scaledMask)

            // Prepare Outputs
            // Output 0: Inpainted Image [1, 256, 256, 3]
            val outputBuffer = ByteBuffer.allocateDirect(1 * IMG_SIZE * IMG_SIZE * 3 * NUM_BYTES_PER_CHANNEL)
            outputBuffer.order(ByteOrder.nativeOrder())

            // Run Inference
            val inputs = arrayOf(imgInput, maskInput)
            val outputs = mapOf(0 to outputBuffer)

            interpreter?.runForMultipleInputsOutputs(inputs, outputs)

            // Postprocess
            outputBuffer.rewind()
            val outputBitmap = convertByteBufferToBitmap(outputBuffer, IMG_SIZE, IMG_SIZE)

            // Clean up scaled intermediates
            scaledOriginal.recycle()
            scaledMask.recycle()

            // Resize result back to original size
            val finalResult = Bitmap.createScaledBitmap(outputBitmap, originalBitmap.width, originalBitmap.height, true)
            outputBitmap.recycle() // Recycle 256x256 result

            return finalResult

        } catch (e: Exception) {
            Log.e("TfliteInpaint", "Inference failed", e)
            return null
        }
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(1 * IMG_SIZE * IMG_SIZE * 3 * NUM_BYTES_PER_CHANNEL)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(IMG_SIZE * IMG_SIZE)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var pixel = 0
        for (i in 0 until IMG_SIZE) {
            for (j in 0 until IMG_SIZE) {
                val input = intValues[pixel++]

                // RGB Conversion (Ignore Alpha)
                // Normalize to [0, 1]
                byteBuffer.putFloat(((input shr 16 and 0xFF) / 255.0f))
                byteBuffer.putFloat(((input shr 8 and 0xFF) / 255.0f))
                byteBuffer.putFloat(((input and 0xFF) / 255.0f))
            }
        }
        return byteBuffer
    }

    private fun convertMaskToByteBuffer(bitmap: Bitmap): ByteBuffer {
        // Mask should be 1 channel
        val byteBuffer = ByteBuffer.allocateDirect(1 * IMG_SIZE * IMG_SIZE * 1 * NUM_BYTES_PER_CHANNEL)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(IMG_SIZE * IMG_SIZE)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var pixel = 0
        for (i in 0 until IMG_SIZE) {
            for (j in 0 until IMG_SIZE) {
                val input = intValues[pixel++]

                // Mask logic: Non-transparent/White is 1.0 (remove), Transparent/Black is 0.0 (keep)
                // Assuming mask is drawn with white on transparent.
                // Check alpha or brightness
                val alpha = (input shr 24 and 0xFF)
                val red = (input shr 16 and 0xFF)

                val value = if (alpha > 0 && red > 10) 1.0f else 0.0f
                byteBuffer.putFloat(value)
            }
        }
        return byteBuffer
    }

    private fun convertByteBufferToBitmap(byteBuffer: ByteBuffer, width: Int, height: Int): Bitmap {
        byteBuffer.rewind()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val intValues = IntArray(width * height)

        for (i in 0 until width * height) {
            val r = (byteBuffer.float * 255).toInt().coerceIn(0, 255)
            val g = (byteBuffer.float * 255).toInt().coerceIn(0, 255)
            val b = (byteBuffer.float * 255).toInt().coerceIn(0, 255)

            // Reconstruct ARGB (Alpha 255)
            intValues[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        bitmap.setPixels(intValues, 0, width, 0, 0, width, height)
        return bitmap
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
