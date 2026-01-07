package com.astral.typer.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.astral.typer.models.ImageLayer
import com.astral.typer.models.Layer
import com.astral.typer.models.TextLayer
import com.google.gson.*
import com.google.gson.reflect.TypeToken
import java.io.*
import java.lang.reflect.Type
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

    fun saveProject(
        context: Context,
        layers: List<Layer>,
        width: Int,
        height: Int,
        canvasColor: Int,
        bgBitmap: Bitmap?,
        projectName: String
    ): Boolean {
        // 1. Prepare Temp Directory
        val tempDir = File(context.cacheDir, "temp_save")
        if (tempDir.exists()) tempDir.deleteRecursively()
        tempDir.mkdirs()

        val imagesDir = File(tempDir, "images")
        imagesDir.mkdirs()

        // 2. Save Background
        if (bgBitmap != null) {
            saveBitmap(bgBitmap, File(imagesDir, "background.png"))
        }

        // 3. Convert Layers to Model & Save Images
        val layerModels = mutableListOf<LayerModel>()

        for ((index, layer) in layers.withIndex()) {
            if (layer is TextLayer) {
                // Todo: Handle full serialization of TextLayer properties (shadows, gradients, etc)
                // For MVP, basic properties
                layerModels.add(LayerModel(
                    type = "TEXT",
                    x = layer.x, y = layer.y,
                    rotation = layer.rotation,
                    scaleX = layer.scaleX, scaleY = layer.scaleY,
                    text = layer.text.toString(),
                    color = layer.color,
                    fontSize = layer.fontSize,
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

        // 4. Zip to Target
        val targetFile = getProjectFile(projectName)
        return zipFolder(tempDir, targetFile)
    }

    private fun getProjectFile(name: String): File {
        val root = File(Environment.getExternalStorageDirectory(), "Pictures/AstralTyper/Project")
        if (!root.exists()) root.mkdirs()
        return File(root, "$name.atd")
    }

    fun getRecentProjects(): List<File> {
        val root = File(Environment.getExternalStorageDirectory(), "Pictures/AstralTyper/Project")
        if (!root.exists()) return emptyList()
        return root.listFiles { file -> file.extension == "atd" }
            ?.sortedByDescending { it.lastModified() }
            ?.take(10) ?: emptyList()
    }

    fun loadProject(context: Context, file: File): Pair<ProjectData, Map<String, Bitmap>>? {
         // 1. Unzip to temp
        val tempDir = File(context.cacheDir, "temp_load")
        if (tempDir.exists()) tempDir.deleteRecursively()
        tempDir.mkdirs()

        if (!unzip(file, tempDir)) return null

        // 2. Read JSON
        val jsonFile = File(tempDir, "project.json")
        if (!jsonFile.exists()) return null

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

        return Pair(projectData, imageMap)
    }

    private fun saveBitmap(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    private fun zipFolder(srcFolder: File, destZipFile: File): Boolean {
        try {
            val zipOut = ZipOutputStream(FileOutputStream(destZipFile))
            zipFile(srcFolder, srcFolder.name, zipOut)
            zipOut.close()
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
            for (childFile in children) {
                zipFile(childFile, "$fileName/${childFile.name}", zipOut)
            }
            return
        }
        val fis = FileInputStream(fileToZip)
        val zipEntry = ZipEntry(fileName)
        zipOut.putNextEntry(zipEntry)
        val bytes = ByteArray(1024)
        var length: Int
        while (fis.read(bytes).also { length = it } >= 0) {
            zipOut.write(bytes, 0, length)
        }
        fis.close()
    }

    private fun unzip(zipFile: File, targetDirectory: File): Boolean {
        try {
            val zis = ZipInputStream(FileInputStream(zipFile))
            var zipEntry = zis.nextEntry
            while (zipEntry != null) {
                // Flatten paths: We just extract everything?
                // Wait, our zip structure contains the root folder name "temp_save/..." usually if using above code.
                // Adjusted zip logic: we should zip CONTENTS of folder, not the folder itself as root if we want cleaner paths.
                // But above code: zipFile(srcFolder, srcFolder.name, ...) creates a root folder inside zip.
                // So when unzipping, we expect that root folder.

                // Let's adjust zip logic to not include root folder?
                // Or just handle it here.

                // Actually, simpler unzip:

                val fileName = zipEntry.name
                val newFile = File(targetDirectory, fileName)

                // Zip Slip Protection
                if (!newFile.canonicalPath.startsWith(targetDirectory.canonicalPath + File.separator)) {
                    throw IOException("Zip entry is outside of the target dir: $fileName")
                }

                // create all non exists folders
                // else you will hit FileNotFoundException for compressed folder
                if (zipEntry.isDirectory) {
                     newFile.mkdirs()
                } else {
                     File(newFile.parent).mkdirs()
                     val fos = FileOutputStream(newFile)
                     val buffer = ByteArray(1024)
                     var len: Int
                     while (zis.read(buffer).also { len = it } > 0) {
                         fos.write(buffer, 0, len)
                     }
                     fos.close()
                }
                zipEntry = zis.nextEntry
            }
            zis.closeEntry()
            zis.close()

            // Fix path mismatch:
            // If we zipped "temp_save", the files are in target/temp_save/project.json
            // We want them in target/project.json
            // Let's move them if nested.
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
