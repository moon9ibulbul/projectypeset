package com.astral.typer

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.astral.typer.utils.FontManager
import com.astral.typer.utils.ThemeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager

class FontActivity : AppCompatActivity() {

    private lateinit var btnTabLocalFonts: Button
    private lateinit var btnTabStoreFonts: Button
    private lateinit var layoutLocalFontsContainer: LinearLayout
    private lateinit var layoutStoreFontsContainer: LinearLayout
    private lateinit var spinnerOrderBy: Spinner
    private lateinit var btnAddCategory: Button
    private lateinit var layoutCategoriesList: LinearLayout
    private lateinit var btnImportFont: Button
    private lateinit var etSearchFonts: EditText
    private lateinit var layoutFontsList: RecyclerView
    private var fontAdapter: FontAdapter? = null

    private lateinit var etSearchStoreFonts: EditText
    private lateinit var pbStoreLoading: ProgressBar
    private lateinit var layoutStoreFontsList: RecyclerView
    private lateinit var btnLoadMoreStoreFonts: Button
    private var storeAdapter: StoreFontAdapter? = null
    private var allStoreFonts: List<com.astral.typer.utils.GoogleFontStoreManager.StoreFontItem> = emptyList()
    private var storePageSize = 50
    private var storeCurrentPage = 1
    private var selectedStoreCategory = "All"

    private val importFontLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            lifecycleScope.launch(Dispatchers.IO) {
                val success = FontManager.importFont(this@FontActivity, it)
                withContext(Dispatchers.Main) {
                    if (success) {
                        Toast.makeText(this@FontActivity, "Font imported successfully", Toast.LENGTH_SHORT).show()
                        loadFontsList()
                    } else {
                        Toast.makeText(this@FontActivity, "Failed to import font", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            )
        }
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_font)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            finish()
        }

        btnTabLocalFonts = findViewById(R.id.btnTabLocalFonts)
        btnTabStoreFonts = findViewById(R.id.btnTabStoreFonts)
        layoutLocalFontsContainer = findViewById(R.id.layoutLocalFontsContainer)
        layoutStoreFontsContainer = findViewById(R.id.layoutStoreFontsContainer)

        spinnerOrderBy = findViewById(R.id.spinnerOrderBy)
        btnAddCategory = findViewById(R.id.btnAddCategory)
        layoutCategoriesList = findViewById(R.id.layoutCategoriesList)
        btnImportFont = findViewById(R.id.btnImportFont)
        etSearchFonts = findViewById(R.id.etSearchFonts)
        layoutFontsList = findViewById(R.id.layoutFontsList)
        layoutFontsList.layoutManager = LinearLayoutManager(this)
        layoutFontsList.isNestedScrollingEnabled = false

        etSearchStoreFonts = findViewById(R.id.etSearchStoreFonts)
        pbStoreLoading = findViewById(R.id.pbStoreLoading)
        layoutStoreFontsList = findViewById(R.id.layoutStoreFontsList)
        btnLoadMoreStoreFonts = findViewById(R.id.btnLoadMoreStoreFonts)
        layoutStoreFontsList.layoutManager = LinearLayoutManager(this)
        layoutStoreFontsList.isNestedScrollingEnabled = false

        btnTabLocalFonts.setOnClickListener { switchTab(isLocal = true) }
        btnTabStoreFonts.setOnClickListener { switchTab(isLocal = false) }

        btnLoadMoreStoreFonts.setOnClickListener {
            storeCurrentPage++
            filterStoreFonts(resetPage = false)
        }

        etSearchStoreFonts.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterStoreFonts(resetPage = true)
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        setupStoreCategories()

        // Setup Search
        etSearchFonts.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                loadFontsList()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        // Setup Order By Spinner
        val orderOptions = arrayOf("Name", "Latest Installed", "Most Used")
        val adapter = ArrayAdapter(this, R.layout.item_spinner, orderOptions)
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown)
        spinnerOrderBy.adapter = adapter
        try {
            spinnerOrderBy.setPopupBackgroundDrawable(android.graphics.drawable.ColorDrawable(ThemeUtils.getColorFromAttr(this, R.attr.appSurfaceColor)))
        } catch (_: Exception) {}

        val prefs = getSharedPreferences("font_prefs", MODE_PRIVATE)
        val savedOrder = prefs.getString("font_order_by", "Name") ?: "Name"
        val orderIndex = orderOptions.indexOf(savedOrder).coerceAtLeast(0)
        spinnerOrderBy.setSelection(orderIndex)

        spinnerOrderBy.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putString("font_order_by", orderOptions[position]).apply()
                loadFontsList()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Setup Add Category Button
        btnAddCategory.setOnClickListener {
            showAddCategoryDialog()
        }

        // Setup Import Font Button
        btnImportFont.setOnClickListener {
            importFontLauncher.launch("*/*")
        }

        loadCategoriesList()
        loadFontsList()
        switchTab(isLocal = true)
    }

    private fun switchTab(isLocal: Boolean) {
        if (isLocal) {
            layoutLocalFontsContainer.visibility = View.VISIBLE
            layoutStoreFontsContainer.visibility = View.GONE

            btnTabLocalFonts.setBackgroundColor(com.astral.typer.utils.ThemeUtils.getColorFromAttr(this, com.astral.typer.R.attr.appButtonBgColor))
            btnTabLocalFonts.setTextColor(com.astral.typer.utils.ThemeUtils.getColorFromAttr(this, com.astral.typer.R.attr.appTextColorPrimary))

            btnTabStoreFonts.setBackgroundColor(Color.TRANSPARENT)
            btnTabStoreFonts.setTextColor(com.astral.typer.utils.ThemeUtils.getColorFromAttr(this, com.astral.typer.R.attr.appTextColorSecondary))
        } else {
            layoutLocalFontsContainer.visibility = View.GONE
            layoutStoreFontsContainer.visibility = View.VISIBLE

            btnTabStoreFonts.setBackgroundColor(com.astral.typer.utils.ThemeUtils.getColorFromAttr(this, com.astral.typer.R.attr.appButtonBgColor))
            btnTabStoreFonts.setTextColor(com.astral.typer.utils.ThemeUtils.getColorFromAttr(this, com.astral.typer.R.attr.appTextColorPrimary))

            btnTabLocalFonts.setBackgroundColor(Color.TRANSPARENT)
            btnTabLocalFonts.setTextColor(com.astral.typer.utils.ThemeUtils.getColorFromAttr(this, com.astral.typer.R.attr.appTextColorSecondary))

            if (allStoreFonts.isEmpty()) {
                loadStoreFontsList()
            }
        }
    }

    private fun loadStoreFontsList() {
        pbStoreLoading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val items = com.astral.typer.utils.GoogleFontStoreManager.getGoogleFonts(this@FontActivity)
            allStoreFonts = items
            pbStoreLoading.visibility = View.GONE
            filterStoreFonts(resetPage = true)
        }
    }

    private fun setupStoreCategories() {
        val layoutCategories = findViewById<LinearLayout>(R.id.layoutStoreCategories) ?: return
        layoutCategories.removeAllViews()
        val categories = arrayOf("All", "Serif", "Sans-Serif", "Monospace", "Display", "Handwriting")
        for (cat in categories) {
            val isActive = selectedStoreCategory.equals(cat, ignoreCase = true)
            val btn = Button(this).apply {
                text = cat
                textSize = 12f
                isAllCaps = false
                val density = resources.displayMetrics.density
                val padH = (12 * density).toInt()
                val padV = (6 * density).toInt()
                setPadding(padH, padV, padH, padV)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (36 * density).toInt()
                ).apply {
                    marginEnd = (8 * density).toInt()
                }
                layoutParams = params
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (isActive) Color.CYAN else ThemeUtils.getColorFromAttr(this@FontActivity, R.attr.appButtonBgColor))
                    setStroke((1 * density).toInt(), if (isActive) Color.CYAN else ThemeUtils.getColorFromAttr(this@FontActivity, R.attr.appButtonBorderColor))
                    cornerRadius = 18 * density
                }
                setTextColor(if (isActive) Color.BLACK else ThemeUtils.getColorFromAttr(this@FontActivity, R.attr.appTextColorPrimary))
                setOnClickListener {
                    selectedStoreCategory = cat
                    setupStoreCategories()
                    filterStoreFonts(resetPage = true)
                }
            }
            layoutCategories.addView(btn)
        }
    }

    private fun filterStoreFonts(resetPage: Boolean = true) {
        if (resetPage) {
            storeCurrentPage = 1
        }
        val query = etSearchStoreFonts.text.toString().trim()
        val filtered = allStoreFonts.filter { item ->
            val matchesCategory = if (selectedStoreCategory.equals("All", ignoreCase = true)) {
                true
            } else {
                val catNorm = item.category?.replace("-", "")?.replace(" ", "")?.lowercase() ?: ""
                val targetNorm = selectedStoreCategory.replace("-", "").replace(" ", "").lowercase()
                catNorm == targetNorm
            }
            val matchesQuery = query.isEmpty() || item.family.contains(query, ignoreCase = true)
            matchesCategory && matchesQuery
        }.sortedBy { it.family.lowercase() }

        val displayCount = storeCurrentPage * storePageSize
        val limited = filtered.take(displayCount)

        if (filtered.size > displayCount) {
            btnLoadMoreStoreFonts.visibility = View.VISIBLE
        } else {
            btnLoadMoreStoreFonts.visibility = View.GONE
        }

        if (storeAdapter == null) {
            storeAdapter = StoreFontAdapter(
                this,
                limited,
                onDownload = { item, pos ->
                    downloadStoreFont(item, pos)
                }
            )
            layoutStoreFontsList.adapter = storeAdapter
        } else {
            storeAdapter?.updateItems(limited)
        }
    }

    private fun downloadStoreFont(item: com.astral.typer.utils.GoogleFontStoreManager.StoreFontItem, position: Int) {
        lifecycleScope.launch {
            val success = com.astral.typer.utils.GoogleFontStoreManager.downloadFont(this@FontActivity, item)
            if (success) {
                Toast.makeText(this@FontActivity, "Font '${item.family}' downloaded!", Toast.LENGTH_SHORT).show()
                storeAdapter?.notifyItemChanged(position)
                loadFontsList() // refresh local list
            } else {
                Toast.makeText(this@FontActivity, "Failed to download font", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getCustomCategories(): List<String> {
        val prefs = getSharedPreferences("font_prefs", MODE_PRIVATE)
        val json = prefs.getString("custom_font_categories", "[]") ?: "[]"
        val list = mutableListOf<String>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                list.add(array.getString(i))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private fun saveCustomCategories(categories: List<String>) {
        val array = JSONArray()
        for (cat in categories) {
            array.put(cat)
        }
        getSharedPreferences("font_prefs", MODE_PRIVATE)
            .edit()
            .putString("custom_font_categories", array.toString())
            .apply()
    }

    private fun loadCategoriesList() {
        layoutCategoriesList.removeAllViews()
        val categories = getCustomCategories()

        if (categories.isEmpty()) {
            val tvEmpty = TextView(this).apply {
                text = "No custom categories added yet."
                setTextColor(Color.GRAY)
                setPadding(0, 8, 0, 8)
                textSize = 14f
            }
            layoutCategoriesList.addView(tvEmpty)
            return
        }

        for (cat in categories) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(8, 8, 8, 8)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 4, 0, 4) }
                background = GradientDrawable().apply {
                    setColor(com.astral.typer.utils.ThemeUtils.getColorFromAttr(context, com.astral.typer.R.attr.appButtonBgColor))
                    setStroke(com.astral.typer.utils.ThemeUtils.getDimensionFromAttr(context, com.astral.typer.R.attr.appButtonBorderWidth).toInt(), com.astral.typer.utils.ThemeUtils.getColorFromAttr(context, com.astral.typer.R.attr.appButtonBorderColor))
                    cornerRadius = 8f
                }
            }

            val tvName = TextView(this).apply {
                text = cat
                setTextColor(com.astral.typer.utils.ThemeUtils.getColorFromAttr(context, com.astral.typer.R.attr.appTextColorPrimary))
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val btnRename = Button(this).apply {
                text = "Rename"
                textSize = 12f
                setOnClickListener {
                    showRenameCategoryDialog(cat)
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(4, 0, 4, 0) }
            }

            val btnDelete = Button(this).apply {
                text = "Delete"
                textSize = 12f
                setBackgroundColor(Color.parseColor("#D32F2F"))
                setTextColor(com.astral.typer.utils.ThemeUtils.getColorFromAttr(context, com.astral.typer.R.attr.appTextColorPrimary))
                setOnClickListener {
                    showDeleteCategoryConfirmation(cat)
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(4, 0, 4, 0) }
            }

            row.addView(tvName)
            row.addView(btnRename)
            row.addView(btnDelete)
            layoutCategoriesList.addView(row)
        }
    }

    private fun showAddCategoryDialog() {
        val input = EditText(this).apply {
            hint = "Category Name"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Add Category")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "Category name cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (name in listOf("Standard", "My Font", "Favorite")) {
                    Toast.makeText(this, "Reserved category name", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val categories = getCustomCategories().toMutableList()
                if (categories.contains(name)) {
                    Toast.makeText(this, "Category already exists", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                categories.add(name)
                saveCustomCategories(categories)
                loadCategoriesList()
                loadFontsList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRenameCategoryDialog(oldName: String) {
        val input = EditText(this).apply {
            setText(oldName)
            setSingleLine(true)
            setSelection(oldName.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename Category")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) {
                    Toast.makeText(this, "Category name cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (newName in listOf("Standard", "My Font", "Favorite")) {
                    Toast.makeText(this, "Reserved category name", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val categories = getCustomCategories().toMutableList()
                if (categories.contains(newName) && newName != oldName) {
                    Toast.makeText(this, "Category already exists", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val index = categories.indexOf(oldName)
                if (index != -1) {
                    categories[index] = newName
                    saveCustomCategories(categories)
                    renameCategoryInFonts(oldName, newName)
                    loadCategoriesList()
                    loadFontsList()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteCategoryConfirmation(categoryName: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete Category")
            .setMessage("Are you sure you want to delete category '$categoryName'?")
            .setPositiveButton("Delete") { _, _ ->
                val categories = getCustomCategories().toMutableList()
                categories.remove(categoryName)
                saveCustomCategories(categories)
                deleteCategoryInFonts(categoryName)
                loadCategoriesList()
                loadFontsList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renameCategoryInFonts(oldName: String, newName: String) {
        val prefs = getSharedPreferences("font_prefs", MODE_PRIVATE)
        val allKeys = prefs.all
        val editor = prefs.edit()
        for ((key, value) in allKeys) {
            if (key.startsWith("font_categories_") && value is Set<*>) {
                @Suppress("UNCHECKED_CAST")
                val set = (value as Set<String>).toMutableSet()
                if (set.contains(oldName)) {
                    set.remove(oldName)
                    set.add(newName)
                    editor.putStringSet(key, set)
                }
            }
        }
        editor.apply()
    }

    private fun deleteCategoryInFonts(categoryName: String) {
        val prefs = getSharedPreferences("font_prefs", MODE_PRIVATE)
        val allKeys = prefs.all
        val editor = prefs.edit()
        for ((key, value) in allKeys) {
            if (key.startsWith("font_categories_") && value is Set<*>) {
                @Suppress("UNCHECKED_CAST")
                val set = (value as Set<String>).toMutableSet()
                if (set.contains(categoryName)) {
                    set.remove(categoryName)
                    editor.putStringSet(key, set)
                }
            }
        }
        editor.apply()
    }

    private fun getSortedFonts(fonts: List<FontManager.FontItem>): List<FontManager.FontItem> {
        val prefs = getSharedPreferences("font_prefs", MODE_PRIVATE)
        val orderBy = prefs.getString("font_order_by", "Name") ?: "Name"

        val sortedList = when (orderBy) {
            "Latest Installed" -> {
                fonts.sortedWith(compareByDescending<FontManager.FontItem> { item ->
                    if (item.path != null) {
                        val file = if (item.path.startsWith("std_cache:")) {
                            File(filesDir, "std_fonts_cache/${item.path.substringAfter("std_cache:")}")
                        } else {
                            File(item.path)
                        }
                        if (file.exists()) file.lastModified() else 0L
                    } else {
                        0L
                    }
                }.thenBy { it.name.lowercase() })
            }
            "Most Used" -> {
                val usagePrefs = getSharedPreferences("font_usage_prefs", MODE_PRIVATE)
                fonts.sortedWith(compareByDescending<FontManager.FontItem> { item ->
                    val key = item.path ?: item.name
                    usagePrefs.getInt(key, 0)
                }.thenBy { it.name.lowercase() })
            }
            else -> { // "Name"
                fonts.sortedBy { it.name.lowercase() }
            }
        }

        val defaultFont = sortedList.find { it.name.equals("Default", ignoreCase = true) }
        return if (defaultFont != null) {
            listOf(defaultFont) + sortedList.filter { !it.name.equals("Default", ignoreCase = true) }
        } else {
            sortedList
        }
    }

    private fun loadFontsList() {
        lifecycleScope.launch {
            val allFonts = withContext(Dispatchers.IO) {
                FontManager.getStandardFonts(this@FontActivity) + FontManager.getCustomFonts(this@FontActivity)
            }
            val sorted = getSortedFonts(allFonts)

            val query = etSearchFonts.text.toString().trim()
            val filtered = if (query.isEmpty()) sorted else sorted.filter { it.name.contains(query, ignoreCase = true) }

            withContext(Dispatchers.Main) {
                val rv = findViewById<RecyclerView>(R.id.layoutFontsList)
                val parentView = rv.parent as? ViewGroup
                var tvEmpty = parentView?.findViewWithTag<TextView>("tvFontsEmpty")
                if (filtered.isEmpty()) {
                    rv.visibility = View.GONE
                    if (tvEmpty == null) {
                        tvEmpty = TextView(this@FontActivity).apply {
                            tag = "tvFontsEmpty"
                            text = "No fonts found."
                            setTextColor(Color.GRAY)
                            setPadding(0, 16, 0, 16)
                            textSize = 14f
                        }
                        parentView?.addView(tvEmpty)
                    } else {
                        tvEmpty.visibility = View.VISIBLE
                    }
                    return@withContext
                } else {
                    rv.visibility = View.VISIBLE
                    tvEmpty?.visibility = View.GONE
                }

                if (fontAdapter == null) {
                    fontAdapter = FontAdapter(
                        this@FontActivity,
                        filtered,
                        onManageCategories = { font, pos ->
                            showManageFontCategoriesDialog(font, pos)
                        },
                        onDelete = { font, pos ->
                            showDeleteFontConfirmation(font, pos)
                        }
                    )
                    rv.adapter = fontAdapter
                } else {
                    fontAdapter?.updateItems(filtered)
                }
                rv.requestLayout()
                parentView?.requestLayout()
            }
        }
    }

    private fun showManageFontCategoriesDialog(font: com.astral.typer.utils.FontManager.FontItem, position: Int) {
        val categories = getCustomCategories()
        if (categories.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("No Categories Available")
                .setMessage("Please add some custom categories first using the '+ Add Category' button above.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val fontId = font.path ?: font.name
        val prefs = getSharedPreferences("font_prefs", MODE_PRIVATE)
        val assigned = prefs.getStringSet("font_categories_$fontId", emptySet()) ?: emptySet()

        val checkedItems = BooleanArray(categories.size) { i ->
            assigned.contains(categories[i])
        }

        AlertDialog.Builder(this)
            .setTitle("Assign Categories")
            .setMultiChoiceItems(categories.toTypedArray(), checkedItems) { _, index, isChecked ->
                checkedItems[index] = isChecked
            }
            .setPositiveButton("Save") { _, _ ->
                val newAssignedSet = mutableSetOf<String>()
                for (i in checkedItems.indices) {
                    if (checkedItems[i]) {
                        newAssignedSet.add(categories[i])
                    }
                }
                prefs.edit().putStringSet("font_categories_$fontId", newAssignedSet).apply()
                fontAdapter?.notifyItemChanged(position)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteFontConfirmation(font: com.astral.typer.utils.FontManager.FontItem, position: Int) {
        AlertDialog.Builder(this)
            .setTitle("Delete Custom Font")
            .setMessage("Are you sure you want to delete font '${font.name}'?")
            .setPositiveButton("Delete") { _, _ ->
                val success = FontManager.deleteCustomFont(this, font)
                if (success) {
                    Toast.makeText(this, "Font deleted", Toast.LENGTH_SHORT).show()
                    loadFontsList()
                } else {
                    Toast.makeText(this, "Failed to delete font", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    class FontAdapter(
        private val context: Context,
        private var items: List<com.astral.typer.utils.FontManager.FontItem>,
        private val onManageCategories: (com.astral.typer.utils.FontManager.FontItem, Int) -> Unit,
        private val onDelete: (com.astral.typer.utils.FontManager.FontItem, Int) -> Unit
    ) : RecyclerView.Adapter<FontAdapter.FontViewHolder>() {

        fun updateItems(newItems: List<com.astral.typer.utils.FontManager.FontItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        class FontViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewWithTag("tvName")
            val tvType: TextView = view.findViewWithTag("tvType")
            val tvAssigned: TextView = view.findViewWithTag("tvAssigned")
            val btnManageCategories: Button = view.findViewWithTag("btnManageCategories")
            val btnDelete: Button? = view.findViewWithTag("btnDelete")
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FontViewHolder {
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12, 12, 12, 12)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 6, 0, 6) }
                background = GradientDrawable().apply {
                    setColor(com.astral.typer.utils.ThemeUtils.getColorFromAttr(context, com.astral.typer.R.attr.appCardBgColor))
                    cornerRadius = 10f
                    setStroke(com.astral.typer.utils.ThemeUtils.getDimensionFromAttr(context, com.astral.typer.R.attr.appCardBorderWidth).toInt(), com.astral.typer.utils.ThemeUtils.getColorFromAttr(context, com.astral.typer.R.attr.appCardBorderColor))
                }
            }

            val infoRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val tvName = TextView(context).apply {
                tag = "tvName"
                setTextColor(com.astral.typer.utils.ThemeUtils.getColorFromAttr(context, com.astral.typer.R.attr.appTextColorPrimary))
                textSize = 18f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvType = TextView(context).apply {
                val fontColor = com.astral.typer.utils.ThemeUtils.getColorFromAttr(context, com.astral.typer.R.attr.appTextColorSecondary)
                tag = "tvType"
                textSize = 12f
                setPadding(12, 4, 12, 4)
                background = GradientDrawable().apply {
                    setColor(com.astral.typer.utils.ThemeUtils.getColorFromAttr(context, com.astral.typer.R.attr.appButtonBgColor))
                    setStroke(com.astral.typer.utils.ThemeUtils.getDimensionFromAttr(context, com.astral.typer.R.attr.appButtonBorderWidth).toInt(), com.astral.typer.utils.ThemeUtils.getColorFromAttr(context, com.astral.typer.R.attr.appButtonBorderColor))
                    cornerRadius = 4f
                }
            }

            infoRow.addView(tvName)
            infoRow.addView(tvType)
            card.addView(infoRow)

            val actionsRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 8, 0, 0) }
            }

            val tvAssigned = TextView(context).apply {
                tag = "tvAssigned"
                setTextColor(com.astral.typer.utils.ThemeUtils.getColorFromAttr(context, com.astral.typer.R.attr.appTextColorSecondary))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val btnManageCategories = Button(context).apply {
                tag = "btnManageCategories"
                text = "Categories"
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 4, 0) }
            }

            actionsRow.addView(tvAssigned)
            actionsRow.addView(btnManageCategories)

            val btnDelete = Button(context).apply {
                tag = "btnDelete"
                text = "Delete"
                textSize = 11f
                setBackgroundColor(Color.parseColor("#D32F2F"))
                setTextColor(com.astral.typer.utils.ThemeUtils.getColorFromAttr(context, com.astral.typer.R.attr.appTextColorPrimary))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 4, 0) }
            }
            actionsRow.addView(btnDelete)

            card.addView(actionsRow)
            return FontViewHolder(card)
        }

        override fun onBindViewHolder(holder: FontViewHolder, position: Int) {
            val font = items[position]
            holder.tvName.text = font.name
            try {
                holder.tvName.typeface = font.typeface
            } catch (e: Exception) {
                holder.tvName.typeface = null
            }

            holder.tvType.text = if (font.isCustom) "Custom" else "Standard"
            holder.tvType.setTextColor(if (font.isCustom) Color.CYAN else Color.GRAY)

            val fontId = font.path ?: font.name
            val prefs = context.getSharedPreferences("font_prefs", Context.MODE_PRIVATE)
            val assigned = prefs.getStringSet("font_categories_$fontId", emptySet()) ?: emptySet()
            val categoriesText = if (assigned.isEmpty()) "None" else assigned.joinToString(", ")
            holder.tvAssigned.text = "Categories: $categoriesText"

            holder.btnManageCategories.setOnClickListener {
                onManageCategories(font, holder.adapterPosition)
            }

            if (font.isCustom) {
                holder.btnDelete?.visibility = View.VISIBLE
                holder.btnDelete?.setOnClickListener {
                    onDelete(font, holder.adapterPosition)
                }
            } else {
                holder.btnDelete?.visibility = View.GONE
            }
        }

        override fun getItemCount(): Int = items.size
    }

    class StoreFontAdapter(
        private val context: Context,
        private var items: List<com.astral.typer.utils.GoogleFontStoreManager.StoreFontItem>,
        private val onDownload: (com.astral.typer.utils.GoogleFontStoreManager.StoreFontItem, Int) -> Unit
    ) : RecyclerView.Adapter<StoreFontAdapter.StoreViewHolder>() {

        fun updateItems(newItems: List<com.astral.typer.utils.GoogleFontStoreManager.StoreFontItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        class StoreViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewWithTag("tvName")
            val btnAction: Button = view.findViewWithTag("btnAction")
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StoreViewHolder {
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(12, 12, 12, 12)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 6, 0, 6) }
                background = GradientDrawable().apply {
                    setColor(com.astral.typer.utils.ThemeUtils.getColorFromAttr(context, com.astral.typer.R.attr.appCardBgColor))
                    cornerRadius = 10f
                    setStroke(com.astral.typer.utils.ThemeUtils.getDimensionFromAttr(context, com.astral.typer.R.attr.appCardBorderWidth).toInt(), com.astral.typer.utils.ThemeUtils.getColorFromAttr(context, com.astral.typer.R.attr.appCardBorderColor))
                }
            }

            val tvName = TextView(context).apply {
                tag = "tvName"
                setTextColor(com.astral.typer.utils.ThemeUtils.getColorFromAttr(context, com.astral.typer.R.attr.appTextColorPrimary))
                textSize = 18f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val btnAction = Button(context).apply {
                tag = "btnAction"
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            card.addView(tvName)
            card.addView(btnAction)
            return StoreViewHolder(card)
        }

        override fun onBindViewHolder(holder: StoreViewHolder, position: Int) {
            val item = items[position]
            holder.tvName.text = item.family

            if (item.typeface != null) {
                holder.tvName.typeface = item.typeface
            } else {
                holder.tvName.typeface = null
                (context as? AppCompatActivity)?.lifecycleScope?.launch(Dispatchers.IO) {
                    val tf = com.astral.typer.utils.GoogleFontStoreManager.loadPreviewTypeface(context, item)
                    if (tf != null) {
                        withContext(Dispatchers.Main) {
                            holder.tvName.typeface = tf
                        }
                    }
                }
            }

            if (item.isDownloaded) {
                holder.tvName.alpha = 1.0f
                holder.btnAction.text = "Installed"
                holder.btnAction.isEnabled = false
                holder.btnAction.setTextColor(Color.GREEN)
            } else {
                holder.tvName.alpha = 0.4f
                holder.btnAction.text = "Download"
                holder.btnAction.isEnabled = true
                holder.btnAction.setTextColor(com.astral.typer.utils.ThemeUtils.getColorFromAttr(context, com.astral.typer.R.attr.appTextColorPrimary))
                holder.btnAction.setOnClickListener {
                    holder.btnAction.text = "Downloading..."
                    holder.btnAction.isEnabled = false
                    onDownload(item, holder.adapterPosition)
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
