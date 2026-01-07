package com.astral.typer.utils

import com.astral.typer.models.Layer
import com.astral.typer.models.TextLayer

object UndoManager {
    private val history = java.util.Stack<List<Layer>>()
    private val redoStack = java.util.Stack<List<Layer>>()
    private const val MAX_HISTORY = 50

    // Bitmap History for Inpainting
    private val bitmapHistory = java.util.Stack<android.graphics.Bitmap>()
    private val bitmapRedoStack = java.util.Stack<android.graphics.Bitmap>()
    private const val MAX_BITMAP_HISTORY = 5 // Bitmaps are large

    fun saveState(layers: List<Layer>) {
        // Deep copy list
        val snapshot = layers.map { it.clone() }

        history.push(snapshot)
        if (history.size > MAX_HISTORY) {
            history.removeAt(0)
        }
        redoStack.clear()
    }

    fun saveBitmapState(bitmap: android.graphics.Bitmap) {
        val snapshot = bitmap.copy(bitmap.config, true)
        bitmapHistory.push(snapshot)
        if (bitmapHistory.size > MAX_BITMAP_HISTORY) {
            bitmapHistory.removeAt(0)
        }
        bitmapRedoStack.clear()
    }

    fun undoBitmap(currentBitmap: android.graphics.Bitmap?): android.graphics.Bitmap? {
        if (bitmapHistory.isEmpty()) return null

        if (currentBitmap != null) {
            val snapshot = currentBitmap.copy(currentBitmap.config, true)
            bitmapRedoStack.push(snapshot)
        }

        return bitmapHistory.pop()
    }

    fun redoBitmap(currentBitmap: android.graphics.Bitmap?): android.graphics.Bitmap? {
        if (bitmapRedoStack.isEmpty()) return null

        if (currentBitmap != null) {
            val snapshot = currentBitmap.copy(currentBitmap.config, true)
            bitmapHistory.push(snapshot)
        }

        return bitmapRedoStack.pop()
    }

    fun clearMemory() {
        // Recycle bitmaps to free memory
        bitmapHistory.forEach { it.recycle() }
        bitmapRedoStack.forEach { it.recycle() }
        bitmapHistory.clear()
        bitmapRedoStack.clear()
        history.clear()
        redoStack.clear()
    }

    fun undo(currentLayers: List<Layer>): List<Layer>? {
        if (history.isEmpty()) return null

        // Save current state to redo stack first?
        // Standard undo: Current state is thrown to Redo, Top of History becomes Current.
        // Wait, usually we save state *before* modification.
        // So history contains previous states.

        // If we are at state N. history has [1..N-1].
        // Undo: Move N to Redo. Pop N-1 from History and return it.

        // Actually, current layers object is mutable.
        // We should push CURRENT state to Redo.
        val currentSnapshot = currentLayers.map { it.clone() }
        redoStack.push(currentSnapshot)

        return history.pop()
    }

    fun redo(currentLayers: List<Layer>): List<Layer>? {
        if (redoStack.isEmpty()) return null

        // Push current to history
        val currentSnapshot = currentLayers.map { it.clone() }
        history.push(currentSnapshot)

        return redoStack.pop()
    }

    fun canUndo(): Boolean = history.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()
}
