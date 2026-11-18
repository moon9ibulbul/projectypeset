# AGENTS.md

Guidance for AI coding agents working on this repository (Astral Typer, Android / Kotlin).
Compiled from accumulated project memory. Prefer these conventions over generic assumptions.

## 1. Build, Test & Versioning

- Run Gradle with the **system Gradle** directly, e.g. `gradle testDebugUnitTest`. `./gradlew` does **not** exist in the repo root.
- Current version in `app/build.gradle`: `versionName "1.6.9"`, `versionCode 39`. Bump both when releasing.
- Kotlin pitfall: `::variable.isInitialized` cannot be used on **local** variables (error: `Unsupported [References to variables aren't supported yet]`). Declare/initialize local views or objects *before* the closure that references them; do not use a local `lateinit var`.

## 2. Property Persistence Checklist

When adding a layer/style property (e.g. `reflectionBlur`, `motionBlurWeightMode`, `isMotionShadowTwoWay`, `highlightCornerRadius`, `brushCorrection`), wire it through **all** of:

1. `StylableLayer` / `Layer` and the concrete layers: `TextLayer`, `ShapeLayer`, `BrushLayer`, `ImageLayer`
2. `ProjectManager.LayerModel` (serialization)
3. `StyleManager.StyleModel` (style presets)
4. UI controls in `EditorActivity.kt`
5. Rendering code in the relevant layer class

## 3. UI, Theming & Icons

- Vector drawables follow the app theme: `app:tint="?attr/appIconTint"` in XML, `ThemeUtils.getColorFromAttr(..., R.attr.appIconTint)` in code. No hardcoded tints.
- Drawables use neutral `#FFFFFFFF` stroke/fill.
- Lucide icons: 24x24 viewport, strokeWidth 2.
- Material Symbols Outlined: 960x960 viewport with `<group android:translateY="960">` to match Google's glyph coordinate space.
- Mirror icons (`app/src/main/res/drawable/`): `ic_mirror_horizontal.xml` = flip across the vertical center axis (`scaleX *= -1`); `ic_mirror_vertical.xml` = flip across the horizontal center axis (`scaleY *= -1`).
- Rename/delete icons in `RecentActivity.kt` and `recent_menu.xml`: Lucide `square-pen` (`ic_square_pen.xml`) and `trash` (`ic_trash.xml`), tinted with `R.attr.appIconTint`.
- Control labels such as highlight padding/radius are **English-labeled** plus/minus controls (`createControl`).

### Editor menus (`EditorActivity.kt`)

- Bottom popup menus must fit within `MENU_HEIGHT_DP` (180dp).
- `createFormattingTab`: Mirror Horizontal, Mirror Vertical and Shape Toggle sit inline with alignment icons in one horizontal `alignRow` (no `transformRow`).
- `createSizeTab`: 2-column layout (row 1: Text Size & Box Scale; row 2: Box Width & Rotate) with compact control styling.
- The Format menu has tabs: Formatting, Size, Transform and **Reshape**.
- Top-left `binding.btnBack` must call `onBackPressedDispatcher.onBackPressed()` so the exit dialog ("Do you want to go back to main menu?") and session recovery cleanup behave exactly like the system back gesture.
- `AstralCanvasView.setLayers(newLayers)` preserves selection on undo/redo (`selectedLayer = newLayers.find { it.isSelected }`) and calls `onLayerSelectedListener?.onLayerSelected(selectedLayer)`. The listener handles `"BRUSH" -> showBrushMenu()` when `propertyDetailContainer` is visible so brush sliders/presets re-bind to the restored `BrushLayer`.

## 4. Mode Typer

### 4.1 Tools & Touch Handling (`AstralCanvasView.kt`, `EditorActivity.kt`)

- `TyperTool`: `HAND`, `RECT`, `CIRCLE`, `LASSO`, `MAGIC_WAND`, `ERASER`, `MERGE` (`ic_typer_merge`), `MOVE` (`ic_typer_move`).
- Merge: combines clicked overlapping/touching detected bubbles transitively. Move: drags/translates detected bubbles.
- Magic Wand: long-press triggers auto-merge across the flood-fill mask without segmentation splitting; a quick tap performs standard segmentation.
- For `RECT`, `CIRCLE`, `LASSO`, `MAGIC_WAND`: touches with `pointerCount >= 2` or while pan/zoom is active route to `scaleDetector`/`gestureDetector` (`currentMode = Mode.PAN_ZOOM`) and reset drawing paths.
- For `HAND`: single-touch falls through the early pan/zoom interception (`pointerCount >= 2 || (currentTyperTool != TyperTool.HAND && currentMode == Mode.PAN_ZOOM)`), so taps on detected bubbles reach `ACTION_UP` and invoke `onBubbleClickListener`. Text is inserted into a bubble when HAND taps it.
- Magic Wand panning/zooming protection: multi-touch or single-finger movement beyond touch slop cancels `magicWandHoldRunnable`, sets `hasMoved = true`, and resets `isMagicWandHoldTriggered`, preventing accidental bubble mask creation.
- `layout_typer_tools.xml` is a compact 2-column horizontal container to avoid overlapping the bottom Typer text/paste popup:
  - Column 1 (selection/creation): Hand, Rect, Circle, Lasso
  - Column 2 (manipulation/merge): Magic Wand, Merge, Move, Eraser, Smart Reshape toggle (`btnToolSmartReshape`)

### 4.2 Bubble Detection & Segmentation

- `performTyperMagicWand` flood-fills background canvas tiles to detect bubble bounds and registers `TyperBubble(rect, isOval)`.
- `TyperBubbleSegmentationHelper` provides `segmentConnectedMask`, `detectIsOval`, `performClosedFloodFill`, `fillHoles`:
  - Splits merged overlapping bubbles via distance-transform peak finding.
  - Closes dashed/discontinuous borders via 2D separable sliding-window dilation (radius 24px) plus distance-limited BFS expansion. When standard flood fill leaks outside a dashed boundary, **closed flood fill takes precedence**.
  - `fillHoles` fills interior holes (e.g. dark text glyphs) *before* the Euclidean distance transform so dialogue text does not fragment a bubble.
  - Oval vs rectangle is decided by fill ratio and corner occupancy.
- `computeSmartBubbleBounds` (+ `mergeDetectedBubbleAt` in `AstralCanvasView.kt`): detects narrow speaker tails via row/column pixel occupancy and relative component areas, then trims the tail so text bounds stay centered on the bubble body.
- `BubbleDetectorProcessor.detectCleanBubbles` / `detectCleanBubblesFromPixels`: finds empty/clean bubbles (uniform white/light regions enclosed by dark contours) via connected components, supplementing RT-DETR detections for label `0L` (speech bubbles).

### 4.3 Layout Solver & Hyphenation (`TyperLayoutSolver.kt`, `IndonesianHyphenator.kt`)

- Intelligent typesetting: stepped diamond/oval silhouette line fitting (penalizes 1-word-per-line stacks, rewards diamond line profiles) with orphan-word minimization; Indonesian syllable hyphenation via `IndonesianHyphenator.kt`.
- Clicking a detected bubble (`EditorActivity.kt`): keeps `layer.fontSize` unchanged (100pt or the style font size), runs `TyperLayoutSolver.solveLayout` with `minScale = 0.05f`, then sets `layer.scaleX = layer.scaleY = layoutSolution.scale` and `layer.boxWidth = layoutSolution.boxWidth / layoutSolution.scale`.
- Interior safe padding is **10%** (`bubbleWidth * 0.10f`, `bubbleHeight * 0.10f`) for both oval and rectangular bubbles, so oval scale outputs do not shrink disproportionately.
- Indonesian reduplication words (`isReduplicationWord`, e.g. "Tergopoh-gopoh", "berlari-lari", "kupu-kupu") may be split at the hyphen (`splitReduplication`) across lines; such candidates get a **+15f cost penalty** so intact words are preferred.
- Smart Reshape toggle: `btnToolSmartReshape` toggles `isTyperSmartReshapeEnabled`. With `smartReshape = false` the solver still runs the same stepping evaluation to resolve `scale` and `boxWidth`, but returns the raw trimmed text (`formattedText = trimmed`) with no `\n` line breaks and no hyphenation.
- `TextLayer.kt` oval-indent safeguards: clamp normalized Y to `[-0.85f, 0.85f]`; minimum width factor 0.70f (multi-line text with newlines) or 0.50f otherwise.

### 4.4 Reshape Tab

- Uses `TyperLayoutSolver.generateReshapeVariants` / `generateReshapeVariantsPure` to produce 3 diamond variants: `BALANCED_DIAMOND`, `COMPACT_DIAMOND`, `WIDE_DIAMOND`; avoids Indonesian word hyphenation across lines when possible.
- Applying a variant updates `layer.text` and `layer.boxWidth` only; it **must preserve** `fontSize`, `scaleX`, `scaleY`.
- Thumbnails are lazy, minimal horizontal cards with scaled-down text using the active `layer.typeface`.
- Variants are cached on `TextLayer.cachedReshapeVariants`; do not regenerate when switching variants unless `boxWidth` was manually modified.

### 4.5 Performance (low/mid-end devices)

1. `BubbleDetectorProcessor.kt`: `Semaphore(concurrencyLimit)` clamped to 1..2 for parallel tile inference.
2. `TyperLayoutSolver.solveLayout`: cache word measurements at base font size in a hash map and scale in O(1); avoid repeated `TextPaint` allocations.
3. `EditorActivity.kt`: bubble-tap layout solving runs via `lifecycleScope.launch(Dispatchers.Default)` and posts results to `Dispatchers.Main`.
4. `performTyperMagicWand`: flood fill and segmentation run on a background thread; canvas updates post back to the UI thread.
5. `TyperTextAdapter`: create the spinner `ArrayAdapter` in the `ViewHolder`, not in every `onBindViewHolder`.

## 5. Inpainting

### 5.1 Engines (`InpaintManager.kt`)

`InpaintManager.Engine`: `OPENCV` (Telea), `PATCHMATCH` (Content-Aware Fill), `SMART_SOLID` (Smart Solid Fill), `HYBRID`, `LAMA`, `MIGAN`.

- `HYBRID` (`inpaintHybrid`) follows AstralCleaner's component-based `clean()`: group mask regions into connected blobs; each blob samples an outer background ring (`ring = 8, tol = 22, min_frac = 0.85`); uniform bubble backgrounds are filled via Smart Solid Fill (+1px dilation), complex art/screentone blobs go to LaMa.
- Content-Aware Fill (`inpaintWithPatchMatch`): pre-fills holes with `inpaintWithSmartSolid`, enforces `isValidSource` (source patches sample only non-hole pixels), and uses overlapping patch voting with bilateral filtering over hole pixels.

### 5.2 LaMa (`LaMaProcessor.kt`)

- Model: dynamic-input Manga ONNX (`ogkalu/lama-manga-onnx-dynamic` / `lama-manga-dynamic.onnx`), **CPU only**. NNAPI and the int8 variant were permanently removed; do not reintroduce.
- Crop margin: `max(64, 0.6 * max(w, h))`; +1px mask pre-dilation (removes anti-aliased font edges); preserve unmasked RGB context in 4D tensors; pad dimensions to multiples of 8 using `BORDER_REFLECT_101`; Gaussian-blur edge feathering before blending onto the canvas.

### 5.3 Text Detection & Masks

- `BubbleDetectorProcessor.generateTightTextMasks` (AstralCleaner `text_polys` algorithm): isolates bubble interior via 16-bin RGB paper-color quantization, morphological closing, largest component, hole fill, erosion; extracts Otsu ink masks (filters border outlines in strict mode); dilates by mask padding with an elliptical kernel; traces external contours simplified with Ramer-Douglas-Peucker. The old "Text Mask Shape" setting was removed.
- "Detect Text" button respects `inpaint_detect_button_action` ("Detect + Inpaint" or "Detect Only"). With "Detect + Inpaint", inpainting uses the engine from `inpaintManager.getEngine()` (the toolbar spinner).
- Smart Select Text (`InpaintSelectionMode.SMART_SELECT_TEXT`, `InpaintTool.SMART_SELECT_TEXT`): shown in selection-mode cycling only when `bubbleProcessor.isModelAvailable()`. The user lassos text; `detectSmartSelectedTextForInpaint` runs `bubbleProcessor.detect` on the crop, keeps text rects overlapping the lasso boundary, then calls `generateTightTextMasks`.

### 5.4 Mask Editing (`AstralCanvasView.kt`, `EditorActivity.kt`)

- Expand toggle: -40..+40 slider bound to `canvasView.inpaintMaskExpand`. Dilation (>0) / erosion (<0) operate on the alpha channel via OpenCV (`Imgproc.dilate` / `Imgproc.erode`, `MORPH_ELLIPSE`) or a Euclidean-disk BitSet fallback. The OpenCV branch merges `dstMat` into all 4 RGBA channels (`[dstMat, dstMat, dstMat, dstMat]`) so unmasked pixels stay transparent black (`0x00000000`) and masked pixels are white (`0xFFFFFFFF`); this prevents `COLOR_RGBA2GRAY` in engines from treating transparent pixels as mask ink. Toggling Expand off sets `inpaintMaskExpand = 0`.
- Inpaint ops: `InpaintOp(val path: Path, val tool: InpaintTool, val strokeWidth: Float = 0f)`. Brush/eraser strokes commit with their current `brushSize`; changing the Size slider only affects subsequent strokes.
- Mask history: `saveInpaintMaskState()` before inpainting; `restorePreviousInpaintMask()` when undoing an inpaint bitmap result, so the mask can be reused/edited.

### 5.5 Preference Defaults

- `inpaint_detect_text_outside` defaults to **false**; applies across `activity_settings.xml`, `SettingsActivity.kt`, `EditorActivity.kt`, `ProjectManager.kt`.
- `inpaint_text_padding` defaults to **2px** (`activity_settings.xml` progress 22) across the same files plus `AstralCanvasView.kt`.

### 5.6 Batch Auto Inpaint (`ProjectManager`)

- `autoInpaintProject`, `autoInpaintFolder`, `autoInpaintProjects` batch Auto Detect Text + Inpainting using a given `InpaintManager.Engine` (Hybrid, LaMa, or Smart Solid Fill) and `keepMaskLayer: Boolean`.
- From a Project Folder (on the folder via `autoInpaintFolder`, or on selected projects inside a folder in `RecentActivity.kt`): pass `keepMaskLayer = true`. `background.png` stays untouched; a full-canvas, auto-locked (`isLocked = true`) `ImageLayer` named "Inpaint Mask" is created, centered at `(width / 2f, height / 2f)`, using `DST_IN` alpha blending.
- Outside a folder context: overwrite `background.png` directly.

## 6. Text & Shape Layer Rendering

### 6.1 TextLayer (`TextLayer.kt`)

- `StrokeSpan(strokeColor: Int, strokeWidth: Float) : CharacterStyle(), UpdateAppearance` gives per-character stroke. `showStrokeMenu()` includes `createInputView(layer, false)` so color/width changes apply `StrokeSpan` to the selection in `activeEditText`. Serialized as `SpanModel("STROKE", start, end, "$hexColor,$strokeWidth")` in `ProjectManager.kt`.
- `drawLayoutSafe(canvas, suppressBg, suppressFg)` temporarily suppresses `ForegroundColorSpan`s (`suppressFg = true`) during 1st/2nd/3rd stroke passes and shadow passes, restoring them in a `finally` block so span colors never override stroke/shadow colors.
- Text Tail wave: envelope ramp from origin `(t * 3f).coerceAtMost(1f)`, normalized frequencies (0.035, 0.08, 0.015), displacement scaled by `tailWavyIntensity * 0.6f`.
- Highlight: `highlightCornerRadius`, `highlightPaddingTop/Right/Bottom/Left` (see checklist in section 2); edited in the `showColorPicker` highlight tab.

### 6.2 ShapeLayer (`ShapeLayer.kt`)

- `renderSvgManipulated()` renders embedded SVG vectors intact when `isSmartShape = true` and no layer stroke/shader is active.
- Layer stroke passes (`fill == null && stroke != null`): detect embedded stroke width `origSw` and add it (`effectiveSw = origSw + strokeW`) so new strokes expand outward from existing stroke edges.
- Silhouette/shadow passes with `silhouetteColor != null` (Long Shadow, silhouette): SVG stroke color = `silhouetteColor` (`if (silhouetteColor != null) silhouetteColor!! else (stroke ?: strokeColor)`) so shadows are fully in the shadow color.
- Drop shadow: generated from an offscreen ARGB pass (`drawMain`).
  - `isDropShadowIncludeStroke == true`: `effectiveStrokeColor = stroke ?: strokeColor`; render with `RenderNode` + `RenderEffect.createBlurEffect` on Android 12+ (API 31+) hardware canvas, else `blurBitmapSoftware`. This preserves multi-color fills/strokes.
  - `false`: extract blurred alpha (`extractAlpha`) and recolor with `shadowColor` or `gradientShader`.
- Motion shadow iterations render inside `targetCanvas.saveLayer` using `iterPaint` (`alpha = shadowAlpha`, `maskFilter = BlurMaskFilter(blur, NORMAL)`) to keep smooth blur/opacity fade when `isMotionShadowIncludeStroke` is active.

## 7. Effects

### 7.1 Shadows (`showShadowControls` in `EditorActivity.kt`)

- Motion Shadow supports Two Way / One Way via `var isMotionShadowTwoWay: Boolean = true` (all layer types). When false, the reverse iteration pass is skipped. A "Two Way" checkbox lives under the Motion Shadow controls; serialized in `ProjectManager.LayerModel` and `StyleManager.StyleModel`.
- Opening shadow controls on a layer with active Motion Shadow (`isMotionShadow && (motionShadowDistance > 0f || motionShadowThickness > 0f)`) auto-selects the **Motion Shadow tab** instead of Drop Shadow.
- Tapping the active shadow color deactivates the shadow: if `shadowRadius > 0 || shadowThickness > 0` (Drop) or `motionShadowDistance > 0 || motionShadowThickness > 0` (Motion), reset both radius/distance and thickness to `0f` and refresh the UI.

### 7.2 Motion Blur (`TextEffectType.MOTION_BLUR`)

- Two selectable AGSL shaders: `MOTION_BLUR_SHADER_V1` ("Shader 1", legacy) and `MOTION_BLUR_SHADER` ("Shader 2", default).
- Shader 2: symmetric linear sampling over t in [-0.5, +0.5] along `uVelocity`; dynamic sample count capped at 128; interleaved-gradient-noise jitter for blur lengths > 128px; `uWeightMode` (0 = box/uniform, Photoshop-style; 1 = triangular falloff, Clip Studio Paint-style).
- Properties: `var motionBlurWeightMode: Int = 0`, `var motionBlurShaderVersion: Int = 2` (see checklist); "Shader 1"/"Shader 2" toggle in `EditorActivity.kt`.
- Non-hardware canvas or Android < 13 (API < 33): CPU fallback does **directional** accumulation using `cachedMotionBlurBitmap`, not isotropic Gaussian blur.

### 7.3 Reflection (`TextEffectType.REFLECTION`)

- `reflectionBlur: Float` (0..50 px) across all layer/model classes; "Reflection Blur Strength" slider in `EditorActivity.kt`.
- `TextLayer.REFLECTION_SHADER` implements a calibrated 2D Gaussian blur with continuous exponential weights based on squared Euclidean distance.

### 7.4 Speed Line (`TextEffectType.SPEED_LINE`)

Optimized in `TextLayer.kt`, `ShapeLayer.kt`, `SpeedLineHelper.kt` to avoid ANR/OOM:

1. `calculatePadding()` does **not** expand padding for `SPEED_LINE`; lines are composited onto fills via `SRC_ATOP` and clipped to bounds (no huge offscreen buffers / `saveLayer` allocations).
2. `SpeedLineHelper` batches all contours into a single `Path` and reduces line count during far zoom-out (`viewScale < 0.25f`).
3. Layers cache the path (`cachedSpeedLinePath`, `cachedSpeedLineHash` via `SpeedLineHelper.calculateSpeedLineHash`): one cached draw call per frame.

### 7.5 Perspective / Warp (`EditorActivity.kt`, `AstralCanvasView.kt`)

- Perspective menu has "Reset" and "Skew" buttons. When `isPerspectiveSkewMode = true`, Skew has an active background and cyan border.
- Dragging a corner handle of the 1x1 warp mesh moves only one neighbor: horizontal zone (`abs(dx) >= abs(dy)`) moves the dragged point and its horizontal neighbor along X; vertical zone (`abs(dy) > abs(dx)`) moves the dragged point and its vertical neighbor along Y.

## 8. Brush (`BrushLayer.kt`)

- **Large-canvas performance** (e.g. 27,000 px wide):
  1. Incremental content-bounds tracking (`updateBoundsWithDab`) during dab rendering; never scan the full bitmap with `bmp.getPixels()`.
  2. Viewport frustum culling: intersect active tile bounds (layer-local space) with `canvas.getClipBounds()` plus effect padding (`maxPad`); fall back to `activeTiles` when `visibleTiles` is empty so strokes stay visible during zoom/pan.
  3. Live shadow deferral (`isDrawingStroke`): offscreen shadow compositing waits until `endStroke()` while strokes still render live.
- **Brush Correction** (`brushCorrection`, levels 0..10, persisted in `ProjectManager.LayerModel`): records raw `StrokePoint`s and `preStrokeTileSnapshots`; `reapplyLastStrokeWithCorrection(level)` restores pre-stroke tiles and re-renders with moving-average smoothing (up to 20 passes). Applied automatically in `endStroke()` and when the Brush Correction slider in `showBrushMenu()` changes.
- `startStroke` initializes `STATE_PRESSURE` to `pressure.coerceIn(0.1f, 1f)` (not 0f) to preserve initial width and entry hooks; `endStroke()` flushes remaining `slow_tracking` lag dabs to preserve exit hooks. `calligraphy.myb` uses `slow_tracking = 0.15` and `hardness = 0.88`.

## 9. Projects & Export

- Rename: `ProjectManager.renameFile(file, newName)` handles project directories and `.atd` files. UI entry points: `RecentActivity.kt` (Action Mode menu, single selection) and `MainActivity.kt` (long-press options dialog on recent project cards).
- `PsdExporter` (`com.astral.typer.utils.PsdExporter`) writes Photoshop `.psd` files:
  - Preserves layers, opacity, visibility, blend modes, clipping flags; PackBits RLE RGBA channels.
  - `TextLayer` exports as an editable Type layer via the `TySh` block (affine matrix, `TxLr` descriptor, `EngineData` styling, warp), `luni` Unicode names, and `lrFX` effects (drop shadow), while still writing rendered raster channels for compatibility.
- Image export options in `EditorActivity.kt`: PNG, JPG, WEBP.

## 10. Quick Do / Don't

- **Do** keep heavy work (inference, flood fill, layout solving) off the main thread and post results back to `Dispatchers.Main` / the UI thread.
- **Do** keep menus compact enough for the 180dp popup.
- **Do** preserve `fontSize` and box scale when applying Reshape variants.
- **Don't** add NNAPI or int8 variants to LaMa.
- **Don't** expand layer padding for `SPEED_LINE`.
- **Don't** use `::local.isInitialized` or local `lateinit var`.
- **Don't** hardcode icon tints; use `?attr/appIconTint`.
- **Don't** call `./gradlew`; use system `gradle`.
