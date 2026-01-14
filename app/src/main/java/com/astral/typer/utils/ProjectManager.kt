package com.astral.typer.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Environment
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import com.astral.typer.models.ImageLayer
import com.astral.typer.models.Layer
import com.astral.typer.models.TextEffectType
import com.astral.typer.models.TextLayer
import com.google.gson.GsonBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ProjectManager {

    data class ProjectData(
        val canvasWidth: Int,
        val canvasHeight: Int,
        val canvasColor: Int,
        val layers: List<LayerModel>
    )

    data class SpanModel(
        val type: String, // "BOLD", "ITALIC", "BOLD_ITALIC", "UNDERLINE", "STRIKETHROUGH"
        val start: Int,
        val end: Int
    )

    data class LayerModel(
        val type: String, // "TEXT" or "IMAGE"
        val x: Float, val y: Float, val rotation: Float, val scaleX: Float, val scaleY: Float,
        val isVisible: Boolean, val isLocked: Boolean, val name: String,
        val opacity: Int, val blendMode: String,
        val isOpacityGradient: Boolean, val opacityStart: Int, val opacityEnd: Int, val opacityAngle: Int,

        // Image Layer
        val imagePath: String? = null,

        // Text Layer
        val text: String? = null,
        val spans: List<SpanModel>? = null,
        val color: Int? = null,
        val fontSize: Float? = null,
        val fontPath: String? = null,
        val textAlign: String? = null, // ALIGN_NORMAL, ALIGN_CENTER, ALIGN_OPPOSITE
        val isJustified: Boolean? = null,
        val letterSpacing: Float? = null,
        val lineSpacing: Float? = null,
        val boxWidth: Float? = null,

        // Shadow
        val shadowColor: Int? = null, val shadowRadius: Float? = null, val shadowDx: Float? = null, val shadowDy: Float? = null,
        val isMotionShadow: Boolean? = null, val motionShadowAngle: Int? = null, val motionShadowDistance: Float? = null,

        // Gradient
        val isGradient: Boolean? = null, val gradientStartColor: Int? = null, val gradientEndColor: Int? = null, val gradientAngle: Int? = null,
        val isGradientText: Boolean? = null, val isGradientStroke: Boolean? = null, val isGradientShadow: Boolean? = null,

        // Stroke
        val strokeColor: Int? = null, val strokeWidth: Float? = null,
        val doubleStrokeColor: Int? = null, val doubleStrokeWidth: Float? = null,

        // Perspective
        val isPerspective: Boolean? = null, val perspectivePoints: List<Float>? = null,

        // Warp
        val isWarp: Boolean? = null, val warpRows: Int? = null, val warpCols: Int? = null, val warpMesh: List<Float>? = null,

        // Texture
        val texturePath: String? = null, val textureOffsetX: Float? = null, val textureOffsetY: Float? = null,

        // Erase
        val eraseMaskPath: String? = null,

        // Effect
        val currentEffect: String? = null, val effectSeed: Long? = null
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
        projectName: String,
        thumbnail: Bitmap? = null
    ): Boolean {
        try {
            val tempDir = File(context.cacheDir, "temp_save")
            if (tempDir.exists()) tempDir.deleteRecursively()
            if (!tempDir.mkdirs() && !tempDir.exists()) return false

            val imagesDir = File(tempDir, "images")
            if (!imagesDir.mkdirs() && !imagesDir.exists()) return false

            if (bgBitmap != null) {
                saveBitmap(bgBitmap, File(imagesDir, "background.png"))
            }

            if (thumbnail != null) {
                saveBitmap(thumbnail, File(tempDir, "thumbnail.png"))
            }

            val layerModels = mutableListOf<LayerModel>()

            for ((index, layer) in layers.withIndex()) {
                if (layer is TextLayer) {
                    // Save Bitmaps
                    var texPath: String? = null
                    if (layer.textureBitmap != null) {
                        val name = "tex_$index.png"
                        saveBitmap(layer.textureBitmap!!, File(imagesDir, name))
                        texPath = "images/$name"
                    }

                    var erasePath: String? = null
                    if (layer.eraseMask != null) {
                        val name = "erase_$index.png"
                        saveBitmap(layer.eraseMask!!, File(imagesDir, name))
                        erasePath = "images/$name"
                    }

                    // Extract Spans
                    val spanModels = mutableListOf<SpanModel>()
                    val spanStr = layer.text
                    val styles = spanStr.getSpans(0, spanStr.length, StyleSpan::class.java)
                    for (s in styles) {
                        val type = when(s.style) {
                            android.graphics.Typeface.BOLD -> "BOLD"
                            android.graphics.Typeface.ITALIC -> "ITALIC"
                            android.graphics.Typeface.BOLD_ITALIC -> "BOLD_ITALIC"
                            else -> "NORMAL"
                        }
                        spanModels.add(SpanModel(type, spanStr.getSpanStart(s), spanStr.getSpanEnd(s)))
                    }
                    val underlines = spanStr.getSpans(0, spanStr.length, UnderlineSpan::class.java)
                    for (u in underlines) {
                        spanModels.add(SpanModel("UNDERLINE", spanStr.getSpanStart(u), spanStr.getSpanEnd(u)))
                    }
                    val strikes = spanStr.getSpans(0, spanStr.length, StrikethroughSpan::class.java)
                    for (s in strikes) {
                        spanModels.add(SpanModel("STRIKETHROUGH", spanStr.getSpanStart(s), spanStr.getSpanEnd(s)))
                    }

                    layerModels.add(LayerModel(
                        type = "TEXT",
                        x = layer.x, y = layer.y, rotation = layer.rotation, scaleX = layer.scaleX, scaleY = layer.scaleY,
                        isVisible = layer.isVisible, isLocked = layer.isLocked, name = layer.name,
                        opacity = layer.opacity, blendMode = layer.blendMode,
                        isOpacityGradient = layer.isOpacityGradient, opacityStart = layer.opacityStart, opacityEnd = layer.opacityEnd, opacityAngle = layer.opacityAngle,

                        text = layer.text.toString(),
                        spans = spanModels,
                        color = layer.color,
                        fontSize = layer.fontSize,
                        fontPath = layer.fontPath,
                        textAlign = layer.textAlign.name,
                        isJustified = layer.isJustified,
                        letterSpacing = layer.letterSpacing,
                        lineSpacing = layer.lineSpacing,
                        boxWidth = layer.boxWidth,

                        shadowColor = layer.shadowColor, shadowRadius = layer.shadowRadius, shadowDx = layer.shadowDx, shadowDy = layer.shadowDy,
                        isMotionShadow = layer.isMotionShadow, motionShadowAngle = layer.motionShadowAngle, motionShadowDistance = layer.motionShadowDistance,

                        isGradient = layer.isGradient, gradientStartColor = layer.gradientStartColor, gradientEndColor = layer.gradientEndColor, gradientAngle = layer.gradientAngle,
                        isGradientText = layer.isGradientText, isGradientStroke = layer.isGradientStroke, isGradientShadow = layer.isGradientShadow,

                        strokeColor = layer.strokeColor, strokeWidth = layer.strokeWidth,
                        doubleStrokeColor = layer.doubleStrokeColor, doubleStrokeWidth = layer.doubleStrokeWidth,

                        isPerspective = layer.isPerspective, perspectivePoints = layer.perspectivePoints?.toList(),
                        isWarp = layer.isWarp, warpRows = layer.warpRows, warpCols = layer.warpCols, warpMesh = layer.warpMesh?.toList(),

                        texturePath = texPath, textureOffsetX = layer.textureOffsetX, textureOffsetY = layer.textureOffsetY,
                        eraseMaskPath = erasePath,
                        currentEffect = layer.currentEffect.name, effectSeed = layer.effectSeed
                    ))

                } else if (layer is ImageLayer) {
                    val imgName = "layer_$index.png"
                    saveBitmap(layer.bitmap, File(imagesDir, imgName))
                    layerModels.add(LayerModel(
                        type = "IMAGE",
                        x = layer.x, y = layer.y, rotation = layer.rotation, scaleX = layer.scaleX, scaleY = layer.scaleY,
                        isVisible = layer.isVisible, isLocked = layer.isLocked, name = layer.name,
                        opacity = layer.opacity, blendMode = layer.blendMode,
                        isOpacityGradient = layer.isOpacityGradient, opacityStart = layer.opacityStart, opacityEnd = layer.opacityEnd, opacityAngle = layer.opacityAngle,
                        imagePath = "images/$imgName"
                    ))
                }
            }

            val projectData = ProjectData(width, height, canvasColor, layerModels)
            File(tempDir, "project.json").writeText(gson.toJson(projectData))

            return finalizeSave(context, tempDir, projectName)

        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private fun finalizeSave(context: Context, tempDir: File, projectName: String): Boolean {
        val cleanName = projectName.trim()

        // Invalidate Thumbnail Cache for this project
        try {
            val cacheDir = File(context.cacheDir, "thumbnails")
            val cacheFile = File(cacheDir, "$cleanName.atd.png")
            if (cacheFile.exists()) cacheFile.delete()
        } catch (e: Exception) { e.printStackTrace() }

        // MediaStore (Android 10+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "$cleanName.atd")
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/AstralTyper/Project")
                }
                val resolver = context.contentResolver
                // Note: If file exists, this creates a new one (e.g., name (1).atd)
                val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        ZipOutputStream(out).use { zipOut -> zipFile(tempDir, tempDir.name, zipOut) }
                    }
                    return true
                }
            } catch (e: Exception) { e.printStackTrace() }
        } else {
            // Legacy
            try {
                val file = getPublicProjectFile(cleanName)
                file.parentFile?.mkdirs()
                return zipFolder(tempDir, file)
            } catch (e: Exception) { e.printStackTrace() }
        }

        // Fallback
        val file = getPrivateProjectFile(context, cleanName)
        file.parentFile?.mkdirs()
        return zipFolder(tempDir, file)
    }

    fun loadProject(context: Context, file: File): LoadResult {
        val tempDir = File(context.cacheDir, "temp_load")
        if (tempDir.exists()) tempDir.deleteRecursively()
        tempDir.mkdirs()

        if (!unzip(file, tempDir)) return LoadResult.Error("Failed to unzip project file")

        val jsonFile = File(tempDir, "project.json")
        if (!jsonFile.exists()) return LoadResult.Error("project.json missing")

        val projectData = gson.fromJson(jsonFile.readText(), ProjectData::class.java)
        val imageMap = mutableMapOf<String, Bitmap>()

        File(tempDir, "images").listFiles()?.forEach {
            try {
                val bmp = BitmapFactory.decodeFile(it.absolutePath)
                if (bmp != null) imageMap["images/${it.name}"] = bmp
            } catch (e: Exception) { }
        }

        val missingFonts = mutableListOf<String>()
        val finalData = projectData.copy(layers = projectData.layers.map { model ->
            if (model.type == "TEXT" && !model.fontPath.isNullOrEmpty()) {
                // Verify font existence logic here if needed
            }
            model
        })

        return LoadResult.Success(finalData, imageMap)
    }

    fun loadThumbnail(context: Context, file: File): Bitmap? {
        // First check cache
        val cacheDir = File(context.cacheDir, "thumbnails")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val cacheFile = File(cacheDir, "${file.name}.png")

        if (cacheFile.exists()) {
             return BitmapFactory.decodeFile(cacheFile.absolutePath)
        }

        // Extract from Zip
        try {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry("thumbnail.png") ?: zip.entries().asSequence().firstOrNull { it.name.endsWith("thumbnail.png") }
                if (entry != null) {
                    zip.getInputStream(entry).use { input ->
                        val bmp = BitmapFactory.decodeStream(input)
                        if (bmp != null) {
                            // Save to cache
                            saveBitmap(bmp, cacheFile)
                            return bmp
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    // Helper to Convert Model to Layer
    fun createLayerFromModel(model: LayerModel, imageMap: Map<String, Bitmap>): Layer? {
        if (model.type == "IMAGE" && model.imagePath != null) {
            val bmp = imageMap[model.imagePath] ?: return null
            val layer = ImageLayer(bmp)
            applyCommonProperties(layer, model)
            return layer
        } else if (model.type == "TEXT") {
            val layer = TextLayer(model.text ?: "", model.color ?: Color.BLACK)

            // Restore Spans
            if (!model.text.isNullOrEmpty() && model.spans != null) {
                 val sb = SpannableStringBuilder(model.text)
                 for (span in model.spans) {
                     val start = span.start.coerceIn(0, sb.length)
                     val end = span.end.coerceIn(0, sb.length)
                     if (start < end) {
                         when(span.type) {
                             "BOLD" -> sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                             "ITALIC" -> sb.setSpan(StyleSpan(android.graphics.Typeface.ITALIC), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                             "BOLD_ITALIC" -> sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD_ITALIC), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                             "UNDERLINE" -> sb.setSpan(UnderlineSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                             "STRIKETHROUGH" -> sb.setSpan(StrikethroughSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                         }
                     }
                 }
                 layer.text = sb
            }

            model.fontSize?.let { layer.fontSize = it }
            layer.fontPath = model.fontPath
            model.textAlign?.let {
                try { layer.textAlign = Layout.Alignment.valueOf(it) } catch(e:Exception){}
            }
            model.isJustified?.let { layer.isJustified = it }
            model.letterSpacing?.let { layer.letterSpacing = it }
            model.lineSpacing?.let { layer.lineSpacing = it }
            layer.boxWidth = model.boxWidth

            model.shadowColor?.let { layer.shadowColor = it }
            model.shadowRadius?.let { layer.shadowRadius = it }
            model.shadowDx?.let { layer.shadowDx = it }
            model.shadowDy?.let { layer.shadowDy = it }

            model.isMotionShadow?.let { layer.isMotionShadow = it }
            model.motionShadowAngle?.let { layer.motionShadowAngle = it }
            model.motionShadowDistance?.let { layer.motionShadowDistance = it }

            model.isGradient?.let { layer.isGradient = it }
            model.gradientStartColor?.let { layer.gradientStartColor = it }
            model.gradientEndColor?.let { layer.gradientEndColor = it }
            model.gradientAngle?.let { layer.gradientAngle = it }
            model.isGradientText?.let { layer.isGradientText = it }
            model.isGradientStroke?.let { layer.isGradientStroke = it }
            model.isGradientShadow?.let { layer.isGradientShadow = it }

            model.strokeColor?.let { layer.strokeColor = it }
            model.strokeWidth?.let { layer.strokeWidth = it }
            model.doubleStrokeColor?.let { layer.doubleStrokeColor = it }
            model.doubleStrokeWidth?.let { layer.doubleStrokeWidth = it }

            model.isPerspective?.let { layer.isPerspective = it }
            model.perspectivePoints?.let { layer.perspectivePoints = it.toFloatArray() }

            model.isWarp?.let { layer.isWarp = it }
            model.warpRows?.let { layer.warpRows = it }
            model.warpCols?.let { layer.warpCols = it }
            model.warpMesh?.let { layer.warpMesh = it.toFloatArray() }

            if (model.texturePath != null) {
                layer.textureBitmap = imageMap[model.texturePath]
                layer.textureOffsetX = model.textureOffsetX ?: 0f
                layer.textureOffsetY = model.textureOffsetY ?: 0f
            }

            if (model.eraseMaskPath != null) {
                layer.eraseMask = imageMap[model.eraseMaskPath]?.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
            }

            model.currentEffect?.let {
                try { layer.currentEffect = TextEffectType.valueOf(it) } catch(e:Exception){}
            }
            model.effectSeed?.let { layer.effectSeed = it }

            applyCommonProperties(layer, model)
            return layer
        }
        return null
    }

    private fun applyCommonProperties(layer: Layer, model: LayerModel) {
        layer.x = model.x
        layer.y = model.y
        layer.rotation = model.rotation
        layer.scaleX = model.scaleX
        layer.scaleY = model.scaleY
        layer.isVisible = model.isVisible
        layer.isLocked = model.isLocked
        layer.name = model.name
        layer.opacity = model.opacity
        layer.blendMode = model.blendMode
        layer.isOpacityGradient = model.isOpacityGradient
        layer.opacityStart = model.opacityStart
        layer.opacityEnd = model.opacityEnd
        layer.opacityAngle = model.opacityAngle
    }

    // --- Helper Methods ---

    fun getRecentProjects(context: Context? = null): List<File> {
        val projects = mutableListOf<File>()
        val publicRoot = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "AstralTyper/Project")
        if (publicRoot.exists()) publicRoot.listFiles { f -> f.extension == "atd" }?.let { projects.addAll(it) }

        // Legacy / Root Path scan
        val rootPath = File(Environment.getExternalStorageDirectory(), "AstralTyper/Project")
        if (rootPath.exists()) rootPath.listFiles { f -> f.extension == "atd" }?.let { projects.addAll(it) }

        if (context != null) {
             val privateRoot = context.getExternalFilesDir("Projects")
             if (privateRoot != null && privateRoot.exists()) {
                  privateRoot.listFiles { f -> f.extension == "atd" }?.let { projects.addAll(it) }
             }
        }
        return projects.sortedByDescending { it.lastModified() }.distinctBy { it.name }
    }

    private fun getPublicProjectFile(name: String): File {
        val root = if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            // Use root storage for better visibility on old Androids
            File(Environment.getExternalStorageDirectory(), "AstralTyper/Project")
        } else {
            // Standard path for newer Androids (if accessed via File API)
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "AstralTyper/Project")
        }
        if (!root.exists()) root.mkdirs()
        return File(root, "$name.atd")
    }

    private fun getPrivateProjectFile(context: Context, name: String): File {
        val root = context.getExternalFilesDir("Projects") ?: File(context.filesDir, "Projects")
        if (!root.exists()) root.mkdirs()
        return File(root, "$name.atd")
    }

    private fun saveBitmap(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
    }

    private fun zipFolder(srcFolder: File, destZipFile: File): Boolean {
        try {
            ZipOutputStream(FileOutputStream(destZipFile)).use { zipOut ->
                zipFile(srcFolder, srcFolder.name, zipOut)
            }
            return true
        } catch (e: Exception) { return false }
    }

    private fun zipFile(fileToZip: File, fileName: String, zipOut: ZipOutputStream) {
        if (fileToZip.isHidden) return
        if (fileToZip.isDirectory) {
            val name = if (fileName.endsWith("/")) fileName else "$fileName/"
            zipOut.putNextEntry(ZipEntry(name))
            zipOut.closeEntry()
            fileToZip.listFiles()?.forEach { child -> zipFile(child, "$fileName/${child.name}", zipOut) }
            return
        }
        FileInputStream(fileToZip).use { fis ->
            zipOut.putNextEntry(ZipEntry(fileName))
            fis.copyTo(zipOut)
        }
    }

    private fun unzip(zipFile: File, targetDirectory: File): Boolean {
        try {
            ZipInputStream(FileInputStream(zipFile)).use { zis ->
                var zipEntry = zis.nextEntry
                while (zipEntry != null) {
                    val fileName = zipEntry.name
                    val newFile = File(targetDirectory, fileName)
                    if (!newFile.canonicalPath.startsWith(targetDirectory.canonicalPath + File.separator)) {
                        throw IOException("Zip entry outside target dir")
                    }
                    if (zipEntry.isDirectory) {
                        newFile.mkdirs()
                    } else {
                        newFile.parentFile?.mkdirs()
                        FileOutputStream(newFile).use { fos -> zis.copyTo(fos) }
                    }
                    zipEntry = zis.nextEntry
                }
            }
            // Move content up if nested in single dir
            val children = targetDirectory.listFiles()
            if (children != null && children.size == 1 && children[0].isDirectory) {
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
