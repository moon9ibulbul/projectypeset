package com.astral.typer.utils.cloudflare

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class CloudflareProcessor(private val context: Context) {
    private val TAG = "CloudflareProcessor"

    interface ProgressListener {
        fun onProgress(percent: Int)
    }

    suspend fun inpaint(originalBitmap: Bitmap, maskBitmap: Bitmap, prompt: String, url: String, apiKey: String, listener: ProgressListener? = null): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                // Ensure URLs are valid
                if (url.isEmpty()) {
                    Log.e(TAG, "URL is empty")
                    return@withContext null
                }

                listener?.onProgress(10)

                // Convert bitmaps to byte arrays (PNG)
                val imageBytes = bitmapToByteArray(originalBitmap)
                val maskBytes = bitmapToByteArray(maskBitmap)

                listener?.onProgress(30)

                // The request structure will depend on how the custom worker is implemented.
                // Let's assume the worker accepts a JSON payload with base64 encoded images,
                // or a multipart/form-data. Given we have a worker to write, we can design the worker
                // to accept JSON for simplicity or multipart. Let's use multipart as it's standard for images,
                // but base64 is easier to handle in simple workers.
                // Actually, let's use a standard multipart/form-data request.

                val boundary = "Boundary-" + System.currentTimeMillis()
                val serverUrl = URL(url)
                val connection = serverUrl.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                if (apiKey.isNotEmpty()) {
                    connection.setRequestProperty("Authorization", "Bearer $apiKey")
                }

                connection.connectTimeout = 60000
                connection.readTimeout = 60000

                listener?.onProgress(40)

                val outputStream = connection.outputStream

                // Add prompt
                addFormField("prompt", prompt, boundary, outputStream)

                // Add image
                addFilePart("image", "image.png", imageBytes, boundary, outputStream)

                // Add mask
                addFilePart("mask", "mask.png", maskBytes, boundary, outputStream)

                // End of multipart
                outputStream.write(("--$boundary--\r\n").toByteArray())
                outputStream.flush()
                outputStream.close()

                listener?.onProgress(70)

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val inputStream = connection.inputStream
                    val resultBitmap = BitmapFactory.decodeStream(inputStream)
                    inputStream.close()
                    listener?.onProgress(100)
                    return@withContext resultBitmap
                } else {
                    val errorStream = connection.errorStream
                    val errorResponse = errorStream?.bufferedReader()?.use { it.readText() }
                    Log.e(TAG, "Server returned HTTP $responseCode: $errorResponse")
                    return@withContext null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Cloudflare Inpaint failed", e)
                return@withContext null
            }
        }
    }

    private fun addFormField(name: String, value: String, boundary: String, outputStream: OutputStream) {
        outputStream.write(("--$boundary\r\n").toByteArray())
        outputStream.write(("Content-Disposition: form-data; name=\"$name\"\r\n\r\n").toByteArray())
        outputStream.write((value + "\r\n").toByteArray())
    }

    private fun addFilePart(name: String, filename: String, data: ByteArray, boundary: String, outputStream: OutputStream) {
        outputStream.write(("--$boundary\r\n").toByteArray())
        outputStream.write(("Content-Disposition: form-data; name=\"$name\"; filename=\"$filename\"\r\n").toByteArray())
        outputStream.write(("Content-Type: image/png\r\n\r\n").toByteArray())
        outputStream.write(data)
        outputStream.write(("\r\n").toByteArray())
    }

    private fun bitmapToByteArray(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }
}
