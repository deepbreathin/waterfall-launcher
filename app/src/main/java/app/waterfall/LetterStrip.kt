package app.waterfall

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * The letter strip down the right edge: ☆ (home apps), the letters that have apps, ○ (all
 * apps). While a finger is on it the letters near the finger bend out towards it, and a bubble shows
 * the current one; letting go, they spring back. Touch handling lives in [HomeActivity]; this draws.
 */
class LetterStrip(ctx: Context) : View(ctx) {
    var letters: List<String> = emptyList(); set(v) { field = v; invalidate() }
    var bubble: String? = null
    /** The big letter only shows while the finger is down; after letting go it would cover app names. */
    var fingerDown = false; set(v) { field = v; invalidate() }
    var color = 0xFFE6E9EC.toInt()

    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Fonts.bold(ctx) }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Fonts.bold(ctx) }
    private val bubbleBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }

    // the bend follows the finger; `amount` eases in on touch and springs out on release
    private var fingerY = 0f
    private var amount = 0f
    private var anim: ValueAnimator? = null

    init { setLayerType(LAYER_TYPE_HARDWARE, null) }

    /** The band the letters span: most of the screen height, for more room to aim. */
    fun top() = height * 0.10f
    fun bottom() = height * 0.86f
    fun step() = if (letters.size < 2) 0f else (bottom() - top()) / (letters.size - 1)
    val touching get() = amount > 0f && bubble != null

    fun letterAt(y: Float): String? {
        if (letters.isEmpty()) return null
        val i = Math.round((y - top()) / step().coerceAtLeast(1f)).coerceIn(0, letters.size - 1)
        return letters[i]
    }

    fun follow(y: Float) {
        fingerY = y
        if (amount < 1f && anim?.isRunning != true) animateTo(1f, 90)
        invalidate()
    }

    fun release() { bubble = null; animateTo(0f, 260) }

    private fun animateTo(target: Float, ms: Long) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(amount, target).apply {
            duration = ms; interpolator = DecelerateInterpolator()
            addUpdateListener { amount = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        if (letters.isEmpty()) return
        val baseX = width - 22 * d
        paint.textSize = 15 * d
        val current = if (bubble != null) letterAt(fingerY) else null
        for ((i, l) in letters.withIndex()) {
            val y = top() + i * step()
            val near = Math.max(0f, 1f - Math.abs(y - fingerY) / (190 * d))
            val x = baseX - amount * near * near * 78 * d
            paint.color = if (l == current) 0xFFFFFFFF.toInt() else color
            paint.textSize = (15 + 5 * amount * near * near) * d
            c.drawText(l, x, y + paint.textSize / 3, paint)
        }
        val b = bubble
        if (b != null && amount > 0f && fingerDown) {
            val bx = baseX - 140 * d
            bubbleBg.alpha = (0x33 * amount).toInt()
            c.drawCircle(bx, fingerY, 28 * d, bubbleBg)
            bubblePaint.textSize = 34 * d; bubblePaint.color = 0xFFFFFF or ((0xCC * amount).toInt() shl 24)
            c.drawText(b, bx, fingerY + bubblePaint.textSize / 3, bubblePaint)
        }
    }
}
