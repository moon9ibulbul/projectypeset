package com.astral.typer.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.LruCache
import com.caverock.androidsvg.SVG
import java.io.InputStream
import java.nio.charset.Charset

object PatternManager {

    private val bitmapCache = LruCache<String, Bitmap>(50)

    fun listPatterns(context: Context): List<String> {
        val patterns = mutableListOf<String>()
        try {
            val root = "patterns"
            context.assets.list(root)?.forEach { dir ->
                context.assets.list("$root/$dir")?.forEach { file ->
                    if (file.endsWith(".svg")) {
                        patterns.add("$root/$dir/$file")
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return patterns.sorted()
    }

    private fun loadSvgAsString(context: Context, assetPath: String): String? {
        return try {
            val inputStream: InputStream = context.assets.open(assetPath)
            val size: Int = inputStream.available()
            val buffer = ByteArray(size)
            inputStream.read(buffer)
            inputStream.close()
            String(buffer, Charset.forName("UTF-8"))
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun getPatternBitmap(context: Context, assetPath: String, color: Int): Bitmap? {
        val cacheKey = "$assetPath-${Integer.toHexString(color)}"
        val cached = bitmapCache.get(cacheKey)
        if (cached != null && !cached.isRecycled) return cached

        val svgString = loadSvgAsString(context, assetPath) ?: return null

        // Color Manipulation
        val hexColor = String.format("#%06X", 0xFFFFFF and color)

        val manipulatedSvg = svgString
            .replace("<%= background %>", "none")
            .replace("<%= foreground %>", hexColor)
            .replace("fill='#fff'", "fill='none'")
            .replace("fill=\"#fff\"", "fill=\"none\"")
            .replace("stroke='#aaa'", "stroke='$hexColor'")
            .replace("stroke=\"#aaa\"", "stroke=\"$hexColor\"")
            .replace("stroke='#000'", "stroke='$hexColor'")
            .replace("stroke=\"#000\"", "stroke=\"$hexColor\"")
            .replace("fill='#000'", "fill='$hexColor'")
            .replace("fill=\"#000\"", "fill=\"$hexColor\"")

        return try {
            val svg = SVG.getFromString(manipulatedSvg)

            // Use intrinsic dimensions if available, otherwise default to 20x20 for patterns
            val w = if (svg.documentWidth > 0) svg.documentWidth.toInt() else 20
            val h = if (svg.documentHeight > 0) svg.documentHeight.toInt() else 20

            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            svg.renderToCanvas(canvas)

            bitmapCache.put(cacheKey, bitmap)
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun getPatternSampleBitmap(context: Context, assetPath: String, color: Int, size: Int = 150): Bitmap? {
        val cacheKey = "sample-$assetPath-${Integer.toHexString(color)}-$size"
        val cached = bitmapCache.get(cacheKey)
        if (cached != null && !cached.isRecycled) return cached

        val patternBmp = getPatternBitmap(context, assetPath, color) ?: return null

        val sampleBmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sampleBmp)

        // Fill with pattern
        val shader = android.graphics.BitmapShader(patternBmp, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
        val paint = android.graphics.Paint()
        paint.shader = shader
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)

        bitmapCache.put(cacheKey, sampleBmp)
        return sampleBmp
    }

    fun clearCache() {
        bitmapCache.evictAll()
    }
}
