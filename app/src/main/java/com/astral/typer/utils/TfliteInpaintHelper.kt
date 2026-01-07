package com.astral.typer.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Helper class to run the TFLite Inpainting model.
 * Model expects:
 * - Input 1: Image [1, 256, 256, 3] (RGB, Float32)
 * - Input 2: Mask [1, 256, 256, 1] (Grayscale, Float32)
 * - Output: Inpainted Image [1, 256, 256, 3]
 */
class TfliteInpaintHelper(private val context: Context) {

    private var interpreter: Interpreter? = null
    private val modelName = "inpaint_model.tflite"
    private val inputSize = 256

    init {
        try {
            val modelBuffer = FileUtil.loadMappedFile(context, modelName)
            val options = Interpreter.Options()
            interpreter = Interpreter(modelBuffer, options)
            Log.d("TfliteInpaintHelper", "TFLite Model loaded successfully.")
        } catch (e: Exception) {
            Log.e("TfliteInpaintHelper", "Error loading TFLite model", e)
        }
    }

    fun inpaint(original: Bitmap, mask: Bitmap): Bitmap? {
        if (interpreter == null) {
            Log.e("TfliteInpaintHelper", "Interpreter is null")
            return null
        }

        try {
            // 1. Preprocess Image: Resize -> Extract RGB -> Normalize
            // The user requested mimicking OpenCV's RGBA -> RGB conversion manually if needed.
            // But here we need to feed it into a TensorBuffer.

            // Resize first to avoid processing huge arrays
            val resizedImage = Bitmap.createScaledBitmap(original, inputSize, inputSize, true)
            val resizedMask = Bitmap.createScaledBitmap(mask, inputSize, inputSize, true)

            // Convert Bitmap to ByteBuffer (RGB floats)
            // Model likely expects values normalized to [0, 1] or [-1, 1].
            // Most generative inpainting models (like the one linked) expect 0..1 or -1..1.
            // The linked repo suggests it's a standard generative model.
            // Let's assume [0, 1] first or check the repo code if I could.
            // Since I can't check the repo code easily, I'll assume standard float input [0, 1].
            // However, the user specifically mentioned "Especially the RGBA to RGB conversion part".

            val imageBuffer = convertBitmapToRGBFloatBuffer(resizedImage)
            val maskBuffer = convertMaskToGrayscaleFloatBuffer(resizedMask)

            // Prepare Output Buffer
            // Output shape: [1, 256, 256, 3]
            val outputBuffer = TensorBuffer.createFixedSize(intArrayOf(1, inputSize, inputSize, 3), DataType.FLOAT32)

            // Run Inference
            // Inputs: [Image, Mask]
            val inputs = arrayOf(imageBuffer, maskBuffer)
            val outputs = mapOf(0 to outputBuffer.buffer)

            interpreter?.runForMultipleInputsOutputs(inputs, outputs)

            // Post-process: Convert Output Buffer back to Bitmap
            val outputBitmap = convertOutputToBitmap(outputBuffer.floatArray, inputSize, inputSize)

            // Resize back to original size
            val finalBitmap = Bitmap.createScaledBitmap(outputBitmap, original.width, original.height, true)

            return finalBitmap

        } catch (e: Exception) {
            Log.e("TfliteInpaintHelper", "Inference failed", e)
            return null
        }
    }

    /**
     * Converts RGBA Bitmap to a FloatBuffer of RGB values.
     * Dimensions: [1, 256, 256, 3]
     */
    private fun convertBitmapToRGBFloatBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4) // 3 channels * 4 bytes (float)
        buffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        for (pixel in pixels) {
            // Extract RGB, ignore Alpha
            val r = Color.red(pixel) / 255.0f
            val g = Color.green(pixel) / 255.0f
            val b = Color.blue(pixel) / 255.0f

            buffer.putFloat(r)
            buffer.putFloat(g)
            buffer.putFloat(b)
        }
        buffer.rewind()
        return buffer
    }

    /**
     * Converts Mask Bitmap to a FloatBuffer of Grayscale values.
     * Dimensions: [1, 256, 256, 1]
     */
    private fun convertMaskToGrayscaleFloatBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 1 * 4) // 1 channel
        buffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        for (pixel in pixels) {
            // Logic: If pixel is not transparent, it is part of the mask (1.0).
            // Usually masks are 1 for holes, 0 for valid. Or vice versa.
            // OpenCV telea uses >0 as hole.
            // Generative models usually take 1 for hole, 0 for valid.

            // Check alpha and color intensity
            val alpha = Color.alpha(pixel)
            val r = Color.red(pixel)

            val value = if (alpha > 0 && r > 10) 1.0f else 0.0f
            buffer.putFloat(value)
        }
        buffer.rewind()
        return buffer
    }

    private fun convertOutputToBitmap(floatArray: FloatArray, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)

        for (i in pixels.indices) {
            val offset = i * 3
            var r = (floatArray[offset] * 255).toInt()
            var g = (floatArray[offset + 1] * 255).toInt()
            var b = (floatArray[offset + 2] * 255).toInt()

            // Clamp values
            r = r.coerceIn(0, 255)
            g = g.coerceIn(0, 255)
            b = b.coerceIn(0, 255)

            pixels[i] = Color.rgb(r, g, b)
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }
}
