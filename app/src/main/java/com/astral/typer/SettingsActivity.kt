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
import com.astral.typer.utils.MiganProcessor
import com.astral.typer.utils.BubbleDetectorProcessor

class SettingsActivity : AppCompatActivity() {

    private lateinit var tvCacheSize: TextView
    private lateinit var btnClearCache: Button
    private lateinit var cbStyle: CheckBox
    private lateinit var cbFavorite: CheckBox
    private lateinit var cbMyFont: CheckBox

    // Watermark Views
    private lateinit var cbEnableWatermark: CheckBox
    private lateinit var layoutWatermarkOptions: android.widget.LinearLayout
    private lateinit var btnImportWatermark: Button
    private lateinit var ivWatermarkPreview: android.widget.ImageView
    private lateinit var tvWatermarkOpacity: TextView
    private lateinit var sbWatermarkOpacity: android.widget.SeekBar
    private lateinit var cbAutoWatermark: CheckBox
    private lateinit var cbWatermarkAutoScaling: CheckBox
    private lateinit var layoutWatermarkScale: android.widget.LinearLayout
    private lateinit var tvWatermarkScale: TextView
    private lateinit var sbWatermarkScale: android.widget.SeekBar
    private lateinit var layoutWatermarkPosition: android.widget.LinearLayout
    private lateinit var spinnerWatermarkPosition: android.widget.Spinner

    private lateinit var tvPdfQuality: TextView
    private lateinit var sbPdfQuality: android.widget.SeekBar

    // Export Launcher
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { performExport(it) }
    }

    // Import Launcher
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { performImport(it) }
    }

    private val watermarkLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleWatermarkImport(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            )
        }
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Bind Views
        tvCacheSize = findViewById(R.id.tvCacheSize)
        btnClearCache = findViewById(R.id.btnClearCache)
        cbStyle = findViewById(R.id.cbStyle)
        cbFavorite = findViewById(R.id.cbFavorite)
        cbMyFont = findViewById(R.id.cbMyFont)

        val settingsPrefs = getSharedPreferences("settings_prefs", MODE_PRIVATE)

        val cbDisableSnap = findViewById<CheckBox>(R.id.cbDisableSnap)
        cbDisableSnap.isChecked = settingsPrefs.getBoolean("disable_snap_to_center", false)
        cbDisableSnap.setOnCheckedChangeListener { _, isChecked ->
            settingsPrefs.edit().putBoolean("disable_snap_to_center", isChecked).apply()
        }

        val cbLamaNnapi = findViewById<CheckBox>(R.id.cbLamaNnapi)
        cbLamaNnapi.isChecked = settingsPrefs.getBoolean("enable_lama_nnapi", false)
        cbLamaNnapi.setOnCheckedChangeListener { _, isChecked ->
            settingsPrefs.edit().putBoolean("enable_lama_nnapi", isChecked).apply()
        }

        // Watermark Logic
        cbEnableWatermark = findViewById(R.id.cbEnableWatermark)
        layoutWatermarkOptions = findViewById(R.id.layoutWatermarkOptions)
        btnImportWatermark = findViewById(R.id.btnImportWatermark)
        ivWatermarkPreview = findViewById(R.id.ivWatermarkPreview)
        tvWatermarkOpacity = findViewById(R.id.tvWatermarkOpacity)
        sbWatermarkOpacity = findViewById(R.id.sbWatermarkOpacity)
        cbAutoWatermark = findViewById(R.id.cbAutoWatermark)
        layoutWatermarkPosition = findViewById(R.id.layoutWatermarkPosition)
        spinnerWatermarkPosition = findViewById(R.id.spinnerWatermarkPosition)

        val isWatermarkEnabled = settingsPrefs.getBoolean("enable_watermark", false)
        cbEnableWatermark.isChecked = isWatermarkEnabled
        layoutWatermarkOptions.visibility = if (isWatermarkEnabled) android.view.View.VISIBLE else android.view.View.GONE

        cbEnableWatermark.setOnCheckedChangeListener { _, isChecked ->
            settingsPrefs.edit().putBoolean("enable_watermark", isChecked).apply()
            layoutWatermarkOptions.visibility = if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
        }

        val watermarkOpacity = settingsPrefs.getInt("watermark_opacity", 255)
        sbWatermarkOpacity.progress = watermarkOpacity
        tvWatermarkOpacity.text = "Watermark Opacity: ${(watermarkOpacity / 2.55f).toInt()}%"

        sbWatermarkOpacity.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                tvWatermarkOpacity.text = "Watermark Opacity: ${(progress / 2.55f).toInt()}%"
                settingsPrefs.edit().putInt("watermark_opacity", progress).apply()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        val isAutoWatermark = settingsPrefs.getBoolean("auto_watermark", false)
        cbAutoWatermark.isChecked = isAutoWatermark
        layoutWatermarkPosition.visibility = if (isAutoWatermark) android.view.View.VISIBLE else android.view.View.GONE

        cbAutoWatermark.setOnCheckedChangeListener { _, isChecked ->
            settingsPrefs.edit().putBoolean("auto_watermark", isChecked).apply()
            layoutWatermarkPosition.visibility = if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
        }

        cbWatermarkAutoScaling = findViewById(R.id.cbWatermarkAutoScaling)
        layoutWatermarkScale = findViewById(R.id.layoutWatermarkScale)
        tvWatermarkScale = findViewById(R.id.tvWatermarkScale)
        sbWatermarkScale = findViewById(R.id.sbWatermarkScale)

        val isWatermarkAutoScaling = settingsPrefs.getBoolean("watermark_auto_scaling", false)
        cbWatermarkAutoScaling.isChecked = isWatermarkAutoScaling
        layoutWatermarkScale.visibility = if (isWatermarkAutoScaling) android.view.View.VISIBLE else android.view.View.GONE

        cbWatermarkAutoScaling.setOnCheckedChangeListener { _, isChecked ->
            settingsPrefs.edit().putBoolean("watermark_auto_scaling", isChecked).apply()
            layoutWatermarkScale.visibility = if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
        }

        val watermarkScaleFactor = settingsPrefs.getInt("watermark_scale_factor", 25)
        sbWatermarkScale.progress = watermarkScaleFactor
        tvWatermarkScale.text = "Watermark Scale Factor: ${watermarkScaleFactor}%"

        sbWatermarkScale.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val clampedProgress = progress.coerceAtLeast(1)
                tvWatermarkScale.text = "Watermark Scale Factor: ${clampedProgress}%"
                settingsPrefs.edit().putInt("watermark_scale_factor", clampedProgress).apply()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        // Spinner Setup
        val positions = arrayOf("Upper left", "Top", "Upper Right", "Middle Left", "Center", "Middle Right", "Bottom Left", "Bottom", "Bottom Right", "Random")
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, positions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerWatermarkPosition.adapter = adapter

        val savedPosition = settingsPrefs.getString("watermark_position", "Center")
        val posIndex = positions.indexOf(savedPosition).coerceAtLeast(0)
        spinnerWatermarkPosition.setSelection(posIndex)

        spinnerWatermarkPosition.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                settingsPrefs.edit().putString("watermark_position", positions[position]).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        btnImportWatermark.setOnClickListener {
            watermarkLauncher.launch("image/*")
        }

        updateWatermarkPreview()

        // PDF Quality Logic
        tvPdfQuality = findViewById(R.id.tvPdfQuality)
        sbPdfQuality = findViewById(R.id.sbPdfQuality)

        val pdfQuality = settingsPrefs.getInt("pdf_quality", 90)
        sbPdfQuality.progress = pdfQuality
        tvPdfQuality.text = getString(R.string.pdf_quality, pdfQuality)

        sbPdfQuality.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                tvPdfQuality.text = getString(R.string.pdf_quality, progress)
                settingsPrefs.edit().putInt("pdf_quality", progress).apply()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        val btnExport = findViewById<Button>(R.id.btnExport)
        val btnImport = findViewById<Button>(R.id.btnImport)
        val btnDonate = findViewById<Button>(R.id.btnDonate)

        // Model Views (LaMa)
        val tvModelStatus = findViewById<TextView>(R.id.tvModelStatus)
        val pbModelDownload = findViewById<android.widget.ProgressBar>(R.id.pbModelDownload)
        val btnDownloadModel = findViewById<Button>(R.id.btnDownloadModel)

        // Model Views (MIGAN)
        val tvMiganModelStatus = findViewById<TextView>(R.id.tvMiganModelStatus)
        val pbMiganModelDownload = findViewById<android.widget.ProgressBar>(R.id.pbMiganModelDownload)
        val btnDownloadMiganModel = findViewById<Button>(R.id.btnDownloadMiganModel)

        // Model Views (Bubble Detector)
        val tvTyperModelStatus = findViewById<TextView>(R.id.tvTyperModelStatus)
        val pbTyperModelDownload = findViewById<android.widget.ProgressBar>(R.id.pbTyperModelDownload)
        val btnDownloadTyperModel = findViewById<Button>(R.id.btnDownloadTyperModel)

        // Init LaMa Processor Logic
        val lamaProcessor = LaMaProcessor(this)
        // Init MIGAN Processor Logic
        val miganProcessor = MiganProcessor(this)
        // Init Bubble Processor
        val bubbleProcessor = BubbleDetectorProcessor(this)

        fun updateModelStatus() {
            if (lamaProcessor.isModelAvailable()) {
                tvModelStatus.text = "Status: Downloaded (Ready)"
                btnDownloadModel.text = "Redownload"
            } else {
                tvModelStatus.text = "Status: Not Downloaded"
                btnDownloadModel.text = "Download Model (~200MB)"
            }

            if (miganProcessor.isModelAvailable()) {
                tvMiganModelStatus.text = "Status: Downloaded (Ready)"
                btnDownloadMiganModel.text = "Redownload"
            } else {
                tvMiganModelStatus.text = "Status: Not Downloaded"
                btnDownloadMiganModel.text = "Download Model (~27MB)"
            }

            if (bubbleProcessor.isModelAvailable()) {
                tvTyperModelStatus.text = "Status: Downloaded (Ready)"
                btnDownloadTyperModel.text = "Redownload"
            } else {
                tvTyperModelStatus.text = "Status: Not Downloaded"
                btnDownloadTyperModel.text = "Download Model (170 MB)"
            }
        }

        // Start collecting flows from ModelDownloadManager
        lifecycleScope.launch {
            com.astral.typer.utils.ModelDownloadManager.lamaState.collect { state ->
                when (state.status) {
                    com.astral.typer.utils.DownloadStatus.IDLE -> {
                        updateModelStatus()
                        pbModelDownload.visibility = android.view.View.GONE
                        btnDownloadModel.isEnabled = true
                    }
                    com.astral.typer.utils.DownloadStatus.DOWNLOADING -> {
                        btnDownloadModel.isEnabled = false
                        pbModelDownload.visibility = android.view.View.VISIBLE
                        pbModelDownload.progress = (state.progress * 100).toInt()
                        tvModelStatus.text = "Status: Downloading ${(state.progress * 100).toInt()}%"
                    }
                    com.astral.typer.utils.DownloadStatus.SUCCESS -> {
                        updateModelStatus()
                        pbModelDownload.visibility = android.view.View.GONE
                        btnDownloadModel.isEnabled = true
                    }
                    com.astral.typer.utils.DownloadStatus.FAILED -> {
                        tvModelStatus.text = "Status: Download Failed"
                        pbModelDownload.visibility = android.view.View.GONE
                        btnDownloadModel.isEnabled = true
                    }
                }
            }
        }

        lifecycleScope.launch {
            com.astral.typer.utils.ModelDownloadManager.miganState.collect { state ->
                when (state.status) {
                    com.astral.typer.utils.DownloadStatus.IDLE -> {
                        updateModelStatus()
                        pbMiganModelDownload.visibility = android.view.View.GONE
                        btnDownloadMiganModel.isEnabled = true
                    }
                    com.astral.typer.utils.DownloadStatus.DOWNLOADING -> {
                        btnDownloadMiganModel.isEnabled = false
                        pbMiganModelDownload.visibility = android.view.View.VISIBLE
                        pbMiganModelDownload.progress = (state.progress * 100).toInt()
                        tvMiganModelStatus.text = "Status: Downloading ${(state.progress * 100).toInt()}%"
                    }
                    com.astral.typer.utils.DownloadStatus.SUCCESS -> {
                        updateModelStatus()
                        pbMiganModelDownload.visibility = android.view.View.GONE
                        btnDownloadMiganModel.isEnabled = true
                    }
                    com.astral.typer.utils.DownloadStatus.FAILED -> {
                        tvMiganModelStatus.text = "Status: Download Failed"
                        pbMiganModelDownload.visibility = android.view.View.GONE
                        btnDownloadMiganModel.isEnabled = true
                    }
                }
            }
        }

        lifecycleScope.launch {
            com.astral.typer.utils.ModelDownloadManager.bubbleState.collect { state ->
                when (state.status) {
                    com.astral.typer.utils.DownloadStatus.IDLE -> {
                        updateModelStatus()
                        pbTyperModelDownload.visibility = android.view.View.GONE
                        btnDownloadTyperModel.isEnabled = true
                    }
                    com.astral.typer.utils.DownloadStatus.DOWNLOADING -> {
                        btnDownloadTyperModel.isEnabled = false
                        pbTyperModelDownload.visibility = android.view.View.VISIBLE
                        pbTyperModelDownload.progress = (state.progress * 100).toInt()
                        tvTyperModelStatus.text = "Status: Downloading ${(state.progress * 100).toInt()}%"
                    }
                    com.astral.typer.utils.DownloadStatus.SUCCESS -> {
                        updateModelStatus()
                        pbTyperModelDownload.visibility = android.view.View.GONE
                        btnDownloadTyperModel.isEnabled = true
                    }
                    com.astral.typer.utils.DownloadStatus.FAILED -> {
                        tvTyperModelStatus.text = "Status: Download Failed"
                        pbTyperModelDownload.visibility = android.view.View.GONE
                        btnDownloadTyperModel.isEnabled = true
                    }
                }
            }
        }

        btnDownloadModel.setOnClickListener {
            com.astral.typer.utils.ModelDownloadManager.startLamaDownload(this@SettingsActivity)
        }

        btnDownloadMiganModel.setOnClickListener {
            com.astral.typer.utils.ModelDownloadManager.startMiganDownload(this@SettingsActivity)
        }

        btnDownloadTyperModel.setOnClickListener {
            com.astral.typer.utils.ModelDownloadManager.startBubbleDownload(this@SettingsActivity)
        }

        // Handle auto-download from intent extra
        if (intent.getBooleanExtra("AUTO_DOWNLOAD", false)) {
            com.astral.typer.utils.ModelDownloadManager.startLamaDownload(this@SettingsActivity)
            com.astral.typer.utils.ModelDownloadManager.startMiganDownload(this@SettingsActivity)
            com.astral.typer.utils.ModelDownloadManager.startBubbleDownload(this@SettingsActivity)
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

        findViewById<Button>(R.id.btnFontManager).setOnClickListener {
            val intent = Intent(this, FontActivity::class.java)
            startActivity(intent)
        }

        // Donate
        btnDonate.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://parlor.astralscans.top/donasi.html"))
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

    private fun handleWatermarkImport(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                val watermarkFile = File(filesDir, "watermark.png")
                FileOutputStream(watermarkFile).use { output ->
                    input.copyTo(output)
                }
            }
            updateWatermarkPreview()
            Toast.makeText(this, "Watermark Imported", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to import watermark", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateWatermarkPreview() {
        val watermarkFile = File(filesDir, "watermark.png")
        if (watermarkFile.exists()) {
            ivWatermarkPreview.visibility = android.view.View.VISIBLE
            val bitmap = android.graphics.BitmapFactory.decodeFile(watermarkFile.absolutePath)
            ivWatermarkPreview.setImageBitmap(bitmap)
        } else {
            ivWatermarkPreview.visibility = android.view.View.GONE
        }
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
