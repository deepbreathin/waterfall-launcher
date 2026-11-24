package app.waterfall

import android.content.Context

internal object Appearance {
    const val BACKGROUND = 0xFF09212B.toInt()
    const val FOREGROUND = 0xFFE6E9EC.toInt()
    const val TEXT_SIZE = "textSize"
    const val MONOCHROME_APP_ICONS = "monochromeHomeIcons" // Keep the saved setting from 1.4.
    const val DEFAULT_TEXT_SIZE = 90
    const val SWIPE_RIGHT_APP = "swipeRightApp" // flattened ComponentName, or absent for nothing

    fun preferences(context: Context) = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
}
