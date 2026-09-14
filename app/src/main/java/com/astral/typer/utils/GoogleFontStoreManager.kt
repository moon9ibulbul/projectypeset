package com.astral.typer.utils

import android.content.Context
import android.graphics.Typeface
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object GoogleFontStoreManager {

    private const val ENCODED_KEY = "QUl6YVN5RFBiNGVUUWRQMEZmYk9ENTBhWHBmREZIOVVJTHhULUFV"
    private const val CACHE_FILE_NAME = "google_fonts_cache.json"
    private const val PREVIEW_DIR_NAME = "font_previews"

    private fun getApiKey(): String {
        return String(Base64.decode(ENCODED_KEY, Base64.DEFAULT)).trim()
    }

    data class StoreFontItem(
        val family: String,
        val category: String?,
        val fileUrl: String,
        var isDownloaded: Boolean = false,
        var localPath: String? = null,
        var typeface: Typeface? = null
    )

    private var cachedStoreItems: List<StoreFontItem>? = null

    suspend fun getGoogleFonts(context: Context, forceRefresh: Boolean = false): List<StoreFontItem> = withContext(Dispatchers.IO) {
        if (!forceRefresh && cachedStoreItems != null) {
            return@withContext syncDownloadStatus(context, cachedStoreItems!!)
        }

        val cacheFile = File(context.cacheDir, CACHE_FILE_NAME)
        var jsonString: String? = null

        if (!forceRefresh && cacheFile.exists() && cacheFile.length() > 0) {
            try {
                jsonString = cacheFile.readText()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (jsonString.isNullOrEmpty()) {
            try {
                val apiKey = getApiKey()
                val urlStr = "https://www.googleapis.com/webfonts/v1/webfonts?key=$apiKey&sort=popularity"
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.requestMethod = "GET"

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    jsonString = conn.inputStream.bufferedReader().use { it.readText() }
                    try {
                        cacheFile.writeText(jsonString)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (jsonString.isNullOrEmpty()) {
            return@withContext emptyList()
        }

        val items = parseGoogleFontsJson(jsonString)
        val synced = syncDownloadStatus(context, items)
        val sorted = synced.sortedBy { it.family.lowercase() }
        cachedStoreItems = sorted
        return@withContext sorted
    }

    private fun parseGoogleFontsJson(jsonString: String): List<StoreFontItem> {
        val list = mutableListOf<StoreFontItem>()
        try {
            val root = JSONObject(jsonString)
            val itemsArr = root.optJSONArray("items") ?: return list

            for (i in 0 until itemsArr.length()) {
                val obj = itemsArr.getJSONObject(i)
                val family = obj.optString("family", "")
                if (family.isEmpty()) continue

                val category = if (obj.has("category")) obj.getString("category") else null
                val filesObj = obj.optJSONObject("files") ?: continue

                // Preference: "regular", then "400", then first available variant
                var fileUrl: String? = if (filesObj.has("regular")) filesObj.getString("regular") else null
                if (fileUrl.isNullOrEmpty()) {
                    fileUrl = if (filesObj.has("400")) filesObj.getString("400") else null
                }
                if (fileUrl.isNullOrEmpty()) {
                    val keys = filesObj.keys()
                    if (keys.hasNext()) {
                        val key = keys.next()
                        fileUrl = if (filesObj.has(key)) filesObj.getString(key) else null
                    }
                }

                if (!fileUrl.isNullOrEmpty()) {
                    // Ensure https
                    if (fileUrl.startsWith("http://")) {
                        fileUrl = fileUrl.replace("http://", "https://")
                    }
                    list.add(StoreFontItem(family = family, category = category, fileUrl = fileUrl))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private fun syncDownloadStatus(context: Context, items: List<StoreFontItem>): List<StoreFontItem> {
        val fontsDir = File(context.filesDir, "fonts")
        val customFonts = FontManager.getCustomFonts(context)
        val customMap = customFonts.associateBy { it.name.lowercase() }

        for (item in items) {
            val fontFileName = "${item.family.replace(" ", "_")}.ttf"
            val localFile = File(fontsDir, fontFileName)

            val matchedCustom = customMap[item.family.lowercase()]

            if (localFile.exists()) {
                item.isDownloaded = true
                item.localPath = localFile.absolutePath
                if (item.typeface == null) {
                    try {
                        item.typeface = Typeface.createFromFile(localFile)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } else if (matchedCustom != null && matchedCustom.path != null) {
                item.isDownloaded = true
                item.localPath = matchedCustom.path
                if (item.typeface == null) {
                    item.typeface = matchedCustom.typeface
                }
            } else {
                item.isDownloaded = false
                item.localPath = null
            }
        }
        return items
    }

    suspend fun downloadFont(
        context: Context,
        item: StoreFontItem,
        onProgress: (Int) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val fontsDir = File(context.filesDir, "fonts")
            if (!fontsDir.exists()) fontsDir.mkdirs()

            val fontFileName = "${item.family.replace(" ", "_")}.ttf"
            val targetFile = File(fontsDir, fontFileName)
            val tempFile = File(fontsDir, "$fontFileName.tmp")

            val url = URL(item.fileUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.connect()

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext false
            }

            val fileLength = conn.contentLength
            val input = conn.inputStream
            val output = FileOutputStream(tempFile)

            val data = ByteArray(4096)
            var total: Long = 0
            var count: Int
            while (input.read(data).also { count = it } != -1) {
                total += count.toLong()
                if (fileLength > 0) {
                    val progress = ((total * 100) / fileLength).toInt()
                    withContext(Dispatchers.Main) {
                        onProgress(progress)
                    }
                }
                output.write(data, 0, count)
            }

            output.flush()
            output.close()
            input.close()

            if (tempFile.exists()) {
                if (targetFile.exists()) targetFile.delete()
                tempFile.renameTo(targetFile)
            }

            if (targetFile.exists()) {
                val tf = Typeface.createFromFile(targetFile)
                item.isDownloaded = true
                item.localPath = targetFile.absolutePath
                item.typeface = tf
                FontManager.refreshCache()
                return@withContext true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext false
    }

    private const val MAX_PREVIEW_CACHE_BYTES = 30L * 1024L * 1024L // 30 MB

    private fun prunePreviewCache(dir: File) {
        try {
            if (!dir.exists() || !dir.isDirectory) return
            val files = dir.listFiles() ?: return
            var totalSize = files.sumOf { it.length() }
            if (totalSize > MAX_PREVIEW_CACHE_BYTES) {
                val sortedFiles = files.sortedBy { it.lastModified() }
                for (file in sortedFiles) {
                    val length = file.length()
                    if (file.delete()) {
                        totalSize -= length
                    }
                    if (totalSize <= MAX_PREVIEW_CACHE_BYTES * 0.8) {
                        break
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun loadPreviewTypeface(
        context: Context,
        item: StoreFontItem
    ): Typeface? = withContext(Dispatchers.IO) {
        if (item.typeface != null) {
            return@withContext item.typeface
        }

        val previewDir = File(context.cacheDir, PREVIEW_DIR_NAME)
        if (!previewDir.exists()) previewDir.mkdirs()

        val fileName = "${item.family.replace(" ", "_")}_preview.ttf"
        val previewFile = File(previewDir, fileName)

        if (previewFile.exists() && previewFile.length() > 0) {
            try {
                val tf = Typeface.createFromFile(previewFile)
                item.typeface = tf
                return@withContext tf
            } catch (e: Exception) {
                previewFile.delete()
            }
        }

        var downloaded = false

        // 1. Try Google Fonts Subsetting API first (drastically smaller .ttf download)
        try {
            val familyEncoded = java.net.URLEncoder.encode(item.family, "UTF-8")
            val cssUrlStr = "https://fonts.googleapis.com/css2?family=$familyEncoded&text=$familyEncoded"
            val cssConn = URL(cssUrlStr).openConnection() as HttpURLConnection
            cssConn.connectTimeout = 8000
            cssConn.readTimeout = 8000
            cssConn.requestMethod = "GET"

            if (cssConn.responseCode == HttpURLConnection.HTTP_OK) {
                val cssContent = cssConn.inputStream.bufferedReader().use { it.readText() }
                val fontUrlRegex = Regex("""url\((https?://[^)]+)\)""")
                val match = fontUrlRegex.find(cssContent)
                val subsetFontUrl = match?.groupValues?.get(1)?.trim()

                if (!subsetFontUrl.isNullOrEmpty()) {
                    val fontConn = URL(subsetFontUrl).openConnection() as HttpURLConnection
                    fontConn.connectTimeout = 8000
                    fontConn.readTimeout = 8000
                    fontConn.connect()

                    if (fontConn.responseCode == HttpURLConnection.HTTP_OK) {
                        fontConn.inputStream.use { input ->
                            FileOutputStream(previewFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (previewFile.exists() && previewFile.length() > 0) {
                            downloaded = true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Fallback to direct full TTF download if subsetting failed
        if (!downloaded) {
            try {
                val url = URL(item.fileUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.connect()

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    conn.inputStream.use { input ->
                        FileOutputStream(previewFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (previewFile.exists() && previewFile.length() > 0) {
                        downloaded = true
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (downloaded && previewFile.exists()) {
            try {
                val tf = Typeface.createFromFile(previewFile)
                item.typeface = tf
                prunePreviewCache(previewDir)
                return@withContext tf
            } catch (e: Exception) {
                previewFile.delete()
            }
        }

        return@withContext null
    }
}
