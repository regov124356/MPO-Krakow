package pl.necek.wywoz

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.TextView
import java.time.LocalDate
import java.util.Locale

val BG = 0xFF0E1013.toInt()
val CARD = 0xFF1A1E24.toInt()
val TEXT = 0xFFF2F4F7.toInt()
val MUTED = 0xFF9AA3AE.toInt()
val ACCENT = 0xFFFFB300.toInt()
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
}

fun Context.label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
    text = value
    textSize = size
    setTextColor(color)
    if (bold) typeface = Typeface.DEFAULT_BOLD
}

fun Context.flatButton(title: String, onClick: () -> Unit) =
    Button(this, null, android.R.attr.borderlessButtonStyle).apply {
        text = title
        textSize = 16f
        isAllCaps = false
        setTextColor(TEXT)
        setOnClickListener { onClick() }
    }

fun View.applySystemInsets(base: Int) {
    setPadding(base, base, base, base)
    if (Build.VERSION.SDK_INT >= 30) {
        setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
            )
            v.setPadding(base + b.left, base + b.top, base + b.right, base + b.bottom)
            insets
        }
    }
}

object WasteStyle {
    private val rules = listOf(
        listOf("zmiesz", "resztk") to 0xFF4A4F57,
        listOf("papier", "makulat") to 0xFF1E6FD9,
        listOf("szkł", "szkl") to 0xFF2E9E44,
        listOf("tworzyw", "metal", "plastik", "opakow") to 0xFFF2C200,
        listOf("bio", "kuchen", "beczk") to 0xFF8B5A2B,
        listOf("zielon", "ogrod") to 0xFF7CB342,
        listOf("choink") to 0xFF00897B,
        listOf("gabaryt") to 0xFF8E44AD,
        listOf("elektr") to 0xFFE65100,
    )

    fun color(type: String): Int {
        val t = type.lowercase()
        return (rules.firstOrNull { (keys, _) -> keys.any { t.contains(it) } }?.second ?: 0xFF607D8B).toInt()
    }

    fun textColor(background: Int): Int {
        val r = background shr 16 and 0xFF
        val g = background shr 8 and 0xFF
        val b = background and 0xFF
        return if (0.299 * r + 0.587 * g + 0.114 * b > 160) 0xFF111111.toInt() else 0xFFFFFFFF.toInt()
    }
}

object Dates {
    private val SHORT_DAYS = listOf("pon.", "wt.", "śr.", "czw.", "pt.", "sob.", "niedz.")

    fun full(d: LocalDate) =
        "${ScheduleParser.DAYS[d.dayOfWeek.value - 1]}, ${d.dayOfMonth} ${ScheduleParser.MONTHS[d.monthValue - 1]}"

    fun short(d: LocalDate) =
        String.format(Locale.ROOT, "%s %02d.%02d", SHORT_DAYS[d.dayOfWeek.value - 1], d.dayOfMonth, d.monthValue)

    fun relative(days: Int) = when (days) {
        0 -> "Dziś"
        1 -> "Jutro"
        2 -> "Pojutrze"
        else -> "Za $days dni"
    }
}
