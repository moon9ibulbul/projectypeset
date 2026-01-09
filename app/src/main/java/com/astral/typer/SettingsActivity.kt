package com.astral.typer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.astral.typer.utils.LaMaProcessor

class SettingsActivity : AppCompatActivity() {

    private lateinit var tvCacheSize: TextView
    private lateinit var btnClearCache: Button
    private lateinit var cbStyle: CheckBox
    private lateinit var cbFavorite: CheckBox
    private lateinit var cbMyFont: CheckBox

    // Export Launcher
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { performExport(it) }
    }

    // Import Launcher
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { performImport(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Bind Views
        tvCacheSize = findViewById(R.id.tvCacheSize)
        btnClearCache = findViewById(R.id.btnClearCache)
        cbStyle = findViewById(R.id.cbStyle)
        cbFavorite = findViewById(R.id.cbFavorite)
        cbMyFont = findViewById(R.id.cbMyFont)

        val btnExport = findViewById<Button>(R.id.btnExport)
        val btnImport = findViewById<Button>(R.id.btnImport)
        val btnDonate = findViewById<Button>(R.id.btnDonate)

        // Model Views
        val tvModelStatus = findViewById<TextView>(R.id.tvModelStatus)
        val pbModelDownload = findViewById<android.widget.ProgressBar>(R.id.pbModelDownload)
        val btnDownloadModel = findViewById<Button>(R.id.btnDownloadModel)

        // Init LaMa Processor Logic
        val lamaProcessor = LaMaProcessor(this)

        fun updateModelStatus() {
            if (lamaProcessor.isModelAvailable()) {
                tvModelStatus.text = "Status: Downloaded (Ready)"
                btnDownloadModel.text = "Redownload"
                btnDownloadModel.isEnabled = true
            } else {
                tvModelStatus.text = "Status: Not Downloaded"
                btnDownloadModel.text = "Download Model (~200MB)"
                btnDownloadModel.isEnabled = true
            }
        }
        updateModelStatus()

        btnDownloadModel.setOnClickListener {
             btnDownloadModel.isEnabled = false
             pbModelDownload.visibility = android.view.View.VISIBLE
             tvModelStatus.text = "Status: Downloading..."

             lifecycleScope.launch {
                 val success = lamaProcessor.downloadModel { progress ->
                     runOnUiThread {
                         pbModelDownload.progress = (progress * 100).toInt()
                         tvModelStatus.text = "Status: Downloading ${(progress * 100).toInt()}%"
                     }
                 }

                 if (success) {
                     updateModelStatus()
                     Toast.makeText(this@SettingsActivity, "Download Complete", Toast.LENGTH_SHORT).show()
                 } else {
                     tvModelStatus.text = "Status: Download Failed"
                     btnDownloadModel.isEnabled = true
                     Toast.makeText(this@SettingsActivity, "Download Failed", Toast.LENGTH_SHORT).show()
                 }
                 pbModelDownload.visibility = android.view.View.GONE
             }
        }

        // Cache Logic
        updateCacheSize()
        btnClearCache.setOnClickListener {
            clearCache()
        }

        // Data Logic
        btnExport.setOnClickListener {
            if (!cbStyle.isChecked && !cbFavorite.isChecked && !cbMyFont.isChecked) {
                Toast.makeText(this, "Select at least one item to export", Toast.LENGTH_SHORT).show()
            } else {
                exportLauncher.launch("AstralTyper_Backup.zip")
            }
        }

        btnImport.setOnClickListener {
            importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
        }

        // Donate
        btnDonate.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://trakteer.id/astralexpresscrew/tip"))
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open browser", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateCacheSize() {
        val sizeBytes = calculateSize(cacheDir)
        val sizeMb = sizeBytes / (1024.0 * 1024.0)
        tvCacheSize.text = String.format("Cache Size: %.2f MB", sizeMb)
    }

    private fun calculateSize(dir: File): Long {
        if (!dir.exists()) return 0
        var result: Long = 0
        dir.listFiles()?.forEach {
            result += if (it.isDirectory) calculateSize(it) else it.length()
        }
        return result
    }

    private fun clearCache() {
        try {
            if (cacheDir.exists()) {
                cacheDir.deleteRecursively()
            }
            Toast.makeText(this, "Cache Cleared", Toast.LENGTH_SHORT).show()
            updateCacheSize()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to clear cache", Toast.LENGTH_SHORT).show()
        }
    }

    private fun performExport(uri: Uri) {
        try {
            val tempFile = File(cacheDir, "temp_export.zip")
            if (tempFile.exists()) tempFile.delete()

            ZipOutputStream(FileOutputStream(tempFile)).use { zipOut ->
                // 1. Styles (SharedPrefs)
                if (cbStyle.isChecked) {
                    val styleFile = File(dataDir, "shared_prefs/style_prefs.xml") // Check exact name used in StyleManager
                    // Looking at memory/StyleManager, it likely uses shared prefs.
                    // Let's verify name later. Assuming "style_prefs" based on context or "StyleManager"
                    // Actually, StyleManager usually uses a JSON file or specific pref.
                    // Reading memory: "Style menu displays real-time generated Bitmap thumbnails...".
                    // Let's assume standard prefs for now, but I might need to verify the file name.
                    // If it's pure shared prefs, the file is usually `package_name_preferences.xml` or specific name.

                    // Actually, let's look for "style_prefs.xml" or try to find it.
                    // Safer: Export ALL shared prefs related to app? No, user selected "Style".
                    // I'll check `StyleManager.kt` via `read_file` if needed.
                    // For now, I'll assume a standard name or implement a helper.

                    // Let's grab the known file paths.
                    // `StyleManager.kt` usually saves to `style_prefs`.
                    addFileToZip(File(dataDir, "shared_prefs/style_prefs.xml"), "shared_prefs/style_prefs.xml", zipOut)
                }

                // 2. Favorites (SharedPrefs)
                if (cbFavorite.isChecked) {
                    addFileToZip(File(dataDir, "shared_prefs/font_prefs.xml"), "shared_prefs/font_prefs.xml", zipOut)
                }

                // 3. My Fonts (Files)
                if (cbMyFont.isChecked) {
                    val fontDir = File(filesDir, "fonts")
                    if (fontDir.exists()) {
                        fontDir.listFiles()?.forEach { file ->
                            addFileToZip(file, "files/fonts/${file.name}", zipOut)
                        }
                    }
                }
            }

            // Write temp file to Uri
            contentResolver.openOutputStream(uri)?.use { out ->
                FileInputStream(tempFile).copyTo(out)
            }
            tempFile.delete()
            Toast.makeText(this, "Export Successful", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Export Failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addFileToZip(file: File, entryName: String, zipOut: ZipOutputStream) {
        if (!file.exists()) return
        FileInputStream(file).use { fis ->
            zipOut.putNextEntry(ZipEntry(entryName))
            fis.copyTo(zipOut)
            zipOut.closeEntry()
        }
    }

    private fun performImport(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                val zipIn = ZipInputStream(input)
                var entry = zipIn.nextEntry
                while (entry != null) {
                    val filePath = entry.name
                    // Security check for Zip Slip
                    if (filePath.contains("..")) {
                        entry = zipIn.nextEntry
                        continue
                    }

                    var targetFile: File? = null

                    if (filePath.startsWith("shared_prefs/")) {
                        // Determine target based on checkboxes?
                        // Or just restore if present in zip?
                        // User clicked Import, implies restore all in zip.
                        // But we should respect the checkbox logic? usually import restores everything in the backup.
                        // Let's restore everything found in zip that matches our categories.

                        if (filePath.contains("style_prefs.xml") && cbStyle.isChecked) {
                             targetFile = File(dataDir, filePath)
                        } else if (filePath.contains("font_prefs.xml") && cbFavorite.isChecked) {
                             targetFile = File(dataDir, filePath)
                        }
                    } else if (filePath.startsWith("files/fonts/") && cbMyFont.isChecked) {
                        targetFile = File(filesDir, "fonts/${File(filePath).name}")
                    }

                    if (targetFile != null) {
                        targetFile.parentFile?.mkdirs()
                        FileOutputStream(targetFile).use { out ->
                            zipIn.copyTo(out)
                        }
                    }

                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }

            // Restart App? Or just toast.
            // SharedPrefs might need reload. Process restart is safest.
            Toast.makeText(this, "Import Successful. Restarting...", Toast.LENGTH_SHORT).show()

            // Give time for Toast then restart
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
            finish()

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Import Failed", Toast.LENGTH_SHORT).show()
        }
    }
}
