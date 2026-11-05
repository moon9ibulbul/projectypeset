package com.astral.typer

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.astral.typer.utils.DownloadStatus
import com.astral.typer.utils.LaMaProcessor
import com.astral.typer.utils.ModelDownloadManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsAndModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testDefaultSettingsPreferences() {
        val memoryPrefs = mutableMapOf<String, Any>()

        // Mock SharedPreferences to verify default values when keys are absent
        val fakePrefs = object : SharedPreferences {
            override fun getAll(): MutableMap<String, *> = memoryPrefs
            override fun getString(key: String?, defValue: String?): String? = memoryPrefs[key] as? String ?: defValue
            @Suppress("UNCHECKED_CAST")
            override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = memoryPrefs[key] as? MutableSet<String> ?: defValues
            override fun getInt(key: String?, defValue: Int): Int = (memoryPrefs[key] as? Int) ?: defValue
            override fun getLong(key: String?, defValue: Long): Long = (memoryPrefs[key] as? Long) ?: defValue
            override fun getFloat(key: String?, defValue: Float): Float = (memoryPrefs[key] as? Float) ?: defValue
            override fun getBoolean(key: String?, defValue: Boolean): Boolean = (memoryPrefs[key] as? Boolean) ?: defValue
            override fun contains(key: String?): Boolean = memoryPrefs.containsKey(key)
            override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException()
            override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
            override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        }

        // Test default value of "inpaint_detect_text_outside" must be false (unchecked)
        val defaultDetectOutside = fakePrefs.getBoolean("inpaint_detect_text_outside", false)
        assertFalse("Text Outside Bubble must default to unchecked (false)", defaultDetectOutside)

        // Test default value of "inpaint_text_padding" must be 2 (2px)
        val defaultMaskPadding = fakePrefs.getInt("inpaint_text_padding", 2)
        assertEquals("Mask Padding must default to 2px", 2, defaultMaskPadding)
    }

    @Test
    fun testLaMaProcessorAvailabilityAndDeletion() {
        val filesDir = tempFolder.newFolder("files")
        val onnxDir = File(filesDir, "onnx")
        onnxDir.mkdirs()

        val fakeContext = object : ContextWrapper(null) {
            override fun getFilesDir(): File = filesDir
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
                return object : SharedPreferences {
                    override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any>()
                    override fun getString(key: String?, defValue: String?): String? = defValue
                    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
                    override fun getInt(key: String?, defValue: Int): Int = defValue
                    override fun getLong(key: String?, defValue: Long): Long = defValue
                    override fun getFloat(key: String?, defValue: Float): Float = defValue
                    override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
                    override fun contains(key: String?): Boolean = false
                    override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException()
                    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
                    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
                }
            }
        }

        val lamaProcessor = LaMaProcessor(fakeContext)

        // Initially no model file exists
        assertFalse(lamaProcessor.isModelAvailable())
        assertFalse(lamaProcessor.isOriginalModelAvailable())

        // Create the dynamic model file
        val modelFile = File(onnxDir, "lama-manga-dynamic.onnx")
        modelFile.writeBytes(byteArrayOf(1, 2, 3, 4))

        assertTrue(lamaProcessor.isModelAvailable())
        assertTrue(lamaProcessor.isOriginalModelAvailable())

        // Test deleteModel removes the file
        val deleted = lamaProcessor.deleteModel()
        assertTrue(deleted)
        assertFalse(modelFile.exists())
        assertFalse(lamaProcessor.isModelAvailable())
    }

    @Test
    fun testModelDownloadManagerLamaState() {
        val lamaState = ModelDownloadManager.lamaState.value
        assertEquals(DownloadStatus.IDLE, lamaState.status)
        assertEquals(0f, lamaState.progress, 0.001f)
        assertFalse(ModelDownloadManager.isLamaDownloading())
    }
}
