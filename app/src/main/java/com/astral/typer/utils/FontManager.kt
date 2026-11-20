package com.astral.typer.utils

import android.content.Context
import android.graphics.Typeface
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object FontManager {

    private const val FONTS_DIR = "fonts"
    private const val STD_FONTS_CACHE_DIR = "std_fonts_cache"
    private const val PREFS_NAME = "font_prefs"
    private const val KEY_FAVORITES = "favorite_fonts"
    private const val BUNDLED_FONTS_ZIP = "fontpack.zip"

    private var cachedStandardFonts: List<FontItem>? = null
    private var cachedCustomFonts: List<FontItem>? = null

    private val fontLoadLock = Any()

    private val typefaceLruCache = android.util.LruCache<String, Typeface>(256)

    private val fontPreviewCacheMaxKb: Int =
        ((Runtime.getRuntime().maxMemory() / 1024L) / 20L).toInt().coerceIn(2048, 8192)

    private val fontPreviewLruCache = object : android.util.LruCache<String, android.graphics.Bitmap>(fontPreviewCacheMaxKb) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap): Int {
            return (value.byteCount / 1024).coerceAtLeast(1)
        }
    }

    fun getCachedTypeface(file: File): Typeface? {
        if (!file.exists() || file.length() <= 0L) return null
        val key = "${file.absolutePath}:${file.lastModified()}"
        synchronized(typefaceLruCache) {
            typefaceLruCache.get(key)?.let { return it }
        }
        return try {
            val tf: Typeface? = Typeface.createFromFile(file)
            if (tf != null) {
                synchronized(typefaceLruCache) {
                    typefaceLruCache.put(key, tf)
                }
            }
            tf
        } catch (_: Throwable) {
            null
        }
    }

    fun getOrRenderFontPreview(key: String, typeface: Typeface?, sampleText: String, textColor: Int, widthPx: Int, heightPx: Int): android.graphics.Bitmap? {
        return try {
            val fullKey = "$key:$textColor:${widthPx}x$heightPx:$sampleText"
            synchronized(fontPreviewLruCache) {
                fontPreviewLruCache.get(fullKey)?.let { cached ->
                    if (!cached.isRecycled) return cached
                }
            }
            if (widthPx <= 0 || heightPx <= 0) return null
            val bmp = android.graphics.Bitmap.createBitmap(widthPx, heightPx, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = textColor
                textSize = heightPx * 0.55f
                this.typeface = typeface ?: Typeface.DEFAULT
            }
            val fm = p.fontMetrics
            val y = (heightPx - fm.ascent - fm.descent) / 2f
            c.drawText(sampleText, 8f, y, p)
            synchronized(fontPreviewLruCache) {
                fontPreviewLruCache.put(fullKey, bmp)
            }
            bmp
        } catch (_: Throwable) {
            null
        }
    }

    data class FontItem(
        val name: String,
        val path: String?, // Null for system, "std_cache:xxx" for bundled, absolute path for custom
        val typeface: Typeface?,
        var isFavorite: Boolean = false,
        val isCustom: Boolean = false
    )

    fun getStandardFonts(context: Context): List<FontItem> = synchronized(fontLoadLock) {
        cachedStandardFonts?.let { return it }

        val list = mutableListOf<FontItem>()
        // System Fonts
        list.add(FontItem("Default", null, Typeface.DEFAULT))
        list.add(FontItem("Serif", null, Typeface.SERIF))
        list.add(FontItem("Sans Serif", null, Typeface.SANS_SERIF))
        list.add(FontItem("Monospace", null, Typeface.MONOSPACE))

        // Ensure bundled fonts are extracted
        ensureBundledFontsExtracted(context)

        // Load Bundled Standard Fonts from Cache
        val cacheDir = File(context.filesDir, STD_FONTS_CACHE_DIR)
        if (cacheDir.exists()) {
             cacheDir.listFiles()?.forEach { file ->
                 if (file.extension.lowercase() in listOf("ttf", "otf")) {
                     try {
                         val tf = getCachedTypeface(file)
                         val name = file.nameWithoutExtension
                         list.add(FontItem(
                             name = name,
                             path = "std_cache:${file.name}", // Special prefix to identify it as standard but file-based
                             typeface = tf,
                             isCustom = false // Treat as standard
                         ))
                     } catch (_: Throwable) {
                     }
                 }
             }
        }

        // Check favorites
        val favorites = getFavorites(context)
        list.forEach { it.isFavorite = favorites.contains(it.name) } // Use Name for standard fonts favorites

        cachedStandardFonts = list
        return list
    }

    private fun ensureBundledFontsExtracted(context: Context) {
        val cacheDir = File(context.filesDir, STD_FONTS_CACHE_DIR)

        // Simple check: if directory doesn't exist or is empty, extract.
        // For production, might want a version check to support updates.
        if (!cacheDir.exists() || (cacheDir.listFiles()?.isEmpty() == true)) {
            cacheDir.mkdirs()
            try {
                // Check if zip exists in assets
                val assets = context.assets.list("")
                if (assets?.contains(BUNDLED_FONTS_ZIP) == true) {
                     context.assets.open(BUNDLED_FONTS_ZIP).use { input ->
                         val zipInput = ZipInputStream(input)
                         var entry = zipInput.nextEntry
                         while (entry != null) {
                             if (!entry.isDirectory && (entry.name.endsWith(".ttf", true) || entry.name.endsWith(".otf", true))) {
                                 // Flatten structure: extract only filename
                                 val filename = File(entry.name).name
                                 val outFile = File(cacheDir, filename)
                                 FileOutputStream(outFile).use { output ->
                                     zipInput.copyTo(output)
                                 }
                             }
                             zipInput.closeEntry()
                             entry = zipInput.nextEntry
                         }
                     }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun getCustomFonts(context: Context): List<FontItem> = synchronized(fontLoadLock) {
        cachedCustomFonts?.let { return it }

        val list = mutableListOf<FontItem>()
        val dir = File(context.filesDir, FONTS_DIR)
        if (!dir.exists()) dir.mkdirs()

        val favorites = getFavorites(context)

        dir.listFiles()?.forEach { file ->
            if (file.extension.lowercase() in listOf("ttf", "otf")) {
                try {
                    val tf = getCachedTypeface(file)
                    val name = file.nameWithoutExtension
                    list.add(FontItem(
                        name = name,
                        path = file.absolutePath,
                        typeface = tf,
                        isFavorite = favorites.contains(file.absolutePath), // Use path as ID for custom
                        isCustom = true
                    ))
                } catch (_: Throwable) {
                }
            }
        }
        cachedCustomFonts = list
        return list
    }

    fun refreshCache() {
        cachedStandardFonts = null
        cachedCustomFonts = null
    }

    fun getFavoriteFonts(context: Context): List<FontItem> {
        val all = getStandardFonts(context) + getCustomFonts(context)
        return all.filter { it.isFavorite }
    }

    fun toggleFavorite(context: Context, item: FontItem) {
        val favorites = getFavorites(context).toMutableSet()
        val key = if (item.isCustom) item.path!! else item.name

        if (item.isFavorite) {
            favorites.remove(key)
        } else {
            favorites.add(key)
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_FAVORITES, favorites)
            .apply()

        item.isFavorite = !item.isFavorite
    }

    private fun getFavorites(context: Context): Set<String> {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()
    }

    fun importFont(context: Context, uri: android.net.Uri): Boolean {
        val contentResolver = context.contentResolver
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                // Check if zip
                val type = contentResolver.getType(uri)
                val name = getFileName(context, uri)

                if (name.endsWith(".zip") || type?.contains("zip") == true) {
                    // Unzip
                    val zipInput = ZipInputStream(input)
                    var entry = zipInput.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && (entry.name.endsWith(".ttf", true) || entry.name.endsWith(".otf", true))) {
                             saveFontFile(context, zipInput, File(entry.name).name)
                        }
                        zipInput.closeEntry()
                        entry = zipInput.nextEntry
                    }
                } else if (name.endsWith(".ttf", true) || name.endsWith(".otf", true)) {
                    saveFontFile(context, input, name)
                }
            }
            refreshCache()
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private fun saveFontFile(context: Context, input: java.io.InputStream, filename: String) {
        val dir = File(context.filesDir, FONTS_DIR)
        if (!dir.exists()) dir.mkdirs()

        val file = File(dir, filename)
        FileOutputStream(file).use { output ->
            input.copyTo(output)
        }
    }

    private fun getFileName(context: Context, uri: android.net.Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                         result = cursor.getString(index)
                    }
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result ?: "font.ttf"
    }

    /**
     * Smart font resolution:
     * 1. Check exact match by path (for custom/file-based) or name (for standard).
     * 2. If no exact match found, extract base font name (without path or extension) and match case-insensitively.
     */
    fun findMatchingFont(fonts: List<FontItem>, fontPath: String?): FontItem? {
        if (fontPath.isNullOrEmpty()) return null

        // 1. Exact match
        val exactMatch = fonts.find {
            (it.isCustom && it.path == fontPath) || (!it.isCustom && (it.path == fontPath || it.name == fontPath))
        }
        if (exactMatch != null) return exactMatch

        // 2. Extract base name without path or extension
        val baseName = fontPath.substringAfterLast("/").substringAfterLast(":").substringBeforeLast(".")
        if (baseName.isEmpty()) return null

        // 3. Smart Fallback match by font name or filename without extension (case-insensitive)
        return fonts.find { item ->
            val itemName = item.name
            val itemFileName = item.path?.substringAfterLast("/")?.substringAfterLast(":")?.substringBeforeLast(".") ?: item.name
            itemName.equals(baseName, ignoreCase = true) || itemFileName.equals(baseName, ignoreCase = true)
        }
    }

    fun findMatchingFont(context: Context, fontPath: String?): FontItem? {
        if (fontPath.isNullOrEmpty()) return null
        val availableFonts = getStandardFonts(context) + getCustomFonts(context)
        return findMatchingFont(availableFonts, fontPath)
    }

    fun isMatchingFont(font: FontItem, fontPath: String?, fonts: List<FontItem>): Boolean {
        if (fontPath.isNullOrEmpty()) return false
        val matched = findMatchingFont(fonts, fontPath)
        return matched == font
    }

    fun deleteCustomFont(context: Context, item: FontItem): Boolean {
        if (!item.isCustom || item.path == null) return false
        try {
            val file = File(item.path)
            if (file.exists()) {
                val success = file.delete()
                if (success) {
                    // Remove from favorites if it was there
                    val favorites = getFavorites(context).toMutableSet()
                    if (favorites.contains(item.path)) {
                        favorites.remove(item.path)
                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit()
                            .putStringSet(KEY_FAVORITES, favorites)
                            .apply()
                    }
                    // Remove from category associations
                    val fontId = item.path
                    context.getSharedPreferences("font_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .remove("font_categories_$fontId")
                        .apply()

                    refreshCache()
                    return true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }
}
