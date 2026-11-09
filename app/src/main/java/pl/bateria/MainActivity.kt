package pl.bateria

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.VpnService
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku

class MainActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var sv: ScrollView
    private lateinit var col: LinearLayout
    private var tab = "save"
    private var apps: List<App> = emptyList()
    private var appsLoading = false
    private var withSystem = false
    private var query = ""
    private val sel = LinkedHashSet<String>()
    private var ranking: List<Engine.Use>? = null

    private val BG = 0xFF101010.toInt()
    private val SURF = 0xFF1C1C1E.toInt()
    private val FG = Color.WHITE
    private val DIM = 0xFF9A9A9E.toInt()
    private val LINE = 0xFF2C2C2E.toInt()
    private val ACC = 0xFF4CD964.toInt()
    private val WARN = 0xFFFF9F0A.toInt()

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ -> h.post { onShizukuReady() } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Cfg.init(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        sv = ScrollView(this).apply { isFillViewport = true }
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(40)) }
        sv.addView(col)
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(tabBar())
        setContentView(root)
        try { Shizuku.addRequestPermissionResultListener(permListener) } catch (e: Throwable) {}
        if (!Sh.granted()) Sh.request() else onShizukuReady()
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 3)
        render()
    }

    override fun onDestroy() {
        try { Shizuku.removeRequestPermissionResultListener(permListener) } catch (e: Throwable) {}
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun onShizukuReady() {
        if (!Sh.granted()) return
        Thread {
            Sh.run("appops set $packageName GET_USAGE_STATS allow")
            Sh.run("dumpsys deviceidle whitelist +$packageName")
        }.start()
        BatteryService.start(this)
        render()
    }

    // ------------------------------------------------------------ widoki pomocnicze
    private fun tv(t: String, size: Float = 15f, bold: Boolean = false, color: Int = FG) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun lp(w: Int = -1, hh: Int = -2, weight: Float = 0f, top: Int = 0) =
        LinearLayout.LayoutParams(w, hh, weight).apply { topMargin = dp(top) }

    private fun round(c: Int, r: Int, stroke: Int = 0, sc: Int = LINE) = GradientDrawable().apply {
        setColor(c); cornerRadius = dp(r).toFloat(); if (stroke > 0) setStroke(dp(stroke), sc)
    }

    private fun hbox() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun btn(t: String, primary: Boolean = false, f: () -> Unit) = TextView(this).apply {
        text = t; textSize = 15f; gravity = Gravity.CENTER
        setTextColor(if (primary) Color.BLACK else FG)
        background = if (primary) round(FG, 24) else round(SURF, 24, 1)
        setPadding(dp(18), dp(12), dp(18), dp(12))
        setOnClickListener { f() }
    }

    private fun header(t: String) = col.addView(tv(t.uppercase(), 13f, true, DIM).apply { letterSpacing = 0.08f }, lp(top = 26))

    private fun line() = View(this).apply { setBackgroundColor(LINE) }

    /** Wiersz z przełącznikiem (jak w ustawieniach systemu). */
    private fun toggle(title: String, sub: String, on: Boolean, f: (Boolean) -> Unit) {
        val r = hbox().apply { setPadding(0, dp(14), 0, dp(14)) }
        val l = vbox()
        l.addView(tv(title, 17f))
        if (sub.isNotEmpty()) l.addView(tv(sub, 13f, color = DIM), lp(top = 2))
        r.addView(l, lp(0, -2, 1f))
        val sw = Switch(this).apply { isChecked = on; setOnCheckedChangeListener { _, b -> f(b) } }
        r.addView(sw, lp(-2, -2).apply { leftMargin = dp(12) })
        r.setOnClickListener { sw.isChecked = !sw.isChecked }
        if (col.childCount > 0 && col.getChildAt(col.childCount - 1) !is TextView) col.addView(line(), lp(-1, 1))
        col.addView(r)
    }

    private fun row(title: String, sub: String, f: (() -> Unit)?) {
        val l = vbox().apply { setPadding(0, dp(14), 0, dp(14)) }
        l.addView(tv(title, 17f))
        if (sub.isNotEmpty()) l.addView(tv(sub, 13f, color = DIM), lp(top = 2))
        if (f != null) l.setOnClickListener { f() }
        col.addView(l)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun sheet(title: String, build: (LinearLayout, Dialog) -> Unit): Dialog {
        val d = Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar)
        val c = vbox().apply { setPadding(dp(20), dp(18), dp(20), dp(18)); background = round(SURF, 22) }
        c.addView(tv(title, 19f, true))
        build(c, d)
        d.setContentView(ScrollView(this).apply { addView(c) })
        d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        d.show()
        return d
    }

    /** Zadanie w tle z odświeżeniem ekranu. */
    private fun work(msg: String?, job: () -> String?) {
        if (msg != null) toast(msg)
        Thread {
            val r = try { job() } catch (e: Exception) { "Błąd: " + (e.message ?: "") }
            h.post { if (r != null) toast(r); render() }
        }.start()
    }

    private fun needShizuku(): Boolean {
        if (Sh.granted()) return false
        toast(if (Sh.running()) "Zezwól Baterii w Shizuku" else "Uruchom Shizuku")
        Sh.request()
        return true
    }

    // ------------------------------------------------------------ pasek zakładek
    private lateinit var tabs: LinearLayout

    private fun tabBar(): View {
        tabs = hbox().apply { setBackgroundColor(SURF); setPadding(dp(8), dp(6), dp(8), dp(10)) }
        drawTabs()
        return tabs
    }

    private fun drawTabs() {
        tabs.removeAllViews()
        for ((k, n) in listOf("save" to "Oszczędzanie", "apps" to "Aplikacje", "use" to "Zużycie")) {
            val on = k == tab
            tabs.addView(tv(n, 14f, on, if (on) Color.BLACK else DIM).apply {
                gravity = Gravity.CENTER
                background = if (on) round(FG, 18) else null
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { tab = k; sel.clear(); render(); sv.scrollTo(0, 0) }
            }, lp(0, -2, 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })
        }
    }

    private fun render() {
        val y = sv.scrollY
        col.removeAllViews()
        drawTabs()
        when (tab) {
            "apps" -> renderApps()
            "use" -> renderUse()
            else -> renderSave()
        }
        sv.post { sv.scrollTo(0, y) }
    }

    // ------------------------------------------------------------ zakładka: oszczędzanie
    private fun renderSave() {
        col.addView(tv("Bateria", 34f, true))
        val bm = getSystemService(BatteryManager::class.java)
        val lvl = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
        val chg = bm?.isCharging == true
        row("Bateria: $lvl%", (if (chg) "Ładowanie" else "Na baterii") + if (Engine.saverOn(this)) " · oszczędzanie energii włączone" else "", null)
        col.addView(line(), lp(-1, 1))
        row(if (Sh.granted()) "Shizuku: aktywne" else if (Sh.running()) "Shizuku: stuknij, aby zezwolić" else "Shizuku: nie działa",
            if (Sh.granted()) "Potrzebne do ograniczania, zamrażania i ustawień systemu" else "Uruchom Shizuku i stuknij tutaj") { if (!Sh.granted()) Sh.request() }
        if (Sh.granted() && !BatteryService.alive) BatteryService.start(this)

        header("Szybko")
        col.addView(btn("Ustaw maksymalną oszczędność", true) {
            if (needShizuku()) return@btn
            work("Ustawiam…") { Engine.maxSaving(this); "Gotowe – maksymalna oszczędność" }
        }, lp(-2, -2, top = 6))
        col.addView(tv("60 Hz, ciemny tryb, bez Always On, bez skanowania w tle, głęboki sen, strażnik, automat i ograniczenie usług Samsunga.", 12f, color = DIM), lp(top = 6))

        header("Ekran")
        toggle("60 Hz", "Płynność 120 Hz zużywa sporo prądu. Wyłączenie przywraca poprzednie ustawienie.", Cfg.hz60) {
            if (needShizuku()) return@toggle; Cfg.hz60 = it; work(null) { Engine.applyHz(it); Cfg.save(); null }
        }
        toggle("Ciemne wszystkie aplikacje", "Ciemny tryb systemu + wymuszona ciemność aplikacji bez własnego trybu ciemnego", Cfg.dark) {
            if (needShizuku()) return@toggle; Cfg.dark = it; work(null) { Engine.applyDark(it); Cfg.save(); null }
        }
        toggle("Wyłącz Always On Display", "Zegar na wygaszonym ekranie stale zużywa prąd", Cfg.aodOff) {
            if (needShizuku()) return@toggle; Cfg.aodOff = it; work(null) { Engine.applyAod(it); Cfg.save(); null }
        }
        col.addView(tv("Najwięcej daje czerń: na ekranie AMOLED czarny piksel jest wyłączony.", 12f, color = DIM), lp(top = 6))

        header("W tle")
        toggle("Skanowanie Wi-Fi i Bluetooth w tle", "Wyłącza szukanie sieci i urządzeń przy wyłączonym Wi-Fi i Bluetooth", Cfg.scanOff) {
            if (needShizuku()) return@toggle; Cfg.scanOff = it; work(null) { Engine.applyScan(it); Cfg.save(); null }
        }
        toggle("Głęboki sen po wygaszeniu", "Minutę po wygaszeniu telefon przechodzi w najgłębsze uśpienie (Doze) zamiast czekać ok. godziny. Połączenia i budziki działają, wiadomości mogą przyjść z opóźnieniem.", Cfg.deepDoze) {
            Cfg.deepDoze = it; Cfg.save()
        }
        toggle("Strażnik", "Powiadomi, gdy aplikacja ponad 20 min nie pozwala telefonowi spać – z przyciskiem „Ogranicz”.", Cfg.guard) {
            Cfg.guard = it; Cfg.save()
        }
        toggle("Automat: nieużywane 7 dni → ogranicz", "Aplikacje, których nie otwierasz, same trafiają do „Ogranicz w tle”. Gdy znów ich użyjesz – wracają.", Cfg.autoUsage) {
            Cfg.autoUsage = it; Cfg.save(); if (it) work("Sprawdzam aplikacje…") { Engine.autoByUsage(this, true); "Automat zastosowany" }
        }
        toggle("Automat: nieużywane 30 dni → zamroź", "Bardzo rzadko używane aplikacje są zamrażane na stałe (uruchomisz je z zakładki Aplikacje).", Cfg.autoFreeze) {
            Cfg.autoFreeze = it; Cfg.save()
        }
        toggle("Blokuj internet w tle", "Po wygaszeniu ekranu aplikacje z kategorii „Ogranicz” tracą internet. Używa lokalnego VPN – nie połączysz wtedy innego VPN.", Cfg.netBlock) {
            Cfg.netBlock = it; Cfg.save()
            if (it) VpnService.prepare(this)?.let { i -> startActivityForResult(i, 5) }
        }

        header("Noc i weekend")
        toggle("Tryb nocny", "Od ${Cfg.nightFrom}:00 do ${Cfg.nightTo}:00: " + listOfNotNull(
            if (Cfg.nightData) "bez danych komórkowych" else null, if (Cfg.nightSync) "bez synchronizacji" else null,
            if (Cfg.nightSaver) "oszczędzanie energii" else null).joinToString(", "), Cfg.night) {
            Cfg.night = it; Cfg.save(); Engine.scheduleNight(this)
            if (it && Engine.inNight()) work(null) { Engine.night(this, true); null }
        }
        if (Cfg.night) col.addView(btn("Godziny i zakres nocy") { nightSheet() }, lp(-2, -2, top = 4))
        toggle("Weekend: oszczędzanie cały dzień", "W sobotę i niedzielę oszczędzanie energii włączone od rana", Cfg.weekendSaver) {
            Cfg.weekendSaver = it; Cfg.save(); if (it && Engine.weekend()) work(null) { Engine.saver(true); null }
        }

        header("Sieć i bateria")
        row("5G → LTE", "5G przy słabym zasięgu mocno zużywa baterię. Otwórz ustawienia i wybierz LTE/3G/2G.") {
            open(listOf(Intent("android.settings.NETWORK_OPERATOR_SETTINGS"), Intent(Settings.ACTION_DATA_ROAMING_SETTINGS), Intent(Settings.ACTION_WIRELESS_SETTINGS)))
        }
        col.addView(line(), lp(-1, 1))
        row("Ochrona baterii (ładowanie do 80–85%)", "Nie wydłuża dnia, ale wydłuża życie baterii o lata. Samsung: Bateria → Więcej ustawień → Ochrona baterii.") {
            open(listOf(Intent().setClassName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                Intent(Intent.ACTION_POWER_USAGE_SUMMARY), Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)))
        }
        col.addView(line(), lp(-1, 1))
        row("Ogranicz usługi Samsunga i Facebooka", "Samsung Free, Bixby, Galaxy Store, analityka, Facebook App Manager… – tylko blokada tła, można cofnąć.") {
            vendorSheet()
        }
        col.addView(line(), lp(-1, 1))
        row("Kafelek „Oszczędzanie”", "Dodasz go w szybkich ustawieniach: przeciągnij panel w dół → ołówek → przeciągnij kafelek.", null)

        header("Aktualizacje")
        row("Sprawdź aktualizację", if (Cfg.token.isBlank()) "Wpisz token GitHub (raz)" else "Instaluje się sama przez Shizuku") { updateSheet() }
    }

    private fun open(list: List<Intent>) {
        for (i in list) try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (e: Exception) {}
        toast("Nie mogę otworzyć ustawień")
    }

    private fun nightSheet() {
        sheet("Tryb nocny") { c, d ->
            fun hours(label: String, cur: Int, f: (Int) -> Unit) {
                c.addView(tv(label, 14f, color = DIM), lp(top = 12))
                val r = hbox()
                for (hh in listOf(20, 21, 22, 23, 0, 1, 5, 6, 7, 8)) r.addView(tv("$hh", 15f, hh == cur, if (hh == cur) Color.BLACK else FG).apply {
                    gravity = Gravity.CENTER; background = if (hh == cur) round(FG, 14) else round(BG, 14, 1)
                    setPadding(dp(12), dp(8), dp(12), dp(8)); setOnClickListener { f(hh); d.dismiss(); nightSheet() }
                }, lp(-2, -2).apply { rightMargin = dp(6) })
                c.addView(HorizontalScrollView(this).apply { addView(r) }, lp(top = 4))
            }
            hours("Od godziny", Cfg.nightFrom) { Cfg.nightFrom = it; Cfg.save(); Engine.scheduleNight(this) }
            hours("Do godziny", Cfg.nightTo) { Cfg.nightTo = it; Cfg.save(); Engine.scheduleNight(this) }
            fun opt(t: String, on: Boolean, f: (Boolean) -> Unit) {
                val r = hbox().apply { setPadding(0, dp(10), 0, dp(10)) }
                r.addView(tv(t, 16f), lp(0, -2, 1f))
                r.addView(Switch(this).apply { isChecked = on; setOnCheckedChangeListener { _, b -> f(b); Cfg.save() } })
                c.addView(r)
            }
            opt("Wyłącz dane komórkowe", Cfg.nightData) { Cfg.nightData = it }
            opt("Wyłącz synchronizację kont", Cfg.nightSync) { Cfg.nightSync = it }
            opt("Oszczędzanie energii", Cfg.nightSaver) { Cfg.nightSaver = it }
            c.addView(tv("Przy ładowaniu dane zostają włączone.", 12f, color = DIM), lp(top = 6))
            c.addView(btn("Gotowe", true) { d.dismiss(); render() }, lp(top = 14))
        }
    }

    private fun vendorSheet() {
        val pm = packageManager
        val list = Apps.VENDOR_SAFE.filter { Apps.installed(pm, it.first) }
        sheet("Usługi producenta") { c, d ->
            c.addView(tv("Te aplikacje dostaną blokadę działania w tle (dalej działają po otwarciu):", 14f, color = DIM), lp(top = 6))
            for ((p, n) in list) c.addView(tv((if (Cfg.cat(p) == Cat.BG) "✓ " else "• ") + n, 15f, color = if (Cfg.cat(p) == Cat.BG) ACC else FG), lp(top = 6))
            if (list.isEmpty()) c.addView(tv("Nic do ograniczenia.", 14f), lp(top = 6))
            c.addView(btn("Ogranicz wszystkie", true) {
                if (needShizuku()) return@btn
                d.dismiss(); work("Ograniczam…") { Engine.restrictVendor(this); "Ograniczono" }
            }, lp(top = 14))
            c.addView(btn("Anuluj") { d.dismiss() }, lp(top = 8))
        }
    }

    private fun updateSheet() {
        sheet("Aktualizacja") { c, d ->
            val e = EditText(this).apply {
                setText(Cfg.token); hint = "Token GitHub (ghp_…)"; setTextColor(FG); setHintTextColor(DIM); textSize = 14f
                background = round(BG, 14); setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            c.addView(e, lp(top = 10))
            c.addView(btn("Sprawdź i zainstaluj", true) {
                Cfg.token = e.text.toString().trim(); Cfg.save(); d.dismiss()
                work("Sprawdzam…") {
                    val info = Updater.check(Cfg.token) ?: return@work "Brak wydań"
                    val cur = try { packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt() } catch (x: Exception) { 0 }
                    if (info.number <= cur) "Masz najnowszą wersję (${cur})" else {
                        val r = Updater.install(this, Cfg.token, info)
                        if (r.contains("Success")) "Zainstalowano wersję ${info.number}" else "Instalacja: ${r.take(120)}"
                    }
                }
            }, lp(top = 12))
            c.addView(btn("Otwórz budowanie na GitHubie") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${Updater.REPO}/actions"))) }, lp(top = 8))
        }
    }

    // ------------------------------------------------------------ zakładka: aplikacje (kategorie, przeciąganie)
    private fun loadApps() {
        if (appsLoading) return
        appsLoading = true
        Thread {
            val l = Apps.list(this, withSystem)
            h.post { apps = l; appsLoading = false; if (tab == "apps") render() }
        }.start()
    }

    private fun ago(t: Long): String {
        if (t <= 0) return "dawno nieużywana"
        val d = (System.currentTimeMillis() - t) / 86_400_000L
        return when { d <= 0 -> "dziś"; d == 1L -> "wczoraj"; else -> "$d dni temu" }
    }

    private fun renderApps() {
        col.addView(tv("Aplikacje", 34f, true))
        col.addView(tv("Przytrzymaj aplikację i przeciągnij ją do innej kategorii. Stuknij kilka, żeby zaznaczyć je razem – potem przeciągnij dowolną z nich albo użyj „Przenieś”.", 13f, color = DIM), lp(top = 6))
        if (!Sh.granted()) col.addView(tv("⚠ Shizuku nie jest aktywne – zmiany nie zadziałają.", 13f, true, WARN), lp(top = 8))
        val search = EditText(this).apply {
            setText(query); hint = "Szukaj aplikacji"; setTextColor(FG); setHintTextColor(DIM); textSize = 15f
            background = round(SURF, 16); setPadding(dp(14), dp(10), dp(14), dp(10)); setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s?.toString() ?: ""; h.removeCallbacks(redraw); h.postDelayed(redraw, 350) }
            })
        }
        col.addView(search, lp(top = 12))
        val r = hbox()
        r.addView(tv(if (withSystem) "☑ Pokaż systemowe" else "☐ Pokaż systemowe", 14f, color = DIM).apply {
            setPadding(0, dp(10), dp(16), dp(10)); setOnClickListener { withSystem = !withSystem; apps = emptyList(); loadApps(); render() }
        })
        if (sel.isNotEmpty()) {
            r.addView(btn("Przenieś (${sel.size})", true) { moveSheet(sel.toList()) }, lp(-2, -2).apply { rightMargin = dp(8) })
            r.addView(btn("✕") { sel.clear(); render() })
        }
        col.addView(r, lp(top = 4))
        if (apps.isEmpty()) {
            col.addView(tv("Wczytuję aplikacje…", 14f, color = DIM), lp(top = 20)); loadApps(); return
        }
        val q = query.trim().lowercase()
        val shown = apps.filter { q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.contains(q) }
        val pm = packageManager
        for (cat in Cat.ALL) {
            val list = shown.filter { Cfg.cat(it.pkg) == cat }
            val sec = vbox().apply { background = round(SURF, 18); setPadding(dp(14), dp(12), dp(14), dp(10)) }
            val head = hbox()
            head.addView(tv(Cat.name(cat) + " (${list.size})", 16f, true), lp(0, -2, 1f))
            if (Cat.hasDelay(cat)) head.addView(tv("po ${Cfg.delay[cat] ?: 0} min ▾", 14f, true, ACC).apply {
                setPadding(dp(10), dp(6), 0, dp(6)); setOnClickListener { delaySheet(cat) }
            })
            sec.addView(head)
            sec.addView(tv(Cat.hint(cat), 12f, color = DIM), lp(top = 2))
            if (list.isEmpty()) sec.addView(tv("Upuść tutaj", 13f, color = LINE).apply { gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(10)) })
            for (a in list) sec.addView(appRow(a, cat, pm))
            dropTarget(sec, cat)
            col.addView(sec, lp(top = 14))
        }
    }

    private val redraw = Runnable { if (tab == "apps") render() }

    private fun appRow(a: App, cat: String, pm: android.content.pm.PackageManager): View {
        val r = hbox().apply { setPadding(dp(6), dp(8), dp(6), dp(8)) }
        val on = a.pkg in sel
        if (on) r.background = round(0xFF2E3B2E.toInt(), 12, 1, ACC)
        val iv = ImageView(this).apply { setImageDrawable(Apps.icon(this@MainActivity, a.pkg)) }
        r.addView(iv, LinearLayout.LayoutParams(dp(36), dp(36)))
        val l = vbox()
        l.addView(tv(a.label, 15f, on).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        val extra = (if (a.pkg in Cfg.auto) " · automat" else "") + (if (Apps.frozen(pm, a.pkg)) " · zamrożona" else "")
        l.addView(tv(ago(a.lastUsed) + extra, 12f, color = DIM))
        r.addView(l, lp(0, -2, 1f).apply { leftMargin = dp(12) })
        if (cat == Cat.FZP) r.addView(tv("Uruchom", 13f, true, ACC).apply {
            setPadding(dp(10), dp(6), dp(4), dp(6))
            setOnClickListener {
                work("Odmrażam…") {
                    Engine.freeze(a.pkg, false)
                    packageManager.getLaunchIntentForPackage(a.pkg)?.let { i -> h.postDelayed({ startActivity(i) }, 400) }
                    null
                }
            }
        })
        r.setOnClickListener {
            if (sel.isEmpty()) moveSheet(listOf(a.pkg)) else { if (!sel.add(a.pkg)) sel.remove(a.pkg); render() }
        }
        r.setOnLongClickListener { v ->
            if (a.pkg !in sel) sel.add(a.pkg)
            val data = ClipData.newPlainText("apps", sel.joinToString(","))
            v.startDragAndDrop(data, View.DragShadowBuilder(v), null, 0)
            true
        }
        return r
    }

    /** Sekcja kategorii przyjmuje upuszczone aplikacje; przy krawędzi ekranu przewija listę. */
    private fun dropTarget(v: View, cat: String) {
        v.setOnDragListener { view, e ->
            when (e.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DRAG_ENTERED -> { view.background = round(0xFF233323.toInt(), 18, 2, ACC); true }
                DragEvent.ACTION_DRAG_EXITED -> { view.background = round(SURF, 18); true }
                DragEvent.ACTION_DRAG_LOCATION -> {
                    val loc = IntArray(2); view.getLocationOnScreen(loc)
                    val svLoc = IntArray(2); sv.getLocationOnScreen(svLoc)
                    val y = loc[1] + e.y - svLoc[1]
                    if (y < dp(110)) sv.smoothScrollBy(0, -dp(40)) else if (y > sv.height - dp(110)) sv.smoothScrollBy(0, dp(40))
                    true
                }
                DragEvent.ACTION_DROP -> {
                    val pk = e.clipData?.getItemAt(0)?.text?.toString()?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
                    move(pk, cat)
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> { view.background = round(SURF, 18); true }
                else -> true
            }
        }
    }

    private fun move(pk: List<String>, cat: String) {
        if (pk.isEmpty()) return
        if (needShizuku()) return
        sel.clear()
        work(if (pk.size > 1) "Przenoszę ${pk.size} aplikacji…" else null) {
            Engine.setCat(this, pk, cat)
            "→ " + Cat.name(cat)
        }
    }

    private fun moveSheet(pk: List<String>) {
        sheet(if (pk.size == 1) (apps.firstOrNull { it.pkg == pk[0] }?.label ?: pk[0]) else "Przenieś ${pk.size} aplikacji") { c, d ->
            if (pk.size == 1) {
                c.addView(btn("Zaznacz (żeby przenieść kilka naraz)") { d.dismiss(); sel.add(pk[0]); render() }, lp(top = 10))
            }
            for (cat in Cat.ALL) {
                val cur = pk.size == 1 && Cfg.cat(pk[0]) == cat
                c.addView(btn((if (cur) "✓ " else "") + Cat.name(cat) + if (Cat.hasDelay(cat)) " (po ${Cfg.delay[cat]} min)" else "", cur) {
                    d.dismiss(); move(pk, cat)
                }, lp(top = 8))
            }
        }
    }

    private fun delaySheet(cat: String) {
        sheet(Cat.name(cat)) { c, d ->
            c.addView(tv("Po ilu minutach od wygaszenia ekranu? Zmiana dotyczy wszystkich aplikacji w tej kategorii.", 14f, color = DIM), lp(top = 6))
            val opts = if (cat == Cat.OFF) listOf(0, 1, 5, 15, 30, 60) else listOf(1, 5, 10, 15, 30, 60, 120, 240)
            for (m in opts) c.addView(btn((if (Cfg.delay[cat] == m) "✓ " else "") + if (m == 0) "Od razu" else "Po $m min", Cfg.delay[cat] == m) {
                Cfg.delay[cat] = m; Cfg.save(); d.dismiss(); render()
            }, lp(top = 8))
        }
    }

    // ------------------------------------------------------------ zakładka: zużycie
    private fun renderUse() {
        col.addView(tv("Zużycie", 34f, true))
        val (off, on) = Engine.drain(this)
        col.addView(tv("Ostatnie 24 h", 13f, true, DIM), lp(top = 14))
        col.addView(Chart(this, Engine.history(this)), lp(-1, dp(190), top = 6))
        col.addView(tv(String.format("Przy wygaszonym ekranie: %.1f%%/h   ·   przy włączonym: %.1f%%/h", off, on), 14f), lp(top = 8))
        if (off > 1.5) col.addView(tv("Telefon traci sporo w uśpieniu – sprawdź ranking poniżej i ogranicz winowajców.", 13f, false, WARN), lp(top = 4))

        header("Kto zjada baterię (od ostatniego ładowania)")
        val rk = ranking
        if (rk == null) {
            col.addView(btn("Policz", true) {
                if (needShizuku()) return@btn
                work("Liczę…") { ranking = Engine.ranking(this); null }
            }, lp(-2, -2, top = 6))
        } else {
            val total = rk.sumOf { it.mah }.coerceAtLeast(0.01)
            for (u in rk) {
                val r = hbox().apply { setPadding(0, dp(8), 0, dp(8)) }
                r.addView(ImageView(this).apply { setImageDrawable(Apps.icon(this@MainActivity, u.pkg)) }, LinearLayout.LayoutParams(dp(34), dp(34)))
                val l = vbox()
                l.addView(tv(u.label, 15f).apply { maxLines = 1 })
                l.addView(tv(String.format("%.0f%% zużycia · w tle %.0f mAh · ekran %d min · %s", u.mah * 100 / total, u.bg, u.fgMin, Cat.name(Cfg.cat(u.pkg))), 12f, color = DIM))
                r.addView(l, lp(0, -2, 1f).apply { leftMargin = dp(12) })
                r.setOnClickListener { moveSheet(listOf(u.pkg)) }
                col.addView(r)
            }
            col.addView(btn("Odśwież ranking") { ranking = null; render() }, lp(-2, -2, top = 8))
        }

        header("Lokalizacja w tle")
        col.addView(tv("GPS w tle to jeden z największych pożeraczy. Stuknij, żeby zabrać lub oddać dostęp.", 13f, color = DIM), lp(top = 4))
        val pm = packageManager
        val loc = Engine.bgLocationApps(this)
        for ((p, has) in loc) {
            val label = try { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() } catch (e: Exception) { p }
            val r = hbox().apply { setPadding(0, dp(8), 0, dp(8)) }
            r.addView(ImageView(this).apply { setImageDrawable(Apps.icon(this@MainActivity, p)) }, LinearLayout.LayoutParams(dp(30), dp(30)))
            r.addView(tv(label, 15f), lp(0, -2, 1f).apply { leftMargin = dp(12) })
            r.addView(tv(if (has) "w tle: TAK" else "tylko podczas używania", 12f, true, if (has) WARN else ACC))
            r.setOnClickListener {
                if (needShizuku()) return@setOnClickListener
                if (has) Cfg.bgLocOk.remove(p) else Cfg.bgLocOk.add(p)
                Cfg.save()
                work(null) { Engine.setBgLocation(p, !has); null }
            }
            col.addView(r)
        }
        if (loc.any { it.second }) col.addView(btn("Zabierz wszystkim (poza chronionymi)", true) {
            if (needShizuku()) return@btn
            work("Zabieram…") { "Zabrano: " + Engine.revokeAllBgLocation(this) }
        }, lp(-2, -2, top = 10))
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 5 && resultCode != RESULT_OK) { Cfg.netBlock = false; Cfg.save(); render(); toast("Bez zgody na VPN blokada internetu nie działa") }
    }
}

/** Wykres poziomu baterii z ostatnich 24 h (zielone tło = ładowanie, jaśniejsze = włączony ekran). */
class Chart(c: Context, private val pts: List<Engine.Point>) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(cv: Canvas) {
        super.onDraw(cv)
        val w = width.toFloat(); val hh = height.toFloat()
        val now = System.currentTimeMillis(); val from = now - 86_400_000L
        p.style = Paint.Style.FILL; p.color = 0xFF1C1C1E.toInt()
        cv.drawRoundRect(0f, 0f, w, hh, 24f, 24f, p)
        p.color = 0xFF2C2C2E.toInt(); p.strokeWidth = 2f
        for (k in 1..3) cv.drawLine(0f, hh * k / 4, w, hh * k / 4, p)
        val list = pts.filter { it.t >= from - 3600_000L }
        if (list.size < 2) {
            p.color = 0xFF9A9A9E.toInt(); p.textSize = 34f
            cv.drawText("Historia zbiera się – zajrzyj za kilka godzin", 24f, hh / 2, p); return
        }
        fun x(t: Long) = ((t - from).coerceAtLeast(0).toFloat() / 86_400_000f) * w
        fun y(l: Int) = hh - (l / 100f) * (hh - 16f) - 8f
        for (i in 1 until list.size) {
            val a = list[i - 1]; val b = list[i]
            p.color = when { a.charging -> 0x334CD964; a.screen -> 0x22FFFFFF; else -> 0 }
            if (p.color != 0) cv.drawRect(x(a.t), 0f, x(b.t), hh, p)
        }
        val path = Path()
        list.forEachIndexed { i, pt -> if (i == 0) path.moveTo(x(pt.t), y(pt.level)) else path.lineTo(x(pt.t), y(pt.level)) }
        path.lineTo(w, y(list.last().level))
        p.style = Paint.Style.STROKE; p.strokeWidth = 5f; p.color = 0xFF4CD964.toInt()
        cv.drawPath(path, p)
        p.style = Paint.Style.FILL; p.color = 0xFF9A9A9E.toInt(); p.textSize = 26f
        cv.drawText("-24 h", 12f, hh - 10f, p); cv.drawText("teraz", w - 70f, hh - 10f, p); cv.drawText("100%", 12f, 30f, p)
    }
}

@Suppress("unused")
private fun keep(v: ViewGroup, f: FrameLayout) = Unit
