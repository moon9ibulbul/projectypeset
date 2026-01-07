package com.astral.typer.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
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

class InpaintManager(private val context: Context) {

    private var interpreter: Interpreter? = null
    private val modelName = "inpaint_model.tflite"
    private val modelInputSize = 256

    init {
        try {
            val options = Interpreter.Options()
            options.setNumThreads(4)
            interpreter = Interpreter(FileUtil.loadMappedFile(context, modelName), options)

            // Debug Shapes
            interpreter?.let {
                val inputCount = it.inputTensorCount
                android.util.Log.d("InpaintManager", "Input Count: $inputCount")
                for (i in 0 until inputCount) {
                     val shape = it.getInputTensor(i).shape()
                     val type = it.getInputTensor(i).dataType()
                     android.util.Log.d("InpaintManager", "Input $i Shape: ${shape.contentToString()} Type: $type")
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
            android.util.Log.e("InpaintManager", "Error initializing model: ${e.message}")
        }
    }

    fun inpaint(originalBitmap: Bitmap, maskBitmap: Bitmap): Bitmap? {
        if (interpreter == null) {
            android.util.Log.e("InpaintManager", "Interpreter is null")
            return null
        }

        try {
            // Resize inputs strictly to model size
            val resizedImage = Bitmap.createScaledBitmap(originalBitmap, modelInputSize, modelInputSize, true)
            val resizedMask = Bitmap.createScaledBitmap(maskBitmap, modelInputSize, modelInputSize, false)

            // Prepare Image (Float32, Normalized 0-1)
            val imageProcessor = ImageProcessor.Builder()
                .add(ResizeOp(modelInputSize, modelInputSize, ResizeOp.ResizeMethod.BILINEAR))
                .add(NormalizeOp(0f, 255f))
                .build()

            val tImage = TensorImage(DataType.FLOAT32)
            tImage.load(resizedImage)
            val processedImage = imageProcessor.process(tImage)

            // Prepare Mask (Float32, 1 Channel)
            // Ensure strictly [1, 256, 256, 1] buffer
            val maskBuffer = convertBitmapToGrayscaleByteBuffer(resizedMask)

            // Prepare Output Buffer
            val outputBuffer = TensorBuffer.createFixedSize(intArrayOf(1, modelInputSize, modelInputSize, 3), DataType.FLOAT32)

            // Run Inference
            // Input order is usually [Image, Mask] for this specific model architecture (Generative Inpainting)
            // We use runForMultipleInputsOutputs to be safe
            val inputs = arrayOf(processedImage.buffer, maskBuffer)
            val outputs = mapOf(0 to outputBuffer.buffer)

            val startTime = System.currentTimeMillis()
            interpreter?.runForMultipleInputsOutputs(inputs, outputs)
            android.util.Log.d("InpaintManager", "Inference time: ${System.currentTimeMillis() - startTime}ms")

            // Post process
            val outputFloatArray = outputBuffer.floatArray
            val outputBitmap = convertFloatArrayToBitmap(outputFloatArray, modelInputSize, modelInputSize)

            // Resize back to original
            val finalInpainted = Bitmap.createScaledBitmap(outputBitmap, originalBitmap.width, originalBitmap.height, true)

            // Blend: We only want the inpainted part where the mask was.
            // Although the model returns a full image, we should probably paste the result
            // over the original only where the mask was, to preserve resolution of the unmasked area.

            return blendResult(originalBitmap, finalInpainted, maskBitmap)

        } catch (e: Exception) {
            e.printStackTrace()
            android.util.Log.e("InpaintManager", "Inference Failed", e)
            return null
        }
    }

    private fun convertBitmapToGrayscaleByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(4 * modelInputSize * modelInputSize * 1) // Float32 * H * W * 1
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(modelInputSize * modelInputSize)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        for (pixel in intValues) {
            // Mask is usually white (255) for missing area, black (0) for valid area.
            // Or vice versa.
            // If we draw mask with transparency/color, we just check alpha or simple threshold.
            // Assuming mask drawn is visible (non-zero).

            val r = (pixel shr 16 and 0xFF)
            val valFloat = if (r > 128) 1.0f else 0.0f
            byteBuffer.putFloat(valFloat)
        }
        return byteBuffer
    }

    private fun convertFloatArrayToBitmap(data: FloatArray, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val intValues = IntArray(width * height)
        for (i in intValues.indices) {
            val r = ((data[i * 3] * 255).toInt()).coerceIn(0, 255)
            val g = ((data[i * 3 + 1] * 255).toInt()).coerceIn(0, 255)
            val b = ((data[i * 3 + 2] * 255).toInt()).coerceIn(0, 255)
            intValues[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        bitmap.setPixels(intValues, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun blendResult(original: Bitmap, inpainted: Bitmap, mask: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        // Draw original
        canvas.drawBitmap(original, 0f, 0f, null)

        // Draw inpainted masked
        val paint = Paint()
        paint.isAntiAlias = true

        // We need to cut out the inpainted image using the mask
        val maskedInpainted = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
        val layerCanvas = Canvas(maskedInpainted)
        layerCanvas.drawBitmap(inpainted, 0f, 0f, null)

        val maskPaint = Paint()
        maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        layerCanvas.drawBitmap(mask, 0f, 0f, maskPaint)

        // Draw the masked inpainted area on top of original
        canvas.drawBitmap(maskedInpainted, 0f, 0f, null)

        return result
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
