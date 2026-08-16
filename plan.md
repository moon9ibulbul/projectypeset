1. Add `ThemeUtils` inside `app/src/main/java/com/astral/typer/utils/ThemeUtils.kt` to load dynamic attribute colors like `?attr/appTextColorPrimary`.
2. Replace hardcoded text colors `Color.WHITE` in `EditorActivity.kt`, `MainActivity.kt`, `FontActivity.kt`, `PatternAdapter.kt`, and `ColorPickerHelper.kt` with `ThemeUtils.getColorFromAttr(context, com.astral.typer.R.attr.appTextColorPrimary)`.
3. Replace hardcoded text colors `Color.LTGRAY` in `EditorActivity.kt` with `ThemeUtils.getColorFromAttr(context, com.astral.typer.R.attr.appTextColorSecondary)`.
4. Replace hardcoded icon tints `setColorFilter(Color.WHITE)` in `EditorActivity.kt` with `ThemeUtils.getColorFromAttr(context, com.astral.typer.R.attr.appIconTint)`.
5. Pre-commit check to ensure everything works without regressions.
6. Submit the change.
