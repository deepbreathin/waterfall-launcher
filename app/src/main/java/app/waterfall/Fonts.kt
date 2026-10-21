package app.waterfall

import android.content.Context
import android.graphics.Typeface

/** Nunito (SIL OFL, assets/fonts): a soft, rounded face. One variable font, three weights. */
object Fonts {
    private var cache = HashMap<Int, Typeface>()

    fun get(ctx: Context, weight: Int): Typeface = cache.getOrPut(weight) {
        try {
            Typeface.Builder(ctx.assets, "fonts/Nunito.ttf").setFontVariationSettings("'wght' $weight").build()
        } catch (e: Exception) { Typeface.DEFAULT }
    }

    fun light(ctx: Context) = get(ctx, 300)
    fun regular(ctx: Context) = get(ctx, 500)
    fun bold(ctx: Context) = get(ctx, 750)
}
