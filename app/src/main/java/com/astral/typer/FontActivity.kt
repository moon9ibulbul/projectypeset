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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager

class FontActivity : AppCompatActivity() {

    private lateinit var spinnerOrderBy: Spinner
    private lateinit var btnAddCategory: Button
    private lateinit var layoutCategoriesList: LinearLayout
    private lateinit var btnImportFont: Button
    private lateinit var etSearchFonts: EditText
    private lateinit var layoutFontsList: RecyclerView
    private var fontAdapter: FontAdapter? = null

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
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_font)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            finish()
        }

        spinnerOrderBy = findViewById(R.id.spinnerOrderBy)
        btnAddCategory = findViewById(R.id.btnAddCategory)
        layoutCategoriesList = findViewById(R.id.layoutCategoriesList)
        btnImportFont = findViewById(R.id.btnImportFont)
        etSearchFonts = findViewById(R.id.etSearchFonts)
        layoutFontsList = findViewById(R.id.layoutFontsList)
        layoutFontsList.layoutManager = LinearLayoutManager(this)
        layoutFontsList.isNestedScrollingEnabled = false

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
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, orderOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerOrderBy.adapter = adapter

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
                    setColor(Color.parseColor("#2A2A2A"))
                    cornerRadius = 8f
                }
            }

            val tvName = TextView(this).apply {
                text = cat
                setTextColor(Color.WHITE)
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
                setTextColor(Color.WHITE)
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

        return when (orderBy) {
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
                    setColor(Color.parseColor("#1E1E1E"))
                    cornerRadius = 10f
                    setStroke(1, Color.parseColor("#333333"))
                }
            }

            val infoRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val tvName = TextView(context).apply {
                tag = "tvName"
                setTextColor(Color.WHITE)
                textSize = 18f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvType = TextView(context).apply {
                tag = "tvType"
                textSize = 12f
                setPadding(12, 4, 12, 4)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#2A2A2A"))
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
                setTextColor(Color.parseColor("#A0A0A0"))
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
                setTextColor(Color.WHITE)
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
}
