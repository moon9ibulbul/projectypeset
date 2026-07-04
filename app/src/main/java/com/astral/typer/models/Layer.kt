package com.astral.typer.models

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.PointF

interface StylableLayer {
    var color: Int

    // Shadow
    var shadowColor: Int
    var shadowRadius: Float
    var shadowDx: Float
    var shadowDy: Float
    var isMotionShadow: Boolean
    var isMotionShadowIncludeStroke: Boolean
    var motionShadowAngle: Int
    var motionShadowDistance: Float

    // Gradient
    var isGradient: Boolean
    var gradientStartColor: Int
    var gradientEndColor: Int
    var gradientAngle: Int
    var isGradientText: Boolean // Fill
    var isGradientStroke: Boolean
    var isGradientShadow: Boolean
    var isGlobalGradient: Boolean
    var globalP1: PointF
    var globalP2: PointF

    // Stroke
    var strokeColor: Int
    var strokeWidth: Float
    var doubleStrokeColor: Int
    var doubleStrokeWidth: Float

    // Perspective & Warp
    var isPerspective: Boolean
    var perspectivePoints: FloatArray?
    var isWarp: Boolean
    var warpRows: Int
    var warpCols: Int
    var warpMesh: FloatArray?
    fun updateDenseWarpMesh()

    // Texture & Pattern
    var textureBitmap: Bitmap?
    var textureOffsetX: Float
    var textureOffsetY: Float
    var patternName: String?
    var patternColor: Int
    var patternAlpha: Int
    var patternScale: Float
    var patternRotation: Float

    // Effects
    var currentEffect: TextEffectType
    var secondaryEffect: TextEffectType
    var effectSeed: Long
    var blurRadius: Float
    var longShadowLength: Float
    var longShadowColor: Int
    var longShadowAngle: Float
    var motionBlurLength: Float
    var motionBlurAngle: Int
    var halftoneDotSize: Float
    var halftoneDotColor: Int
    var halftoneThreshold: Float
    var neonRadius: Float
    var neonColor: Int
    var glitchIntensity: Float
    var pixelBlockSize: Float
    var chromaticShift: Float
    var chromaticColors: IntArray
    var fieryColor: Int
    var fieryIntensity: Float
    var wavyIntensity: Float
    var wavyFrequency: Float
    var particleSize: Float
    var particleSpread: Float
    var particleDissolveAngle: Float
    var multiGradientColors: IntArray
    var multiGradientAngle: Float
    var radialBlurInnerRadius: Float
    var radialBlurMotionStrength: Float
    var decayIntensity: Float
    var decayFadingLevel: Float

    // Erase
    var eraseMask: Bitmap?
    val erasePaths: MutableList<ErasePathData>
    var activeErasePath: Path?
    var activeEraseSize: Float
    var activeEraseOpacity: Int
    var activeEraseHardness: Float
    fun addErasePath(path: Path, size: Float, opacity: Int, hardness: Float)
    fun undoLastErasePath(baseMask: Bitmap?)
    fun rebuildEraseMask(baseMask: Bitmap?)
}

data class ErasePathData(val path: Path, val size: Float, val opacity: Int, val hardness: Float)

abstract class Layer {
    var x: Float = 0f
    var y: Float = 0f
    var rotation: Float = 0f
    var scaleX: Float = 1f
    var scaleY: Float = 1f
    var isSelected: Boolean = false

    // New Properties for Layer Management
    var isVisible: Boolean = true
    var isLocked: Boolean = false
    var name: String = "Layer"

    // Opacity & Blend
    var opacity: Int = 255
    var blendMode: String = "NORMAL" // NORMAL, OVERLAY, ADD, MULTIPLY, SCREEN, DARKEN, LIGHTEN

    // Opacity Gradient
    var isOpacityGradient: Boolean = false
    var opacityStart: Int = 255
    var opacityEnd: Int = 0
    var opacityAngle: Int = 0

    // Backwards compatibility for uniform scale getter/setter (optional)
    var scale: Float
        get() = (abs(scaleX) + abs(scaleY)) / 2f
        set(value) {
            scaleX = if (scaleX < 0) -value else value
            scaleY = if (scaleY < 0) -value else value
        }

    private fun abs(f: Float) = if (f < 0) -f else f

    // Bounds in local coordinates (before rotation/scale)
    abstract fun getWidth(): Float
    abstract fun getHeight(): Float

    // Draw the content of the layer
    abstract fun draw(canvas: Canvas)

    open fun evaluateBezierSurface(u: Float, v: Float, outPoint: FloatArray) {}

    open fun updateDenseWarpMesh() {}

    // Deep copy
    abstract fun clone(): Layer

    // Check if a point (canvas coordinates) hits this layer
    fun contains(px: Float, py: Float): Boolean {
        if (!isVisible || isLocked) return false

        // Transform point to local layer coordinates
        val tempMatrix = Matrix()
        tempMatrix.setTranslate(x, y)
        tempMatrix.preRotate(rotation)
        tempMatrix.preScale(scaleX, scaleY)

        val inverted = Matrix()
        if (tempMatrix.invert(inverted)) {
            val points = floatArrayOf(px, py)
            inverted.mapPoints(points)
            val localX = points[0]
            val localY = points[1]

            // Assume center origin for simplicity in rotation?
            // Let's standardise: x,y is the Center of the layer.
            val halfW = getWidth() / 2f
            val halfH = getHeight() / 2f

            return localX >= -halfW && localX <= halfW && localY >= -halfH && localY <= halfH
        }
        return false
    }
}
