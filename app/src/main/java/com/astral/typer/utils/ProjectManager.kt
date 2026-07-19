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
import com.astral.typer.TyperApplication
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
        val type: String, // "BOLD", "ITALIC", "BOLD_ITALIC", "UNDERLINE", "STRIKETHROUGH", "COLOR", "FONT", "SIZE", "LETTER_SPACING"
        val start: Int,
        val end: Int,
        val value: String? = null
    )

    data class LayerModel(
        val type: String, // "TEXT" or "IMAGE"
        val x: Float, val y: Float, val rotation: Float, val scaleX: Float, val scaleY: Float,
        val isVisible: Boolean, val isLocked: Boolean, val name: String,
        val opacity: Int, val blendMode: String,
        val isOpacityGradient: Boolean, val opacityStart: Int, val opacityEnd: Int, val opacityAngle: Int,

        // Brush Layer
        val brushPath: String? = null,
        val brushName: String? = null,
        val brushColor: Int? = null,
        val brushSize: Float? = null,
        val brushHardness: Float? = null,
        val brushOpacity: Int? = null,
        val brushDabsPerActualRadius: Float? = null,
        val brushDabsPerBasicRadius: Float? = null,
        val brushDabsPerSecond: Float? = null,
        val brushOffsetByRandom: Float? = null,
        val brushRadiusByRandom: Float? = null,
        val brushEllipticalDabRatio: Float? = null,
        val brushEllipticalDabAngle: Float? = null,
        val brushSmudge: Float? = null,
        val brushSmudgeLength: Float? = null,
        val brushSlowTracking: Float? = null,

        // Shape Layer
        val shapeName: String? = null,

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
        val isMotionShadow: Boolean? = null, val isMotionShadowIncludeStroke: Boolean? = null, val motionShadowAngle: Int? = null, val motionShadowDistance: Float? = null,
        val motionShadowThickness: Float? = null,

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
        val letterWarpMeshes: Map<String, List<Float>>? = null,
        val letterWarpRows: Map<String, Int>? = null,
        val letterWarpCols: Map<String, Int>? = null,

        // Texture
        val texturePath: String? = null, val textureOffsetX: Float? = null, val textureOffsetY: Float? = null,

        // Built-in Pattern
        val patternName: String? = null,
        val patternColor: Int? = null,
        val patternAlpha: Int? = null,
        val patternScale: Float? = null,
        val patternRotation: Float? = null,

        // Erase
        val eraseMaskPath: String? = null,

        // Effect
        val currentEffect: String? = null, val secondaryEffect: String? = null, val effectSeed: Long? = null,
        val chromaticColors: List<Int>? = null,
        val blurRadius: Float? = null,
        val longShadowLength: Float? = null, val longShadowColor: Int? = null, val longShadowAngle: Float? = null,
        val motionBlurLength: Float? = null, val motionBlurAngle: Int? = null,
        val halftoneDotSize: Float? = null, val halftoneDotColor: Int? = null, val halftoneThreshold: Float? = null,
        val neonRadius: Float? = null, val neonColor: Int? = null,
        val glitchIntensity: Float? = null,
        val pixelBlockSize: Float? = null,
        val chromaticShift: Float? = null,
        val fieryColor: Int? = null, val fieryIntensity: Float? = null,
        val wavyIntensity: Float? = null, val wavyFrequency: Float? = null,
        val particleSize: Float? = null, val particleSpread: Float? = null, val particleDissolveAngle: Float? = null,
        val multiGradientColors: List<Int>? = null, val multiGradientAngle: Float? = null,
        val radialBlurInnerRadius: Float? = null, val radialBlurMotionStrength: Float? = null,
        val decayIntensity: Float? = null, val decayFadingLevel: Float? = null,
        val isOval: Boolean? = null,
        val fixedHeight: Float? = null,
        val isGlobalGradient: Boolean? = null,
        val globalP1X: Float? = null, val globalP1Y: Float? = null,
        val globalP2X: Float? = null, val globalP2Y: Float? = null
    )

    private val gson = GsonBuilder().setPrettyPrinting().create()

    @Volatile
    var isSaving = false

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
        thumbnail: Bitmap? = null,
        subFolder: String? = null
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
                    val foregroundColors = spanStr.getSpans(0, spanStr.length, android.text.style.ForegroundColorSpan::class.java)
                    for (fc in foregroundColors) {
                        val hexColor = String.format("#%08X", fc.foregroundColor)
                        spanModels.add(SpanModel("COLOR", spanStr.getSpanStart(fc), spanStr.getSpanEnd(fc), hexColor))
                    }
                    val customFonts = spanStr.getSpans(0, spanStr.length, CustomTypefaceSpan::class.java)
                    for (cf in customFonts) {
                        spanModels.add(SpanModel("FONT", spanStr.getSpanStart(cf), spanStr.getSpanEnd(cf), cf.fontPath))
                    }
                    val absoluteSizes = spanStr.getSpans(0, spanStr.length, android.text.style.AbsoluteSizeSpan::class.java)
                    for (asize in absoluteSizes) {
                        spanModels.add(SpanModel("SIZE", spanStr.getSpanStart(asize), spanStr.getSpanEnd(asize), asize.size.toString()))
                    }
                    val letterSpacings = spanStr.getSpans(0, spanStr.length, LetterSpacingSpan::class.java)
                    for (ls in letterSpacings) {
                        spanModels.add(SpanModel("LETTER_SPACING", spanStr.getSpanStart(ls), spanStr.getSpanEnd(ls), ls.spacing.toString()))
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
                        isMotionShadow = layer.isMotionShadow, isMotionShadowIncludeStroke = layer.isMotionShadowIncludeStroke, motionShadowAngle = layer.motionShadowAngle, motionShadowDistance = layer.motionShadowDistance,
                        motionShadowThickness = layer.motionShadowThickness,

                        isGradient = layer.isGradient, gradientStartColor = layer.gradientStartColor, gradientEndColor = layer.gradientEndColor, gradientAngle = layer.gradientAngle,
                        isGradientText = layer.isGradientText, isGradientStroke = layer.isGradientStroke, isGradientShadow = layer.isGradientShadow,

                        strokeColor = layer.strokeColor, strokeWidth = layer.strokeWidth,
                        doubleStrokeColor = layer.doubleStrokeColor, doubleStrokeWidth = layer.doubleStrokeWidth,

                        isPerspective = layer.isPerspective, perspectivePoints = layer.perspectivePoints?.toList(),
                        isWarp = layer.isWarp, warpRows = layer.mainWarpRows, warpCols = layer.mainWarpCols, warpMesh = layer.mainWarpMesh?.toList(),
                        letterWarpMeshes = layer.letterWarpMeshes.mapKeys { it.key.toString() }.mapValues { it.value.toList() },
                        letterWarpRows = layer.letterWarpRows.mapKeys { it.key.toString() },
                        letterWarpCols = layer.letterWarpCols.mapKeys { it.key.toString() },

                        texturePath = texPath, textureOffsetX = layer.textureOffsetX, textureOffsetY = layer.textureOffsetY,
                        patternName = layer.patternName,
                        patternColor = layer.patternColor,
                        patternAlpha = layer.patternAlpha,
                        patternScale = layer.patternScale,
                        patternRotation = layer.patternRotation,
                        eraseMaskPath = erasePath,
                        currentEffect = layer.currentEffect.name, secondaryEffect = layer.secondaryEffect.name, effectSeed = layer.effectSeed,
                        chromaticColors = layer.chromaticColors.toList(),
                        blurRadius = layer.blurRadius,
                        longShadowLength = layer.longShadowLength, longShadowColor = layer.longShadowColor, longShadowAngle = layer.longShadowAngle,
                        motionBlurLength = layer.motionBlurLength, motionBlurAngle = layer.motionBlurAngle,
                        halftoneDotSize = layer.halftoneDotSize, halftoneDotColor = layer.halftoneDotColor, halftoneThreshold = layer.halftoneThreshold,
                        neonRadius = layer.neonRadius, neonColor = layer.neonColor,
                        glitchIntensity = layer.glitchIntensity,
                        pixelBlockSize = layer.pixelBlockSize,
                        chromaticShift = layer.chromaticShift,
                        fieryColor = layer.fieryColor, fieryIntensity = layer.fieryIntensity,
                        wavyIntensity = layer.wavyIntensity, wavyFrequency = layer.wavyFrequency,
                        particleSize = layer.particleSize, particleSpread = layer.particleSpread, particleDissolveAngle = layer.particleDissolveAngle,
                        multiGradientColors = layer.multiGradientColors.toList(), multiGradientAngle = layer.multiGradientAngle,
                        radialBlurInnerRadius = layer.radialBlurInnerRadius, radialBlurMotionStrength = layer.radialBlurMotionStrength,
                        decayIntensity = layer.decayIntensity, decayFadingLevel = layer.decayFadingLevel,
                        isOval = layer.isOval,
                        fixedHeight = layer.fixedHeight,
                        isGlobalGradient = layer.isGlobalGradient,
                        globalP1X = layer.globalP1.x, globalP1Y = layer.globalP1.y,
                        globalP2X = layer.globalP2.x, globalP2Y = layer.globalP2.y
                    ))

                } else if (layer is com.astral.typer.models.ShapeLayer) {
                    var texPath: String? = null
                    if (layer.textureBitmap != null) {
                        val name = "shape_tex_$index.png"
                        saveBitmap(layer.textureBitmap!!, File(imagesDir, name))
                        texPath = "images/$name"
                    }
                    var erasePath: String? = null
                    if (layer.eraseMask != null) {
                        val name = "shape_erase_$index.png"
                        saveBitmap(layer.eraseMask!!, File(imagesDir, name))
                        erasePath = "images/$name"
                    }
                    layerModels.add(LayerModel(
                        type = "SHAPE",
                        x = layer.x, y = layer.y, rotation = layer.rotation, scaleX = layer.scaleX, scaleY = layer.scaleY,
                        isVisible = layer.isVisible, isLocked = layer.isLocked, name = layer.name,
                        opacity = layer.opacity, blendMode = layer.blendMode,
                        isOpacityGradient = layer.isOpacityGradient, opacityStart = layer.opacityStart, opacityEnd = layer.opacityEnd, opacityAngle = layer.opacityAngle,
                        shapeName = layer.shapeName, color = layer.color,
                        shadowColor = layer.shadowColor, shadowRadius = layer.shadowRadius, shadowDx = layer.shadowDx, shadowDy = layer.shadowDy,
                        isMotionShadow = layer.isMotionShadow, isMotionShadowIncludeStroke = layer.isMotionShadowIncludeStroke, motionShadowAngle = layer.motionShadowAngle, motionShadowDistance = layer.motionShadowDistance,
                        motionShadowThickness = layer.motionShadowThickness,
                        isGradient = layer.isGradient, gradientStartColor = layer.gradientStartColor, gradientEndColor = layer.gradientEndColor, gradientAngle = layer.gradientAngle,
                        isGradientText = layer.isGradientText, isGradientStroke = layer.isGradientStroke, isGradientShadow = layer.isGradientShadow,
                        strokeColor = layer.strokeColor, strokeWidth = layer.strokeWidth,
                        doubleStrokeColor = layer.doubleStrokeColor, doubleStrokeWidth = layer.doubleStrokeWidth,
                        isPerspective = layer.isPerspective, perspectivePoints = layer.perspectivePoints?.toList(),
                        isWarp = layer.isWarp, warpRows = layer.warpRows, warpCols = layer.warpCols, warpMesh = layer.warpMesh?.toList(),
                        texturePath = texPath, textureOffsetX = layer.textureOffsetX, textureOffsetY = layer.textureOffsetY,
                        patternName = layer.patternName, patternColor = layer.patternColor, patternAlpha = layer.patternAlpha, patternScale = layer.patternScale, patternRotation = layer.patternRotation,
                        eraseMaskPath = erasePath,
                        currentEffect = layer.currentEffect.name, secondaryEffect = layer.secondaryEffect.name, effectSeed = layer.effectSeed,
                        chromaticColors = layer.chromaticColors.toList(), blurRadius = layer.blurRadius,
                        longShadowLength = layer.longShadowLength, longShadowColor = layer.longShadowColor, longShadowAngle = layer.longShadowAngle,
                        motionBlurLength = layer.motionBlurLength, motionBlurAngle = layer.motionBlurAngle,
                        halftoneDotSize = layer.halftoneDotSize, halftoneDotColor = layer.halftoneDotColor, halftoneThreshold = layer.halftoneThreshold,
                        neonRadius = layer.neonRadius, neonColor = layer.neonColor, glitchIntensity = layer.glitchIntensity, pixelBlockSize = layer.pixelBlockSize, chromaticShift = layer.chromaticShift,
                        fieryColor = layer.fieryColor, fieryIntensity = layer.fieryIntensity, wavyIntensity = layer.wavyIntensity, wavyFrequency = layer.wavyFrequency,
                        particleSize = layer.particleSize, particleSpread = layer.particleSpread, particleDissolveAngle = layer.particleDissolveAngle,
                        multiGradientColors = layer.multiGradientColors.toList(), multiGradientAngle = layer.multiGradientAngle,
                        radialBlurInnerRadius = layer.radialBlurInnerRadius, radialBlurMotionStrength = layer.radialBlurMotionStrength,
                        decayIntensity = layer.decayIntensity, decayFadingLevel = layer.decayFadingLevel,
                        isGlobalGradient = layer.isGlobalGradient, globalP1X = layer.globalP1.x, globalP1Y = layer.globalP1.y, globalP2X = layer.globalP2.x, globalP2Y = layer.globalP2.y
                    ))
                } else if (layer is ImageLayer) {
                    val imgName = "layer_$index.png"
                    saveBitmap(layer.bitmap, File(imagesDir, imgName))

                    var erasePath: String? = null
                    if (layer.eraseMask != null) {
                        val name = "image_erase_$index.png"
                        saveBitmap(layer.eraseMask!!, File(imagesDir, name))
                        erasePath = "images/$name"
                    }

                    layerModels.add(LayerModel(
                        type = "IMAGE",
                        x = layer.x, y = layer.y, rotation = layer.rotation, scaleX = layer.scaleX, scaleY = layer.scaleY,
                        isVisible = layer.isVisible, isLocked = layer.isLocked, name = layer.name,
                        opacity = layer.opacity, blendMode = layer.blendMode,
                        isOpacityGradient = layer.isOpacityGradient, opacityStart = layer.opacityStart, opacityEnd = layer.opacityEnd, opacityAngle = layer.opacityAngle,
                        imagePath = "images/$imgName",
                        isPerspective = layer.isPerspective, perspectivePoints = layer.perspectivePoints?.toList(),
                        isWarp = layer.isWarp, warpRows = layer.warpRows, warpCols = layer.warpCols, warpMesh = layer.warpMesh?.toList(),
                        eraseMaskPath = erasePath,
                        decayIntensity = layer.decayIntensity, decayFadingLevel = layer.decayFadingLevel
                    ))
                } else if (layer is com.astral.typer.models.BrushLayer) {
                    val brushImgName = "brush_$index.png"
                    saveBitmap(layer.bitmap, File(imagesDir, brushImgName))

                    var erasePath: String? = null
                    if (layer.eraseMask != null) {
                        val name = "brush_erase_$index.png"
                        saveBitmap(layer.eraseMask!!, File(imagesDir, name))
                        erasePath = "images/$name"
                    }

                    layerModels.add(LayerModel(
                        type = "BRUSH",
                        x = layer.x, y = layer.y, rotation = layer.rotation, scaleX = layer.scaleX, scaleY = layer.scaleY,
                        isVisible = layer.isVisible, isLocked = layer.isLocked, name = layer.name,
                        opacity = layer.opacity, blendMode = layer.blendMode,
                        isOpacityGradient = layer.isOpacityGradient, opacityStart = layer.opacityStart, opacityEnd = layer.opacityEnd, opacityAngle = layer.opacityAngle,
                        brushPath = "images/$brushImgName",
                        brushName = layer.brushName,
                        brushColor = layer.brushColor,
                        brushSize = layer.brushSize,
                        brushHardness = layer.brushHardness,
                        brushOpacity = layer.brushOpacity,
                        brushDabsPerActualRadius = layer.brushDabsPerActualRadius,
                        brushDabsPerBasicRadius = layer.brushDabsPerBasicRadius,
                        brushDabsPerSecond = layer.brushDabsPerSecond,
                        brushOffsetByRandom = layer.brushOffsetByRandom,
                        brushRadiusByRandom = layer.brushRadiusByRandom,
                        brushEllipticalDabRatio = layer.brushEllipticalDabRatio,
                        brushEllipticalDabAngle = layer.brushEllipticalDabAngle,
                        brushSmudge = layer.brushSmudge,
                        brushSmudgeLength = layer.brushSmudgeLength,
                        brushSlowTracking = layer.brushSlowTracking,
                        eraseMaskPath = erasePath
                    ))
                }
            }

            val projectData = ProjectData(width, height, canvasColor, layerModels)
            File(tempDir, "project.json").writeText(gson.toJson(projectData))

            return finalizeSave(context, tempDir, projectName, subFolder)

        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private fun finalizeSave(context: Context, tempDir: File, projectName: String, subFolder: String? = null): Boolean {
        var cleanName = projectName.trim()

        if (cleanName == "autosave") {
            cleanName = "autosave_${System.currentTimeMillis()}"
        }

        // Invalidate Thumbnail Cache for this project
        try {
            val cacheDir = File(context.cacheDir, "thumbnails")
            val cacheFile = File(cacheDir, "$cleanName.atd.png")
            if (cacheFile.exists()) cacheFile.delete()
        } catch (e: Exception) { e.printStackTrace() }

        var success = false

        // MediaStore (Android 10+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                val resolver = context.contentResolver

                val relativePath = if (subFolder.isNullOrEmpty()) "Pictures/AstralTyper/Project" else "Pictures/AstralTyper/Project/$subFolder"

                // Attempt to overwrite existing MediaStore entry by deleting it first
                val selection = "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
                val selectionArgs = arrayOf("$cleanName.atd", "%$relativePath%")

                try {
                    resolver.query(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        arrayOf(android.provider.MediaStore.MediaColumns._ID),
                        selection,
                        selectionArgs,
                        null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val id = cursor.getLong(cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns._ID))
                            val uriToDelete = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                            resolver.delete(uriToDelete, null, null)
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }

                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "$cleanName.atd")
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                }

                val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        ZipOutputStream(out).use { zipOut -> zipFile(tempDir, tempDir.name, zipOut) }
                    }
                    success = true
                }
            } catch (e: Exception) { e.printStackTrace() }
        } else {
            // Legacy (Android 9 and below)
            try {
                val file = getPublicProjectFile(cleanName, subFolder)
                if (file.parentFile?.exists() == false) file.parentFile?.mkdirs()
                success = zipFolder(tempDir, file)
            } catch (e: Exception) { e.printStackTrace() }
        }

        if (!success) {
            // Fallback
            val file = getPrivateProjectFile(context, cleanName, subFolder)
            file.parentFile?.mkdirs()
            success = zipFolder(tempDir, file)
        }

        if (success && projectName.trim() == "autosave") {
            performAutosaveRotation(context)
        }

        return success
    }

    private fun performAutosaveRotation(context: Context) {
        try {
            val allProjects = getRecentProjects(context)
            val autosaves = allProjects.filter { it.name.startsWith("autosave_") }
                .sortedByDescending { it.lastModified() }

            if (autosaves.size > 3) {
                for (i in 3 until autosaves.size) {
                    autosaves[i].delete()
                    // Delete thumbnail cache if exists
                    try {
                        val cacheDir = File(context.cacheDir, "thumbnails")
                        val cacheFile = File(cacheDir, "${autosaves[i].name}.png")
                        if (cacheFile.exists()) cacheFile.delete()
                    } catch (e: Exception) {}
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
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

    fun loadFolderThumbnail(context: Context, folder: File): Bitmap? {
        val projects = folder.listFiles { f -> f.extension == "atd" }?.sortedByDescending { it.lastModified() }?.take(3) ?: return null
        if (projects.isEmpty()) return null

        val size = 300
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(result)

        val itemSize = (size * 0.8f).toInt()
        for (i in projects.indices.reversed()) {
            val bmp = loadThumbnail(context, projects[i])
            if (bmp != null) {
                val offset = (i * 20).toFloat()

                // Draw center-cropped
                val src = android.graphics.Rect()
                val dst = android.graphics.RectF(offset, offset, offset + itemSize, offset + itemSize)

                val bmpW = bmp.width
                val bmpH = bmp.height
                if (bmpW > bmpH) {
                    val left = (bmpW - bmpH) / 2
                    src.set(left, 0, left + bmpH, bmpH)
                } else {
                    val top = (bmpH - bmpW) / 2
                    src.set(0, top, bmpW, top + bmpW)
                }

                canvas.drawBitmap(bmp, src, dst, null)
                bmp.recycle()
            }
        }
        return result
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
    fun createLayerFromModel(context: Context, model: LayerModel, imageMap: Map<String, Bitmap>): Layer? {
        if (model.type == "IMAGE" && model.imagePath != null) {
            val bmp = imageMap[model.imagePath] ?: return null
            val layer = ImageLayer(bmp)

            model.isPerspective?.let { layer.isPerspective = it }
            model.perspectivePoints?.let { layer.perspectivePoints = it.toFloatArray() }
            model.isWarp?.let { layer.isWarp = it }
            model.warpRows?.let { layer.warpRows = it }
            model.warpCols?.let { layer.warpCols = it }
            model.warpMesh?.let { layer.warpMesh = it.toFloatArray() }
            model.decayIntensity?.let { layer.decayIntensity = it }
            model.decayFadingLevel?.let { layer.decayFadingLevel = it }
            if (model.eraseMaskPath != null) {
                layer.eraseMask = imageMap[model.eraseMaskPath]?.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
            }

            applyCommonProperties(layer, model)
            return layer
        } else if (model.type == "TEXT") {
            val layer = TextLayer(model.text ?: "", model.color ?: Color.BLACK)

            // Restore Spans
            if (!model.text.isNullOrEmpty() && model.spans != null) {
                 val sb = SpannableStringBuilder(model.text)
                 val availableFonts = FontManager.getStandardFonts(TyperApplication.instance!!) + FontManager.getCustomFonts(TyperApplication.instance!!)

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
                             "COLOR" -> {
                                 span.value?.let { colorStr ->
                                     try {
                                         sb.setSpan(android.text.style.ForegroundColorSpan(Color.parseColor(colorStr)), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                                     } catch (e: Exception) {}
                                 }
                             }
                             "FONT" -> {
                                 span.value?.let { fontPath ->
                                     val found = availableFonts.find {
                                         (it.isCustom && it.path == fontPath) || (!it.isCustom && it.name == fontPath)
                                     }
                                     if (found != null) {
                                         sb.setSpan(CustomTypefaceSpan(found.typeface, fontPath), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                                     }
                                 }
                             }
                             "SIZE" -> {
                                 span.value?.let { sizeStr ->
                                     try {
                                         sb.setSpan(android.text.style.AbsoluteSizeSpan(sizeStr.toInt()), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                                     } catch (e: Exception) {}
                                 }
                             }
                             "LETTER_SPACING" -> {
                                 span.value?.let { lsStr ->
                                     try {
                                         sb.setSpan(LetterSpacingSpan(lsStr.toFloat()), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                                     } catch (e: Exception) {}
                                 }
                             }
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
            model.isMotionShadowIncludeStroke?.let { layer.isMotionShadowIncludeStroke = it }
            model.motionShadowAngle?.let { layer.motionShadowAngle = it }
            model.motionShadowDistance?.let { layer.motionShadowDistance = it }
            model.motionShadowThickness?.let { layer.motionShadowThickness = it }

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

            model.letterWarpMeshes?.forEach { (k, v) ->
                val idx = k.toIntOrNull()
                if (idx != null) {
                    layer.letterWarpMeshes[idx] = v.toFloatArray()
                }
            }
            model.letterWarpRows?.forEach { (k, v) ->
                val idx = k.toIntOrNull()
                if (idx != null) {
                    layer.letterWarpRows[idx] = v
                }
            }
            model.letterWarpCols?.forEach { (k, v) ->
                val idx = k.toIntOrNull()
                if (idx != null) {
                    layer.letterWarpCols[idx] = v
                }
            }

            if (model.texturePath != null) {
                layer.textureBitmap = imageMap[model.texturePath]
                layer.textureOffsetX = model.textureOffsetX ?: 0f
                layer.textureOffsetY = model.textureOffsetY ?: 0f
            }

            model.patternName?.let { layer.patternName = it }
            model.patternColor?.let { layer.patternColor = it }
            model.patternAlpha?.let { layer.patternAlpha = it }
            model.patternScale?.let { layer.patternScale = it }
            model.patternRotation?.let { layer.patternRotation = it }

            if (model.eraseMaskPath != null) {
                layer.eraseMask = imageMap[model.eraseMaskPath]?.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
            }

            model.currentEffect?.let {
                try { layer.currentEffect = TextEffectType.valueOf(it) } catch(e:Exception){}
            }
            model.secondaryEffect?.let {
                try { layer.secondaryEffect = TextEffectType.valueOf(it) } catch(e:Exception){}
            }
            model.effectSeed?.let { layer.effectSeed = it }
            model.chromaticColors?.let { layer.chromaticColors = it.toIntArray() }

            model.blurRadius?.let { layer.blurRadius = it }
            model.longShadowLength?.let { layer.longShadowLength = it }
            model.longShadowColor?.let { layer.longShadowColor = it }
            model.longShadowAngle?.let { layer.longShadowAngle = it }
            model.motionBlurLength?.let { layer.motionBlurLength = it }
            model.motionBlurAngle?.let { layer.motionBlurAngle = it }
            model.halftoneDotSize?.let { layer.halftoneDotSize = it }
            model.halftoneDotColor?.let { layer.halftoneDotColor = it }
            model.halftoneThreshold?.let { layer.halftoneThreshold = it }
            model.neonRadius?.let { layer.neonRadius = it }
            model.neonColor?.let { layer.neonColor = it }
            model.glitchIntensity?.let { layer.glitchIntensity = it }
            model.pixelBlockSize?.let { layer.pixelBlockSize = it }
            model.chromaticShift?.let { layer.chromaticShift = it }
            model.fieryColor?.let { layer.fieryColor = it }
            model.fieryIntensity?.let { layer.fieryIntensity = it }
            model.wavyIntensity?.let { layer.wavyIntensity = it }
            model.wavyFrequency?.let { layer.wavyFrequency = it }
            model.particleSize?.let { layer.particleSize = it }
            model.particleSpread?.let { layer.particleSpread = it }
            model.particleDissolveAngle?.let { layer.particleDissolveAngle = it }
            model.multiGradientColors?.let { layer.multiGradientColors = it.toIntArray() }
            model.multiGradientAngle?.let { layer.multiGradientAngle = it }
            model.radialBlurInnerRadius?.let { layer.radialBlurInnerRadius = it }
            model.radialBlurMotionStrength?.let { layer.radialBlurMotionStrength = it }
            model.decayIntensity?.let { layer.decayIntensity = it }
            model.decayFadingLevel?.let { layer.decayFadingLevel = it }
            model.isOval?.let { layer.isOval = it }
            model.fixedHeight?.let { layer.fixedHeight = it }
            model.isGlobalGradient?.let { layer.isGlobalGradient = it }
            if (model.globalP1X != null && model.globalP1Y != null) {
                layer.globalP1.set(model.globalP1X, model.globalP1Y)
            }
            if (model.globalP2X != null && model.globalP2Y != null) {
                layer.globalP2.set(model.globalP2X, model.globalP2Y)
            }

            applyCommonProperties(layer, model)
            return layer
        } else if (model.type == "SHAPE" && model.shapeName != null) {
            val layer = com.astral.typer.models.ShapeLayer(model.shapeName, model.color ?: Color.BLACK)
            model.shadowColor?.let { layer.shadowColor = it }
            model.shadowRadius?.let { layer.shadowRadius = it }
            model.shadowDx?.let { layer.shadowDx = it }
            model.shadowDy?.let { layer.shadowDy = it }
            model.isMotionShadow?.let { layer.isMotionShadow = it }
            model.isMotionShadowIncludeStroke?.let { layer.isMotionShadowIncludeStroke = it }
            model.motionShadowAngle?.let { layer.motionShadowAngle = it }
            model.motionShadowDistance?.let { layer.motionShadowDistance = it }
            model.motionShadowThickness?.let { layer.motionShadowThickness = it }
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
            model.patternName?.let { layer.patternName = it }
            model.patternColor?.let { layer.patternColor = it }
            model.patternAlpha?.let { layer.patternAlpha = it }
            model.patternScale?.let { layer.patternScale = it }
            model.patternRotation?.let { layer.patternRotation = it }
            if (model.eraseMaskPath != null) {
                layer.eraseMask = imageMap[model.eraseMaskPath]?.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
            }
            model.currentEffect?.let {
                try { layer.currentEffect = TextEffectType.valueOf(it) } catch(e:Exception){}
            }
            model.secondaryEffect?.let {
                try { layer.secondaryEffect = TextEffectType.valueOf(it) } catch(e:Exception){}
            }
            model.effectSeed?.let { layer.effectSeed = it }
            model.chromaticColors?.let { layer.chromaticColors = it.toIntArray() }
            model.blurRadius?.let { layer.blurRadius = it }
            model.longShadowLength?.let { layer.longShadowLength = it }
            model.longShadowColor?.let { layer.longShadowColor = it }
            model.longShadowAngle?.let { layer.longShadowAngle = it }
            model.motionBlurLength?.let { layer.motionBlurLength = it }
            model.motionBlurAngle?.let { layer.motionBlurAngle = it }
            model.halftoneDotSize?.let { layer.halftoneDotSize = it }
            model.halftoneDotColor?.let { layer.halftoneDotColor = it }
            model.halftoneThreshold?.let { layer.halftoneThreshold = it }
            model.neonRadius?.let { layer.neonRadius = it }
            model.neonColor?.let { layer.neonColor = it }
            model.glitchIntensity?.let { layer.glitchIntensity = it }
            model.pixelBlockSize?.let { layer.pixelBlockSize = it }
            model.chromaticShift?.let { layer.chromaticShift = it }
            model.fieryColor?.let { layer.fieryColor = it }
            model.fieryIntensity?.let { layer.fieryIntensity = it }
            model.wavyIntensity?.let { layer.wavyIntensity = it }
            model.wavyFrequency?.let { layer.wavyFrequency = it }
            model.particleSize?.let { layer.particleSize = it }
            model.particleSpread?.let { layer.particleSpread = it }
            model.particleDissolveAngle?.let { layer.particleDissolveAngle = it }
            model.multiGradientColors?.let { layer.multiGradientColors = it.toIntArray() }
            model.multiGradientAngle?.let { layer.multiGradientAngle = it }
            model.radialBlurInnerRadius?.let { layer.radialBlurInnerRadius = it }
            model.radialBlurMotionStrength?.let { layer.radialBlurMotionStrength = it }
            model.decayIntensity?.let { layer.decayIntensity = it }
            model.decayFadingLevel?.let { layer.decayFadingLevel = it }
            model.isGlobalGradient?.let { layer.isGlobalGradient = it }
            if (model.globalP1X != null && model.globalP1Y != null) {
                layer.globalP1.set(model.globalP1X, model.globalP1Y)
            }
            if (model.globalP2X != null && model.globalP2Y != null) {
                layer.globalP2.set(model.globalP2X, model.globalP2Y)
            }

            applyCommonProperties(layer, model)
            return layer
        } else if (model.type == "BRUSH" && model.brushPath != null) {
            val bmp = imageMap[model.brushPath] ?: return null
            val layer = com.astral.typer.models.BrushLayer(bmp.width, bmp.height)
            layer.bitmap = bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, true)

            model.brushName?.let {
                layer.brushName = it
                // Dynamically load its preset so that map settings are initialized
                val presetPath = "brushes/classic/${it}.myb"
                try {
                    val preset = com.astral.typer.utils.MyPaintBrushHelper.loadPreset(context, presetPath)
                    layer.mapChangeColorH = preset.changeColorH
                    layer.mapChangeColorL = preset.changeColorL
                    layer.mapChangeColorHslS = preset.changeColorHslS
                    layer.mapChangeColorV = preset.changeColorV
                    layer.mapChangeColorHsvS = preset.changeColorHsvS
                    layer.mapColorH = preset.colorH
                    layer.mapColorS = preset.colorS
                    layer.mapColorV = preset.colorV
                    layer.mapColorize = preset.colorize
                    layer.mapStrokeDurationLogarithmic = preset.strokeDurationLogarithmic
                    layer.mapStrokeHoldtime = preset.strokeHoldtime
                    layer.mapCustomInput = preset.customInput
                    layer.mapCustomInputSlowness = preset.customInputSlowness
                    layer.mapSpeed1Slowness = preset.speed1Slowness
                    layer.mapSpeed1Gamma = preset.speed1Gamma
                    layer.mapSpeed2Slowness = preset.speed2Slowness
                    layer.mapSpeed2Gamma = preset.speed2Gamma

                    layer.mapOpaque = preset.opaqueMapping
                    layer.mapHardness = preset.hardnessMapping
                    layer.mapRadiusLog = preset.radiusLogMapping
                    layer.mapOffsetByRandom = preset.offsetByRandomMapping
                    layer.mapRadiusByRandom = preset.radiusByRandomMapping
                    layer.mapEllipticalDabRatio = preset.ellipticalDabRatioMapping
                    layer.mapEllipticalDabAngle = preset.ellipticalDabAngleMapping
                    layer.mapSmudge = preset.smudgeMapping
                    layer.mapSmudgeLength = preset.smudgeLengthMapping
                } catch(e: Exception) {
                    // Ignore or fallback
                }
            }
            model.brushColor?.let { layer.brushColor = it }
            model.brushSize?.let { layer.brushSize = it }
            model.brushHardness?.let { layer.brushHardness = it }
            model.brushOpacity?.let { layer.brushOpacity = it }
            model.brushDabsPerActualRadius?.let { layer.brushDabsPerActualRadius = it }
            model.brushDabsPerBasicRadius?.let { layer.brushDabsPerBasicRadius = it }
            model.brushDabsPerSecond?.let { layer.brushDabsPerSecond = it }
            model.brushOffsetByRandom?.let { layer.brushOffsetByRandom = it }
            model.brushRadiusByRandom?.let { layer.brushRadiusByRandom = it }
            model.brushEllipticalDabRatio?.let { layer.brushEllipticalDabRatio = it }
            model.brushEllipticalDabAngle?.let { layer.brushEllipticalDabAngle = it }
            model.brushSmudge?.let { layer.brushSmudge = it }
            model.brushSmudgeLength?.let { layer.brushSmudgeLength = it }
            model.brushSlowTracking?.let { layer.brushSlowTracking = it }
            if (model.eraseMaskPath != null) {
                layer.eraseMask = imageMap[model.eraseMaskPath]?.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
            }

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

    fun projectExists(context: Context, projectName: String, subFolder: String? = null): Boolean {
        val cleanName = projectName.trim()

        // Check MediaStore (Android 10+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val relativePath = if (subFolder.isNullOrEmpty()) "Pictures/AstralTyper/Project" else "Pictures/AstralTyper/Project/$subFolder"
            val selection = "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf("$cleanName.atd", "%$relativePath%")
            try {
                context.contentResolver.query(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(android.provider.MediaStore.MediaColumns._ID),
                    selection,
                    selectionArgs,
                    null
                )?.use { cursor ->
                    if (cursor.count > 0) return true
                }
            } catch (e: Exception) {}
        }

        // Check Legacy/Public
        val publicFile = getPublicProjectFile(cleanName, subFolder)
        if (publicFile.exists()) return true

        // Check Private Fallback
        val privateFile = getPrivateProjectFile(context, cleanName, subFolder)
        if (privateFile.exists()) return true

        return false
    }

    fun getRecentProjects(context: Context? = null, parentFolder: File? = null): List<File> {
        if (parentFolder != null) {
            return parentFolder.listFiles()?.toList()?.sortedByDescending { it.lastModified() } ?: emptyList()
        }

        val projects = mutableListOf<File>()
        val publicRoot = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "AstralTyper/Project")
        if (publicRoot.exists()) {
            publicRoot.listFiles()?.let { projects.addAll(it) }
        }

        // Legacy / Root Path scan
        val rootPath = File(Environment.getExternalStorageDirectory(), "AstralTyper/Project")
        if (rootPath.exists()) {
            rootPath.listFiles()?.let { projects.addAll(it) }
        }

        if (context != null) {
             val privateRoot = context.getExternalFilesDir("Projects")
             if (privateRoot != null && privateRoot.exists()) {
                  privateRoot.listFiles()?.let { projects.addAll(it) }
             }
        }
        return projects.sortedByDescending { it.lastModified() }.distinctBy { it.name }
    }

    fun createFolder(context: Context, parentFolder: File?, name: String): Boolean {
        val root = if (parentFolder != null) {
            parentFolder
        } else {
             // Use private root for simplicity in creating folders
             context.getExternalFilesDir("Projects") ?: File(context.filesDir, "Projects")
        }
        val newFolder = File(root, name)
        return newFolder.mkdirs()
    }

    fun renameFile(file: File, newName: String): Boolean {
        val ext = if (file.isDirectory) "" else "." + file.extension
        val target = File(file.parentFile, newName + ext)
        return file.renameTo(target)
    }

    private fun getPublicProjectFile(name: String, subFolder: String? = null): File {
        var root = if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            File(Environment.getExternalStorageDirectory(), "AstralTyper/Project")
        } else {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "AstralTyper/Project")
        }
        if (subFolder != null) {
            root = File(root, subFolder)
        }
        if (!root.exists()) root.mkdirs()
        return File(root, "$name.atd")
    }

    private fun getPrivateProjectFile(context: Context, name: String, subFolder: String? = null): File {
        var root = context.getExternalFilesDir("Projects") ?: File(context.filesDir, "Projects")
        if (subFolder != null) {
            root = File(root, subFolder)
        }
        if (!root.exists()) root.mkdirs()
        return File(root, "$name.atd")
    }

    private fun saveBitmap(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
    }

    fun zipProjectFolder(folder: File, zipFile: File): Boolean {
        return zipFolder(folder, zipFile)
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

    fun exportFolderToPdf(context: Context, folder: File, outputFile: File, quality: Int = 80, onProgress: (Int, Int) -> Unit = {_,_ ->}): Boolean {
        val projects = folder.listFiles { f -> f.extension == "atd" }?.sortedBy { it.name } ?: return false
        if (projects.isEmpty()) return false

        try {
            // Pass 1: Find max width
            var maxWidth = 0
            for (projectFile in projects) {
                val loadResult = loadProject(context, projectFile)
                if (loadResult is LoadResult.Success) {
                    if (loadResult.projectData.canvasWidth > maxWidth) {
                        maxWidth = loadResult.projectData.canvasWidth
                    }
                    loadResult.images.values.forEach { it.recycle() }
                }
            }

            if (maxWidth == 0) return false

            // Dynamically scale target page width based on quality setting to reduce file size quadratically
            val baseWidth = if (maxWidth > 1080) 1080 else maxWidth
            val scaleFactor = (quality.toFloat() / 100f).coerceIn(0.3f, 1.0f)
            val targetPageWidth = (baseWidth * scaleFactor).toInt()

            val jpegBytesList = mutableListOf<ByteArray>()
            val widths = mutableListOf<Int>()
            val heights = mutableListOf<Int>()

            // Pass 2: Process one project at a time and render to JPEG bytes
            for (i in projects.indices) {
                onProgress(i + 1, projects.size)
                val loadResult = loadProject(context, projects[i])
                if (loadResult is LoadResult.Success) {
                    val data = loadResult.projectData
                    val images = loadResult.images

                    val scale = targetPageWidth.toFloat() / data.canvasWidth
                    val targetHeight = (data.canvasHeight * scale).toInt()

                    // Intermediate bitmap for rendering
                    val pageBitmap = Bitmap.createBitmap(targetPageWidth, targetHeight, Bitmap.Config.ARGB_8888)
                    val tempCanvas = android.graphics.Canvas(pageBitmap)
                    tempCanvas.scale(scale, scale)

                    // Draw Content to tempCanvas
                    tempCanvas.drawColor(data.canvasColor)
                    if (images.containsKey("images/background.png")) {
                        tempCanvas.drawBitmap(images["images/background.png"]!!, 0f, 0f, null)
                    }
                    for (model in data.layers) {
                        val layer = createLayerFromModel(context, model, images)
                        layer?.draw(tempCanvas)
                    }

                    // Compress using a high JPEG quality to ensure presentable visual clarity (no blocky artifacts)
                    val stream = java.io.ByteArrayOutputStream()
                    val jpegQuality = quality.coerceAtLeast(80)
                    pageBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, stream)
                    jpegBytesList.add(stream.toByteArray())
                    widths.add(targetPageWidth)
                    heights.add(targetHeight)

                    pageBitmap.recycle()
                    images.values.forEach { it.recycle() }
                }
            }

            if (jpegBytesList.isEmpty()) return false

            // Pass 3: Write PDF using direct JPEG embedding (DCTDecode)
            FileOutputStream(outputFile).use { fos ->
                val out = java.io.ByteArrayOutputStream()
                val offsets = mutableListOf<Long>()

                fun writeStr(str: String) {
                    out.write(str.toByteArray(Charsets.ISO_8859_1))
                }

                writeStr("%PDF-1.4\n")
                writeStr("%\u00e2\u00e3\u00cf\u00d3\n")

                // 1. Catalog Object
                offsets.add(out.size().toLong())
                writeStr("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")

                // 2. Pages Object
                val kids = StringBuilder()
                for (idx in jpegBytesList.indices) {
                    kids.append("${3 + 3 * idx} 0 R ")
                }
                offsets.add(out.size().toLong())
                writeStr("2 0 obj\n<< /Type /Pages /Kids [ $kids] /Count ${jpegBytesList.size} >>\nendobj\n")

                // Pages and Resources
                for (idx in jpegBytesList.indices) {
                    val pageObjectId = 3 + 3 * idx
                    val imageObjectId = 4 + 3 * idx
                    val contentObjectId = 5 + 3 * idx
                    val w = widths[idx]
                    val h = heights[idx]
                    val jpegBytes = jpegBytesList[idx]

                    // Page Object
                    offsets.add(out.size().toLong())
                    writeStr("$pageObjectId 0 obj\n")
                    writeStr("<< /Type /Page\n")
                    writeStr(" /Parent 2 0 R\n")
                    writeStr(" /MediaBox [ 0 0 $w $h ]\n")
                    writeStr(" /Resources << /XObject << /Im1 $imageObjectId 0 R >> >>\n")
                    writeStr(" /Contents $contentObjectId 0 R\n")
                    writeStr(">>\n")
                    writeStr("endobj\n")

                    // Image Object (DCTDecode)
                    offsets.add(out.size().toLong())
                    writeStr("$imageObjectId 0 obj\n")
                    writeStr("<< /Type /XObject\n")
                    writeStr(" /Subtype /Image\n")
                    writeStr(" /Width $w\n")
                    writeStr(" /Height $h\n")
                    writeStr(" /ColorSpace /DeviceRGB\n")
                    writeStr(" /BitsPerComponent 8\n")
                    writeStr(" /Filter /DCTDecode\n")
                    writeStr(" /Length ${jpegBytes.size}\n")
                    writeStr(">>\n")
                    writeStr("stream\n")
                    out.write(jpegBytes)
                    writeStr("\nendstream\n")
                    writeStr("endobj\n")

                    // Content Stream Object
                    val contentStream = "q\n$w 0 0 $h 0 0 cm\n/Im1 Do\nQ\n"
                    val contentBytes = contentStream.toByteArray(Charsets.ISO_8859_1)

                    offsets.add(out.size().toLong())
                    writeStr("$contentObjectId 0 obj\n")
                    writeStr("<< /Length ${contentBytes.size} >>\n")
                    writeStr("stream\n")
                    out.write(contentBytes)
                    writeStr("\nendstream\n")
                    writeStr("endobj\n")
                }

                // Xref table
                val xrefStart = out.size().toLong()
                writeStr("xref\n")
                writeStr("0 ${offsets.size + 1}\n")
                writeStr("0000000000 65535 f\r\n")
                for (offset in offsets) {
                    writeStr(String.format(java.util.Locale.US, "%010d 00000 n\r\n", offset))
                }

                // Trailer
                writeStr("trailer\n")
                writeStr("<< /Size ${offsets.size + 1}\n")
                writeStr(" /Root 1 0 R\n")
                writeStr(">>\n")
                writeStr("startxref\n")
                writeStr("$xrefStart\n")
                writeStr("%%EOF\n")

                out.writeTo(fos)
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    fun importZipContent(context: Context, zipUri: android.net.Uri, zipName: String, onProgress: (Int, Int) -> Unit): Boolean {
        val tempDir = File(context.cacheDir, "import_zip_temp")
        if (tempDir.exists()) tempDir.deleteRecursively()
        tempDir.mkdirs()

        val zipFile = File(tempDir, "temp.zip")
        try {
            context.contentResolver.openInputStream(zipUri)?.use { input ->
                zipFile.outputStream().use { output -> input.copyTo(output) }
            }

            val extractDir = File(tempDir, "extracted")
            extractDir.mkdirs()
            if (!unzip(zipFile, extractDir)) return false

            val targetFolder = File(context.getExternalFilesDir("Projects"), zipName)
            targetFolder.mkdirs()

            val files = extractDir.listFiles() ?: return false
            val images = files.filter { it.extension.lowercase() in listOf("jpg", "jpeg", "png", "webp") }
            val projects = files.filter { it.extension.lowercase() == "atd" }

            if (images.isNotEmpty()) {
                for ((index, imgFile) in images.withIndex()) {
                    val bmp = BitmapFactory.decodeFile(imgFile.absolutePath)
                    if (bmp != null) {
                        saveProject(context, emptyList(), bmp.width, bmp.height, Color.TRANSPARENT, bmp, imgFile.nameWithoutExtension, bmp, zipName)
                        bmp.recycle()
                    }
                    onProgress(index + 1, images.size)
                }
                return true
            } else if (projects.isNotEmpty()) {
                for ((index, projFile) in projects.withIndex()) {
                    projFile.copyTo(File(targetFolder, projFile.name), true)
                    onProgress(index + 1, projects.size)
                }
                return true
            }
            return false
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            tempDir.deleteRecursively()
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
