package com.astral.typer.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import com.astral.typer.models.ImageLayer
import com.astral.typer.models.Layer
import com.astral.typer.models.TextLayer
import com.google.gson.*
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ProjectManager {

    data class ProjectData(
        val canvasWidth: Int,
        val canvasHeight: Int,
        val canvasColor: Int,
        val layers: List<LayerModel>
    )

    // Simplified Model for Serialization
    data class LayerModel(
        val type: String, // "TEXT" or "IMAGE"
        val x: Float,
        val y: Float,
        val rotation: Float,
        val scaleX: Float,
        val scaleY: Float,
        val imagePath: String? = null,
        // Text Properties
        val text: String? = null,
        val color: Int? = null,
        val fontSize: Float? = null,
        val fontName: String? = null, // Store font name/path to restore
        val opacity: Float? = null,
        val shadowColor: Int? = null,
        val shadowRadius: Float? = null,
        val shadowDx: Float? = null,
        val shadowDy: Float? = null,
        // ... other properties
        val boxWidth: Float? = null
    )

    private val gson = GsonBuilder().setPrettyPrinting().create()

    sealed class LoadResult {
        data class Success(val projectData: ProjectData, val images: Map<String, Bitmap>) : LoadResult()
        data class MissingAssets(val projectData: ProjectData, val images: Map<String, Bitmap>, val missingFonts: List<String>) : LoadResult()
        data class Error(val message: String) : LoadResult()
    }

    fun saveProject(
        context: Context,
        layers: List<Layer>,
        width: Int,
        height: Int,
        canvasColor: Int,
        bgBitmap: Bitmap?,
        projectName: String
    ): Boolean {
        try {
            // 1. Prepare Temp Directory
            val tempDir = File(context.cacheDir, "temp_save")
            if (tempDir.exists()) tempDir.deleteRecursively()
            if (!tempDir.mkdirs()) {
                // Try again if deleteRecursively didn't finish immediately or failed partially
                if (!tempDir.exists() && !tempDir.mkdirs()) return false
            }

            val imagesDir = File(tempDir, "images")
            if (!imagesDir.mkdirs()) {
                if (!imagesDir.exists()) return false
            }

            // 2. Save Background
            if (bgBitmap != null) {
                saveBitmap(bgBitmap, File(imagesDir, "background.png"))
            }

            // 3. Convert Layers to Model & Save Images
            val layerModels = mutableListOf<LayerModel>()

            for ((index, layer) in layers.withIndex()) {
                if (layer is TextLayer) {
                    layerModels.add(LayerModel(
                        type = "TEXT",
                        x = layer.x, y = layer.y,
                        rotation = layer.rotation,
                        scaleX = layer.scaleX, scaleY = layer.scaleY,
                        text = layer.text.toString(),
                        color = layer.color,
                        fontSize = layer.fontSize,
                        fontName = layer.fontPath, // Save Font Path
                        boxWidth = layer.boxWidth,
                        // Basic Shadow
                        shadowColor = layer.shadowColor,
                        shadowRadius = layer.shadowRadius,
                        shadowDx = layer.shadowDx,
                        shadowDy = layer.shadowDy
                    ))
                } else if (layer is ImageLayer) {
                    val imgName = "layer_$index.png"
                    saveBitmap(layer.bitmap, File(imagesDir, imgName))
                    layerModels.add(LayerModel(
                        type = "IMAGE",
                        x = layer.x, y = layer.y,
                        rotation = layer.rotation,
                        scaleX = layer.scaleX, scaleY = layer.scaleY,
                        imagePath = "images/$imgName"
                    ))
                }
            }

            val projectData = ProjectData(width, height, canvasColor, layerModels)
            val json = gson.toJson(projectData)
            File(tempDir, "project.json").writeText(json)

            // 4. Zip to Target (Try MediaStore for Public Access on Q+, else legacy File)
            val cleanName = projectName.trim()
            var success = false

            // Attempt MediaStore save (Primary for Android 10+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                try {
                    val contentValues = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "$cleanName.atd")
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/zip") // or application/octet-stream
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/AstralTyper/Project")
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { out ->
                            // Zip children of tempDir directly to root of zip
                            ZipOutputStream(out).use { zipOut ->
                                tempDir.listFiles()?.forEach { child ->
                                    zipFile(child, child.name, zipOut)
                                }
                            }
                        }
                        success = true
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    success = false
                }
            } else {
                // Legacy Android < 10: Use direct File
                try {
                    val primaryFile = getPublicProjectFile(cleanName)
                    primaryFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
                    success = zipFolder(tempDir, primaryFile)
                } catch (e: Exception) {
                    e.printStackTrace()
                    success = false
                }
            }

            // 5. Fallback to App-Specific Storage if Primary failed (Scoped Storage or Permission denied)
            if (!success) {
                val fallbackFile = getPrivateProjectFile(context, cleanName)
                fallbackFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
                success = zipFolder(tempDir, fallbackFile)
            }

            return success
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private fun getPublicProjectFile(name: String): File {
        val root = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "AstralTyper/Project")
        // Try to create if not exists
        try {
            if (!root.exists()) root.mkdirs()
        } catch (e: Exception) { e.printStackTrace() }
        return File(root, "$name.atd")
    }

    private fun getPrivateProjectFile(context: Context, name: String): File {
        val root = context.getExternalFilesDir("Projects") ?: File(context.filesDir, "Projects")
        if (!root.exists()) root.mkdirs()
        return File(root, "$name.atd")
    }

    fun getRecentProjects(context: Context? = null): List<File> {
        val projects = mutableListOf<File>()

        // 1. Scan Public Dir
        val publicRoot = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "AstralTyper/Project")
        if (publicRoot.exists()) {
             publicRoot.listFiles { file -> file.extension == "atd" }?.let { projects.addAll(it) }
        }

        // 2. Scan Private Dir (Fallback)
        if (context != null) {
             val privateRoot = context.getExternalFilesDir("Projects")
             if (privateRoot != null && privateRoot.exists()) {
                  privateRoot.listFiles { file -> file.extension == "atd" }?.let { projects.addAll(it) }
             }
        }

        return projects.sortedByDescending { it.lastModified() }
            .distinctBy { it.name } // Avoid duplicates if same name exists? Unlikely with paths, but safe.
            .take(10)
    }

    fun loadProject(context: Context, file: File): LoadResult {
         // 1. Unzip to temp
        val tempDir = File(context.cacheDir, "temp_load")
        if (tempDir.exists()) tempDir.deleteRecursively()
        tempDir.mkdirs()

        if (!unzip(file, tempDir)) return LoadResult.Error("Failed to unzip project file")

        // 2. Read JSON
        val jsonFile = File(tempDir, "project.json")
        if (!jsonFile.exists()) return LoadResult.Error("Invalid project structure: project.json missing")

        val projectData = gson.fromJson(jsonFile.readText(), ProjectData::class.java)

        // 3. Load Images
        val imageMap = mutableMapOf<String, Bitmap>()
        val imagesDir = File(tempDir, "images")
        if (imagesDir.exists()) {
            imagesDir.listFiles()?.forEach {
                try {
                    val bmp = BitmapFactory.decodeFile(it.absolutePath)
                    if (bmp != null) {
                         // Key is relative path e.g. "images/layer_0.png"
                         imageMap["images/${it.name}"] = bmp
                         if (it.name == "background.png") imageMap["background"] = bmp
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }

        // 4. Verify Fonts
        val missingFonts = mutableSetOf<String>()
        val availableFonts = FontManager.getStandardFonts(context) + FontManager.getCustomFonts(context)
        val availableFontNames = availableFonts.map { if(it.isCustom) it.path else it.name } // Identifier logic

        for (layer in projectData.layers) {
            if (layer.type == "TEXT" && !layer.fontName.isNullOrEmpty()) {
                val fontIdentifier = layer.fontName
                // Check if this identifier exists in current available fonts
                // Logic:
                // Standard fonts: stored as "Name" (e.g. "Serif")
                // Custom fonts: stored as path? If path is absolute "/sdcard/...", it might fail on other device.
                // If it fails, we treat it as missing.

                // Better check:
                // If standard name matches any standard name -> OK
                // If path, check file existence.

                val exists = availableFonts.any {
                    if (it.isCustom) it.path == fontIdentifier // Path match
                    else it.name == fontIdentifier // Name match
                }

                if (!exists) {
                    // Try by name for custom fonts as fallback?
                    // If stored path is "/old/path/font.ttf" and we have "/new/path/font.ttf" with same name?
                    // Not guaranteed.

                    // Simple check: File existence if it looks like a path
                    val asFile = File(fontIdentifier)
                    if (asFile.isAbsolute) {
                        if (!asFile.exists()) missingFonts.add(fontIdentifier)
                    } else {
                        // It's a name, and not found in standard list
                         missingFonts.add(fontIdentifier)
                    }
                }
            }
        }

        if (missingFonts.isNotEmpty()) {
            return LoadResult.MissingAssets(projectData, imageMap, missingFonts.toList())
        }

        return LoadResult.Success(projectData, imageMap)
    }

    private fun saveBitmap(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    private fun zipFolder(srcFolder: File, destZipFile: File): Boolean {
        try {
            ZipOutputStream(FileOutputStream(destZipFile)).use { zipOut ->
                // Zip the contents of the srcFolder, using srcFolder name as root
                zipFile(srcFolder, srcFolder.name, zipOut)
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private fun zipFile(fileToZip: File, fileName: String, zipOut: ZipOutputStream) {
        if (fileToZip.isHidden) return
        if (fileToZip.isDirectory) {
            if (fileName.endsWith("/")) {
                zipOut.putNextEntry(ZipEntry(fileName))
                zipOut.closeEntry()
            } else {
                zipOut.putNextEntry(ZipEntry("$fileName/"))
                zipOut.closeEntry()
            }
            val children = fileToZip.listFiles()
            children?.forEach { childFile ->
                zipFile(childFile, "$fileName/${childFile.name}", zipOut)
            }
            return
        }
        FileInputStream(fileToZip).use { fis ->
            val zipEntry = ZipEntry(fileName)
            zipOut.putNextEntry(zipEntry)
            val bytes = ByteArray(1024)
            var length: Int
            while (fis.read(bytes).also { length = it } >= 0) {
                zipOut.write(bytes, 0, length)
            }
        }
    }

    private fun unzip(zipFile: File, targetDirectory: File): Boolean {
        try {
            ZipInputStream(FileInputStream(zipFile)).use { zis ->
                var zipEntry = zis.nextEntry
                while (zipEntry != null) {
                    val fileName = zipEntry.name
                    val newFile = File(targetDirectory, fileName)

                    // Zip Slip Protection
                    if (!newFile.canonicalPath.startsWith(targetDirectory.canonicalPath + File.separator)) {
                        throw IOException("Zip entry is outside of the target dir: $fileName")
                    }

                    if (zipEntry.isDirectory) {
                        newFile.mkdirs()
                    } else {
                        File(newFile.parent).mkdirs()
                        FileOutputStream(newFile).use { fos ->
                            val buffer = ByteArray(1024)
                            var len: Int
                            while (zis.read(buffer).also { len = it } > 0) {
                                fos.write(buffer, 0, len)
                            }
                        }
                    }
                    zipEntry = zis.nextEntry
                }
                zis.closeEntry()
            }

            // Fix path mismatch:
            val children = targetDirectory.listFiles()
            if (children != null && children.size == 1 && children[0].isDirectory) {
                // Move content up
                val sub = children[0]
                sub.copyRecursively(targetDirectory, overwrite = true)
                sub.deleteRecursively()
            }

            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
}
