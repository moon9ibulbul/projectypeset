Yes! In Android, `BackgroundColorSpan.updateDrawState(TextPaint ds)` ONLY sets `ds.bgColor = mColor`.
Then, in `TextLine.drawRun`, if `wp.bgColor != 0`, it draws the background rect. Then it draws the text using `wp.color`.
If we set `layout.paint.color = Color.TRANSPARENT`, the text itself will be invisible, but the background rect will be drawn using `wp.bgColor`!

So the fix is to completely separate the Background rendering from the Text rendering inside `drawMain`.

At the very top of `drawMain` (before `// 0. Triple Stroke`), we can add:
```kotlin
            // -1. Background Highlight Pass
            val spannable = layout.text as? android.text.Spannable
            val bgSpans = spannable?.getSpans(0, spannable.length, android.text.style.BackgroundColorSpan::class.java)
            if (bgSpans != null && bgSpans.isNotEmpty()) {
                val prevColor = paint.color
                val prevStyle = paint.style
                val prevShader = paint.shader

                paint.color = Color.TRANSPARENT
                paint.style = Paint.Style.FILL
                paint.shader = null
                paint.clearShadowLayer()
                layout.draw(targetCanvas)

                paint.color = prevColor
                paint.style = prevStyle
                paint.shader = prevShader
            }
```
Wait! What if we are generating `cleanContentCache` for `extractAlpha` (`isDrawingStrokePass == true`)?
If `isDrawingStrokePass` is TRUE, we want to draw the text fill WITHOUT the background highlight, so that the highlight doesn't become part of the stroke mask.
So if `isDrawingStrokePass` is TRUE, we should NOT draw the background highlight pass!
So:
```kotlin
            if (!isDrawingStrokePass && !isDrawingShadowPass && bgSpans != null && bgSpans.isNotEmpty()) {
                ...
```

Then, for ALL subsequent passes (Stroke 0, 1, 2, and Fill 3), we should ALWAYS suppress the background!
Because we already drew it in pass -1!
Wait, what if `isDrawingStrokePass == true`? We suppress it too, which is correct because we don't want it in the mask!
What if `isDrawingShadowPass == true`? We suppress it too, which is correct!
So we just ALWAYS suppress the background in ALL `drawLayoutSafe` calls inside `drawMain`!

Let's review:
```kotlin
            // -1. Background Highlight Pass
            val spannableForBg = layout.text as? android.text.Spannable
            val hasBg = spannableForBg?.getSpans(0, spannableForBg.length, android.text.style.BackgroundColorSpan::class.java)?.isNotEmpty() == true

            if (!isDrawingStrokePass && !isDrawingShadowPass && !isDrawingClippingMask && hasBg) {
                val prevColor = paint.color
                val prevStyle = paint.style
                val prevShader = paint.shader
                val prevShadow = paint.maskFilter // wait, paint.clearShadowLayer()

                paint.color = Color.TRANSPARENT
                paint.style = Paint.Style.FILL
                paint.shader = null
                paint.clearShadowLayer()
                layout.draw(targetCanvas)

                paint.color = prevColor
                paint.style = prevStyle
                paint.shader = prevShader
            }
```
Then, we change `drawLayoutSafe` to ALWAYS suppress!
Actually, we don't even need the `suppressBg` parameter anymore, we can just hardcode it to true, but keeping the parameter is fine.

Wait! If we draw the background pass, will it draw tail paths?
`drawTailPath(targetCanvas, paint)`
The tail path is not part of `layout.draw(targetCanvas)`. The tail path is an effect. We shouldn't draw the tail path in the background pass (it doesn't have a background anyway).

Wait, if we use `paint.color = Color.TRANSPARENT`, will it draw shadows?
We called `paint.clearShadowLayer()`. So no.

What if there are OTHER spans that draw something? `UnderlineSpan`, `StrikethroughSpan`.
`UnderlineSpan` and `StrikethroughSpan` use `wp.color`!
If `paint.color` is `Color.TRANSPARENT`, the underline and strikethrough will ALSO be transparent!
So they won't be drawn in the background pass. This is perfect!

Let's double check `drawLayoutSafe` usage.
I will change all `drawLayoutSafe` calls to pass `true` inside `drawMain`.
And inject the `Background Highlight Pass` at the top of `drawMain`.

Let's test this logic!
