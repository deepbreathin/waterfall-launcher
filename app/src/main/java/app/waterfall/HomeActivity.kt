package app.waterfall

import android.annotation.SuppressLint
import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.Choreographer
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors

/**
 * Waterfall: a minimal home screen.
 * - Home: clock, date, a few widgets, and a short column of favourite app icons (no names).
 * - The right edge: touch it and a letter strip appears and bends towards the finger; the left side
 *   shows that letter's apps, most-used first. Slide onto one and let go to open it. Let go on the
 *   strip and the full list takes over exactly where things are, then scrolls freely (search on top).
 * - Swipe down: notifications. (The swipe-left widget page is hidden for now.)
 * - Drag right from the left half: opens the app chosen in settings (if any).
 * - Long-press an app to add or remove it from home; long-press empty space for widgets and settings.
 * Other apps can react to the home screen showing or hiding: it broadcasts [HOME_VISIBLE] with
 * extra "visible" (true/false). App rows are reused across home returns.
 */
class HomeActivity : Activity() {

    companion object {
        /** Broadcast when home shows or hides (extra "visible"), for add-ons such as floating buttons. */
        const val HOME_VISIBLE = "app.waterfall.HOME_VISIBLE"
        private const val BG = Appearance.BACKGROUND
        private const val FG = Appearance.FOREGROUND
        private const val DIM = 0xFF8A949E.toInt()
        private const val HOST_ID = 2048
        private const val PICK = 1; private const val CONFIGURE = 2; private const val PICK_SIDE = 3; private const val CONFIGURE_SIDE = 4
        private const val SECTION_ROWS = 12
        private const val USAGE_REFRESH_MS = 30 * 60_000L
        private val widgetWorker = Executors.newSingleThreadExecutor()
    }

    private lateinit var index: AppIndex
    private lateinit var host: AppWidgetHost
    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * d).toInt()

    private lateinit var root: FrameLayout
    private lateinit var homePage: FrameLayout
    private lateinit var homeContent: LinearLayout
    private lateinit var favourites: LinearLayout
    private lateinit var widgets: LinearLayout
    private lateinit var section: LinearLayout          // shown while the finger is on the strip
    private lateinit var sectionHeader: TextView
    private val sectionRows = ArrayList<View>()
    private lateinit var allList: ScrollView            // full list after letting go on the strip
    private lateinit var allColumn: LinearLayout
    private lateinit var allWrap: LinearLayout
    private lateinit var search: EditText
    private lateinit var strip: LetterStrip
    private lateinit var side: View
    private var sideShown = false
    private var pendingWidget = -1
    private val catalogWorker = Executors.newSingleThreadExecutor()
    private val iconWorker = Executors.newSingleThreadExecutor()
    private var pendingApps: List<AppIndex.App>? = null
    private var usageRequestedAt: Long? = null
    private var widgetsStarted = false
    private var widgetsBuilt = false
    private var widgetsListening = false
    private var allBuiltFor: List<AppIndex.App>? = null
    private val allRows = HashMap<String, View>()
    private val allHeaders = HashMap<String, View>()
    private var favouritesShown: List<String>? = null
    private val appearancePrefs by lazy { Appearance.preferences(this) }
    private val textSizes = WeakHashMap<TextView, Float>()
    private val appIcons = Collections.newSetFromMap(WeakHashMap<ImageView, Boolean>())
    private var textScale = 1f
    private val monochrome = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
    private var appIconFilter: ColorMatrixColorFilter? = null
    private val appearanceChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Appearance.TEXT_SIZE -> {
                textScale = readTextScale()
                strip.textScale = textScale
                textSizes.forEach { (view, size) -> view.textSize = size * textScale }
            }
            Appearance.MONOCHROME_APP_ICONS -> {
                appIconFilter = readIconFilter()
                appIcons.forEach { it.colorFilter = appIconFilter }
            }
        }
    }

    private val packages = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { reload() }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        index = AppIndex(this)
        catalogWorker.execute {
            index.restoreApps()?.let { apps -> runOnUiThread { publishApps(apps) } }
        }
        host = object : AppWidgetHost(this, HOST_ID) {
            override fun onCreateView(context: Context, id: Int, info: AppWidgetProviderInfo): AppWidgetHostView =
                super.onCreateView(context, id, info).apply { setExecutor(widgetWorker) }
        }
        textScale = readTextScale()
        appIconFilter = readIconFilter()
        build()
        appearancePrefs.registerOnSharedPreferenceChangeListener(appearanceChanged)
        registerReceiver(packages, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED); addDataScheme("package")
        })
        refreshHome()
        reload()
    }

    override fun onDestroy() {
        unregisterReceiver(packages)
        appearancePrefs.unregisterOnSharedPreferenceChangeListener(appearanceChanged)
        catalogWorker.shutdownNow()
        iconWorker.shutdownNow()
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        widgetsStarted = true
        // Let home draw before querying the widget service.
        Choreographer.getInstance().postFrameCallback { root.post {
            if (widgetsStarted) startWidgets()
        } }
    }

    override fun onStop() {
        widgetsStarted = false
        if (widgetsListening) {
            widgetsListening = false
            try { host.stopListening() } catch (_: Exception) {}
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        sendBroadcast(Intent(HOME_VISIBLE).putExtra("visible", true))
        if (!strip.fingerDown && (section.visibility == View.VISIBLE || allList.visibility != View.GONE)) closeAll()
        refreshUsage()
    }

    override fun onPause() {
        super.onPause()
        if (strip.fingerDown) closeAll()
        sendBroadcast(Intent(HOME_VISIBLE).putExtra("visible", false))
    }

    /** Home pressed again while already home: back to the plain home page. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (!strip.fingerDown) closeAll()
        if (side.visibility == View.VISIBLE) {
            side.animate().cancel()
            side.visibility = View.INVISIBLE
            homePage.animate().cancel()
            homePage.translationX = 0f
            homePage.alpha = 1f
            sideShown = false
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (sideShown) showSide(false) else closeAll() }

    private fun reload() = catalogWorker.execute {
        val apps = index.loadApps()
        runOnUiThread { publishApps(apps) }
        index.saveApps(apps)
    }

    private fun publishApps(apps: List<AppIndex.App>) {
        if (isDestroyed) return
        // Keep the letter positions and rows stable while the picker or drawer is in use.
        if (index.apps.isNotEmpty() && (section.visibility == View.VISIBLE || allList.visibility != View.GONE)) {
            pendingApps = apps
            return
        }
        pendingApps = null
        index.apps = apps
        strip.letters = listOf("☆") + index.letters() + "○"
        if (strip.fingerDown && strip.bubble == null) pickLetter(stripY)
        favouritesShown = null
        refreshHome()
    }

    private fun refreshUsage() {
        val now = SystemClock.elapsedRealtime()
        val lastRequest = usageRequestedAt
        if (lastRequest != null && now - lastRequest < USAGE_REFRESH_MS) return
        usageRequestedAt = now
        catalogWorker.execute {
            val usage = index.readUsage()
            runOnUiThread {
                if (isDestroyed || index.usage == usage) return@runOnUiThread
                index.usage = usage
                allBuiltFor = null
            }
        }
    }

    // ---------------------------------------------------------------- layout

    private fun readTextScale() = appearancePrefs.getInt(Appearance.TEXT_SIZE, Appearance.DEFAULT_TEXT_SIZE) / 100f

    private fun readIconFilter() = monochrome.takeIf {
        appearancePrefs.getBoolean(Appearance.MONOCHROME_APP_ICONS, false)
    }

    private fun TextView.launcherTextSize(size: Float) {
        textSizes[this] = size
        textSize = size * textScale
    }

    private fun label(size: Float, weight: Int, color: Int = FG) = TextView(this).apply {
        launcherTextSize(size); setTextColor(color); typeface = Fonts.get(this@HomeActivity, weight); isSingleLine = true
    }

    private fun build() {
        root = FrameLayout(this).apply { setBackgroundColor(BG) }
        homePage = FrameLayout(this)

        homeContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            // the content column starts ~21% down the screen
            addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ ->
                val want = ((bottom - top) * 0.21f).toInt()
                if (v.paddingTop != want) v.post { v.setPadding(dp(40), want, dp(76), 0) }
            }
        }
        val clock = TextClock(this).apply {
            format12Hour = "H:mm"; format24Hour = "H:mm"; launcherTextSize(60f); setTextColor(FG)
            typeface = Fonts.light(this@HomeActivity)
            setOnClickListener { open(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)) }
        }
        val date = TextClock(this).apply {
            format12Hour = "EEE d MMM"; format24Hour = "EEE d MMM"; launcherTextSize(20f); setTextColor(FG)
            typeface = Fonts.regular(this@HomeActivity)
            setPadding(dp(6), 0, 0, dp(12))
        }
        widgets = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        favourites = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0) }
        homeContent.addView(clock); homeContent.addView(date); homeContent.addView(widgets); homeContent.addView(favourites)
        homePage.addView(homeContent, FrameLayout.LayoutParams(-1, -1))

        section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.TOP
            visibility = View.GONE
            isClickable = true
            setOnClickListener { strip.release(); closeAll() }   // tap empty space: back to home
        }
        sectionHeader = label(28f, 750).apply { setPadding(0, 0, 0, dp(10)) }
        section.addView(sectionHeader)
        repeat(SECTION_ROWS) { section.addView(row(label = true).also { sectionRows += it }) }
        homePage.addView(section, FrameLayout.LayoutParams(-1, -1))

        allColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        allWrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(40), dp(56), dp(24), tail()) }
        allList = ScrollView(this).apply { addView(allWrap); visibility = View.GONE; isVerticalScrollBarEnabled = false }
        homePage.addView(allList, FrameLayout.LayoutParams(-1, -1))
        search = EditText(this).apply {
            hint = "Search"; setHintTextColor(DIM); setTextColor(FG); launcherTextSize(18f); typeface = Fonts.regular(this@HomeActivity)
            setBackgroundColor(0x22FFFFFF); setPadding(dp(16), dp(10), dp(16), dp(10)); isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { filterAll(s?.toString() ?: "") }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        allWrap.addView(search, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        allWrap.addView(allColumn)

        strip = LetterStrip(this).apply { alpha = 0f; textScale = this@HomeActivity.textScale }
        homePage.addView(strip, FrameLayout.LayoutParams(-1, -1, Gravity.END))   // full width: on home, the right half picks letters
        stripTouch()

        side = buildSide().apply { visibility = View.INVISIBLE }
        root.addView(homePage, FrameLayout.LayoutParams(-1, -1))
        root.addView(side, FrameLayout.LayoutParams(-1, -1))
        homeGestures()
        setContentView(root)
    }

    /** A reusable app row: icon, and the name when [label]. */
    private fun row(label: Boolean): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), dp(6), 0, dp(6))
        addView(ImageView(context).apply {
            colorFilter = appIconFilter
            appIcons.add(this)
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        if (label) addView(label(19f, 500).apply { setPadding(dp(20), 0, 0, 0); isSingleLine = false; maxLines = 2 },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        setOnClickListener {
            (tag as? AppIndex.App)?.let {
                if (section.visibility == View.VISIBLE || allList.visibility != View.GONE) closeAll()
                index.launch(it)
            }
        }
        setOnLongClickListener { (tag as? AppIndex.App)?.let { a -> appMenu(this, a) }; true }
    }

    private fun bind(v: View, a: AppIndex.App) {
        if (v.tag === a) return
        val r = v as LinearLayout
        r.tag = a
        (r.getChildAt(1) as? TextView)?.text = a.label
        val image = r.getChildAt(0) as ImageView
        val icon = a.cachedIcon()
        image.setImageDrawable(icon)
        if (icon == null) iconWorker.execute {
            a.loadIcon()
            runOnUiThread {
                if (!isDestroyed && r.tag === a) image.setImageDrawable(a.cachedIcon())
            }
        }
    }

    private fun refreshHome() {
        val favs = index.favourites()
        if (favs.map { it.key } != favouritesShown) {
            favourites.removeAllViews()
            for (a in favs) favourites.addView(row(label = false).also { bind(it, a) })
            if (favs.isEmpty() && !index.setUp()) favourites.addView(label(15f, 500, DIM).apply {
                text = "Touch the right edge for your apps. Long-press one to add it here."; isSingleLine = false
            })
            favouritesShown = favs.map { it.key }
        }
    }

    private fun appMenu(v: View, a: AppIndex.App) {
        PopupMenu(this, v).apply {
            menu.add(if (index.isFavourite(a)) "Remove from home" else "Add to home")
            menu.add("App info")
            menu.add("Uninstall")
            setOnMenuItemClickListener {
                when (it.title) {
                    "App info" -> index.appInfo(a)
                    "Uninstall" -> open(Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + a.component.packageName)))
                    else -> { index.toggleFavourite(a); refreshHome() }
                }
                true
            }
        }.show()
    }

    // ---------------------------------------------------------------- the letter strip

    private var highlighted: View? = null
    // where the finger went down: letters are picked right of it, moving left of it goes onto the list
    private var stripX0 = 0f; private var stripY0 = 0f; private var stripT0 = 0L; private var stripWide = false
    private var stripY = 0f

    /** On the bare home screen the whole right half picks letters; widgets there still take their own touches. */
    private fun wideCatch(e: MotionEvent): Boolean {
        if (section.visibility == View.VISIBLE || allList.visibility == View.VISIBLE || sideShown) return false
        if (e.x < strip.width / 2f) return false
        val loc = IntArray(2); widgets.getLocationOnScreen(loc)
        return widgets.childCount == 0 || e.rawY < loc[1] || e.rawY > loc[1] + widgets.height
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun stripTouch() {
        strip.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                val edge = e.x >= strip.width - dp(if (allList.visibility == View.VISIBLE) 76 else 72)   // a generous edge: no need to aim
                stripWide = !edge && wideCatch(e)
                if (!edge && !stripWide) return@setOnTouchListener false
                stripX0 = e.x; stripY0 = e.y; stripT0 = e.eventTime
                // Held still on the wide area: that's a long-press on home, so the home menu, not letters.
                strip.removeCallbacks(stripLongPress)
                if (stripWide) strip.postDelayed(stripLongPress, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                // The press belongs to the strip even if its app list is still loading.
                strip.fingerDown = true
            }
            if (!strip.fingerDown) return@setOnTouchListener true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
                    if (Math.abs(e.x - stripX0) > slop || Math.abs(e.y - stripY0) > slop) strip.removeCallbacks(stripLongPress)
                    stripY = e.y
                    val onStrip = e.x > Math.min(strip.width - dp(90).toFloat(), stripX0 - dp(40))
                    if (onStrip || highlighted == null && section.visibility != View.VISIBLE) {
                        pickLetter(e.y)
                        highlight(null)
                    } else highlight(rowAt(e.rawY))   // finger moved left onto the list
                }
                MotionEvent.ACTION_UP -> {
                    strip.removeCallbacks(stripLongPress)
                    strip.fingerDown = false
                    val app = highlighted?.tag as? AppIndex.App
                    val letter = strip.bubble
                    highlight(null)
                    val dx = e.x - stripX0; val dy = e.y - stripY0
                    when {
                        // a quick sideways flick from the wide area does nothing (the swipe-left page is hidden for now)
                        stripWide && dx < -dp(80) && Math.abs(dx) > Math.abs(dy) * 1.5f && e.eventTime - stripT0 < 300 ->
                            { strip.release(); closeAll() }
                        app != null -> { strip.release(); closeAll(); index.launch(app) }
                        letter == "○" -> { strip.release(); openAll(null) }
                        // Let go on a letter: the full list takes over, lined up so that letter's apps stay
                        // exactly where they were, and from there it scrolls freely.
                        letter == "☆" -> {}
                        letter != null -> { strip.release(); handOverToList(letter) }
                        else -> { strip.release(); closeAll() }
                    }
                }
                MotionEvent.ACTION_CANCEL -> { strip.removeCallbacks(stripLongPress); closeAll() }
            }
            true
        }
    }

    private val stripLongPress = Runnable {
        if (!strip.fingerDown || !stripWide) return@Runnable
        strip.fingerDown = false          // the rest of this touch is ignored by the strip
        strip.release(); closeAll()
        strip.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        homeMenu(homeContent)
    }

    private fun pickLetter(y: Float) {
        val letter = strip.letterAt(y) ?: return
        strip.alpha = 1f
        strip.follow(y)
        if (strip.bubble != letter) {
            strip.bubble = letter
            showSection(letter)
            strip.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    private fun rowAt(rawY: Float): View? {
        val loc = IntArray(2)
        for (r in sectionRows) {
            if (r.visibility != View.VISIBLE) continue
            r.getLocationOnScreen(loc)
            if (rawY >= loc[1] && rawY <= loc[1] + r.height) return r
        }
        return null
    }

    private fun highlight(v: View?) {
        if (highlighted === v) return
        highlighted?.setBackgroundColor(Color.TRANSPARENT)
        v?.setBackgroundColor(0x22FFFFFF)
        highlighted = v
    }

    private fun showSection(letter: String) {
        val top = (strip.height * 0.20f).toInt()
        if (section.paddingTop != top) section.setPadding(dp(40), top, dp(16), 0)
        val apps = when (letter) { "☆" -> index.favourites(); "○" -> emptyList(); else -> index.section(letter) }
        sectionHeader.text = when (letter) { "☆" -> "Home"; "○" -> "All apps"; else -> letter }
        for ((i, r) in sectionRows.withIndex()) {
            val a = apps.getOrNull(i)
            if (a != null) bind(r, a)
            r.visibility = if (a == null) View.GONE else View.VISIBLE
        }
        allList.visibility = View.GONE
        homeContent.visibility = View.INVISIBLE
        section.visibility = View.VISIBLE
    }

    private fun openAll(letter: String?) {
        buildAll()
        section.visibility = View.GONE; homeContent.visibility = View.INVISIBLE
        allList.visibility = View.VISIBLE; strip.alpha = 1f
        if (search.text.isNotEmpty()) search.setText("")
        allList.post {
            val h = if (letter == null) null else allHeaders[letter]
            allList.smoothScrollTo(0, h?.top ?: 0)
        }
    }

    /**
     * Swap the held section for the full list without anything moving on screen: the list is laid out
     * invisibly, scrolled so [letter]'s header sits exactly where the section's header is, then shown.
     */
    private fun handOverToList(letter: String) {
        buildAll()
        if (search.text.isNotEmpty()) search.setText("")
        val header = allHeaders[letter] ?: return openAll(letter)
        allList.visibility = View.INVISIBLE
        allList.post {
            if (allList.visibility != View.INVISIBLE) return@post
            // line up the letters themselves (the list header has extra space above its text)
            val want = IntArray(2).also { sectionHeader.getLocationOnScreen(it) }[1] + sectionHeader.paddingTop
            val listTop = IntArray(2).also { allList.getLocationOnScreen(it) }[1]
            val inContent = header.top + header.paddingTop + allColumn.top   // the letter's y inside the scrolling content
            val scroll = inContent - (want - listTop)
            if (scroll >= 0) { allList.scrollTo(0, scroll); showList() }
            else {
                // early letters: the list can't scroll above its top, so add that much room above it
                allWrap.setPadding(dp(40), dp(56) - scroll, dp(24), tail())
                allList.post {
                    if (allList.visibility == View.INVISIBLE) {
                        allList.scrollTo(0, 0)
                        showList()
                    }
                }
            }
        }
    }

    /** Space under the last app, so even Z can scroll up to line up with the held view. */
    private fun tail() = (resources.displayMetrics.heightPixels * 0.75f).toInt()

    private fun showList() {
        run {
            allList.visibility = View.VISIBLE
            section.visibility = View.GONE
            homeContent.visibility = View.INVISIBLE
            strip.alpha = 1f
        }
    }

    /** The full list is built once per app-list load and then only shown/filtered. */
    private fun buildAll() {
        if (allBuiltFor === index.apps) return
        allColumn.removeAllViews(); allRows.clear(); allHeaders.clear()
        for (l in index.letters()) {
            val header = label(28f, 750).apply { text = l; setPadding(0, dp(18), 0, dp(10)) }
            allColumn.addView(header); allHeaders[l] = header
            for (a in index.section(l)) allColumn.addView(row(label = true).also { bind(it, a); allRows[a.key] = it })
        }
        allBuiltFor = index.apps
    }

    private fun filterAll(q: String) {
        val query = q.trim().lowercase()
        for ((letter, apps) in index.apps.groupBy { it.letter }) {
            var any = false
            for (a in apps) {
                val show = query.isEmpty() || a.label.lowercase().contains(query)
                allRows[a.key]?.visibility = if (show) View.VISIBLE else View.GONE
                any = any || show
            }
            allHeaders[letter]?.visibility = if (any) View.VISIBLE else View.GONE
        }
    }

    private fun closeAll() {
        if (!::section.isInitialized) return
        strip.fingerDown = false
        highlight(null)
        if (allWrap.paddingTop != dp(56)) allWrap.setPadding(dp(40), dp(56), dp(24), tail())
        if (strip.bubble != null) strip.release()
        section.visibility = View.GONE; allList.visibility = View.GONE
        homeContent.visibility = View.VISIBLE
        strip.alpha = 0f
        if (search.hasFocus()) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(search.windowToken, 0)
            search.clearFocus()
        }
        pendingApps?.let(::publishApps)
    }

    // ---------------------------------------------------------------- swipes: side page, notifications

    @SuppressLint("ClickableViewAccessibility")
    private fun homeGestures() {
        var x0 = 0f; var y0 = 0f
        val swipe = View.OnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY }
                MotionEvent.ACTION_UP -> {
                    val dx = e.rawX - x0; val dy = e.rawY - y0
                    val sideways = Math.abs(dx) > Math.abs(dy) * 1.5f
                    when {
                        dx > dp(80) && sideways && sideShown -> showSide(false)     // swipe right: back home
                        dy > dp(90) && !sideways && !sideShown -> notifications()
                    }
                }
            }
            false
        }
        homeContent.setOnTouchListener(swipe)
        homeContent.isClickable = true
        homeContent.setOnLongClickListener { homeMenu(it); true }
        side.setOnTouchListener(swipe)
    }

    // Drag right from anywhere on the left half of bare home (widgets included) opens the chosen app.
    // Watched at the window. Once the finger is clearly moving right the launcher takes the touch
    // (views underneath get a cancel), so no long-press menu or widget can pick it up mid-drag.
    private var rightX0 = -1f; private var rightY0 = 0f; private var rightT0 = 0L; private var rightOwned = false

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                rightOwned = false
                val home = !sideShown && ::section.isInitialized && ::allList.isInitialized &&
                    section.visibility != View.VISIBLE && allList.visibility != View.VISIBLE
                val chosen = Appearance.preferences(this).contains(Appearance.SWIPE_RIGHT_APP)
                rightX0 = if (home && chosen && ::root.isInitialized && e.rawX < root.width / 2f) e.rawX else -1f
                rightY0 = e.rawY; rightT0 = e.eventTime
            }
            MotionEvent.ACTION_MOVE -> if (rightX0 >= 0f && !rightOwned) {
                val dx = e.rawX - rightX0; val dy = e.rawY - rightY0
                if (dx > dp(24) && dx > Math.abs(dy) * 1.5f) {
                    rightOwned = true
                    super.dispatchTouchEvent(MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL })
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (!rightOwned) rightX0 = -1f
            MotionEvent.ACTION_CANCEL -> { rightX0 = -1f; if (rightOwned) { rightOwned = false; return true } }
            MotionEvent.ACTION_UP -> if (rightOwned) {
                val dx = e.rawX - rightX0; val dy = e.rawY - rightY0
                rightOwned = false; rightX0 = -1f
                if (dx > dp(80) && Math.abs(dx) > Math.abs(dy) * 1.5f) openSwipeRightApp()
                return true
            }
        }
        return if (rightOwned) true else super.dispatchTouchEvent(e)
    }

    private fun openSwipeRightApp(): Boolean {
        val key = Appearance.preferences(this).getString(Appearance.SWIPE_RIGHT_APP, null) ?: return false
        val component = android.content.ComponentName.unflattenFromString(key) ?: return false
        return try {
            getSystemService(android.content.pm.LauncherApps::class.java)
                .startMainActivity(component, android.os.Process.myUserHandle(), null, null)
            true
        } catch (_: Exception) { false }
    }

    private fun showSide(show: Boolean) {
        if (!::side.isInitialized || show == sideShown) return
        val w = root.width.toFloat()
        if (show) {
            side.translationX = w; side.visibility = View.VISIBLE
            side.animate().translationX(0f).setDuration(220).start()
            homePage.animate().translationX(-w * 0.25f).alpha(0f).setDuration(220).start()
        } else {
            side.animate().translationX(w).setDuration(200).start()
            homePage.animate().translationX(0f).alpha(1f).setDuration(200).start()
        }
        sideShown = show
    }

    @SuppressLint("WrongConstant")
    private fun notifications() = try {
        Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(getSystemService("statusbar"))
    } catch (_: Exception) {}

    private fun open(i: Intent) = try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}

    // ---------------------------------------------------------------- widgets

    private fun homeMenu(v: View) {
        PopupMenu(this, v).apply {
            menu.add("Add a widget")
            if (index.widgetIds().isNotEmpty()) menu.add("Remove widgets")
            if (!index.usageAllowed()) menu.add("Order letters by most used")
            menu.add("Default home app")
            menu.add("Waterfall settings")
            setOnMenuItemClickListener {
                when (it.title) {
                    "Add a widget" -> pickWidget()
                    "Remove widgets" -> { index.widgetIds().forEach { id -> host.deleteAppWidgetId(id) }; index.setWidgetIds(emptyList()); fillWidgets() }
                    "Order letters by most used" -> open(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    "Default home app" -> open(Intent(android.provider.Settings.ACTION_HOME_SETTINGS))
                    "Waterfall settings" -> open(Intent(this@HomeActivity, SettingsActivity::class.java))
                }
                true
            }
        }.show()
    }

    private fun pickWidget(side: Boolean = false) {
        pendingWidget = host.allocateAppWidgetId()
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(AppWidgetManager.ACTION_APPWIDGET_PICK)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidget), if (side) PICK_SIDE else PICK)
    }

    // ---------------------------------------------------------------- the side page (swipe left)

    private lateinit var sideHolder: FrameLayout

    /** One widget, full page. Long-press to choose it. */
    private fun buildSide(): View {
        sideHolder = FrameLayout(this).apply {
            setBackgroundColor(BG); setPadding(dp(24), dp(56), dp(24), dp(40))
            isClickable = true
            setOnLongClickListener { pickWidget(side = true); true }
        }
        return sideHolder
    }

    private fun startWidgets() {
        if (!widgetsBuilt) {
            widgetsBuilt = true
            fillWidgets()
            // The swipe-left side page is hidden for now (its widget isn't loaded); its code stays for later.
        }
        if (!widgetsListening && index.widgetIds().isNotEmpty()) {
            host.startListening()
            widgetsListening = true
        }
    }

    private fun fillSide() {
        sideHolder.removeAllViews()
        val id = index.sideWidget()
        val info = if (id > 0) AppWidgetManager.getInstance(this).getAppWidgetInfo(id) else null
        if (info == null) {
            sideHolder.addView(label(18f, 500, DIM).apply {
                text = "Long-press here to put a widget on this page."; isSingleLine = false
            }, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
            return
        }
        val v = host.createView(this, id, info)
        v.setOnLongClickListener { pickWidget(side = true); true }
        sideHolder.addView(v, FrameLayout.LayoutParams(-1, -1))
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val id = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidget) ?: pendingWidget
        if (res != RESULT_OK) { if (id > 0) host.deleteAppWidgetId(id); return }
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(id) ?: return
        val side = req == PICK_SIDE || req == CONFIGURE_SIDE
        if ((req == PICK || req == PICK_SIDE) && info.configure != null) {
            try { host.startAppWidgetConfigureActivityForResult(this, id, 0, if (side) CONFIGURE_SIDE else CONFIGURE, null); return } catch (_: Exception) {}
        }
        if (side) {
            index.sideWidget().takeIf { it > 0 && it != id }?.let { host.deleteAppWidgetId(it) }
            index.setSideWidget(id); fillSide()
        } else {
            index.setWidgetIds(index.widgetIds() + id)
            fillWidgets()
        }
        if (widgetsStarted && widgetsBuilt) startWidgets()
    }

    private fun fillWidgets() {
        widgets.removeAllViews()
        val mgr = AppWidgetManager.getInstance(this)
        for (id in index.widgetIds()) {
            val info = mgr.getAppWidgetInfo(id) ?: continue
            val v = host.createView(this, id, info)
            widgets.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, info.minHeight.coerceAtLeast(dp(90))).apply { bottomMargin = dp(8) })
        }
    }
}
