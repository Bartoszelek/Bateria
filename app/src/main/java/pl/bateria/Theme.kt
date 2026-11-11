package pl.bateria

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build

/**
 * Motywy kolorystyczne trybu kolorowego (jak w Pocket Casts / Material You).
 * W trybie e-ink nie są używane.
 */
object Theme {
    class Preset(val id: String, val name: String, val a: Int, val b: Int)

    val PRESETS = listOf(
        Preset("auto", "Z tapety", 0, 0),
        Preset("indigo", "Indygo", 0xFF4F46E5.toInt(), 0xFF06B6D4.toInt()),
        Preset("ocean", "Ocean", 0xFF0284C7.toInt(), 0xFF14B8A6.toInt()),
        Preset("forest", "Las", 0xFF15803D.toInt(), 0xFF84CC16.toInt()),
        Preset("rose", "Rosé", 0xFFE11D74.toInt(), 0xFFF59E0B.toInt()),
        Preset("sunset", "Zachód słońca", 0xFFEA580C.toInt(), 0xFFE11D48.toInt()),
        Preset("berry", "Jagoda", 0xFF7C3AED.toInt(), 0xFFEC4899.toInt()),
        Preset("graphite", "Grafit", 0xFF334155.toInt(), 0xFFF59E0B.toInt())
    )
    val MODES = listOf("system" to "Systemowy", "light" to "Jasny", "dark" to "Ciemny", "black" to "Czarny")

    var accent = 0xFF4F46E5.toInt()
    var accent2 = 0xFF06B6D4.toInt()
    val onAccent = Color.WHITE
    var bg = 0xFFF5F6FA.toInt()
    var surface = Color.WHITE
    var fg = 0xFF111827.toInt()
    var dim = 0xFF6B7280.toInt()
    var line = 0xFFE5E7EB.toInt()
    var soft = 0xFFEEF0FF.toInt()
    var dark = false

    fun hasWallpaperColors() = Build.VERSION.SDK_INT >= 31

    /** Kolory motywu (dla „Z tapety” – kolory Material You z systemu). */
    fun colorsOf(ctx: Context, p: Preset): Pair<Int, Int> {
        if (p.id == "auto") {
            return if (Build.VERSION.SDK_INT >= 31) {
                Pair(ctx.getColor(android.R.color.system_accent1_600), ctx.getColor(android.R.color.system_accent3_500))
            } else Pair(PRESETS[1].a, PRESETS[1].b)
        }
        return Pair(p.a, p.b)
    }

    fun blend(base: Int, c: Int, t: Float): Int = Color.rgb(
        (Color.red(base) * (1 - t) + Color.red(c) * t).toInt(),
        (Color.green(base) * (1 - t) + Color.green(c) * t).toInt(),
        (Color.blue(base) * (1 - t) + Color.blue(c) * t).toInt()
    )

    fun isDark(ctx: Context, mode: String): Boolean = when (mode) {
        "light" -> false
        "dark", "black" -> true
        else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    fun apply(ctx: Context, themeId: String, mode: String) {
        val p = PRESETS.firstOrNull { it.id == themeId } ?: PRESETS[1]
        val (a, b) = colorsOf(ctx, p)
        dark = isDark(ctx, mode)
        accent2 = b
        if (!dark) {
            accent = a
            bg = blend(0xFFF6F7F9.toInt(), a, 0.07f)
            surface = Color.WHITE
            fg = 0xFF111827.toInt()
            dim = 0xFF6B7280.toInt()
            line = blend(0xFFE5E7EB.toInt(), a, 0.08f)
            soft = blend(Color.WHITE, a, 0.13f)
        } else {
            accent = blend(a, Color.WHITE, 0.22f)
            val black = mode == "black"
            bg = if (black) Color.BLACK else blend(0xFF0E1015.toInt(), a, 0.07f)
            surface = if (black) 0xFF111111.toInt() else blend(0xFF1A1D24.toInt(), a, 0.05f)
            fg = 0xFFF3F4F6.toInt()
            dim = 0xFF9CA3AF.toInt()
            line = if (black) 0xFF262626.toInt() else blend(0xFF2A2F38.toInt(), a, 0.08f)
            soft = blend(surface, a, 0.28f)
        }
    }

    fun gradient(radiusPx: Float): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(accent, accent2)).apply { cornerRadius = radiusPx }

    fun withAlpha(c: Int, a: Float) = Color.argb((a * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))
}

/** Pierścień postępu (bateria, pamięć, ocena bezpieczeństwa, minutnik) z płynną animacją. */
class ProgressRing(c: android.content.Context) : android.view.View(c) {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.ROUND }
    private val tp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textAlign = android.graphics.Paint.Align.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD }
    private var shown = 0f
    var value = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            android.animation.ValueAnimator.ofFloat(shown, field).apply {
                duration = 900; interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            }.start()
        }
    var big = ""
    var small = ""
    var color1 = Theme.accent
    var color2 = Theme.accent2

    override fun onDraw(cv: android.graphics.Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val s = minOf(w, h); val sw = s * 0.09f
        val r = android.graphics.RectF((w - s) / 2 + sw, (h - s) / 2 + sw, (w + s) / 2 - sw, (h + s) / 2 - sw)
        p.strokeWidth = sw
        p.shader = null; p.color = Theme.withAlpha(Theme.fg, 0.10f)
        cv.drawArc(r, 0f, 360f, false, p)
        p.shader = android.graphics.SweepGradient(w / 2, h / 2, intArrayOf(color1, color2, color1), null).also {
            it.setLocalMatrix(android.graphics.Matrix().apply { setRotate(-90f, w / 2, h / 2) })
        }
        cv.drawArc(r, -90f, 360f * shown, false, p)
        tp.color = Theme.fg; tp.textSize = s * 0.24f
        cv.drawText(big, w / 2, h / 2 + tp.textSize * 0.30f - (if (small.isNotEmpty()) s * 0.05f else 0f), tp)
        if (small.isNotEmpty()) { tp.textSize = s * 0.09f; tp.color = Theme.dim; tp.typeface = android.graphics.Typeface.DEFAULT; cv.drawText(small, w / 2, h / 2 + s * 0.20f, tp); tp.typeface = android.graphics.Typeface.DEFAULT_BOLD }
    }
}
