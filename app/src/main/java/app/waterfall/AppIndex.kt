package app.waterfall

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Every launchable app, grouped by first letter and, within a letter, most-used first, so the ones
 * you open most are at the top of their letter. Usage = how often this launcher
 * opened it, plus Android's usage stats when "Usage access" is allowed (so the order is right from
 * day one instead of starting alphabetical).
 */
class AppIndex(private val ctx: Context) {

    data class App(val label: String, val component: ComponentName) {
        val key: String get() = component.flattenToString()
        val letter: String get() = label.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()
            ?.let { if (it in 'A'..'Z') it.toString() else "#" } ?: "#"
    }

    private val la = ctx.getSystemService(LauncherApps::class.java)
    private val prefs = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private val icons = ConcurrentHashMap<String, Drawable>()
    @Volatile var apps: List<App> = emptyList(); private set
    private var usage: Map<String, Long> = emptyMap()   // package → recent foreground ms

    fun load() {
        apps = la.getActivityList(null, Process.myUserHandle())
            .filter { it.componentName.packageName != ctx.packageName }
            .map { App(it.label.toString().trim(), it.componentName) }
            .sortedBy { it.label.lowercase() }
        usage = readUsage()
    }

    fun icon(a: App): Drawable? = icons[a.key] ?: try {
        la.getActivityList(a.component.packageName, Process.myUserHandle())
            .firstOrNull { it.componentName == a.component }?.getBadgedIcon(0)?.also { icons[a.key] = it }
    } catch (e: Exception) { null }

    /** Warm the icon cache off the main thread. */
    fun preloadIcons() = apps.forEach { icon(it) }

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

    private fun readUsage(): Map<String, Long> {
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

    @Suppress("unused") private fun unused() = JSONObject()
}
