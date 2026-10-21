package app.waterfall

import android.annotation.SuppressLint
import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
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
import kotlin.concurrent.thread

/**
 * Waterfall: a minimal home screen.
 * - Home: clock, date, a few widgets, and a short column of favourite app icons (no names).
 * - The right edge: touch it and a letter strip appears and bends towards the finger; the left side
 *   shows that letter's apps, most-used first. Slide onto one and let go to open it. Let go on the
 *   strip and the full list takes over exactly where things are, then scrolls freely (search on top).
 * - Swipe left: a side page that holds one widget of your choice. Swipe down: notifications.
 * - Long-press an app to add or remove it from home; long-press empty space for widgets and settings.
 * Other apps can react to the home screen showing or hiding: it broadcasts [HOME_VISIBLE] with
 * extra "visible" (true/false). Rows are built once and reused; nothing reloads on returning home.
 */
class HomeActivity : Activity() {

    companion object {
        /** Broadcast when home shows or hides (extra "visible"), for add-ons such as floating buttons. */
        const val HOME_VISIBLE = "app.waterfall.HOME_VISIBLE"
        private const val BG = 0xFF09212B.toInt()            // deep teal, calm
        private const val FG = 0xFFE6E9EC.toInt()
        private const val DIM = 0xFF8A949E.toInt()
        private const val HOST_ID = 2048
        private const val PICK = 1; private const val CONFIGURE = 2; private const val PICK_SIDE = 3; private const val CONFIGURE_SIDE = 4
        private const val SECTION_ROWS = 12
        private const val USAGE_REFRESH_MS = 30 * 60_000L
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
    private var loadedAt = 0L
    private var allBuiltFor: List<AppIndex.App>? = null
    private val allRows = HashMap<String, View>()
    private val allHeaders = HashMap<String, View>()
    private var favouritesShown: List<String>? = null
    private var widgetsShown: List<Int>? = null

    private val packages = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { reload() }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        index = AppIndex(this)
        host = AppWidgetHost(this, HOST_ID)
        build()
        registerReceiver(packages, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED); addDataScheme("package")
        })
        reload()
    }

    override fun onDestroy() { try { unregisterReceiver(packages) } catch (_: Exception) {}; super.onDestroy() }
    override fun onStart() { super.onStart(); host.startListening() }
    override fun onStop() { super.onStop(); try { host.stopListening() } catch (_: Exception) {} }

    override fun onResume() {
        super.onResume()
        sendBroadcast(Intent(HOME_VISIBLE).putExtra("visible", true))
        closeAll()
        if (loadedAt > 0 && SystemClock.elapsedRealtime() - loadedAt > USAGE_REFRESH_MS) reload()   // usage order, now and then
        else refreshHome()
    }

    override fun onPause() { super.onPause(); sendBroadcast(Intent(HOME_VISIBLE).putExtra("visible", false)) }

    /** Home pressed again while already home: back to the plain home page. */
    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); closeAll(); showSide(false) }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (sideShown) showSide(false) else closeAll() }

    private fun reload() = thread {
        index.load(); index.preloadIcons()
        loadedAt = SystemClock.elapsedRealtime()
        runOnUiThread { strip.letters = listOf("☆") + index.letters() + "○"; allBuiltFor = null; favouritesShown = null; refreshHome() }
    }

    // ---------------------------------------------------------------- layout

    private fun label(size: Float, weight: Int, color: Int = FG) = TextView(this).apply {
        textSize = size; setTextColor(color); typeface = Fonts.get(this@HomeActivity, weight); isSingleLine = true
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
            format12Hour = "H:mm"; format24Hour = "H:mm"; textSize = 60f; setTextColor(FG)
            typeface = Fonts.light(this@HomeActivity)
            setOnClickListener { open(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)) }
        }
        val date = TextClock(this).apply {
            format12Hour = "EEE d MMM"; format24Hour = "EEE d MMM"; textSize = 20f; setTextColor(FG)
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
            addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ ->
                val want = ((bottom - top) * 0.20f).toInt()
                if (v.paddingTop != want) v.post { v.setPadding(dp(40), want, dp(16), 0) }   // the strip overlays, no gap kept
            }
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
            hint = "Search"; setHintTextColor(DIM); setTextColor(FG); textSize = 18f; typeface = Fonts.regular(this@HomeActivity)
            setBackgroundColor(0x22FFFFFF); setPadding(dp(16), dp(10), dp(16), dp(10)); isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { filterAll(s?.toString() ?: "") }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        allWrap.addView(search, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        allWrap.addView(allColumn)

        strip = LetterStrip(this).apply { alpha = 0f }
        homePage.addView(strip, FrameLayout.LayoutParams(dp(200), -1, Gravity.END))
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
        addView(ImageView(context), LinearLayout.LayoutParams(dp(42), dp(42)))
        if (label) addView(label(19f, 500).apply { setPadding(dp(20), 0, 0, 0); isSingleLine = false; maxLines = 2 },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        setOnClickListener { (tag as? AppIndex.App)?.let { index.launch(it) } }
        setOnLongClickListener { (tag as? AppIndex.App)?.let { a -> appMenu(this, a) }; true }
    }

    private fun bind(v: View, a: AppIndex.App) {
        val r = v as LinearLayout
        val icon = index.icon(a)
        (r.getChildAt(0) as ImageView).setImageDrawable(icon?.constantState?.newDrawable() ?: icon)
        (r.getChildAt(1) as? TextView)?.text = a.label
        r.tag = a
    }

    private fun refreshHome() {
        val favs = index.favourites()
        if (favs.map { it.key } != favouritesShown) {
            favourites.removeAllViews()
            for (a in favs) favourites.addView(row(label = false).also { bind(it, a) })
            if (favs.isEmpty()) favourites.addView(label(15f, 500, DIM).apply {
                text = "Touch the right edge for your apps. Long-press one to add it here."; isSingleLine = false
            })
            favouritesShown = favs.map { it.key }
        }
        if (index.widgetIds() != widgetsShown) fillWidgets()
    }

    private fun appMenu(v: View, a: AppIndex.App) {
        PopupMenu(this, v).apply {
            menu.add(if (index.isFavourite(a)) "Remove from home" else "Add to home")
            menu.add("App info")
            setOnMenuItemClickListener {
                if (it.title == "App info") index.appInfo(a) else { index.toggleFavourite(a); refreshHome() }
                true
            }
        }.show()
    }

    // ---------------------------------------------------------------- the letter strip

    private var highlighted: View? = null

    @SuppressLint("ClickableViewAccessibility")
    private fun stripTouch() {
        strip.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN && e.x < strip.width - dp(if (allList.visibility == View.VISIBLE) 76 else 72))   // a generous edge: no need to aim
                return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val onStrip = e.x > strip.width - dp(90)
                    if (onStrip || highlighted == null && section.visibility != View.VISIBLE) {
                        val l = strip.letterAt(e.y) ?: return@setOnTouchListener true
                        strip.alpha = 1f; strip.fingerDown = true; strip.follow(e.y)
                        if (strip.bubble != l) { strip.bubble = l; showSection(l); strip.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
                        highlight(null)
                    } else highlight(rowAt(e.rawY))   // finger moved left onto the list
                }
                MotionEvent.ACTION_UP -> {
                    strip.fingerDown = false
                    val app = highlighted?.tag as? AppIndex.App
                    val letter = strip.bubble
                    highlight(null)
                    when {
                        app != null -> { strip.release(); closeAll(); index.launch(app) }
                        letter == "○" -> { strip.release(); openAll(null) }
                        // Let go on a letter: the full list takes over, lined up so that letter's apps stay
                        // exactly where they were, and from there it scrolls freely.
                        letter == "☆" -> {}
                        letter != null -> { strip.release(); handOverToList(letter) }
                        else -> { strip.release(); closeAll() }
                    }
                }
                MotionEvent.ACTION_CANCEL -> { strip.release(); highlight(null); closeAll() }
            }
            true
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
        val apps = when (letter) { "☆" -> index.favourites(); "○" -> emptyList(); else -> index.section(letter) }
        sectionHeader.text = when (letter) { "☆" -> "Home"; "○" -> "All apps"; else -> letter }
        for ((i, r) in sectionRows.withIndex()) {
            val a = apps.getOrNull(i)
            if (a == null) r.visibility = View.GONE else {
                bind(r, a); r.visibility = View.VISIBLE
                // a short glide per row (staggered a little) rather than a hard cut between letters
                r.animate().cancel(); r.alpha = 0.35f; r.translationY = dp(8).toFloat()
                r.animate().alpha(1f).translationY(0f).setStartDelay(i * 12L).setDuration(170)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            }
        }
        if (section.visibility != View.VISIBLE) {
            allList.visibility = View.GONE
            homeContent.animate().alpha(0f).setDuration(90).withEndAction { if (section.visibility == View.VISIBLE) homeContent.visibility = View.INVISIBLE }.start()
            section.alpha = 0f; section.visibility = View.VISIBLE
            section.animate().alpha(1f).setDuration(110).start()
        }
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
            // line up the letters themselves (the list header has extra space above its text)
            val want = IntArray(2).also { sectionHeader.getLocationOnScreen(it) }[1] + sectionHeader.paddingTop
            val listTop = IntArray(2).also { allList.getLocationOnScreen(it) }[1]
            val inContent = header.top + header.paddingTop + allColumn.top   // the letter's y inside the scrolling content
            val scroll = inContent - (want - listTop)
            if (scroll >= 0) { allList.scrollTo(0, scroll); showList() }
            else {
                // early letters: the list can't scroll above its top, so add that much room above it
                allWrap.setPadding(dp(40), dp(56) - scroll, dp(24), tail())
                allList.post { allList.scrollTo(0, 0); showList() }
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
        for (l in index.letters()) {
            var any = false
            for (a in index.apps.filter { it.letter == l }) {
                val show = query.isEmpty() || a.label.lowercase().contains(query)
                allRows[a.key]?.visibility = if (show) View.VISIBLE else View.GONE
                any = any || show
            }
            allHeaders[l]?.visibility = if (any) View.VISIBLE else View.GONE
        }
    }

    private fun closeAll() {
        if (!::section.isInitialized) return
        if (allWrap.paddingTop != dp(56)) allWrap.setPadding(dp(40), dp(56), dp(24), tail())
        if (strip.bubble != null) strip.release()
        section.visibility = View.GONE; allList.visibility = View.GONE
        homeContent.animate().cancel(); homeContent.alpha = 1f; homeContent.visibility = View.VISIBLE
        strip.alpha = 0f
        if (search.text.isNotEmpty()) search.setText("")
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)
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
                        dx < -dp(80) && sideways && !sideShown -> showSide(true)    // swipe left: the side page in from the right
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
            setOnMenuItemClickListener {
                when (it.title) {
                    "Add a widget" -> pickWidget()
                    "Remove widgets" -> { index.widgetIds().forEach { id -> host.deleteAppWidgetId(id) }; index.setWidgetIds(emptyList()); fillWidgets() }
                    "Order letters by most used" -> open(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    "Default home app" -> open(Intent(android.provider.Settings.ACTION_HOME_SETTINGS))
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
        fillSide()
        return sideHolder
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
    }

    private fun fillWidgets() {
        widgets.removeAllViews()
        val mgr = AppWidgetManager.getInstance(this)
        for (id in index.widgetIds()) {
            val info = mgr.getAppWidgetInfo(id) ?: continue
            val v = host.createView(this, id, info)
            widgets.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, info.minHeight.coerceAtLeast(dp(90))).apply { bottomMargin = dp(8) })
        }
        widgetsShown = index.widgetIds()
    }
}
