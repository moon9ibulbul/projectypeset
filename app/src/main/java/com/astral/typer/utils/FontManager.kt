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

    data class FontItem(
        val name: String,
        val path: String?, // Null for system, "std_cache:xxx" for bundled, absolute path for custom
        val typeface: Typeface,
        var isFavorite: Boolean = false,
        val isCustom: Boolean = false
    )

    fun getStandardFonts(context: Context): List<FontItem> {
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
                         val tf = Typeface.createFromFile(file)
                         val name = file.nameWithoutExtension
                         list.add(FontItem(
                             name = name,
                             path = "std_cache:${file.name}", // Special prefix to identify it as standard but file-based
                             typeface = tf,
                             isCustom = false // Treat as standard
                         ))
                     } catch (e: Exception) {
                         e.printStackTrace()
                     }
                 }
             }
        }

        // Check favorites
        val favorites = getFavorites(context)
        list.forEach { it.isFavorite = favorites.contains(it.name) } // Use Name for standard fonts favorites

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

    fun getCustomFonts(context: Context): List<FontItem> {
        val list = mutableListOf<FontItem>()
        val dir = File(context.filesDir, FONTS_DIR)
        if (!dir.exists()) dir.mkdirs()

        val favorites = getFavorites(context)

        dir.listFiles()?.forEach { file ->
            if (file.extension.lowercase() in listOf("ttf", "otf")) {
                try {
                    val tf = Typeface.createFromFile(file)
                    val name = file.nameWithoutExtension
                    list.add(FontItem(
                        name = name,
                        path = file.absolutePath,
                        typeface = tf,
                        isFavorite = favorites.contains(file.absolutePath), // Use path as ID for custom
                        isCustom = true
                    ))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        return list
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
}
