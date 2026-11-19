package pl.necek.wywoz

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val stamp = DateTimeFormatter.ofPattern("dd.MM HH:mm")
    private lateinit var store: Store
    private lateinit var dateView: TextView
    private lateinit var addressView: TextView
    private lateinit var clockView: TextView
    private lateinit var content: LinearLayout
    private lateinit var statusView: TextView
    private var schedule: Schedule? = null
    private var shownNumberId: String? = null
    private var loading = false
    private var lastError: String? = null
    private var lastAttempt = 0L
    private var renderedDay: LocalDate? = null
    private var setupShown = false

    private val tick = object : Runnable {
        override fun run() {
            if (LocalDate.now() != renderedDay) render() else updateClock()
            val s = schedule
            if (s == null || System.currentTimeMillis() - s.fetchedAt > REFRESH_MS) refresh(false)
            handler.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000 + 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupShown = savedInstanceState != null
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        store = Store(this)
        setContentView(buildLayout())
    }

    override fun onResume() {
        super.onResume()
        val id = store.numberId
        if (id == null) {
            if (setupShown) finish() else {
                setupShown = true
                startActivity(Intent(this, SetupActivity::class.java))
            }
            return
        }
        if (id != shownNumberId) {
            shownNumberId = id
            lastError = null
            lastAttempt = 0L
        }
        addressView.text = store.address
        schedule = store.loadSchedule()
        hideSystemBars()
        render()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (store.numberId != null) render()
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        root.applySystemInsets(dp(24))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        dateView = label("", 30f, TEXT, bold = true)
        addressView = label("", 18f, MUTED)
        titles.addView(dateView)
        titles.addView(addressView)
        clockView = label("", 64f, TEXT, bold = true)
        header.addView(titles, LinearLayout.LayoutParams(0, WRAP, 1f))
        header.addView(clockView)

        content = LinearLayout(this)

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusView = label("", 15f, MUTED)
        statusView.setOnLongClickListener {
            showRaw()
            true
        }
        footer.addView(statusView, LinearLayout.LayoutParams(0, WRAP, 1f))
        footer.addView(flatButton("Odśwież") { refresh(true) })
        footer.addView(flatButton("Zmień adres") { startActivity(Intent(this, SetupActivity::class.java)) })

        root.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f).apply {
            topMargin = dp(24)
            bottomMargin = dp(12)
        })
        root.addView(footer, LinearLayout.LayoutParams(MATCH, WRAP))
        return root
    }

    private fun render() {
        val today = LocalDate.now()
        renderedDay = today
        updateClock()
        content.removeAllViews()
        content.orientation = LinearLayout.VERTICAL
        val upcoming = schedule?.pickups?.filter { !it.date.isBefore(today) }.orEmpty()
        when {
            upcoming.isNotEmpty() -> showSchedule(upcoming, today)
            loading -> showMessage("Pobieram harmonogram…")
            lastError != null -> showError()
            schedule == null -> {
                showMessage("Pobieram harmonogram…")
                refresh(true)
            }
            else -> showMessage("Brak zaplanowanych odbiorów w harmonogramie")
        }
        updateStatus()
    }

    private fun refresh(manual: Boolean) {
        val id = store.numberId ?: return
        if (loading) return
        if (!manual && System.currentTimeMillis() - lastAttempt < RETRY_MS) return
        loading = true
        lastAttempt = System.currentTimeMillis()
        updateStatus()
        Thread {
            var raw: String? = null
            val result = runCatching {
                val text = PdfText.extract(applicationContext, MpoApi.pdf(id))
                raw = text
                ScheduleParser.parse(text)
            }
            runOnUiThread {
                loading = false
                if (isDestroyed) return@runOnUiThread
                if (id != store.numberId) {
                    render()
                    return@runOnUiThread
                }
                raw?.let { store.rawText = it }
                result.onSuccess { pickups ->
                    if (pickups.isEmpty()) {
                        lastError = "Nie udało się odczytać dat z PDF"
                    } else {
                        val fresh = Schedule(System.currentTimeMillis(), pickups)
                        store.saveSchedule(fresh)
                        schedule = fresh
                        lastError = null
                    }
                }.onFailure { e ->
                    lastError = e.message ?: e.javaClass.simpleName
                }
                render()
            }
        }.start()
    }

    private fun showSchedule(upcoming: List<Pickup>, today: LocalDate) {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        content.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL

        val hero = heroCard(upcoming.first(), today)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(label("Kolejne odbiory", 18f, MUTED))
        val rest = upcoming.drop(1).take(12)
        rest.forEach { list.addView(row(it, today)) }
        if (rest.isEmpty()) list.addView(label("Brak kolejnych terminów w harmonogramie", 18f, MUTED))
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(list)
        }

        if (landscape) {
            val heroScroll = ScrollView(this).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
                addView(hero)
            }
            content.addView(heroScroll, LinearLayout.LayoutParams(0, MATCH, 1f))
            content.addView(scroll, LinearLayout.LayoutParams(0, MATCH, 1.2f).apply { marginStart = dp(24) })
        } else {
            content.addView(hero, LinearLayout.LayoutParams(MATCH, WRAP))
            content.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(24) })
        }
    }

    private fun heroCard(p: Pickup, today: LocalDate): View {
        val days = ChronoUnit.DAYS.between(today, p.date).toInt()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(32), dp(28), dp(32), dp(28))
            background = rounded(CARD, dp(24).toFloat())
        }
        card.addView(label("Najbliższy odbiór", 18f, MUTED))
        card.addView(label(Dates.relative(days), 64f, if (days <= 1) ACCENT else TEXT, bold = true))
        card.addView(label(Dates.full(p.date), 24f, TEXT))
        if (days == 1) {
            card.addView(label("Wystaw pojemniki dziś wieczorem", 18f, ACCENT).apply { setPadding(0, dp(6), 0, 0) })
        }
        val chips = FlowLayout(this, dp(12))
        p.types.forEach { chips.addView(chip(it, 26f, dp(14))) }
        card.addView(chips, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(18) })
        return card
    }

    private fun row(p: Pickup, today: LocalDate): View {
        val days = ChronoUnit.DAYS.between(today, p.date).toInt()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = rounded(CARD, dp(16).toFloat())
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) }
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(label(Dates.short(p.date), 22f, TEXT, bold = true))
        left.addView(label(Dates.relative(days), 15f, MUTED))
        row.addView(left, LinearLayout.LayoutParams(dp(150), WRAP))
        val chips = FlowLayout(this, dp(8))
        p.types.forEach { chips.addView(chip(it, 18f, dp(10))) }
        row.addView(chips, LinearLayout.LayoutParams(0, WRAP, 1f))
        return row
    }

    private fun chip(type: String, size: Float, pad: Int): TextView {
        val bg = WasteStyle.color(type)
        return TextView(this).apply {
            text = type
            textSize = size
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(WasteStyle.textColor(bg))
            setPadding(pad + dp(6), pad, pad + dp(6), pad)
            background = rounded(bg, dp(12).toFloat())
        }
    }

    private fun showMessage(text: String) {
        val view = label(text, 26f, MUTED).apply { gravity = Gravity.CENTER }
        content.addView(view, LinearLayout.LayoutParams(MATCH, MATCH))
    }

    private fun showError() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        box.addView(label("Nie udało się pobrać harmonogramu", 26f, TEXT, bold = true).apply { gravity = Gravity.CENTER })
        box.addView(label(lastError.orEmpty(), 18f, MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(16))
        })
        box.addView(flatButton("Spróbuj ponownie") { refresh(true) })
        if (store.rawText != null) box.addView(flatButton("Pokaż tekst z PDF") { showRaw() })
        content.addView(box, LinearLayout.LayoutParams(MATCH, MATCH))
    }

    private fun updateClock() {
        val now = LocalTime.now()
        clockView.text = String.format(Locale.ROOT, "%02d:%02d", now.hour, now.minute)
        dateView.text = Dates.full(LocalDate.now()).replaceFirstChar { it.uppercase() }
    }

    private fun updateStatus() {
        val time = schedule?.let { stamp.format(Instant.ofEpochMilli(it.fetchedAt).atZone(ZoneId.systemDefault())) }
        statusView.text = when {
            loading -> "Pobieram harmonogram…"
            lastError != null -> listOfNotNull("Błąd: $lastError", time?.let { "dane z $it" }).joinToString(" · ")
            time != null -> "Zaktualizowano $time"
            else -> ""
        }
    }

    private fun showRaw() {
        val raw = store.rawText ?: return
        AlertDialog.Builder(this)
            .setTitle("Tekst odczytany z PDF")
            .setMessage(raw.take(20_000))
            .setPositiveButton("Zamknij", null)
            .setNeutralButton("Kopiuj") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("pdf", raw))
            }
            .show()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
    }

    companion object {
        private const val REFRESH_MS = 12 * 60 * 60 * 1000L
        private const val RETRY_MS = 15 * 60 * 1000L
    }
}
