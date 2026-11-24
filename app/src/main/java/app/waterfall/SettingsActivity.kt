package app.waterfall

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class SettingsActivity : Activity() {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Appearance.BACKGROUND
        window.navigationBarColor = Appearance.BACKGROUND

        val prefs = Appearance.preferences(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(28))
        }
        content.addView(heading("Waterfall settings", 30f))
        content.addView(heading("Text size", 20f).apply { setPadding(0, dp(24), 0, dp(4)) })

        val sizes = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val selectedSize = prefs.getInt(Appearance.TEXT_SIZE, Appearance.DEFAULT_TEXT_SIZE)
        for ((size, title) in listOf(80 to "Compact", 90 to "Small", 100 to "Original", 110 to "Large")) {
            val option = RadioButton(this).apply {
                id = View.generateViewId()
                text = title
                minHeight = dp(48)
            }
            sizes.addView(option)
            if (size == selectedSize) sizes.check(option.id)
            option.setOnClickListener { prefs.edit().putInt(Appearance.TEXT_SIZE, size).apply() }
        }
        content.addView(sizes)

        content.addView(Switch(this).apply {
            text = "Monochrome app icons"
            textSize = 18f
            minHeight = dp(56)
            isChecked = prefs.getBoolean(Appearance.MONOCHROME_APP_ICONS, false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(Appearance.MONOCHROME_APP_ICONS, checked).apply()
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        content.addView(TextView(this).apply {
            text = "Use grayscale icons on home, in the alphabet picker, and in the app list."
            textSize = 14f
        })

        content.addView(heading("Swipe right", 20f).apply { setPadding(0, dp(24), 0, dp(4)) })
        content.addView(TextView(this).apply {
            text = "Drag right from anywhere on the left half of home to open an app."
            textSize = 14f
        })
        content.addView(Button(this).apply {
            isAllCaps = false
            fun label() {
                val key = prefs.getString(Appearance.SWIPE_RIGHT_APP, null)
                val app = key?.let { k -> AppIndex(this@SettingsActivity).loadApps().firstOrNull { it.key == k } }
                text = "Opens: " + (app?.label ?: "nothing")
            }
            label()
            setOnClickListener {
                val apps = AppIndex(this@SettingsActivity).loadApps()
                val names = arrayOf("Nothing") + apps.map { it.label }
                AlertDialog.Builder(this@SettingsActivity).setTitle("Swipe right opens")
                    .setItems(names) { _, i ->
                        prefs.edit().apply {
                            if (i == 0) remove(Appearance.SWIPE_RIGHT_APP) else putString(Appearance.SWIPE_RIGHT_APP, apps[i - 1].key)
                        }.apply()
                        label()
                    }.show()
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        content.addView(Button(this).apply {
            text = "Default home app"
            isAllCaps = false
            setOnClickListener { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
        content.addView(Button(this).apply {
            text = "Usage access"
            isAllCaps = false
            setOnClickListener { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Appearance.BACKGROUND)
            addView(content)
        })
    }

    private fun heading(title: String, size: Float) = TextView(this).apply {
        text = title
        textSize = size
        setTextColor(Appearance.FOREGROUND)
        typeface = Fonts.bold(this@SettingsActivity)
    }
}
