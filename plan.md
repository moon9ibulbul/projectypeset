1. **Analyze the Problem**:
   The issue is that when Warp and Stroke are active, the text opacity and highlight colors lose transparency and fade to the stroke color instead. Additionally, the stroke gradient does not work.
   The root cause lies in how the post-warp stroke extraction is performed:
   - Stroke extraction uses a `ColorMatrix` that forces RGB to the stroke color but maps the alpha using `100 * A - 250`.
   - The matrix creates a solid blob underneath the text. Since it's drawn behind the text, if the text has opacity (like 50%), the solid black stroke bleeds through, causing a darkening effect rather than transparency.
   - For gradient strokes, the `ColorMatrix` is used to force the color into the solid `strokeColor`, effectively ignoring the `gradientShader`.

2. **Resolution**:
   - **Gradient Stroke**: Fix the `ColorMatrix` to leave the RGB components untouched (`1f, 1f, 1f`) and only apply the threshold logic to the Alpha channel. Then apply the `gradientShader` (or `strokeColor`) to the `Paint`.
   - **Opacity Text Color**: Punch a hole in the solid post-warp stroke blob matching the exact shape of the warped text. This can be done by using `canvas.saveLayer()`, drawing the extracted stroke blob, and erasing the text area using `PorterDuff.Mode.DST_OUT` combined with a `ColorMatrix` that scales the text's alpha to fully erase it.

3. **Pre Commit Steps**: Ensure proper testing, verification, review, and reflection are done by calling the pre commit instruction tool and following its guidelines.

4. **Submit**: Once verified, commit the changes using a meaningful message.
