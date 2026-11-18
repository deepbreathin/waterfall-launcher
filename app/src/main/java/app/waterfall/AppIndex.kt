package app.waterfall

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Process
import org.json.JSONArray
import org.json.JSONException

/**
 * Every launchable app, grouped by first letter and, within a letter, most-used first, so the ones
 * you open most are at the top of their letter. Usage = how often this launcher
 * opened it, plus Android's usage stats when "Usage access" is allowed (so the order is right from
 * day one instead of starting alphabetical).
 */
class AppIndex(private val ctx: Context) {

    class App(val label: String, val component: ComponentName, private val fetchIcon: () -> Drawable?) {
        val key = component.flattenToString()
        val letter = label.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()
            ?.let { if (it in 'A'..'Z') it.toString() else "#" } ?: "#"

        @Volatile private var icon: Drawable? = null

        fun cachedIcon(): Drawable? = icon?.let { it.constantState?.newDrawable() ?: it }

        fun loadIcon() {
            if (icon == null) icon = try { fetchIcon() } catch (_: Exception) { null }
        }
    }

    private val la = ctx.getSystemService(LauncherApps::class.java)
    private val prefs = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private val catalogPrefs by lazy { ctx.getSharedPreferences("app_catalog", Context.MODE_PRIVATE) }
    private val locale by lazy { ctx.resources.configuration.locales.toLanguageTags() }
    var apps: List<App> = emptyList()
    var usage: Map<String, Long> = emptyMap()   // package → recent foreground ms

    fun loadApps(): List<App> =
        la.getActivityList(null, Process.myUserHandle())
            .map { activity -> App(activity.label.toString().trim(), activity.componentName) { activity.getBadgedIcon(0) } }
            .sortedBy { it.label.lowercase() }

    fun restoreApps(): List<App>? {
        if (catalogPrefs.getString("locale", null) != locale) return null
        val saved = catalogPrefs.getString("apps", null) ?: return null
        return try {
            val entries = JSONArray(saved)
            (0 until entries.length()).mapNotNull { i ->
                val entry = entries.getJSONArray(i)
                val component = ComponentName.unflattenFromString(entry.getString(0)) ?: return@mapNotNull null
                App(entry.getString(1), component) {
                    la.resolveActivity(Intent().setComponent(component), Process.myUserHandle())?.getBadgedIcon(0)
                }
            }
        } catch (_: JSONException) {
            null
        }
    }

    fun saveApps(apps: List<App>) {
        val saved = JSONArray()
        for (app in apps) saved.put(JSONArray(listOf(app.key, app.label)))
        val encoded = saved.toString()
        if (catalogPrefs.getString("apps", null) != encoded || catalogPrefs.getString("locale", null) != locale) {
            catalogPrefs.edit().putString("apps", encoded).putString("locale", locale).apply()
        }
    }

    fun letters(): List<String> = apps.map { it.letter }.distinct().sortedWith(compareBy({ it == "#" }, { it }))

    private fun score(a: App): Double {
        val opened = prefs.getInt("n:" + a.key, 0)
        val fg = (usage[a.component.packageName] ?: 0L) / 60_000.0   // minutes in the last 30 days
        return opened * 3.0 + Math.sqrt(fg)
    }

    /** One letter's apps, most-used first. */
    fun section(letter: String): List<App> =
        apps.filter { it.letter == letter }.sortedWith(compareByDescending<App> { score(it) }.thenBy { it.label.lowercase() })

    fun favourites(): List<App> {
        val keys = favouriteKeys()
        return keys.mapNotNull { k -> apps.firstOrNull { it.key == k } }
    }

    private fun favouriteKeys(): List<String> {
        val raw = prefs.getString("favourites", null)
        if (raw == null) return emptyList()   // first run: empty, long-press an app to add it
        return try { JSONArray(raw).let { a -> (0 until a.length()).map { a.getString(it) } } } catch (e: Exception) { emptyList() }
    }

    private fun setFavourites(keys: List<String>) = prefs.edit().putString("favourites", JSONArray(keys).toString()).apply()

    /** True once anything has ever been added to home: the first-run hint is never shown again. */
    fun setUp() = prefs.contains("favourites")

    fun isFavourite(a: App) = a.key in favouriteKeys()
    fun toggleFavourite(a: App) {
        val k = favouriteKeys().toMutableList()
        if (!k.remove(a.key)) k += a.key
        setFavourites(k)
    }

    fun launch(a: App) {
        prefs.edit().putInt("n:" + a.key, prefs.getInt("n:" + a.key, 0) + 1).apply()
        try { la.startMainActivity(a.component, Process.myUserHandle(), null, null) } catch (e: Exception) {}
    }

    fun appInfo(a: App) = try { la.startAppDetailsActivity(a.component, Process.myUserHandle(), null, null) } catch (e: Exception) {}

    fun usageAllowed(): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
    }

    fun readUsage(): Map<String, Long> {
        if (!usageAllowed()) return emptyMap()
        return try {
            val usm = ctx.getSystemService(UsageStatsManager::class.java)
            val now = System.currentTimeMillis()
            usm.queryAndAggregateUsageStats(now - 30L * 24 * 3600 * 1000, now).mapValues { it.value.totalTimeInForeground }
        } catch (e: Exception) { emptyMap() }
    }

    // Widgets on the home screen (AppWidgetHost ids), in order.
    fun widgetIds(): List<Int> = try {
        JSONArray(prefs.getString("widgets", "[]")).let { a -> (0 until a.length()).map { a.getInt(it) } }
    } catch (e: Exception) { emptyList() }

    /** The widget on the side page (swipe left), or 0. */
    fun sideWidget(): Int = prefs.getInt("sideWidget", 0)
    fun setSideWidget(id: Int) = prefs.edit().putInt("sideWidget", id).apply()

    fun setWidgetIds(ids: List<Int>) = prefs.edit().putString("widgets", JSONArray(ids).toString()).apply()

}
