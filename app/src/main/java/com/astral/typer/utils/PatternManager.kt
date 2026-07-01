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

    fun getPatternBitmap(context: Context, assetPath: String, color: Int, size: Int = 100): Bitmap? {
        val cacheKey = "$assetPath-${Integer.toHexString(color)}-$size"
        val cached = bitmapCache.get(cacheKey)
        if (cached != null && !cached.isRecycled) return cached

        val svgString = loadSvgAsString(context, assetPath) ?: return null

        // Color Manipulation
        // Based on the repository, patterns often use #fff for background and #000 or #aaa for lines.
        // We want background to be transparent and lines to be the selected color.
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

            // Set size for rendering
            svg.documentWidth = size.toFloat()
            svg.documentHeight = size.toFloat()

            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            svg.renderToCanvas(canvas)

            bitmapCache.put(cacheKey, bitmap)
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun clearCache() {
        bitmapCache.evictAll()
    }
}
