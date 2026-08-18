import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.StaticLayout
import android.text.TextPaint

fun main() {
    println("If TextPaint color is transparent, text is invisible, but BackgroundColorSpan is still drawn because it sets wp.setColor(bgColor) in TextLine.")
}
