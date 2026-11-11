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
import java.util.Locale

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
    private var selMode = false
    private lateinit var actionBar: LinearLayout
    private var ranking: List<Engine.Use>? = null
    private var rankBusy = false
    private var rankErr = ""
    private var update: Updater.Info? = null
    private var mine: List<MyApps.State>? = null
    private var updMsg = ""
    private var updStatus: TextView? = null
    private val ACTION_INSTALL = "pl.bateria.INSTALL_RESULT"

    private val BG get() = Theme.bg
    private val SURF get() = Theme.surface
    private val FG get() = Theme.fg
    private val DIM get() = Theme.dim
    private val LINE get() = Theme.line
    private val ACC get() = Theme.accent
    private val WARN = 0xFFFF9F0A.toInt()
    private lateinit var rootView: LinearLayout

    private fun applyTheme() {
        Theme.apply(this, Cfg.theme, Cfg.mode)
        window.statusBarColor = Theme.bg; window.navigationBarColor = Theme.surface
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (Theme.dark) 0 else (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
    }

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ -> h.post { onShizukuReady() } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Cfg.init(this)
        applyTheme()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        rootView = root
        sv = ScrollView(this).apply { isFillViewport = true }
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(40)) }
        sv.addView(col)
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        actionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Theme.soft); setPadding(dp(16), dp(10), dp(12), dp(10)); visibility = View.GONE
        }
        root.addView(actionBar, LinearLayout.LayoutParams(-1, -2))
        root.addView(tabBar())
        setContentView(root)
        try { Shizuku.addRequestPermissionResultListener(permListener) } catch (e: Throwable) {}
        if (!Sh.granted()) Sh.request() else onShizukuReady()
        // dawna opcja „Prywatny DNS AdGuard” psuła część sieci Wi-Fi – wyłączamy ją, blokowanie robi Strażnik sieci
        Thread { try { if (Priv.dnsOn(this)) Priv.setDns(this, false) } catch (e: Exception) {} }.start()
        handleInstall(intent)
        intent?.getStringExtra("tab")?.let { tab = it }
        if (intent?.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT)?.let { tab = "priv"; privSub = "links"; linkText = it }
        checkUpdate(false)
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 3)
        render()
    }

    override fun onDestroy() {
        try { Shizuku.removeRequestPermissionResultListener(permListener) } catch (e: Throwable) {}
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleInstall(intent)
        intent.getStringExtra("tab")?.let { tab = it; render() }
        if (intent.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT)?.let { tab = "priv"; privSub = "links"; linkText = it; render() }
    }

    override fun onPause() {
        super.onPause()
        if (vaultOpen && !vaultPicking) { vaultOpen = false; Vault.wipeTemp(this); if (privSub == "vault") privSub = "" }
    }

    override fun onResume() {
        super.onResume()
        vaultPicking = false
        render()
    }

    private fun onShizukuReady() {
        if (!Sh.granted()) return
        Thread {
            Sys.grantAll(this)
            Engine.applyPending()
            h.post { render() }
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
        setTextColor(if (primary) Theme.onAccent else FG)
        background = if (primary) Theme.gradient(dp(24).toFloat()) else round(SURF, 24, 1)
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

    /** Ustawienia systemu: wystarczy stałe uprawnienie albo Shizuku. */
    private fun needWrite(): Boolean = if (Sys.canWrite(this) || Sh.granted()) false else needShizuku()

    private fun needShizuku(): Boolean {
        if (Sh.granted()) return false
        toast(if (Sh.running()) "Zezwól Bastionowi w Shizuku" else "Uruchom Shizuku")
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
        for ((k, n) in listOf("save" to "Bateria", "apps" to "Aplikacje", "use" to "Zużycie", "mem" to "Pamięć", "notif" to "Powiad.", "priv" to "Ochrona")) {
            val on = k == tab
            tabs.addView(tv(n, 11f, on, if (on) Theme.onAccent else DIM).apply {
                gravity = Gravity.CENTER; maxLines = 1
                background = if (on) Theme.gradient(dp(18).toFloat()) else null
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { tab = k; sel.clear(); selMode = false; memSub = ""; memSel.clear(); privSub = ""; render(); sv.scrollTo(0, 0) }
            }, lp(0, -2, 1f).apply { leftMargin = dp(2); rightMargin = dp(2) })
        }
    }

    private fun render() {
        val y = sv.scrollY
        rootView.setBackgroundColor(BG); tabs.setBackgroundColor(SURF)
        col.removeAllViews()
        drawTabs()
        drawActionBar()
        when (tab) {
            "apps" -> renderApps()
            "use" -> renderUse()
            "mem" -> renderMem()
            "priv" -> renderPriv()
            "notif" -> renderNotif()
            else -> renderSave()
        }
        sv.post { sv.scrollTo(0, y) }
    }

    // ------------------------------------------------------------ zakładka: oszczędzanie
    private fun renderSave() {
        val hero = hbox().apply { background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Theme.blend(SURF, Theme.accent, 0.18f), Theme.blend(SURF, Theme.accent2, 0.10f))).apply { cornerRadius = dp(28).toFloat() }; setPadding(dp(18), dp(16), dp(18), dp(16)) }
        val hl = vbox()
        hl.addView(tv("Bastion", 30f, true))
        hl.addView(tv("bateria · pamięć · prywatność", 13f, color = DIM))
        val bmH = getSystemService(BatteryManager::class.java)
        val lvlH = bmH?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
        val (offH, _) = try { Engine.drain(this) } catch (e: Exception) { Pair(0.0, 0.0) }
        val leftH = if (offH > 0.05 && bmH?.isCharging != true) " · ok. ${(lvlH / offH).toInt()} h w uśpieniu" else ""
        hl.addView(tv((if (bmH?.isCharging == true) "⚡ Ładowanie" else "Na baterii") + leftH, 13f, true, ACC), lp(top = 10))
        hl.addView(tv(if (Sh.granted()) "🛡 Shizuku aktywne" else "Shizuku wyłączone", 12f, color = DIM), lp(top = 2))
        hero.addView(hl, lp(0, -2, 1f))
        hero.addView(ProgressRing(this).apply { big = "$lvlH%"; small = "bateria"; value = lvlH / 100f }, LinearLayout.LayoutParams(dp(124), dp(124)))
        col.addView(hero)
        val upd = update
        if (upd != null) {
            val box = hbox().apply { background = round(SURF, 18); setPadding(dp(16), dp(12), dp(12), dp(12)) }
            val busyMsg = updMsg.startsWith("Pobieram") || updMsg.startsWith("Błąd") || updMsg.startsWith("Potwierdź")
            val st = tv(if (busyMsg) updMsg else "Nowa wersja 1.${upd.number}", 15f, true)
            updStatus = st
            box.addView(st, lp(0, -2, 1f))
            box.addView(btn("Zainstaluj", true) { installUpdate() })
            col.addView(box, lp(top = 12))
        }
        val bm = getSystemService(BatteryManager::class.java)
        val lvl = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
        val chg = bm?.isCharging == true
        row("Bateria: $lvl%", (if (chg) "Ładowanie" else "Na baterii") + if (Engine.saverOn(this)) " · oszczędzanie energii włączone" else "", null)
        col.addView(line(), lp(-1, 1))
        val drops = Cfg.drops.count { it > System.currentTimeMillis() - 86_400_000L }
        row(if (Sh.granted()) "Shizuku: aktywne" else if (Sh.running()) "Shizuku: stuknij, aby zezwolić" else "Shizuku: wyłączone",
            (if (Sh.granted()) "Zamrażanie, głęboki sen i zmiany kategorii działają na bieżąco."
            else "Działa dalej: ograniczenia w tle, zamrożone na stałe, 60 Hz, AOD, skanowanie, ranking i strażnik. Czeka: zamrażanie po wygaszeniu, głęboki sen" +
                (if (Cfg.pendingFrom.isNotEmpty()) " i ${Cfg.pendingFrom.size} zmian kategorii" else "") + ". Połącz się z dowolnym Wi-Fi i uruchom Shizuku.") +
                (if (drops > 0) "\nWyłączenia w ostatnich 24 h: $drops" else "")) {
            if (Sh.granted()) return@row
            if (Sh.running()) Sh.request() else packageManager.getLaunchIntentForPackage(Sys.shizukuPkg(this))?.let { startActivity(it) } ?: toast("Zainstaluj Shizuku")
        }
        col.addView(line(), lp(-1, 1))
        val perm = Sys.canWrite(this) && Sys.canDump(this)
        row(if (perm) "Stałe uprawnienia: nadane" else "Stałe uprawnienia: brak",
            if (perm) "Ustawienia, ranking i strażnik działają także bez Shizuku – po restarcie i zmianie Wi-Fi."
            else "Nadadzą się same przy pierwszym działaniu Shizuku.") { if (!perm && Sh.granted()) work("Nadaję…") { Sys.grantAll(this); null } }
        if (Sh.granted() && !BatteryService.alive) BatteryService.start(this)

        header("Szybko")
        col.addView(btn("Ustaw maksymalną oszczędność", true) {
            if (needShizuku()) return@btn
            work("Ustawiam…") { Engine.maxSaving(this); "Gotowe – maksymalna oszczędność" }
        }, lp(-2, -2, top = 6))
        col.addView(tv("60 Hz, ciemny tryb, bez Always On, bez skanowania w tle, głęboki sen, strażnik, automat i ograniczenie usług Samsunga.", 12f, color = DIM), lp(top = 6))

        header("Ekran")
        toggle("60 Hz", "Płynność 120 Hz zużywa sporo prądu. Wyłączenie przywraca poprzednie ustawienie.", Cfg.hz60) {
            if (needWrite()) return@toggle; Cfg.hz60 = it; work(null) { Engine.applyHz(it); Cfg.save(); null }
        }
        toggle("Ciemne wszystkie aplikacje", "Ciemny tryb systemu + wymuszona ciemność aplikacji bez własnego trybu ciemnego", Cfg.dark) {
            if (needShizuku()) return@toggle; Cfg.dark = it; work(null) { Engine.applyDark(it); Cfg.save(); null }
        }
        toggle("Wyłącz Always On Display", "Zegar na wygaszonym ekranie stale zużywa prąd", Cfg.aodOff) {
            if (needWrite()) return@toggle; Cfg.aodOff = it; work(null) { Engine.applyAod(it); Cfg.save(); null }
        }
        col.addView(tv("Najwięcej daje czerń: na ekranie AMOLED czarny piksel jest wyłączony.", 12f, color = DIM), lp(top = 6))

        header("W tle")
        toggle("Skanowanie Wi-Fi i Bluetooth w tle", "Wyłącza szukanie sieci i urządzeń przy wyłączonym Wi-Fi i Bluetooth", Cfg.scanOff) {
            if (needWrite()) return@toggle; Cfg.scanOff = it; work(null) { Engine.applyScan(it); Cfg.save(); null }
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

        header("Tryb ultra")
        toggle("Tryb ultra teraz", "Ekran czarno-biały, bez animacji, 60 Hz, oszczędzanie energii. Na ostatnie procenty baterii.", Cfg.ultraOn) {
            if (!Sys.canWrite(this) && needShizuku()) return@toggle
            work(null) { Power.ultra(this, it); null }
        }
        toggle("Włączaj sam przy niskiej baterii", "Poniżej ${Cfg.ultraAt}% – wyłącza się po podłączeniu ładowarki.", Cfg.ultraAuto) { Cfg.ultraAuto = it; Cfg.save() }
        val ur = hbox()
        for (n in listOf(10, 15, 20, 30)) ur.addView(btn("$n%", Cfg.ultraAt == n) { Cfg.ultraAt = n; Cfg.save(); render() }, lp(-2, -2).apply { rightMargin = dp(6) })
        col.addView(ur, lp(top = 4))

        header("Kondycja baterii i ładowanie")
        val cap = Power.capacity(this)
        val w = Power.nowWatts(this)
        row(if (cap > 0) "Rzeczywista pojemność: ok. $cap mAh (${cap * 100 / Cfg.designMah}% fabrycznej)" else "Rzeczywista pojemność: jeszcze nie zmierzona",
            if (cap > 0) "Mediana z ostatnich ładowań. Fabrycznie ${Cfg.designMah} mAh. Poniżej 80% warto pomyśleć o wymianie baterii."
            else "Zmierzę ją sam podczas ładowania o co najmniej 15% (im dłuższe ładowanie, tym dokładniej).", null)
        col.addView(line(), lp(-1, 1))
        row("Teraz: " + (if (w >= 0) "ładowanie" else "pobór") + " %.1f W".format(Math.abs(w)), "Moc chwilowa. Szybka ładowarka daje 25–45 W, zwykła 5–15 W.") { render() }
        val ses = Power.sessions(this).takeLast(5).reversed()
        if (ses.isNotEmpty()) {
            val df = java.text.SimpleDateFormat("d.MM HH:mm", Locale("pl"))
            for (o in ses) col.addView(tv("${df.format(java.util.Date(o.optLong("t")))}: ${o.optInt("from")}% → ${o.optInt("to")}% w ${o.optInt("min")} min · do ${o.optDouble("w")} W · maks. ${o.optInt("temp") / 10} °C · ${o.optInt("cap")} mAh", 12f, color = DIM), lp(top = 2))
        }
        toggle("Strażnik ładowania", "Powiadomi przy ${Cfg.chargeLimit}% („odłącz”), przy przegrzewaniu i przy bardzo wolnej ładowarce.", Cfg.chargeGuard) { Cfg.chargeGuard = it; Cfg.save() }
        val cr = hbox()
        for (n in listOf(80, 85, 90, 100)) cr.addView(btn("$n%", Cfg.chargeLimit == n) { Cfg.chargeLimit = n; Cfg.save(); render() }, lp(-2, -2).apply { rightMargin = dp(6) })
        col.addView(cr, lp(top = 4))

        header("Profile według sieci Wi-Fi")
        col.addView(tv("Po połączeniu z zapamiętaną siecią Bastion sam ustawia oszczędzanie, Bluetooth i synchronizację. Sieć rozpoznaję po adresie routera – bez lokalizacji.", 12f, color = DIM), lp(top = 2))
        val net = Power.netId(this)
        for (pr in Power.profiles()) {
            val r = hbox().apply { setPadding(0, dp(8), 0, dp(8)) }
            r.addView(tv((if (pr.optString("net") == net && net.isNotEmpty()) "● " else "") + pr.optString("n") + ": " + listOfNotNull(if (pr.optBoolean("saver")) "oszczędzanie" else null,
                if (pr.optBoolean("bt")) "Bluetooth" else "bez Bluetooth", if (pr.optBoolean("sync")) "synchronizacja" else "bez synchronizacji").joinToString(", "), 14f), lp(0, -2, 1f))
            r.addView(btn("Usuń") { Power.removeProfile(pr.optString("n")); render() })
            col.addView(r)
        }
        if (net.isNotEmpty()) col.addView(btn("Zapisz obecną sieć jako profil") { profileSheet(net) }, lp(-2, -2, top = 6))
        else col.addView(tv("Połącz się z Wi-Fi, żeby zapisać profil tej sieci.", 12f, color = DIM), lp(top = 4))

        header("Wydajność")
        val sc = Power.animScale(this)
        col.addView(tv("Animacje systemu", 16f), lp(top = 6))
        val an = hbox()
        for ((v, n) in listOf("0" to "Wyłączone", "0.5" to "Szybkie", "1" to "Zwykłe")) an.addView(btn(n, sc == v || (v == "1" && sc == "1.0")) {
            if (!Sys.canWrite(this) && needShizuku()) return@btn; work(null) { Power.animations(this, v); null }
        }, lp(-2, -2).apply { rightMargin = dp(6) })
        col.addView(an, lp(top = 4))
        toggle("Nie ubijaj procesów w tle (Termux, Shizuku)", "Android zabija długie zadania aplikacji takich jak Termux. Wyłączam ten limit – opcja niedostępna w zwykłych ustawieniach.", Cfg.phantom) {
            if (needShizuku()) return@toggle
            Cfg.phantom = it; Cfg.save(); work(null) { Power.phantomFix(it); null }
        }

        header("Wi-Fi i Bluetooth")
        val wifiOn = (applicationContext.getSystemService(android.net.wifi.WifiManager::class.java))?.isWifiEnabled == true
        val btOn = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.isEnabled == true
        val wb = hbox()
        wb.addView(btn(if (wifiOn) "Wi-Fi: włączone" else "Wi-Fi: wyłączone", wifiOn) {
            if (needShizuku()) return@btn; work(null) { Sh.run("svc wifi ${if (wifiOn) "disable" else "enable"}"); Thread.sleep(800); null }
        }, lp(0, -2, 1f).apply { rightMargin = dp(8) })
        wb.addView(btn(if (btOn) "Bluetooth: włączony" else "Bluetooth: wyłączony", btOn) {
            if (needShizuku()) return@btn; work(null) { Engine.bluetooth(!btOn); Thread.sleep(800); null }
        }, lp(0, -2, 1f))
        col.addView(wb, lp(top = 6))
        toggle("Wyłączaj Bluetooth, gdy nic nie jest połączone", "Po 10 min wygaszonego ekranu bez słuchawek, auta czy zegarka. Nie wyłączy, gdy coś jest połączone.", Cfg.btAuto) {
            Cfg.btAuto = it; Cfg.save()
        }
        toggle("Wyłączaj Wi-Fi poza zasięgiem sieci", "Po 15 min bez połączenia z żadną siecią (np. poza domem i pracą). Uwaga: wyłączenie Wi-Fi może zatrzymać Shizuku.", Cfg.wifiAuto) {
            Cfg.wifiAuto = it; Cfg.save()
        }
        col.addView(btn("Kto ma dostęp do Wi-Fi i Bluetooth →") { tab = "priv"; privSub = "g:bt"; render() }, lp(-2, -2, top = 6))

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

        header("Wygląd")
        val tr = hbox()
        for (pz in Theme.PRESETS) {
            val (c1, c2) = Theme.colorsOf(this, pz)
            val on = Cfg.theme == pz.id
            val chip = vbox().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(4), dp(4), dp(4), dp(4)) }
            chip.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2)).apply { shape = GradientDrawable.OVAL; if (on) setStroke(dp(3), FG) } }, LinearLayout.LayoutParams(dp(44), dp(44)))
            chip.addView(tv(pz.name, 11f, on, if (on) FG else DIM))
            chip.setOnClickListener { Cfg.theme = pz.id; Cfg.save(); applyTheme(); render() }
            tr.addView(chip, LinearLayout.LayoutParams(dp(78), -2))
        }
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tr) }, lp(top = 6))
        val mr = hbox()
        for ((k, n) in Theme.MODES) mr.addView(btn(n, Cfg.mode == k) { Cfg.mode = k; Cfg.save(); applyTheme(); render() }, lp(-2, -2).apply { rightMargin = dp(6) })
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(mr) }, lp(top = 8))

        header("Kopia ustawień")
        val bk = hbox()
        bk.addView(btn("Eksportuj") {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "bastion-ustawienia.json"), 21)
        }, lp(-2, -2).apply { rightMargin = dp(8) })
        bk.addView(btn("Importuj") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 22)
        })
        col.addView(bk, lp(top = 6))
        col.addView(tv("Kategorie aplikacji, blokady internetu, listy domen i wszystkie przełączniki – do przeniesienia na inny telefon.", 12f, color = DIM), lp(top = 6))

        header("Moje aplikacje")
        col.addView(tv("Instalacja i aktualizacje wszystkich Twoich aplikacji kanałem ADB (Shizuku): bez okna instalatora i bez ostrzeżeń Play Protect. Tę drogę Google zostawia otwartą także po zaostrzeniu zasad instalowania.", 12f, color = DIM), lp(top = 2))
        val ms = mine
        if (ms == null) {
            col.addView(btn("Sprawdź moje aplikacje", true) {
                if (Cfg.token.isBlank()) { toast("Najpierw wklej token GitHub niżej"); return@btn }
                work("Sprawdzam…") { mine = MyApps.check(this); null }
            }, lp(-2, -2, top = 8))
        } else {
            val todo = ms.filter { it.latest != null && it.latest.number > it.installed && it.app.pkg != packageName }
            for (st in ms) {
                val r = hbox().apply { setPadding(0, dp(8), 0, dp(8)) }
                if (st.installed >= 0) r.addView(ImageView(this).apply { setImageDrawable(Apps.icon(this@MainActivity, st.app.pkg)) }, LinearLayout.LayoutParams(dp(32), dp(32)))
                else r.addView(View(this), LinearLayout.LayoutParams(dp(32), dp(32)))
                val l = vbox()
                l.addView(tv(st.app.name, 15f, true))
                l.addView(tv((if (st.installed >= 0) "zainstalowana 1.${st.installed}" else "niezainstalowana") +
                    (st.latest?.let { " · najnowsza 1.${it.number}" } ?: if (st.error.isNotEmpty()) " · ${st.error.take(40)}" else ""), 12f, color = DIM))
                r.addView(l, lp(0, -2, 1f).apply { leftMargin = dp(12) })
                val lt = st.latest
                when {
                    lt == null -> {}
                    st.installed < 0 -> r.addView(btn("Zainstaluj", true) { work("Instaluję…") { val x = MyApps.install(this, st) { }; mine = null; x } })
                    lt.number > st.installed && st.app.pkg == packageName -> r.addView(btn("Aktualizuj", true) { installUpdate() })
                    lt.number > st.installed -> r.addView(btn("Aktualizuj", true) { work("Aktualizuję…") { val x = MyApps.install(this, st) { }; mine = null; x } })
                    else -> r.addView(tv("✓ aktualna", 13f, true, ACC))
                }
                col.addView(r)
            }
            if (todo.size > 1) col.addView(btn("Aktualizuj wszystkie (${todo.size})", true) {
                work("Aktualizuję…") { val res = todo.map { MyApps.install(this, it) { } }; mine = null; res.joinToString("\n") }
            }, lp(-2, -2, top = 8))
            col.addView(btn("Sprawdź ponownie") { mine = null; render() }, lp(-2, -2, top = 8))
        }
        toggle("Aktualizuj moje aplikacje automatycznie", "Raz dziennie w tle, gdy działa Shizuku – z powiadomieniem, co zostało zaktualizowane.", Cfg.mineAuto) { Cfg.mineAuto = it; Cfg.save() }

        header("Aktualizacje")
        val ver = vbox().apply { setPadding(0, dp(10), 0, dp(4)) }
        val vr = hbox()
        vr.addView(tv("Zainstalowana wersja", 16f), lp(0, -2, 1f))
        vr.addView(tv(versionName(), 16f, true))
        ver.addView(vr)
        col.addView(ver)
        if (Cfg.token.isBlank()) {
            col.addView(tv("Wklej token GitHub (ten sam co w Termuksie). Bastion będzie sam sprawdzać i instalować nowe wersje.", 13f, color = DIM), lp(top = 8))
            val e = EditText(this).apply {
                hint = "ghp_…"; setTextColor(FG); setHintTextColor(DIM); textSize = 15f; setSingleLine()
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                background = round(SURF, 14); setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            col.addView(e, lp(top = 8))
            col.addView(btn("Zapisz token") { Cfg.token = e.text.toString().trim(); Cfg.save(); checkUpdate(true) }, lp(-2, -2, top = 8))
        } else {
            val tr = hbox()
            tr.addView(tv("Token GitHub zapisany", 16f), lp(0, -2, 1f))
            tr.addView(btn("Usuń") { Cfg.token = ""; Cfg.save(); update = null; updMsg = ""; render() })
            col.addView(tr, lp(top = 6))
            val st = tv(updMsg, 14f, color = DIM)
            updStatus = st
            if (updMsg.isNotEmpty()) col.addView(st, lp(top = 6))
            val u2 = update
            if (u2 != null) col.addView(btn("Pobierz i zainstaluj 1.${u2.number}", true) { installUpdate() }, lp(-2, -2, top = 8))
            else col.addView(btn("Sprawdź aktualizacje") { checkUpdate(true) }, lp(-2, -2, top = 8))
        }
        col.addView(btn("Otwórz budowanie na GitHubie") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${Updater.REPO}/actions"))) }, lp(-2, -2, top = 8))
        col.addView(tv("Gdy działa Shizuku, aktualizacja instaluje się sama, bez okna instalatora i bez ostrzeżenia Google Play Protect.", 12f, color = DIM), lp(top = 8))
        col.addView(tv("Bastion ${versionName()}", 12f, color = DIM).apply { gravity = Gravity.CENTER }, lp(top = 18))
    }

    // ------------------------------------------------------------ aktualizacje (jak w Kartach i Audiotece)
    private fun installedVersion(): Long = try { packageManager.getPackageInfo(packageName, 0).longVersionCode } catch (e: Exception) { 0L }

    private fun versionName(): String = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }

    private fun checkUpdate(manual: Boolean) {
        val token = Cfg.token
        if (token.isBlank()) { if (manual) toast("Najpierw wklej token GitHub"); return }
        if (manual) { updMsg = "Sprawdzam…"; render() }
        Thread {
            val res: Pair<Updater.Info?, String> = try {
                val i = Updater.check(token)
                if (i != null && i.number > installedVersion()) Pair(i, "Dostępna wersja 1.${i.number}") else Pair(null, "Masz najnowszą wersję")
            } catch (e: Exception) {
                Pair(null, "Błąd: " + (e.message ?: "brak połączenia"))
            }
            h.post {
                update = res.first
                if (manual || res.first != null) { updMsg = res.second; render() }
            }
        }.start()
    }

    private fun installUpdate() {
        val info = update ?: return
        if (!Sh.granted() && !packageManager.canRequestPackageInstalls()) {
            toast("Zezwól Bastionowi na instalowanie aplikacji, potem wróć i stuknij ponownie")
            try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) } catch (e: Exception) {}
            return
        }
        updMsg = "Pobieram… 0%"
        render()
        val shell = Sh.granted()
        // przez Shizuku instalacja idzie kanałem ADB – bez okna instalatora i bez ostrzeżenia Play Protect
        val file = java.io.File(if (shell) (getExternalFilesDir(null) ?: cacheDir) else cacheDir, "update.apk")
        Thread {
            try {
                Updater.download(Cfg.token, info, file) { pr -> h.post { updMsg = "Pobieram… $pr%"; updStatus?.text = updMsg } }
                if (shell) {
                    h.post { updMsg = "Instaluję…"; updStatus?.text = updMsg }
                    Sys.put(this, "global", "verifier_verify_adb_installs", "0")
                    val out = Sh.run("pm install -r \"${file.path}\"")
                    if (!out.contains("Success")) {
                        // kanał ADB zawiódł – zwykły instalator
                        installApk(file)
                        h.post { updMsg = "Potwierdź instalację"; updStatus?.text = updMsg }
                    }
                    return@Thread
                }
                installApk(file)
                h.post { updMsg = "Potwierdź instalację"; updStatus?.text = updMsg }
            } catch (e: Exception) {
                h.post { updMsg = "Błąd: " + (e.message ?: "pobieranie nieudane"); render() }
            }
        }.start()
    }

    private fun installApk(file: java.io.File) {
        val pi = packageManager.packageInstaller
        val id = pi.createSession(android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        val session = pi.openSession(id)
        try {
            session.openWrite("bateria", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val i = Intent(this, MainActivity::class.java).setAction(ACTION_INSTALL)
            val pend = android.app.PendingIntent.getActivity(this, 7, i, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE)
            session.commit(pend.intentSender)
        } finally {
            session.close()
        }
    }

    private fun handleInstall(i: Intent?) {
        if (i?.action != ACTION_INSTALL) return
        when (i.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -999)) {
            android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) startActivity(confirm)
            }
            android.content.pm.PackageInstaller.STATUS_SUCCESS -> toast("Zaktualizowano")
            -999 -> {}
            else -> { updMsg = "Instalacja nieudana: " + (i.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "nieznany błąd"); render() }
        }
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
                for (hh in listOf(20, 21, 22, 23, 0, 1, 5, 6, 7, 8)) r.addView(tv("$hh", 15f, hh == cur, if (hh == cur) Theme.onAccent else FG).apply {
                    gravity = Gravity.CENTER; background = if (hh == cur) Theme.gradient(dp(14).toFloat()) else round(BG, 14, 1)
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
        col.addView(tv(if (selMode) "Stukaj aplikacje, żeby je zaznaczyć. Potem „Przenieś do…” na dole – albo przytrzymaj dowolną zaznaczoną i przeciągnij do kategorii."
            else "Stuknij aplikację, żeby zmienić jej kategorię. „Zaznacz kilka” – żeby przenieść wiele naraz. Możesz też przytrzymać i przeciągnąć.", 13f, color = DIM), lp(top = 6))
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
        r.addView(View(this), lp(0, 1, 1f))
        r.addView(btn(if (selMode) "Gotowe" else "☑ Zaznacz kilka", selMode) {
            selMode = !selMode
            if (!selMode) sel.clear()
            render()
        })
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
            if (selMode && list.isNotEmpty()) {
                val all = list.all { it.pkg in sel }
                sec.addView(tv(if (all) "☑ Odznacz wszystkie w tej kategorii" else "☐ Zaznacz wszystkie w tej kategorii", 14f, true, ACC).apply {
                    setPadding(0, dp(8), 0, dp(4))
                    setOnClickListener { if (all) list.forEach { sel.remove(it.pkg) } else list.forEach { sel.add(it.pkg) }; render() }
                })
            }
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
        if (on) r.background = round(Theme.soft, 12, 1, ACC)
        if (selMode) r.addView(tv(if (on) "☑" else "☐", 22f, true, if (on) ACC else DIM).apply { setPadding(0, 0, dp(10), 0) })
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
            if (selMode || sel.isNotEmpty()) { if (!sel.add(a.pkg)) sel.remove(a.pkg); selMode = true; render() }
            else moveSheet(listOf(a.pkg))
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
                DragEvent.ACTION_DRAG_ENTERED -> { view.background = round(Theme.soft, 18, 2, ACC); true }
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
        sel.clear()
        selMode = false
        val live = Sh.granted()
        work(if (pk.size > 1) "Przenoszę ${pk.size} aplikacji…" else null) {
            Engine.setCat(this, pk, cat)
            if (live) "→ " + Cat.name(cat) + (if (pk.size > 1) " (${pk.size})" else "")
            else "Zapisane – zadziała, gdy uruchomisz Shizuku"
        }
    }

    private fun moveSheet(pk: List<String>) {
        sheet(if (pk.size == 1) (apps.firstOrNull { it.pkg == pk[0] }?.label ?: pk[0]) else "Przenieś ${pk.size} aplikacji") { c, d ->
            if (pk.size == 1) {
                c.addView(btn("☑ Zaznacz kilka (od tej aplikacji)") { d.dismiss(); sel.add(pk[0]); selMode = true; render() }, lp(top = 10))
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

        val rk = ranking
        if (rk == null) {
            header("Kto zjada baterię")
            if (rankBusy) col.addView(tv("Liczę zużycie…", 14f, color = DIM), lp(top = 6))
            else if (rankErr.isNotEmpty()) col.addView(tv("⚠ $rankErr", 13f, false, WARN), lp(top = 6))
            col.addView(btn("Policz ponownie", true) { loadRanking(true) }, lp(-2, -2, top = 8))
            if (!rankBusy && rankErr.isEmpty()) loadRanking(false)
        } else {
            fun list(title: String, hint: String, items: List<Engine.Use>, value: (Engine.Use) -> Double) {
                header(title)
                col.addView(tv(hint, 12f, color = DIM), lp(top = 2))
                val sum = items.sumOf(value).coerceAtLeast(0.01)
                if (items.isEmpty()) col.addView(tv("Brak danych – telefon niedawno się ładował.", 13f, color = DIM), lp(top = 6))
                for ((k, u) in items.withIndex()) {
                    val r = hbox().apply { setPadding(0, dp(8), 0, dp(8)) }
                    r.addView(tv("${k + 1}.", 14f, true, DIM).apply { minWidth = dp(26) })
                    r.addView(ImageView(this).apply { setImageDrawable(Apps.icon(this@MainActivity, u.pkg)) }, LinearLayout.LayoutParams(dp(34), dp(34)))
                    val l = vbox()
                    l.addView(tv(u.label, 15f).apply { maxLines = 1 })
                    l.addView(tv(String.format(Locale.US, "%.0f%% · %.1f mAh · ekran %d min · %s", value(u) * 100 / sum, value(u), u.fgMin, Cat.name(Cfg.cat(u.pkg))), 12f, color = DIM))
                    r.addView(l, lp(0, -2, 1f).apply { leftMargin = dp(10) })
                    r.setOnClickListener { moveSheet(listOf(u.pkg)) }
                    col.addView(r)
                }
            }
            list("Zużycie w tle (od ostatniego ładowania)", "Co aplikacje zużywają, gdy z nich nie korzystasz – to tu szukaj winowajców. Stuknij, żeby zmienić kategorię.",
                rk.filter { it.bg > 0.05 }.sortedByDescending { it.bg }.take(15)) { it.bg }
            list("Zużycie ogólne (od ostatniego ładowania)", "Wszystko razem: na ekranie i w tle.",
                rk.sortedByDescending { it.mah }.take(15)) { it.mah }
            col.addView(btn("Odśwież") { loadRanking(true) }, lp(-2, -2, top = 8))
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
        if (requestCode == 7) { if (resultCode == RESULT_OK) { Cfg.monitor = true; Cfg.save(); NetBlock.monitor(this, true) } else toast("Bez zgody strażnik sieci nie działa"); h.postDelayed({ render() }, 800) }
        if (requestCode == 41 && resultCode == RESULT_OK && data != null) {
            val uris = ArrayList<Uri>()
            data.clipData?.let { cd -> for (k in 0 until cd.itemCount) uris.add(cd.getItemAt(k).uri) }
            if (uris.isEmpty()) data.data?.let { uris.add(it) }
            work("Szyfruję…") {
                var n = 0
                for (x in uris) try {
                    var name = "plik"
                    contentResolver.query(x, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0) ?: name }
                    contentResolver.openInputStream(x)!!.use { Vault.add(this, name, it) }
                    n++
                    if (vaultDelete) try { android.provider.DocumentsContract.deleteDocument(contentResolver, x) } catch (e: Exception) {}
                } catch (e: Exception) {
                }
                "Dodano do sejfu: $n" + if (vaultDelete) " (oryginały usunięte, jeśli system pozwolił)" else ""
            }
        }
        if (requestCode == 51 && resultCode == RESULT_OK && data?.data != null) {
            val t = data.data!!
            try { contentResolver.takePersistableUriPermission(t, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (e: Exception) {}
            Cfg.backupTree = t.toString(); Cfg.save(); render()
        }
        val u = data?.data
        if (requestCode == 21 && resultCode == RESULT_OK && u != null) try {
            Cfg.save(); contentResolver.openOutputStream(u)?.use { it.write(Cfg.file(this).readBytes()) }; toast("Zapisano kopię ustawień")
        } catch (e: Exception) { toast("Błąd zapisu") }
        if (requestCode == 22 && resultCode == RESULT_OK && u != null) try {
            val txt = contentResolver.openInputStream(u)!!.use { String(it.readBytes()) }
            org.json.JSONObject(txt).getJSONObject("cats")
            Cfg.file(this).writeText(txt)
            Cfg.reload(this)
            work("Przywracam ustawienia…") {
                // zastosuj kategorie w systemie
                val by = Cfg.cats.entries.groupBy({ it.value }, { it.key })
                Cfg.cats.clear()
                for ((cat, pkgs) in by) Engine.setCat(this, pkgs.filter { Apps.installed(packageManager, it) }, cat)
                "Przywrócono ustawienia"
            }
        } catch (e: Exception) { toast("To nie jest kopia ustawień Bastionu") }
    }

    // ================================================================== PAMIĘĆ (jak CCleaner Pro, i więcej)
    private var memSub = ""
    private var memBusy = false
    private var memMsg = ""
    private val memSel = LinkedHashSet<String>()
    private var overview: Mem.Overview? = null
    private var junkList: List<Mem.Junk>? = null
    private val junkOn = HashSet<Int>(listOf(0, 1, 2, 3, 4))
    private var dupGroups: List<List<FileItem>>? = null
    private var simGroups: List<List<FileItem>>? = null
    private var appSizes: List<Mem.AppSize>? = null
    private var appSort = "size"
    private var showMax = 200
    private val thumbs = android.util.LruCache<String, android.graphics.Bitmap>(120)
    private val thumbPool = java.util.concurrent.Executors.newFixedThreadPool(3)

    /** Zadanie pamięci w tle z komunikatem postępu. */
    private fun memWork(msg: String, after: (() -> Unit)? = null, job: ((String) -> Unit) -> String?) {
        if (memBusy) return
        memBusy = true; memMsg = msg; render()
        Thread {
            var ok = true
            val r = try { job { m -> h.post { memMsg = m; memStatus?.text = m } } } catch (e: Throwable) { ok = false; "Błąd: " + (e.message ?: "") }
            h.post { memBusy = false; memMsg = ""; if (r != null) toast(r); if (ok && after != null) after() else render() }
        }.start()
    }

    private var memStatus: TextView? = null

    private fun needFiles(): Boolean {
        if (Mem.allFiles()) return false
        if (Sh.granted()) { Thread { Sys.grantAll(this) }.start(); toast("Nadaję dostęp do plików – stuknij jeszcze raz"); return true }
        toast("Zezwól na dostęp do wszystkich plików")
        try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) } catch (e: Exception) {}
        return true
    }

    private fun ensureScan(then: () -> Unit) {
        if (needFiles()) return
        if (Mem.scannedAt > System.currentTimeMillis() - 10 * 60_000L) { then(); return }
        memWork("Przeglądam pamięć…", after = then) { p -> Mem.scan(p); null }
    }

    private fun card(title: String, sub: String, right: String = "", f: () -> Unit) {
        val r = hbox().apply { background = round(SURF, 18); setPadding(dp(16), dp(14), dp(14), dp(14)); setOnClickListener { f() } }
        val l = vbox()
        l.addView(tv(title, 16f, true))
        if (sub.isNotEmpty()) l.addView(tv(sub, 12f, color = DIM), lp(top = 2))
        r.addView(l, lp(0, -2, 1f))
        if (right.isNotEmpty()) r.addView(tv(right, 15f, true, ACC).apply { setPadding(dp(8), 0, 0, 0) })
        r.addView(tv("›", 22f, true, DIM).apply { setPadding(dp(8), 0, 0, 0) })
        col.addView(r, lp(top = 10))
    }

    private fun backRow(title: String) {
        val r = hbox()
        r.addView(tv("‹", 30f, true).apply { setPadding(0, 0, dp(14), 0); setOnClickListener { memSub = ""; memSel.clear(); showMax = 200; lastPre = ""; render() } })
        r.addView(tv(title, 26f, true), lp(0, -2, 1f))
        col.addView(r)
    }

    private fun renderMem() {
        if (memBusy) {
            col.addView(tv("Pamięć", 34f, true))
            memStatus = tv(memMsg, 16f, true)
            col.addView(memStatus, lp(top = 24))
            col.addView(tv("To może chwilę potrwać…", 13f, color = DIM), lp(top = 6))
            return
        }
        when (memSub) {
            "junk" -> return renderJunk()
            "apps" -> return renderAppSizes()
            "trash" -> return renderTrash()
            "info" -> return renderInfo()
            "dups" -> return renderGroups("Duplikaty", "Zostawiam najstarszy plik z każdej grupy, resztę zaznaczam.", dupGroups ?: emptyList(), keepFirst = true)
            "similar" -> return renderGroups("Podobne zdjęcia", "Serie i prawie takie same ujęcia. Zostawiam najostrzejsze z każdej grupy.", simGroups ?: emptyList(), keepFirst = true)
            "blurry" -> return renderFiles("Nieostre zdjęcia", "Poruszone i nieostre ujęcia.", Mem.blurry(), true)
            "dark" -> return renderFiles("Ciemne zdjęcia", "Zdjęcia prawie całkiem czarne (np. z kieszeni).", Mem.dark(), true)
            "shots" -> return renderFiles("Stare zrzuty ekranu", "Starsze niż 14 dni.", Mem.oldScreenshots(), true)
            "msg" -> return renderFiles("Media z komunikatorów", "Zdjęcia, filmy i notatki głosowe z WhatsAppa, Telegrama, Messengera i Vibera.", Mem.messengerMedia(), false)
            "videos" -> return renderFiles("Duże filmy", "Filmy powyżej 100 MB.", Mem.largeVideos(), false)
            "big" -> return renderFiles("Duże pliki", "Powyżej 50 MB.", Mem.bigFiles(), false)
            "dl" -> return renderFiles("Stare pobrane", "Pliki w Pobranych starsze niż 30 dni.", Mem.oldDownloads(), false)
            "opt" -> return renderOptimize()
            "map" -> return renderMap()
            "wa" -> return renderWa()
            "boot" -> return renderBoot()
            "ram" -> return renderRam()
            "backup" -> return renderBackup()
        }
        col.addView(tv("Pamięć", 34f, true))
        if (!Mem.allFiles()) {
            col.addView(tv("Potrzebny dostęp do wszystkich plików – żeby znaleźć śmieci, duplikaty i duże pliki.", 14f, color = DIM), lp(top = 8))
            col.addView(btn("Zezwól", true) { needFiles() }, lp(-2, -2, top = 8))
        }
        // przegląd zajętości
        val ov = overview
        val st = android.os.StatFs(android.os.Environment.getDataDirectory().path)
        val used = st.totalBytes - st.availableBytes
        col.addView(ProgressRing(this).apply { big = "${(used * 100 / st.totalBytes.coerceAtLeast(1))}%"; small = "zajęte"; value = used.toFloat() / st.totalBytes.coerceAtLeast(1) }, lp(-1, dp(160), top = 8))
        col.addView(tv("${Mem.fmt(used)} z ${Mem.fmt(st.totalBytes)} zajęte · wolne ${Mem.fmt(st.availableBytes)}", 15f, true).apply { gravity = Gravity.CENTER }, lp(top = 8))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = round(LINE, 8) }
        val colors = listOf(0xFF4CD964.toInt(), 0xFF5AC8FA.toInt(), 0xFFFF9F0A.toInt(), 0xFFAF52DE.toInt(), 0xFFFF375F.toInt(), 0xFFFFD60A.toInt(), 0xFF8E8E93.toInt(), 0xFF636366.toInt())
        if (ov != null) {
            ov.cats.forEachIndexed { i, (_, b) -> if (b > 0) bar.addView(View(this).apply { setBackgroundColor(colors[i % colors.size]) }, LinearLayout.LayoutParams(0, dp(14), b.toFloat())) }
            if (ov.free > 0) bar.addView(View(this), LinearLayout.LayoutParams(0, dp(14), ov.free.toFloat()))
        } else {
            bar.addView(View(this).apply { setBackgroundColor(0xFF8E8E93.toInt()) }, LinearLayout.LayoutParams(0, dp(14), used.toFloat()))
            bar.addView(View(this), LinearLayout.LayoutParams(0, dp(14), st.availableBytes.toFloat()))
        }
        col.addView(bar, lp(-1, dp(14), top = 8))
        if (ov != null) for ((i, cb) in ov.cats.withIndex()) {
            if (cb.second <= 0) continue
            val r = hbox().apply { setPadding(0, dp(4), 0, 0) }
            r.addView(View(this).apply { background = round(colors[i % colors.size], 4) }, LinearLayout.LayoutParams(dp(12), dp(12)))
            r.addView(tv(cb.first, 14f), lp(0, -2, 1f).apply { leftMargin = dp(10) })
            r.addView(tv(Mem.fmt(cb.second), 14f, true))
            col.addView(r)
        } else col.addView(btn("Przeanalizuj pamięć", true) {
            ensureScan { memWork("Liczę…") { overview = Mem.overview(this); null } }
        }, lp(-2, -2, top = 10))

        header("Szybkie czyszczenie")
        card("🧹 Śmieci i pamięć podręczna", "Pamięć podręczna aplikacji, miniatury, pliki tymczasowe, zbędne APK, pozostałości po aplikacjach, puste foldery",
            junkList?.let { Mem.fmt(it.sumOf { j -> j.size }) } ?: "") {
            ensureScan { memWork("Szukam śmieci…") { junkList = Mem.junk(this); h.post { memSub = "junk"; render() }; null } }
        }

        header("Zdjęcia i filmy")
        card("👯 Duplikaty", "Identyczne pliki w kilku miejscach", dupGroups?.let { g -> Mem.fmt(g.sumOf { it.drop(1).sumOf { f -> f.size } }) } ?: "") {
            ensureScan { memWork("Szukam duplikatów…") { p -> dupGroups = Mem.duplicates(p); h.post { memSub = "dups"; render() }; null } }
        }
        fun photosThen(sub: String) = ensureScan {
            if (Mem.photos.isNotEmpty()) { memSub = sub; render() }
            else memWork("Analizuję zdjęcia…") { p -> Mem.analyzePhotos(p); simGroups = Mem.similar(); h.post { memSub = sub; render() }; null }
        }
        card("🖼 Podobne zdjęcia", "Serie i prawie takie same ujęcia", simGroups?.let { g -> "${g.size} grup" } ?: "") { photosThen("similar") }
        card("🌫 Nieostre zdjęcia", "Poruszone ujęcia") { photosThen("blurry") }
        card("🌑 Ciemne zdjęcia", "Zdjęcia z kieszeni i prawie czarne") { photosThen("dark") }
        card("📱 Stare zrzuty ekranu", "Starsze niż 14 dni") { ensureScan { memSub = "shots"; render() } }
        card("💬 Media z komunikatorów", "WhatsApp, Telegram, Messenger, Viber") { ensureScan { memSub = "msg"; render() } }
        card("🎬 Duże filmy", "Powyżej 100 MB") { ensureScan { memSub = "videos"; render() } }
        card("🗜 Optymalizator zdjęć", "Zmniejsza duże zdjęcia bez widocznej utraty jakości (oryginały w koszu)") { ensureScan { memSub = "opt"; render() } }

        header("Pliki i aplikacje")
        card("📦 Duże pliki", "Powyżej 50 MB") { ensureScan { memSub = "big"; render() } }
        card("⬇ Stare pobrane", "Pobrane ponad 30 dni temu") { ensureScan { memSub = "dl"; render() } }
        card("📲 Menedżer aplikacji", "Rozmiar, nieużywane, odinstalowanie, czyszczenie pamięci podręcznej, hibernacja kilku naraz") {
            memWork("Liczę rozmiary aplikacji…") { appSizes = Mem.appSizes(this); h.post { memSub = "apps"; render() }; null }
        }
        card("🗺 Mapa pamięci", "Które foldery zajmują najwięcej – z wchodzeniem w głąb") { ensureScan { mapPath = Mem.root.path; memSub = "map"; render() } }
        card("🟢 WhatsApp według rodzaju", "Filmy, zdjęcia, wiadomości głosowe, dokumenty, naklejki") { ensureScan { memSub = "wa"; render() } }
        card("🚀 Autostart", "Które aplikacje uruchamiają się same po starcie telefonu") { memSub = "boot"; bootList = null; render() }
        card("🧠 Pamięć RAM", "Najcięższe aplikacje w pamięci, z zatrzymywaniem") { memSub = "ram"; ramList = null; render() }
        card("💾 Kopia zdjęć bez chmury", "Nowe zdjęcia i filmy na pendrive, kartę albo dysk w sieci domowej") { ensureScan { memSub = "backup"; render() } }
        card("🗑 Kosz", "Usunięte pliki trzymam 30 dni – można przywrócić", Mem.fmt(Mem.trashSize())) { memSub = "trash"; render() }

        header("Automatycznie")
        toggle("Czyść co tydzień", "Pamięć podręczna aplikacji, miniatury, pliki tymczasowe, puste foldery i kosz starszy niż 30 dni – w tle, z powiadomieniem.", Cfg.autoClean) {
            Cfg.autoClean = it; Cfg.save(); Engine.scheduleClean(this)
        }
        toggle("Sprzątaj Pobrane", "Pliki w Pobranych starsze niż 60 dni trafiają do kosza (30 dni na przywrócenie).", Cfg.dlAuto) { Cfg.dlAuto = it; Cfg.save(); Engine.scheduleClean(this) }
        toggle("Ostrzegaj, gdy kończy się miejsce", "Powiadomienie, gdy wolne będzie poniżej 10%.", Cfg.storageAlert) {
            Cfg.storageAlert = it; Cfg.save(); Engine.scheduleClean(this)
        }

        header("Telefon")
        card("ℹ Informacje o telefonie", "RAM, procesor, temperatura, kondycja i cykle baterii") { memSub = "info"; render() }
    }

    private fun renderJunk() {
        backRow("Śmieci")
        val js = junkList ?: emptyList()
        val appCache = appSizes?.sumOf { it.cache }
        // pamięć podręczna aplikacji
        val r0 = hbox().apply { setPadding(0, dp(12), 0, dp(12)) }
        val l0 = vbox()
        l0.addView(tv("Pamięć podręczna aplikacji", 16f, true))
        l0.addView(tv(if (Sh.granted()) "Wszystkie aplikacje naraz (Shizuku)" else "Wymaga Shizuku", 12f, color = DIM))
        r0.addView(l0, lp(0, -2, 1f))
        r0.addView(tv(appCache?.let { Mem.fmt(it) } ?: "?", 15f, true, ACC))
        col.addView(r0)
        if (appCache == null) Thread { val a = Mem.appSizes(this); h.post { appSizes = a; if (memSub == "junk") render() } }.start()
        for ((i, j) in js.withIndex()) {
            col.addView(line(), lp(-1, 1))
            val r = hbox().apply { setPadding(0, dp(12), 0, dp(12)) }
            val on = i in junkOn
            r.addView(tv(if (on) "☑" else "☐", 22f, true, if (on) ACC else DIM).apply { setPadding(0, 0, dp(12), 0) })
            val l = vbox()
            l.addView(tv(j.label + " (${j.count})", 16f, true))
            l.addView(tv(j.hint, 12f, color = DIM))
            r.addView(l, lp(0, -2, 1f))
            r.addView(tv(Mem.fmt(j.size), 15f, true, ACC))
            r.setOnClickListener { if (!junkOn.add(i)) junkOn.remove(i); render() }
            col.addView(r)
        }
        val total = js.filterIndexed { i, _ -> i in junkOn }.sumOf { it.size } + (if (Sh.granted()) appCache ?: 0L else 0L)
        col.addView(btn("Wyczyść (${Mem.fmt(total)})", true) {
            memWork("Czyszczę…") {
                val before = android.os.StatFs(android.os.Environment.getDataDirectory().path).availableBytes
                Mem.trimAllCaches()
                Mem.cleanJunk(this, js.filterIndexed { i, _ -> i in junkOn })
                val after = android.os.StatFs(android.os.Environment.getDataDirectory().path).availableBytes
                junkList = null; appSizes = null; overview = null
                h.post { memSub = "" }
                "Zwolniono ${Mem.fmt((after - before).coerceAtLeast(0))}"
            }
        }, lp(-2, -2, top = 16))
    }

    private fun thumb(iv: ImageView, f: FileItem) {
        iv.tag = f.path
        thumbs.get(f.path)?.let { iv.setImageBitmap(it); return }
        iv.setImageDrawable(null)
        thumbPool.execute {
            val bm = try {
                val sz = android.util.Size(dp(96), dp(96))
                when (f.ext) {
                    in Mem.IMG -> android.media.ThumbnailUtils.createImageThumbnail(java.io.File(f.path), sz, null)
                    in Mem.VID -> android.media.ThumbnailUtils.createVideoThumbnail(java.io.File(f.path), sz, null)
                    else -> null
                }
            } catch (e: Throwable) { null }
            if (bm != null) h.post { thumbs.put(f.path, bm); if (iv.tag == f.path) iv.setImageBitmap(bm) }
        }
    }

    private fun fileRow(f: FileItem): View {
        val on = f.path in memSel
        val r = hbox().apply { setPadding(0, dp(6), 0, dp(6)) }
        r.addView(tv(if (on) "☑" else "☐", 22f, true, if (on) ACC else DIM).apply { setPadding(0, 0, dp(10), 0) })
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = round(LINE, 8) }
        if (f.ext in Mem.IMG || f.ext in Mem.VID) thumb(iv, f)
        r.addView(iv, LinearLayout.LayoutParams(dp(56), dp(56)))
        val l = vbox()
        l.addView(tv(f.name, 14f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
        val df = java.text.SimpleDateFormat("d.MM.yyyy", Locale("pl"))
        l.addView(tv(Mem.fmt(f.size) + " · " + df.format(java.util.Date(f.mod)) + " · " + f.path.removePrefix(Mem.root.path).substringBeforeLast('/'), 11f, color = DIM).apply { maxLines = 1 })
        r.addView(l, lp(0, -2, 1f).apply { leftMargin = dp(10) })
        r.setOnClickListener { if (!memSel.add(f.path)) memSel.remove(f.path); render() }
        r.setOnLongClickListener { openFile(f); true }
        return r
    }

    private fun openFile(f: FileItem) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", java.io.File(f.path))
            val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.ext) ?: "*/*"
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) { toast("Nie mogę otworzyć") }
    }

    private fun trashBar(all: List<FileItem>) {
        val r = hbox()
        r.addView(btn(if (memSel.size == all.size && all.isNotEmpty()) "Odznacz wszystkie" else "Zaznacz wszystkie") {
            if (memSel.size == all.size) memSel.clear() else { memSel.clear(); memSel.addAll(all.map { it.path }) }; render()
        }, lp(-2, -2).apply { rightMargin = dp(8) })
        val size = all.filter { it.path in memSel }.sumOf { it.size }
        if (memSel.isNotEmpty()) r.addView(btn("Do kosza (${memSel.size} · ${Mem.fmt(size)})", true) {
            val paths = memSel.toList()
            memWork("Przenoszę do kosza…") {
                val b = Mem.toTrash(this, paths); memSel.clear()
                dupGroups = dupGroups?.map { g -> g.filter { java.io.File(it.path).exists() } }?.filter { it.size > 1 }
                simGroups = simGroups?.map { g -> g.filter { java.io.File(it.path).exists() } }?.filter { it.size > 1 }
                "Przeniesiono ${Mem.fmt(b)} do kosza"
            }
        })
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(r) }, lp(top = 10))
        col.addView(tv("Stuknij, żeby zaznaczyć · przytrzymaj, żeby otworzyć", 11f, color = DIM), lp(top = 4))
    }

    private fun renderFiles(title: String, hint: String, list: List<FileItem>, preselect: Boolean) {
        backRow(title)
        col.addView(tv("$hint ${list.size} plików · ${Mem.fmt(list.sumOf { it.size })}", 13f, color = DIM), lp(top = 4))
        if (preselect && memSel.isEmpty() && list.isNotEmpty() && memSub != lastPre) { memSel.addAll(list.map { it.path }); lastPre = memSub }
        trashBar(list)
        for (f in list.take(showMax)) col.addView(fileRow(f))
        if (list.size > showMax) col.addView(btn("Pokaż więcej (${list.size - showMax})") { showMax += 300; render() }, lp(-2, -2, top = 8))
        if (list.isEmpty()) col.addView(tv("Nic nie znaleziono 👍", 15f), lp(top = 16))
    }

    private var lastPre = ""

    private fun renderGroups(title: String, hint: String, groups: List<List<FileItem>>, keepFirst: Boolean) {
        backRow(title)
        val extra = groups.sumOf { g -> g.drop(1).sumOf { it.size } }
        col.addView(tv("$hint ${groups.size} grup · do odzyskania ${Mem.fmt(extra)}", 13f, color = DIM), lp(top = 4))
        if (keepFirst && memSel.isEmpty() && groups.isNotEmpty() && memSub != lastPre) { groups.forEach { g -> g.drop(1).forEach { memSel.add(it.path) } }; lastPre = memSub }
        trashBar(groups.flatten())
        var shown = 0
        for ((i, g) in groups.withIndex()) {
            if (shown > showMax) { col.addView(btn("Pokaż więcej") { showMax += 300; render() }, lp(-2, -2, top = 8)); break }
            col.addView(tv("Grupa ${i + 1} · ${g.size} pliki · ${Mem.fmt(g.first().size)}", 13f, true, DIM), lp(top = 14))
            for ((k, f) in g.withIndex()) {
                val row = fileRow(f)
                if (k == 0) (row as LinearLayout).addView(tv("zostaje", 11f, true, ACC))
                col.addView(row); shown++
            }
        }
        if (groups.isEmpty()) col.addView(tv("Nic nie znaleziono 👍", 15f), lp(top = 16))
    }

    private fun renderOptimize() {
        backRow("Optymalizator zdjęć")
        val list = Mem.optimizable()
        val est = (list.sumOf { it.size } * 0.55).toLong()
        col.addView(tv("Zdjęcia z aparatu powyżej 2,5 MB: ${list.size} · ${Mem.fmt(list.sumOf { it.size })}. Po zmniejszeniu do 3000 px (jakość 82) zwykle odzyskasz ok. ${Mem.fmt(est)}. Na ekranie telefonu różnicy nie widać; data, miejsce i obrót zdjęcia zostają. Oryginały trafiają do kosza na 30 dni.", 13f, color = DIM), lp(top = 6))
        if (memSel.isEmpty() && memSub != lastPre) { memSel.addAll(list.map { it.path }); lastPre = memSub }
        val chosen = list.filter { it.path in memSel }
        col.addView(btn("Optymalizuj (${chosen.size})", true) {
            memWork("Optymalizuję…") { p -> val b = Mem.optimize(this, chosen, p); memSel.clear(); "Odzyskano ${Mem.fmt(b)} (oryginały w koszu)" }
        }, lp(-2, -2, top = 10))
        for (f in list.take(showMax)) col.addView(fileRow(f))
    }

    private fun renderTrash() {
        backRow("Kosz")
        val l = Mem.trash().sortedByDescending { it.optLong("d") }
        col.addView(tv("${l.size} plików · ${Mem.fmt(l.sumOf { it.optLong("s") })}. Pliki starsze niż 30 dni usuwają się same.", 13f, color = DIM), lp(top = 4))
        val r = hbox()
        val chosen = l.filter { it.optString("t") in memSel }
        if (chosen.isNotEmpty()) r.addView(btn("Przywróć (${chosen.size})", true) { memWork("Przywracam…") { Mem.restore(this, chosen); memSel.clear(); Mem.scannedAt = 0; "Przywrócono" } }, lp(-2, -2).apply { rightMargin = dp(8) })
        if (l.isNotEmpty()) r.addView(btn("Opróżnij kosz") {
            android.app.AlertDialog.Builder(this).setTitle("Opróżnić kosz?").setMessage("Pliki zostaną usunięte na zawsze.")
                .setPositiveButton("Usuń na zawsze") { _, _ -> memWork("Usuwam…") { "Zwolniono ${Mem.fmt(Mem.emptyTrash(0))}" } }.setNegativeButton("Anuluj", null).show()
        })
        col.addView(r, lp(top = 10))
        val df = java.text.SimpleDateFormat("d.MM HH:mm", Locale("pl"))
        for (o in l.take(showMax)) {
            val t = o.optString("t")
            val on = t in memSel
            val row = hbox().apply { setPadding(0, dp(8), 0, dp(8)) }
            row.addView(tv(if (on) "☑" else "☐", 22f, true, if (on) ACC else DIM).apply { setPadding(0, 0, dp(10), 0) })
            val fi = FileItem(t, o.optLong("s"), o.optLong("d"))
            val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = round(LINE, 8) }
            if (fi.ext in Mem.IMG || fi.ext in Mem.VID) thumb(iv, fi)
            row.addView(iv, LinearLayout.LayoutParams(dp(48), dp(48)))
            val v = vbox()
            v.addView(tv(o.optString("o").substringAfterLast('/'), 14f).apply { maxLines = 1 })
            v.addView(tv(Mem.fmt(o.optLong("s")) + " · usunięto " + df.format(java.util.Date(o.optLong("d"))), 11f, color = DIM))
            row.addView(v, lp(0, -2, 1f).apply { leftMargin = dp(10) })
            row.setOnClickListener { if (!memSel.add(t)) memSel.remove(t); render() }
            col.addView(row)
        }
    }

    private fun renderAppSizes() {
        backRow("Menedżer aplikacji")
        val all = (appSizes ?: emptyList()).filter { !it.system || withSystem }
        val list = when (appSort) {
            "cache" -> all.sortedByDescending { it.cache }
            "unused" -> all.sortedBy { if (it.lastUsed == 0L) 0L else it.lastUsed }
            else -> all.sortedByDescending { it.total }
        }
        col.addView(tv("${all.size} aplikacji · razem ${Mem.fmt(all.sumOf { it.total })} · pamięć podręczna ${Mem.fmt(all.sumOf { it.cache })}", 13f, color = DIM), lp(top = 4))
        val sr = hbox()
        for ((k, n) in listOf("size" to "Rozmiar", "cache" to "Pamięć podręczna", "unused" to "Nieużywane")) sr.addView(btn(n, appSort == k) { appSort = k; render() }, lp(-2, -2).apply { rightMargin = dp(6) })
        sr.addView(btn(if (withSystem) "☑ Systemowe" else "☐ Systemowe") { withSystem = !withSystem; render() })
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(sr) }, lp(top = 8))
        val chosen = memSel.toList()
        if (chosen.isNotEmpty()) {
            val ar = hbox()
            ar.addView(btn("Odinstaluj (${chosen.size})", true) {
                android.app.AlertDialog.Builder(this).setTitle("Odinstalować ${chosen.size} aplikacji?").setPositiveButton("Odinstaluj") { _, _ ->
                    if (Sh.granted()) memWork("Odinstalowuję…") { Mem.uninstall(chosen); memSel.clear(); appSizes = Mem.appSizes(this); "Gotowe" }
                    else { chosen.forEach { p -> try { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$p"))) } catch (e: Exception) {} }; memSel.clear() }
                }.setNegativeButton("Anuluj", null).show()
            }, lp(-2, -2).apply { rightMargin = dp(6) })
            ar.addView(btn("Wyczyść pamięć podręczną") { if (needShizuku()) return@btn; memWork("Czyszczę…") { Mem.clearCache(chosen); memSel.clear(); appSizes = Mem.appSizes(this); "Wyczyszczono" } }, lp(-2, -2).apply { rightMargin = dp(6) })
            ar.addView(btn("Hibernuj") { if (needShizuku()) return@btn; memWork("Usypiam…") { Mem.hibernate(chosen); memSel.clear(); "Uśpiono ${chosen.size} aplikacji" } }, lp(-2, -2).apply { rightMargin = dp(6) })
            ar.addView(btn("Do kategorii…") { moveSheet(chosen); memSel.clear() })
            col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(ar) }, lp(top = 8))
        }
        for (a in list.take(showMax)) {
            val on = a.pkg in memSel
            val r = hbox().apply { setPadding(0, dp(8), 0, dp(8)) }
            r.addView(tv(if (on) "☑" else "☐", 22f, true, if (on) ACC else DIM).apply { setPadding(0, 0, dp(10), 0) })
            r.addView(ImageView(this).apply { setImageDrawable(Apps.icon(this@MainActivity, a.pkg)) }, LinearLayout.LayoutParams(dp(36), dp(36)))
            val l = vbox()
            l.addView(tv(a.label, 15f).apply { maxLines = 1 })
            l.addView(tv("${Mem.fmt(a.total)} · podręczna ${Mem.fmt(a.cache)} · ${ago(a.lastUsed)}", 12f, color = DIM))
            r.addView(l, lp(0, -2, 1f).apply { leftMargin = dp(10) })
            r.setOnClickListener { if (!memSel.add(a.pkg)) memSel.remove(a.pkg); render() }
            r.setOnLongClickListener { try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}"))) } catch (e: Exception) {}; true }
            col.addView(r)
        }
        if (list.size > showMax) col.addView(btn("Pokaż więcej") { showMax += 300; render() }, lp(-2, -2, top = 8))
    }

    private fun renderInfo() {
        backRow("Telefon")
        for ((k, v) in Mem.systemInfo(this)) {
            val l = vbox().apply { setPadding(0, dp(12), 0, dp(12)) }
            l.addView(tv(k, 13f, color = DIM)); l.addView(tv(v, 16f))
            col.addView(l); col.addView(line(), lp(-1, 1))
        }
        col.addView(btn("Odśwież") { render() }, lp(-2, -2, top = 10))
    }

    // ================================================================== PRYWATNOŚĆ
    private var privSub = ""
    private var privBusy = false
    private var privMsg = ""
    private var privList: List<Priv.Entry>? = null
    private var specialList: List<Priv.SpecialEntry>? = null
    private var trackers: List<Priv.AppTrackers>? = null
    private var privSys = false

    private fun privWork(msg: String, job: ((String) -> Unit) -> String?) {
        if (privBusy) return
        privBusy = true; privMsg = msg; render()
        Thread {
            val r = try { job { m -> h.post { privMsg = m; memStatus?.text = m } } } catch (e: Throwable) { "Błąd: " + (e.message ?: "") }
            h.post { privBusy = false; privMsg = ""; if (r != null) toast(r); render() }
        }.start()
    }

    private fun privBack(title: String) {
        val r = hbox()
        r.addView(tv("‹", 30f, true).apply { setPadding(0, 0, dp(14), 0); setOnClickListener { privSub = ""; privList = null; specialList = null; render() } })
        r.addView(tv(title, 24f, true), lp(0, -2, 1f))
        col.addView(r)
    }

    private fun appIconRow(pkg: String, label: String, sub: String, right: String, rightColor: Int, f: () -> Unit): View {
        val r = hbox().apply { setPadding(0, dp(8), 0, dp(8)); setOnClickListener { f() } }
        r.addView(ImageView(this).apply { setImageDrawable(Apps.icon(this@MainActivity, pkg)) }, LinearLayout.LayoutParams(dp(34), dp(34)))
        val l = vbox()
        l.addView(tv(label, 15f).apply { maxLines = 1 })
        if (sub.isNotEmpty()) l.addView(tv(sub, 11f, color = DIM).apply { maxLines = 2 })
        val marks = listOfNotNull(if (pkg in Cfg.netDeny) "🚫 internet w tle odcięty" else null, if (pkg in Cfg.trackBlock) "🛡 trackery zablokowane" else null)
        if (marks.isNotEmpty()) l.addView(tv(marks.joinToString(" · "), 11f, true, ACC))
        r.addView(l, lp(0, -2, 1f).apply { leftMargin = dp(12) })
        r.addView(tv(right, 12f, true, rightColor).apply { setPadding(dp(8), 0, 0, 0) })
        return r
    }

    private fun renderPriv() {
        if (privBusy) {
            col.addView(tv("Prywatność", 34f, true))
            memStatus = tv(privMsg, 15f, true)
            col.addView(memStatus, lp(top = 24))
            return
        }
        if (privSub.startsWith("g:")) return renderGroup(Priv.GROUPS.first { it.key == privSub.removePrefix("g:") })
        if (privSub.startsWith("s:")) return renderSpecial(Priv.SPECIAL.first { it.key == privSub.removePrefix("s:") })
        if (privSub.startsWith("app:")) return renderAppPriv(privSub.removePrefix("app:"))
        if (privSub.startsWith("mon:")) return renderMonApp(privSub.removePrefix("mon:"))
        when (privSub) {
            "report" -> return renderReport()
            "vault" -> return renderVault()
            "applock" -> return renderAppLock()
            "links" -> return renderLinks()
            "theft" -> return renderTheft()
            "wifi" -> return renderWifi()
            "pass" -> return renderPass()
            "av" -> return renderAv()
            "mon" -> return renderMonitor()
            "net" -> return renderNet()
            "trackers" -> return renderTrackers()
            "vendor" -> return renderVendor()
        }
        col.addView(tv("Prywatność", 34f, true))
        if (!Sh.granted()) col.addView(tv("⚠ Zmiany wymagają działającego Shizuku – podgląd działa bez niego.", 13f, true, WARN), lp(top = 6))

        header("Bezpieczeństwo")
        card("🛡 Raport bezpieczeństwa", "Ocena telefonu: poprawki, blokada, groźne dostępy, aplikacje spoza sklepu – z eksportem do PDF",
            report?.let { "${Security.score(it)}/100" } ?: "") {
            privWork("Sprawdzam telefon…") { report = Security.run(this); h.post { privSub = "report" }; null }
        }
        val bad = synchronized(Av.results) { Av.results.count { it.level >= 1 } }
        card("🦠 Antywirus", "Sprawdza aplikacje i pliki w ok. 70 silnikach antywirusowych + własna analiza APK. Nowe pliki i aplikacje sprawdza od razu.",
            if (bad > 0) "$bad!" else "") { privSub = "av"; render() }
        card("📡 Strażnik sieci i adblock", "Kto łączy się z czym, blokada reklam i trackerów we wszystkich aplikacjach i przeglądarkach",
            if (NetBlock.monitoring) "działa" else "") { privSub = "mon"; render() }
        card("🔗 Fałszywe linki i SMS-y", "Sprawdza linki z SMS-ów i wklejone adresy: listy CERT Polska, podszywanie się pod banki i kurierów") { privSub = "links"; render() }
        card("🚨 Ochrona przed kradzieżą", "Syrena i zdjęcie po błędnych próbach odblokowania",
            if (Cfg.antiTheft && Theft.active(this)) "włączona" else "") { privSub = "theft"; render() }
        card("📶 Sieć Wi-Fi", "Czy sieć jest bezpieczna i kto jeszcze jest w niej podłączony") { privSub = "wifi"; render() }
        card("🔐 Sejf", "Zaszyfrowany schowek na dokumenty i zdjęcia – otwierany odciskiem palca", if (Vault.items(this).isNotEmpty()) "${Vault.items(this).size}" else "") {
            Bio.ask(this, "Sejf", "Potwierdź, że to Ty", ok = { vaultOpen = true; privSub = "vault"; render() }, fail = { toast("Nie odblokowano") })
        }
        card("🔒 Blokada aplikacji", "Wybrane aplikacje (bank, poczta, galeria) wymagają odcisku przy otwarciu", if (Cfg.locked.isNotEmpty()) "${Cfg.locked.size}" else "") {
            Bio.ask(this, "Blokada aplikacji", "Potwierdź, że to Ty", ok = { privSub = "applock"; render() }, fail = {})
        }
        toggle("✈ Tryb podróży", "Lotnisko, hotel, konferencja: Bluetooth i NFC wyłączone, bez skanowania, USB tylko ładuje, treść powiadomień ukryta na blokadzie, otwarte sieci Wi-Fi rozłączane.", Cfg.travel) {
            if (needShizuku()) return@toggle
            work(if (it) "Uszczelniam telefon…" else "Przywracam…") { Travel.set(this, it); if (it) "Tryb podróży włączony" else "Tryb podróży wyłączony" }
        }
        toggle("Czyść schowek po wygaszeniu ekranu", "Skopiowane hasła i kody nie zostają w schowku. Kto ma dostęp do schowka – niżej, w „Dostęp do danych”.", Cfg.clipClear) { Cfg.clipClear = it; Cfg.save() }
        card("🔑 Sprawdź hasło", "Siła hasła i lista 100 tys. najczęściej łamanych – liczone w telefonie") { privSub = "pass"; render() }
        toggle("Alarmy bezpieczeństwa", "Nowa aplikacja (trackery, uprawnienia), nowy groźny dostęp, mikrofon lub aparat używany przy wygaszonym ekranie.", Cfg.alerts) {
            Cfg.alerts = it; Cfg.save()
        }

        header("Śledzenie")
        val tr = trackers
        card("🕵 Trackery w aplikacjach", if (tr == null) "Sprawdzam kod każdej aplikacji pod kątem bibliotek śledzących (jak Exodus Privacy)"
            else "${tr.count { it.trackers.isNotEmpty() }} z ${tr.size} aplikacji zawiera trackery · ${tr.flatMap { it.trackers }.map { it.name }.distinct().size} różnych",
            tr?.let { "${it.sumOf { a -> a.trackers.size }}" } ?: "") {
            if (trackers != null) { privSub = "trackers"; render() }
            else privWork("Sprawdzam aplikacje…") { p -> trackers = Priv.scanAll(this, privSys, p); h.post { privSub = "trackers" }; null }
        }
        card("🏢 Usługi śledzące producenta", "Samsung, Google, Facebook, Microsoft – telemetria, profilowanie, reklamy") { privSub = "vendor"; render() }
        row("Blokada reklam i trackerów w całym telefonie", if (NetBlock.monitoring && Cfg.blockTrackers) "Działa – Strażnik sieci, ${Dns.lists.size} domen na liście w telefonie"
            else "Włącz Strażnika sieci (sekcja Bezpieczeństwo). Działa w telefonie i nie psuje Wi-Fi.") { privSub = "mon"; render() }
        col.addView(line(), lp(-1, 1))
        row("Identyfikator reklamowy Google", "Usuń go – aplikacje nie połączą Twoich danych w jeden profil reklamowy") {
            open(listOf(Intent("com.google.android.gms.settings.ADS_PRIVACY"), Intent(Settings.ACTION_PRIVACY_SETTINGS)))
        }
        col.addView(line(), lp(-1, 1))
        row("Prywatność w ustawieniach Samsunga", "Usługa dostosowywania, dane diagnostyczne, reklamy Samsunga") {
            open(listOf(Intent().setClassName("com.android.settings", "com.samsung.android.settings.privacy.PrivacySettingsActivity"), Intent(Settings.ACTION_PRIVACY_SETTINGS)))
        }

        header("Dostęp do danych")
        card("🌐 Internet w tle", if (Cfg.netDeny.isEmpty()) "Nikomu nie odciąłeś internetu" else "Odcięte: " + Cfg.netDeny.take(6).joinToString(", ") { p ->
            try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString() } catch (e: Exception) { p } } + if (Cfg.netDeny.size > 6) "…" else "",
            "${Cfg.netDeny.size} odciętych") { privSub = "net"; render() }
        if (Cfg.trackBlock.isNotEmpty()) col.addView(tv("🛡 Trackery zablokowane w ${Cfg.trackBlock.size} aplikacjach (internet zostawiony)", 12f, true, ACC), lp(top = 6))
        for (g in Priv.GROUPS) card(g.name, g.desc) { privSub = "g:" + g.key; render() }

        header("Dostępy specjalne (najgroźniejsze)")
        for (sp in Priv.SPECIAL) card(sp.name, sp.desc) { privSub = "s:" + sp.key; render() }

        val r = hbox().apply { setPadding(0, dp(16), 0, 0) }
        r.addView(tv(if (privSys) "☑ Pokazuj aplikacje systemowe" else "☐ Pokazuj aplikacje systemowe", 14f, color = DIM).apply {
            setOnClickListener { privSys = !privSys; trackers = null; render() }
        })
        col.addView(r)
    }

    private fun renderGroup(g: Priv.Group) {
        privBack(g.name)
        col.addView(tv(g.desc, 13f, color = DIM), lp(top = 4))
        val list = privList ?: run {
            col.addView(tv("Wczytuję…", 14f, color = DIM), lp(top = 10))
            Thread {
                val l = Priv.groupApps(this, g, privSys)
                if (g.op != null) { val lu = Priv.lastUse(g.op, l.filter { it.granted }.map { it.pkg }); l.forEach { e -> lu[e.pkg]?.let { e.lastUse = it } } }
                h.post { privList = l; render() }
            }.start()
            return
        }
        val granted = list.count { it.granted }
        col.addView(tv("Dostęp ma $granted z ${list.size} aplikacji. Stuknij, żeby zabrać lub oddać. Zabrany dostęp jest zablokowany – aplikacja nie zapyta ponownie.", 13f, color = DIM), lp(top = 6))
        if (granted > 0) col.addView(btn("Zabierz wszystkim poza chronionymi", true) {
            if (needShizuku()) return@btn
            work("Zabieram…") {
                var n = 0
                for (e in list.filter { it.granted && Cfg.cat(it.pkg) != Cat.PROTECT }) { Priv.set(this, e.pkg, g, false); n++ }
                privList = null; "Zabrano dostęp $n aplikacjom"
            }
        }, lp(-2, -2, top = 8))
        for (e in list) col.addView(appIconRow(e.pkg, e.label, listOfNotNull(if (e.system) "systemowa" else null, if (e.lastUse.isNotEmpty()) "ostatnio: ${e.lastUse}" else null,
            if (Cfg.cat(e.pkg) == Cat.PROTECT) "chroniona" else null).joinToString(" · "),
            if (e.granted) "MA DOSTĘP" else "zablokowany", if (e.granted) WARN else ACC) {
            if (needShizuku()) return@appIconRow
            work(null) { Priv.set(this, e.pkg, g, !e.granted); privList = null; null }
        })
    }

    private fun renderNet() {
        privBack("Internet w tle")
        col.addView(tv("Zablokowana aplikacja nie wyśle niczego w tle przez dane komórkowe (blokada systemowa), a po wygaszeniu ekranu traci też Wi-Fi (lokalny filtr). Gdy jej używasz – działa normalnie przez Wi-Fi.", 13f, color = DIM), lp(top = 4))
        val list = privList ?: run {
            col.addView(tv("Wczytuję…", 14f, color = DIM), lp(top = 10))
            Thread { val l = Priv.netApps(this, privSys); h.post { privList = l; render() } }.start()
            return
        }
        if (Cfg.netDeny.isNotEmpty() && VpnService.prepare(this) != null) col.addView(btn("Zezwól na filtr sieci (raz)", true) {
            VpnService.prepare(this)?.let { i -> startActivityForResult(i, 6) }
        }, lp(-2, -2, top = 8))
        for (e in list) col.addView(appIconRow(e.pkg, e.label, if (Cfg.cat(e.pkg) == Cat.PROTECT) "chroniona – ostrożnie" else "",
            if (e.granted) "internet w tle" else "ZABLOKOWANY", if (e.granted) DIM else ACC) {
            if (needShizuku()) return@appIconRow
            work(null) { Priv.setNet(this, e.pkg, !e.granted); privList = null; null }
        })
    }

    private fun renderSpecial(sp: Priv.Special) {
        privBack(sp.name)
        col.addView(tv(sp.desc, 13f, color = DIM), lp(top = 4))
        val list = specialList ?: run {
            col.addView(tv("Wczytuję…", 14f, color = DIM), lp(top = 10))
            Thread { val l = Priv.specialApps(this, sp.key); h.post { specialList = l; render() } }.start()
            return
        }
        if (list.isEmpty()) col.addView(tv("Żadna aplikacja nie ma tego dostępu 👍", 15f), lp(top = 12))
        for (e in list) col.addView(appIconRow(e.pkg, e.label, e.component.substringAfter('/', ""), "Zabierz", WARN) {
            android.app.AlertDialog.Builder(this).setTitle("Zabrać „${sp.name}” aplikacji ${e.label}?").setPositiveButton("Zabierz") { _, _ ->
                work(null) { Priv.revokeSpecial(this, sp.key, e); specialList = null; "Zabrano" }
            }.setNegativeButton("Anuluj", null).show()
        })
    }

    private fun renderTrackers() {
        privBack("Trackery w aplikacjach")
        val list = trackers ?: emptyList()
        val withT = list.filter { it.trackers.isNotEmpty() }
        col.addView(tv("Znalezione w kodzie aplikacji biblioteki śledzące: analityka, reklamy, profilowanie, nagrywanie ekranu. ${withT.size} z ${list.size} aplikacji je zawiera. Stuknij aplikację, żeby zobaczyć szczegóły i ją odciąć.", 13f, color = DIM), lp(top = 4))
        // ranking trackerów
        header("Najczęstsze trackery")
        for ((name, n) in withT.flatMap { it.trackers }.groupingBy { it.name }.eachCount().entries.sortedByDescending { it.value }.take(10).map { it.key to it.value }) {
            val r = hbox().apply { setPadding(0, dp(4), 0, dp(4)) }
            r.addView(tv(name, 14f), lp(0, -2, 1f)); r.addView(tv("$n aplik.", 13f, true, WARN))
            col.addView(r)
        }
        header("Aplikacje")
        val notYet = withT.filter { it.pkg !in Cfg.trackBlock }
        if (notYet.isNotEmpty()) col.addView(btn("Zablokuj trackery w sieci wszystkim (${notYet.size})", true) {
            Cfg.trackBlock.addAll(notYet.map { it.pkg }); Cfg.save()
            if (!NetBlock.monitoring) {
                val i = VpnService.prepare(this)
                if (i != null) startActivityForResult(i, 7) else { Cfg.monitor = true; Cfg.save(); NetBlock.monitor(this, true) }
            }
            toast("Zablokowano – aplikacje działają dalej"); h.postDelayed({ render() }, 600)
        }, lp(-2, -2, top = 6))
        for (a in withT) col.addView(appIconRow(a.pkg, a.label, a.trackers.joinToString(", ") { it.name },
            "${a.trackers.size}", if (a.trackers.size >= 5) 0xFFFF453A.toInt() else WARN) { privSub = "app:" + a.pkg; render() })
        col.addView(btn("Skanuj ponownie") { trackers = null; privWork("Sprawdzam aplikacje…") { p -> trackers = Priv.scanAll(this, privSys, p); null } }, lp(-2, -2, top = 10))
    }

    private fun renderAppPriv(pkg: String) {
        val a = trackers?.firstOrNull { it.pkg == pkg }
        privBack(a?.label ?: pkg)
        if (a != null) {
            header("Trackery (${a.trackers.size})")
            for (t in a.trackers) {
                val r = hbox().apply { setPadding(0, dp(4), 0, dp(4)) }
                r.addView(tv(t.name, 15f), lp(0, -2, 1f)); r.addView(tv(t.kind, 12f, true, WARN))
                col.addView(r)
            }
        }
        // ---- stan tej aplikacji – zawsze na górze
        val blocked = pkg in Cfg.netDeny
        val tb = pkg in Cfg.trackBlock
        val comps = Priv.trackerComponents(this, pkg)
        val compsOff = comps.count { !it.second }
        val box = vbox().apply { background = round(SURF, 16); setPadding(dp(14), dp(12), dp(14), dp(12)) }
        box.addView(tv("Stan", 12f, true, DIM))
        box.addView(tv(if (blocked) "🚫 Internet w tle: ODCIĘTY" else "🌐 Internet w tle: działa", 15f, true, if (blocked) ACC else FG), lp(top = 4))
        box.addView(tv(if (tb || Cfg.blockTrackers && NetBlock.monitoring) "🛡 Trackery w sieci: ZABLOKOWANE" + (if (!tb) " (blokada ogólna)" else "") else "⚠ Trackery w sieci: przepuszczane", 15f, true,
            if (tb || Cfg.blockTrackers && NetBlock.monitoring) ACC else WARN), lp(top = 2))
        if (comps.isNotEmpty()) box.addView(tv(if (compsOff == comps.size) "🛡 Składniki śledzące: WYŁĄCZONE ($compsOff)" else if (compsOff > 0) "Składniki śledzące: wyłączone $compsOff z ${comps.size}" else "⚠ Składniki śledzące: działają (${comps.size})", 15f, true,
            if (compsOff == comps.size) ACC else WARN), lp(top = 2))
        col.addView(box, lp(top = 10))

        header("Wyłącz trackery (aplikacja działa dalej)")
        col.addView(tv("Aplikacja zachowuje internet i działa normalnie – blokowane są tylko połączenia z serwerami reklam i śledzenia oraz jej wewnętrzne usługi śledzące.", 12f, color = DIM), lp(top = 2))
        col.addView(btn(if (tb) "✓ Trackery w sieci zablokowane – cofnij" else "Zablokuj trackery w sieci", !tb) {
            if (!tb) {
                Cfg.trackBlock.add(pkg); Cfg.save()
                if (!NetBlock.monitoring) {
                    val i = VpnService.prepare(this)
                    if (i != null) startActivityForResult(i, 7) else { Cfg.monitor = true; Cfg.save(); NetBlock.monitor(this, true) }
                }
                if (Dns.lists.isEmpty()) toast("Pobierz listy blokowania: Ochrona → Strażnik sieci")
            } else { Cfg.trackBlock.remove(pkg); Cfg.save() }
            h.postDelayed({ render() }, 600)
        }, lp(-2, -2, top = 8))
        if (comps.isNotEmpty()) col.addView(btn(if (compsOff == comps.size) "✓ Składniki śledzące wyłączone – włącz z powrotem" else "Wyłącz składniki śledzące (${comps.size})", compsOff != comps.size) {
            if (needShizuku()) return@btn
            val enable = compsOff == comps.size
            work(if (enable) "Włączam…" else "Wyłączam…") {
                Priv.setTrackerComponents(pkg, comps.map { it.first }, enable)
                val after = Priv.trackerComponents(this, pkg).count { !it.second }
                when {
                    enable -> "Składniki włączone"
                    after == 0 -> "Android nie pozwala wyłączać składników tej aplikacji. Użyj „Zablokuj trackery w sieci” – efekt jest ten sam."
                    else -> "Wyłączono $after składników. Gdyby aplikacja zaczęła się zawieszać – włącz je z powrotem."
                }
            }
        }, lp(-2, -2, top = 8))
        if (comps.isNotEmpty()) col.addView(tv(comps.joinToString("\n") { (if (it.second) "• " else "✕ ") + it.first.substringAfterLast('.') + "  (" + it.first.substringBeforeLast('.').takeLast(40) + ")" }, 11f, color = DIM), lp(top = 6))

        header("Odetnij całkiem")
        col.addView(btn(if (blocked) "✓ Internet w tle odcięty – przywróć" else "Odetnij internet w tle", !blocked) {
            if (needShizuku()) return@btn; work(null) { Priv.setNet(this, pkg, blocked); if (blocked) "Internet w tle przywrócony" else "Internet w tle odcięty" }
        }, lp(-2, -2, top = 8))
        col.addView(btn("Ogranicz działanie w tle") { moveSheet(listOf(pkg)) }, lp(-2, -2, top = 8))
        col.addView(btn("Uprawnienia aplikacji (ustawienia systemu)") {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))) } catch (e: Exception) {}
        }, lp(-2, -2, top = 8))
        col.addView(btn("Odinstaluj") {
            if (Sh.granted()) work("Odinstalowuję…") { Mem.uninstall(listOf(pkg)); trackers = trackers?.filter { it.pkg != pkg }; h.post { privSub = "trackers" }; "Odinstalowano" }
            else try { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))) } catch (e: Exception) {}
        }, lp(-2, -2, top = 8))
        header("Dostęp do danych")
        Thread {
            val pm = packageManager
            val req = try { pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions?.toSet() ?: emptySet() } catch (e: Exception) { emptySet() }
            val rows = Priv.GROUPS.mapNotNull { g ->
                val w = g.perms.filter { it in req }
                if (w.isEmpty()) null else Pair(g, w.any { pm.checkPermission(it, pkg) == android.content.pm.PackageManager.PERMISSION_GRANTED })
            }
            h.post {
                if (privSub != "app:$pkg") return@post
                for ((g, has) in rows) {
                    val r = hbox().apply { setPadding(0, dp(10), 0, dp(10)) }
                    r.addView(tv(g.name, 15f), lp(0, -2, 1f))
                    r.addView(tv(if (has) "MA DOSTĘP" else "zablokowany", 12f, true, if (has) WARN else ACC))
                    r.setOnClickListener { if (needShizuku()) return@setOnClickListener; work(null) { Priv.set(this, pkg, g, !has); null } }
                    col.addView(r)
                }
            }
        }.start()
    }

    private fun renderVendor() {
        privBack("Usługi producenta")
        col.addView(tv("Preinstalowane usługi, które wysyłają dane o Tobie i Twoim telefonie. Wyłączenie jest odwracalne. Oznaczenia: „bezpieczne” – telefon działa normalnie; „ostrożnie” – może zniknąć jakaś funkcja; „nie wyłączaj” – psuje konto lub ważne funkcje.", 13f, color = DIM), lp(top = 4))
        val st = Priv.vendorState(this).filter { it.second != null }
        val safe = st.filter { it.first.risk == "bezpieczne" && it.second == true }
        if (safe.isNotEmpty()) col.addView(btn("Wyłącz wszystkie bezpieczne (${safe.size})", true) {
            if (needShizuku()) return@btn
            work("Wyłączam…") { safe.forEach { Priv.setVendor(it.first.pkg, false) }; "Wyłączono ${safe.size} usług" }
        }, lp(-2, -2, top = 8))
        for ((v, on) in st) {
            val l = vbox().apply { setPadding(0, dp(10), 0, dp(10)) }
            val r = hbox()
            r.addView(ImageView(this).apply { setImageDrawable(Apps.icon(this@MainActivity, v.pkg)) }, LinearLayout.LayoutParams(dp(30), dp(30)))
            val t = vbox()
            t.addView(tv(v.name, 15f, true))
            t.addView(tv(v.what, 12f, color = DIM))
            t.addView(tv(v.risk, 11f, true, when (v.risk) { "bezpieczne" -> ACC; "nie wyłączaj" -> 0xFFFF453A.toInt(); else -> WARN }))
            r.addView(t, lp(0, -2, 1f).apply { leftMargin = dp(12) })
            r.addView(btn(if (on == true) "Wyłącz" else "Włącz", on == true) {
                if (needShizuku()) return@btn
                if (on == true && v.risk == "nie wyłączaj") {
                    android.app.AlertDialog.Builder(this).setTitle("Na pewno?").setMessage(v.what).setPositiveButton("Wyłącz mimo to") { _, _ -> work(null) { Priv.setVendor(v.pkg, false); null } }.setNegativeButton("Anuluj", null).show()
                } else work(null) { Priv.setVendor(v.pkg, on != true); null }
            })
            l.addView(r)
            col.addView(l); col.addView(line(), lp(-1, 1))
        }
        if (st.isEmpty()) col.addView(tv("Nie znaleziono znanych usług śledzących 👍", 15f), lp(top = 12))
    }

    // ================================================================== BEZPIECZEŃSTWO: raport, antywirus, strażnik sieci
    private var report: List<Security.Check>? = null
    private var avBusy = false
    private var avStop = false
    private var avMsg = ""
    private var avStatus: TextView? = null

    private fun renderReport() {
        privBack("Raport bezpieczeństwa")
        val l = report ?: emptyList()
        val sc = Security.score(l)
        col.addView(ProgressRing(this).apply {
            big = "$sc"; small = "na 100"; value = sc / 100f
            if (sc < 65) { color1 = 0xFFFF453A.toInt(); color2 = 0xFFFF9F0A.toInt() } else if (sc < 85) { color1 = WARN; color2 = 0xFFFFD60A.toInt() }
        }, lp(-1, dp(170), top = 8))
        col.addView(tv("${l.count { it.level == 2 }} ryzyk · ${l.count { it.level == 1 }} uwag · ${l.count { it.level == 0 }} w porządku", 13f, color = DIM))
        val r = hbox()
        r.addView(btn("Eksport do PDF", true) {
            work("Tworzę PDF…") {
                val u = Security.pdf(this, l) ?: return@work "Nie udało się zapisać PDF"
                h.post { try { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Raport")) } catch (e: Exception) {} }
                "Zapisano w Pobranych"
            }
        }, lp(-2, -2).apply { rightMargin = dp(8) })
        r.addView(btn("Sprawdź ponownie") { privWork("Sprawdzam telefon…") { report = Security.run(this); null } })
        col.addView(r, lp(top = 10))
        for (ch in l.sortedByDescending { it.level }) {
            val v = vbox().apply { setPadding(0, dp(12), 0, dp(12)) }
            val hr = hbox()
            hr.addView(tv(when (ch.level) { 0 -> "✓"; 1 -> "!"; else -> "✕" }, 18f, true, when (ch.level) { 0 -> ACC; 1 -> WARN; else -> 0xFFFF453A.toInt() }).apply { minWidth = dp(28) })
            hr.addView(tv(ch.title, 16f, true), lp(0, -2, 1f))
            v.addView(hr)
            v.addView(tv(ch.detail, 13f, color = DIM), lp(top = 2).apply { leftMargin = dp(28) })
            if (ch.level > 0 && ch.fix.isNotBlank()) v.addView(tv("→ " + ch.fix, 13f), lp(top = 4).apply { leftMargin = dp(28) })
            col.addView(v); col.addView(line(), lp(-1, 1))
        }
    }

    private fun avRun(what: String, targets: () -> List<Av.Result>) {
        if (avBusy) return
        if (!Sig.ready(this)) { toast("Najpierw pobierz bazy sygnatur"); return }
        avBusy = true; avStop = false; avMsg = "Zaczynam…"; render()
        Thread {
            try {
                Av.scan(this, targets(), { i, n, name -> h.post { avMsg = "$what: $i/$n – $name"; avStatus?.text = avMsg } }, { avStop })
            } catch (e: Throwable) {
            }
            h.post { avBusy = false; avMsg = ""; render() }
        }.start()
    }

    private fun renderAv() {
        privBack("Antywirus")
        Sig.load(this)
        col.addView(tv("Działa w całości w telefonie. Każdy plik dostaje odciski MD5, SHA-1 i SHA-256, które porównuję z bazą znanego złośliwego oprogramowania zapisaną w pamięci telefonu. Nic nie jest wysyłane – internet służy tylko do pobrania baz. Aplikacje dodatkowo analizuję sam: uprawnienia, ukrywanie się, zaszyfrowany kod.", 13f, color = DIM), lp(top = 4))
        val df = java.text.SimpleDateFormat("d.MM.yyyy", Locale("pl"))
        row(if (Sig.ready(this)) "Bazy sygnatur: ok. ${Sig.count / 1_000_000} mln odcisków" else "Bazy sygnatur: brak",
            if (Sig.ready(this)) "Pobrane ${df.format(java.util.Date(Cfg.sigAt))} · odświeżają się same co 3 dni" else "Pobierz je raz na Wi-Fi (kilkadziesiąt MB)", null)
        col.addView(btn(if (Sig.ready(this)) "Aktualizuj bazy teraz" else "Pobierz bazy sygnatur", !Sig.ready(this)) {
            work("Pobieram bazy…") { val n = Sig.update(this) { }; "Bazy gotowe: ok. ${n / 1_000_000} mln odcisków" }
        }, lp(-2, -2, top = 4))
        toggle("Ochrona na bieżąco", "Każdy nowy plik w Pobranych i każda nowo zainstalowana aplikacja są sprawdzane od razu.", Cfg.avRealtime) { Cfg.avRealtime = it; Cfg.save() }

        if (avBusy) {
            avStatus = tv(avMsg, 14f, true)
            col.addView(avStatus, lp(top = 12))
            col.addView(btn("Przerwij") { avStop = true }, lp(-2, -2, top = 8))
        } else {
            val br = hbox()
            br.addView(btn("Skanuj aplikacje", true) { avRun("Aplikacje") { Av.appTargets(this, privSys) } }, lp(-2, -2).apply { rightMargin = dp(8) })
            br.addView(btn("Pobrane pliki") { ensureScan { avRun("Pliki") { Av.fileTargets(false) } } }, lp(-2, -2).apply { rightMargin = dp(8) })
            br.addView(btn("Cała pamięć (wszystkie pliki)") { ensureScan { avRun("Pliki") { Av.fileTargets(true) } } })
            col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(br) }, lp(top = 12))
            if (Cfg.avAt > 0) col.addView(tv("Ostatni skan: ${java.text.SimpleDateFormat("d.MM HH:mm", Locale("pl")).format(java.util.Date(Cfg.avAt))} · sprawdzono ${Av.scanned} plików w tej sesji", 12f, color = DIM), lp(top = 6))
        }

        val res = synchronized(Av.results) { Av.results.toList() }.sortedByDescending { it.level }
        header(if (res.isEmpty()) "Wyniki: nic nie wykryto" else "Wyniki: ${res.count { it.level == 2 }} zagrożeń · ${res.count { it.level == 1 }} podejrzanych")
        for (r in res.take(200)) {
            val v = vbox().apply { setPadding(0, dp(10), 0, dp(10)) }
            v.addView(tv((if (r.level == 2) "✕ ZAGROŻENIE: " else "! Podejrzane: ") + r.name, 15f, true, if (r.level == 2) 0xFFFF453A.toInt() else WARN))
            val info = ArrayList<String>()
            if (r.known) info += "odcisk pliku jest w bazie znanego złośliwego oprogramowania"
            info += r.heur
            if (r.pkg == null) info += r.path.removePrefix(Mem.root.path)
            v.addView(tv(info.joinToString("\n• ", "• "), 12f, color = DIM), lp(top = 2))
            val ar = hbox()
            if (r.pkg != null) ar.addView(btn("Odinstaluj", r.level == 2) {
                if (Sh.granted()) work("Odinstalowuję…") { Mem.uninstall(listOf(r.pkg)); synchronized(Av.results) { Av.results.remove(r) }; "Odinstalowano ${r.name}" }
                else try { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${r.pkg}"))) } catch (e: Exception) {}
            }, lp(-2, -2).apply { rightMargin = dp(8) })
            else ar.addView(btn("Do kwarantanny", r.level == 2) {
                work("Przenoszę…") { Mem.toTrash(this, listOf(r.path)); synchronized(Av.results) { Av.results.remove(r) }; "Plik w kwarantannie (Pamięć → Kosz)" }
            }, lp(-2, -2).apply { rightMargin = dp(8) })
            if (r.pkg != null) {
                val cut = r.pkg in Cfg.netDeny
                ar.addView(btn(if (cut) "✓ Internet odcięty – przywróć" else "Odetnij internet", cut) {
                    if (needShizuku()) return@btn; work(null) { Priv.setNet(this, r.pkg, cut); if (cut) "Internet przywrócony" else "Internet w tle odcięty" }
                }, lp(-2, -2).apply { rightMargin = dp(8) })
            }
            if (r.pkg != null) ar.addView(btn("Oryginał w Sklepie Play") { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${r.pkg}"))) } catch (e: Exception) {} }, lp(-2, -2).apply { rightMargin = dp(8) })
            ar.addView(btn("To fałszywy alarm") {
                Cfg.avTrust.add(r.pkg ?: r.sha); Cfg.save()
                synchronized(Av.results) { Av.results.remove(r) }; render()
            })
            v.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(ar) }, lp(top = 6))
            col.addView(v); col.addView(line(), lp(-1, 1))
        }
        col.addView(tv("Uczciwie: baza zna miliony próbek, ale nowego, nieznanego jeszcze wirusa nie rozpozna żaden skaner oparty na sygnaturach – dlatego jest też analiza zachowania aplikacji, alarmy i strażnik sieci. Zainfekowanej aplikacji nie da się „wyleczyć”: usuń ją i zainstaluj oryginał z oficjalnego źródła.", 12f, color = DIM), lp(top = 12))
    }

    private fun renderMonitor() {
        privBack("Strażnik sieci")
        col.addView(tv("Lokalny filtr (VPN w telefonie – nic nie wychodzi na zewnątrz) widzi zapytania o adresy stron wszystkich aplikacji. Dzięki temu wiesz, kto łączy się z czym, a reklamy i trackery są blokowane w każdej aplikacji i przeglądarce (Chrome, Brave, Reddit…).", 13f, color = DIM), lp(top = 4))
        toggle("Strażnik sieci", if (NetBlock.monitoring) "Działa" else "Wyłączony", Cfg.monitor) {
            if (it) {
                val i = VpnService.prepare(this)
                if (i != null) startActivityForResult(i, 7) else { Cfg.monitor = true; Cfg.save(); NetBlock.monitor(this, true); h.postDelayed({ render() }, 800) }
            } else { Cfg.monitor = false; Cfg.save(); NetBlock.monitor(this, false); h.postDelayed({ render() }, 800) }
        }
        toggle("Blokuj reklamy i trackery", "Listy: ${Dns.lists.size} domen" + (if (Cfg.listsAt > 0) " · z " + java.text.SimpleDateFormat("d.MM", Locale("pl")).format(java.util.Date(Cfg.listsAt)) else " – pobierz listy poniżej"), Cfg.blockTrackers) {
            Cfg.blockTrackers = it; Cfg.save()
        }
        col.addView(tv("Blokowanie odbywa się w telefonie na pobranych listach. Zapytania idą do tego samego serwera DNS co zwykle (operatora lub routera) – bez dodatkowych usług.", 12f, color = DIM), lp(top = 4))
        col.addView(btn("Pobierz / odśwież listy blokowania", Dns.lists.isEmpty()) {
            work("Pobieram listy…") { "Załadowano ${Dns.updateLists(this) { }} domen (listy reklam i śledzenia, polskie filtry, CERT Polska)" }
        }, lp(-2, -2, top = 8))
        col.addView(tv("Czego to nie zablokuje: reklam podawanych z tego samego adresu co treść (promowane posty na Reddicie, reklamy w YouTube). W Brave włącz też jego własne „Tarcze”; w Chrome wyłącz „Bezpieczny DNS”, bo omija filtr.", 12f, color = DIM), lp(top = 8))

        val sum = Dns.summary()
        if (sum.isNotEmpty()) {
            header("Kto się łączy (od włączenia)")
            for ((pkg, n, t) in sum.take(60)) {
                val label = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
                col.addView(appIconRow(pkg, label, "$n zapytań" + if (t > 0) " · $t domen śledzących" else "", if (t > 0) "$t" else "", if (t >= 5) 0xFFFF453A.toInt() else WARN) { privSub = "mon:$pkg"; render() })
            }
            header("Ostatnie połączenia")
            val df = java.text.SimpleDateFormat("HH:mm:ss", Locale("pl"))
            for (hit in Dns.recent(60)) {
                val mark = when (hit.state) { 5 -> "☠"; 1, 4 -> "⛔"; 2 -> "🚫"; 3 -> "⚠"; else -> "·" }
                col.addView(tv("${df.format(java.util.Date(hit.t))} $mark ${hit.domain}  ← ${hit.pkg.substringAfterLast('.')}", 12f, color = if (hit.state == 0) DIM else FG).apply { maxLines = 1 })
            }
            col.addView(btn("Odśwież") { render() }, lp(-2, -2, top = 8))
            col.addView(btn("Wyczyść dziennik") { Dns.clear(this); render() }, lp(-2, -2, top = 8))
        }
    }

    private fun renderMonApp(pkg: String) {
        val label = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
        val r = hbox()
        r.addView(tv("‹", 30f, true).apply { setPadding(0, 0, dp(14), 0); setOnClickListener { privSub = "mon"; render() } })
        r.addView(tv(label, 22f, true), lp(0, -2, 1f))
        col.addView(r)
        col.addView(tv("Domeny, z którymi łączyła się ta aplikacja. Stuknij, żeby zablokować lub odblokować domenę dla całego telefonu.", 13f, color = DIM), lp(top = 4))
        for ((d, a) in Dns.domains(pkg)) {
            val mine = d in Cfg.blockDomains
            val allow = d in Cfg.allowDomains
            val tracker = Dns.isTracker(d) || Dns.listed(d)
            val row = hbox().apply { setPadding(0, dp(8), 0, dp(8)) }
            val l = vbox()
            l.addView(tv(d, 14f).apply { maxLines = 1 })
            l.addView(tv("${a[0]}× · " + when { mine -> "zablokowana przez Ciebie"; allow -> "dopuszczona przez Ciebie"; tracker -> "reklamy / śledzenie"; else -> "zwykła" }, 11f, color = if (tracker || mine) WARN else DIM))
            row.addView(l, lp(0, -2, 1f))
            row.addView(tv(when { mine -> "odblokuj"; tracker && !allow -> "dopuść"; allow -> "cofnij"; else -> "zablokuj" }, 12f, true, ACC))
            row.setOnClickListener {
                when {
                    mine -> Cfg.blockDomains.remove(d)
                    allow -> Cfg.allowDomains.remove(d)
                    tracker -> Cfg.allowDomains.add(d)
                    else -> Cfg.blockDomains.add(d)
                }
                Cfg.save(); render()
            }
            col.addView(row)
        }
        col.addView(btn("Odetnij tej aplikacji internet w tle") { if (needShizuku()) return@btn; work(null) { Priv.setNet(this, pkg, false); "Zablokowano" } }, lp(-2, -2, top = 10))
    }

    // ================================================================== profile, mapa pamięci, WhatsApp, autostart, RAM, kopia, powiadomienia
    private var mapPath = ""
    private var bootList: List<Tools.Boot>? = null
    private var ramList: List<Triple<String, String, Long>>? = null
    private var waKind = ""
    private var notifQuery = ""
    private var notifView = "rules"

    private fun profileSheet(net: String) {
        var saver = false; var bt = true; var sync = true
        sheet("Profil tej sieci") { c, d ->
            val name = EditText(this).apply { hint = "Nazwa, np. Dom albo Praca"; setTextColor(FG); setHintTextColor(DIM); background = round(BG, 14); setPadding(dp(12), dp(10), dp(12), dp(10)) }
            c.addView(name, lp(top = 10))
            fun opt(t: String, on: Boolean, f: (Boolean) -> Unit) {
                val r = hbox().apply { setPadding(0, dp(10), 0, dp(10)) }
                r.addView(tv(t, 16f), lp(0, -2, 1f))
                r.addView(Switch(this).apply { isChecked = on; setOnCheckedChangeListener { _, b -> f(b) } })
                c.addView(r)
            }
            opt("Oszczędzanie energii", saver) { saver = it }
            opt("Bluetooth włączony", bt) { bt = it }
            opt("Synchronizacja kont", sync) { sync = it }
            c.addView(btn("Zapisz", true) {
                val n = name.text.toString().trim().ifEmpty { "Sieć" }
                Power.saveProfile(n, net, saver, bt, sync); d.dismiss(); render()
            }, lp(top = 10))
        }
    }

    private fun renderMap() {
        val r = hbox()
        r.addView(tv("‹", 30f, true).apply { setPadding(0, 0, dp(14), 0); setOnClickListener {
            if (mapPath == Mem.root.path) { memSub = "" } else mapPath = mapPath.substringBeforeLast('/')
            render()
        } })
        r.addView(tv("Mapa pamięci", 24f, true), lp(0, -2, 1f))
        col.addView(r)
        col.addView(tv(mapPath.removePrefix(Mem.root.path).ifEmpty { "/" }, 13f, color = DIM), lp(top = 2))
        val list = Tools.folders(mapPath)
        val max = list.firstOrNull()?.second?.coerceAtLeast(1) ?: 1
        val total = list.sumOf { it.second }
        col.addView(tv("Razem ${Mem.fmt(total)}", 14f, true), lp(top = 6))
        for ((name, size) in list.take(80)) {
            val isDir = name.endsWith("/")
            val v = vbox().apply { setPadding(0, dp(8), 0, dp(8)) }
            val hr = hbox()
            hr.addView(tv((if (isDir) "📁 " else "📄 ") + name.trimEnd('/'), 14f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }, lp(0, -2, 1f))
            hr.addView(tv(Mem.fmt(size), 13f, true))
            v.addView(hr)
            val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = round(LINE, 4) }
            bar.addView(View(this).apply { background = round(ACC, 4) }, LinearLayout.LayoutParams(0, dp(6), size.toFloat()))
            bar.addView(View(this), LinearLayout.LayoutParams(0, dp(6), (max - size).toFloat().coerceAtLeast(0f)))
            v.addView(bar, lp(-1, dp(6), top = 4))
            if (isDir) v.setOnClickListener { mapPath = mapPath + "/" + name.trimEnd('/'); render(); sv.scrollTo(0, 0) }
            col.addView(v)
        }
    }

    private fun renderWa() {
        if (waKind.isNotEmpty()) {
            val g = Tools.whatsapp().firstOrNull { it.first == waKind }
            val back = hbox()
            back.addView(tv("‹", 30f, true).apply { setPadding(0, 0, dp(14), 0); setOnClickListener { waKind = ""; memSel.clear(); render() } })
            back.addView(tv("WhatsApp: $waKind", 22f, true), lp(0, -2, 1f))
            col.addView(back)
            val list = g?.second ?: emptyList()
            trashBar(list)
            for (f in list.take(showMax)) col.addView(fileRow(f))
            if (list.size > showMax) col.addView(btn("Pokaż więcej") { showMax += 300; render() }, lp(-2, -2, top = 8))
            return
        }
        backRow("WhatsApp")
        col.addView(tv("WhatsApp nie zapisuje plików osobno dla każdej rozmowy, więc dzielę je według rodzaju. Usunięcie pliku tutaj nie kasuje wiadomości – znika tylko załącznik.", 13f, color = DIM), lp(top = 4))
        for ((name, list) in Tools.whatsapp()) card(name, "${list.size} plików", Mem.fmt(list.sumOf { it.size })) { waKind = name; render() }
    }

    private fun renderBoot() {
        backRow("Autostart")
        col.addView(tv("Aplikacje, które uruchamiają się same po włączeniu telefonu. Wyłączenie działa przez ograniczenie pracy w tle: aplikacja nie startuje z telefonem i nie działa w tle, dopóki jej nie otworzysz – po otwarciu działa normalnie. Komunikatorom, bankom i budzikom zostaw włączony, bo przestaną przychodzić ich powiadomienia.", 13f, color = DIM), lp(top = 4))
        val l = bootList ?: run {
            col.addView(tv("Wczytuję…", 14f, color = DIM), lp(top = 10))
            Thread { val x = Tools.autostart(this, withSystem); h.post { bootList = x; if (memSub == "boot") render() } }.start(); return
        }
        col.addView(tv("${l.count { it.enabled }} z ${l.size} startuje z telefonem", 14f, true), lp(top = 8))
        // hurtowo tylko aplikacjom, których nie otwierałeś od tygodnia i które nie są preinstalowane – reszcie pojedynczo
        val use = Apps.usage(this, 30)
        val week = System.currentTimeMillis() - 7 * 86_400_000L
        val off = l.filter { b ->
            b.enabled && Cfg.cat(b.pkg) != Cat.PROTECT && !b.system && (use[b.pkg] ?: 0L) < week &&
                try { packageManager.getApplicationInfo(b.pkg, 0).flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 } catch (e: Exception) { false }
        }
        if (off.isNotEmpty()) col.addView(btn("Wyłącz nieużywanym od tygodnia (${off.size})", true) {
            if (needShizuku()) return@btn
            work("Wyłączam…") { off.forEach { Tools.setAutostart(this, it, false) }; bootList = null; "Wyłączono autostart ${off.size} aplikacjom" }
        }, lp(-2, -2, top = 8))
        for (b in l) col.addView(appIconRow(b.pkg, b.label, if (Cfg.cat(b.pkg) == Cat.PROTECT) "chroniona" else if (b.system) "systemowa" else "",
            if (b.enabled) "STARTUJE" else "wyłączony", if (b.enabled) WARN else ACC) {
            if (needShizuku()) return@appIconRow
            work(null) { Tools.setAutostart(this, b, !b.enabled); bootList = null; if (b.enabled) "Autostart wyłączony: ${b.label}" else "Autostart włączony: ${b.label}" }
        })
    }

    private fun renderRam() {
        backRow("Pamięć RAM")
        val am = getSystemService(android.app.ActivityManager::class.java)
        val mi = android.app.ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        col.addView(tv("${Mem.fmt(mi.totalMem - mi.availMem)} z ${Mem.fmt(mi.totalMem)} zajęte", 16f, true), lp(top = 8))
        col.addView(tv("Android sam zarządza pamięcią i wolny RAM nie przyspiesza telefonu. Zatrzymuj tylko aplikacje, które wyraźnie przesadzają – inaczej uruchomią się od nowa i zużyją więcej baterii.", 12f, color = DIM), lp(top = 4))
        val l = ramList ?: run {
            col.addView(tv("Wczytuję…", 14f, color = DIM), lp(top = 10))
            Thread { val x = Tools.ram(this); h.post { ramList = x; if (memSub == "ram") render() } }.start(); return
        }
        if (l.isEmpty()) col.addView(tv("Brak dostępu do statystyk – uruchom raz Shizuku.", 13f, true, WARN), lp(top = 8))
        for ((pkg, label, bytes) in l) col.addView(appIconRow(pkg, label, Mem.fmt(bytes), "Zatrzymaj", ACC) {
            if (needShizuku()) return@appIconRow
            work(null) { Mem.hibernate(listOf(pkg)); ramList = null; "Zatrzymano $label" }
        })
    }

    private fun renderBackup() {
        backRow("Kopia zdjęć")
        col.addView(tv("Kopiuje nowe zdjęcia i filmy do folderu, który wskażesz: pendrive USB-C, karta, albo dysk w sieci domowej (jeśli masz go dodanego w Moich plikach). Układa je w folderach miesiącami. Nic nie idzie do chmury.", 13f, color = DIM), lp(top = 4))
        col.addView(btn(if (Cfg.backupTree.isEmpty()) "Wybierz folder docelowy" else "Zmień folder docelowy", Cfg.backupTree.isEmpty()) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 51)
        }, lp(-2, -2, top = 8))
        if (Cfg.backupTree.isNotEmpty()) {
            val pend = Tools.backupPending(this)
            col.addView(tv("Do skopiowania: ${pend.size} plików · ${Mem.fmt(pend.sumOf { it.size })}", 15f, true), lp(top = 10))
            if (pend.isNotEmpty()) col.addView(btn("Kopiuj teraz", true) {
                memWork("Kopiuję…") { p -> val (n, b) = Tools.backup(this, Uri.parse(Cfg.backupTree), p); "Skopiowano $n plików (${Mem.fmt(b)})" }
            }, lp(-2, -2, top = 8))
            else col.addView(tv("Wszystko skopiowane 👍", 14f), lp(top = 6))
        }
    }

    private fun renderNotif() {
        col.addView(tv("Powiadomienia", 34f, true))
        if (!Notif.enabled(this)) {
            col.addView(tv("Żeby prowadzić historię, paczki i ciche godziny, Bastion potrzebuje dostępu do powiadomień. Wszystko zostaje w telefonie.", 14f, color = DIM), lp(top = 8))
            col.addView(btn("Włącz dostęp", true) { work(null) { Notif.enable(this); Thread.sleep(700); null } }, lp(-2, -2, top = 8))
            return
        }
        val tr = hbox()
        for ((k, n) in listOf("rules" to "Zasady", "hist" to "Historia")) tr.addView(btn(n, notifView == k) { notifView = k; render() }, lp(-2, -2).apply { rightMargin = dp(6) })
        col.addView(tr, lp(top = 10))
        if (notifView == "hist") {
            col.addView(field(notifQuery, "Szukaj w powiadomieniach") { notifQuery = it; h.removeCallbacks(notifRedraw); h.postDelayed(notifRedraw, 500) }, lp(top = 8))
            val q = notifQuery.trim().lowercase()
            val pm = packageManager
            val df = java.text.SimpleDateFormat("d.MM HH:mm", Locale("pl"))
            val l = Notif.all(this).toList().reversed().filter { q.isEmpty() || it.optString("ti").lowercase().contains(q) || it.optString("tx").lowercase().contains(q) }
            col.addView(tv("${l.size} powiadomień (także usunięte i zatrzymane)", 12f, color = DIM), lp(top = 6))
            for (o in l.take(300)) {
                val p = o.optString("p")
                val name = try { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() } catch (e: Exception) { p }
                val v = vbox().apply { setPadding(0, dp(8), 0, dp(8)) }
                v.addView(tv(name + " · " + df.format(java.util.Date(o.optLong("t"))) + (if (o.optString("b").isNotEmpty()) " · zatrzymane: " + o.optString("b") else ""), 11f, true, if (o.optString("b").isNotEmpty()) WARN else DIM))
                if (o.optString("ti").isNotEmpty()) v.addView(tv(o.optString("ti"), 14f, true))
                if (o.optString("tx").isNotEmpty()) v.addView(tv(o.optString("tx"), 13f).apply { setTextIsSelectable(true) })
                col.addView(v); col.addView(line(), lp(-1, 1))
            }
            col.addView(btn("Wyczyść historię") { Notif.clear(this); render() }, lp(-2, -2, top = 10))
            return
        }
        toggle("Zapisuj historię", "Każde powiadomienie trafia do historii – także te, które nadawca potem usunął.", Cfg.notifHistory) { Cfg.notifHistory = it; Cfg.save() }
        header("Blokuj po słowach")
        col.addView(tv("Powiadomienia zawierające te słowa znikają od razu (zostają w historii). Oddziel przecinkami.", 12f, color = DIM), lp(top = 2))
        col.addView(field(Cfg.notifWords.joinToString(", "), "np. promocja, rabat, -50%, wygraj") {
            Cfg.notifWords.clear(); Cfg.notifWords.addAll(it.split(',').map { w -> w.trim() }.filter { w -> w.length >= 3 }); Cfg.save()
        }, lp(top = 6))
        header("Ciche godziny")
        val qr = hbox()
        qr.addView(tv("Od ${Cfg.quietFrom}:00 do ${Cfg.quietTo}:00", 16f), lp(0, -2, 1f))
        qr.addView(btn("Zmień") {
            sheet("Ciche godziny") { c, d ->
                fun pick(label: String, cur: Int, f: (Int) -> Unit) {
                    c.addView(tv(label, 13f, color = DIM), lp(top = 10))
                    val r = hbox()
                    for (hh in listOf(18, 20, 21, 22, 23, 0, 5, 6, 7, 8, 9)) r.addView(btn("$hh", hh == cur) { f(hh); Cfg.save(); d.dismiss(); render() }, lp(-2, -2).apply { rightMargin = dp(6) })
                    c.addView(HorizontalScrollView(this).apply { addView(r) }, lp(top = 4))
                }
                pick("Od", Cfg.quietFrom) { Cfg.quietFrom = it }
                pick("Do", Cfg.quietTo) { Cfg.quietTo = it }
            }
        })
        col.addView(qr, lp(top = 6))
        header("Aplikacje")
        col.addView(tv("Stuknij, żeby zmienić: Normalnie → Paczka (zbiorczo o 9:00, 13:00 i 18:00) → Ciche godziny → Normalnie. Chronione aplikacje zostaw w spokoju.", 12f, color = DIM), lp(top = 2))
        if (apps.isEmpty()) { col.addView(tv("Wczytuję aplikacje…", 14f, color = DIM), lp(top = 12)); if (!appsLoading) { appsLoading = true; Thread { val l = Apps.list(this, false); h.post { apps = l; appsLoading = false; if (tab == "notif") render() } }.start() }; return }
        val cnt = Priv.notifCounts(this)
        for (a in apps.sortedByDescending { cnt[it.pkg] ?: 0 }) {
            val mode = when (a.pkg) { in Cfg.bundleApps -> "PACZKA"; in Cfg.quietApps -> "CICHE GODZINY"; else -> "normalnie" }
            col.addView(appIconRow(a.pkg, a.label, "${cnt[a.pkg] ?: 0} / 24 h", mode, if (mode == "normalnie") DIM else ACC) {
                when (a.pkg) {
                    in Cfg.bundleApps -> { Cfg.bundleApps.remove(a.pkg); Cfg.quietApps.add(a.pkg) }
                    in Cfg.quietApps -> Cfg.quietApps.remove(a.pkg)
                    else -> Cfg.bundleApps.add(a.pkg)
                }
                Cfg.save(); Engine.scheduleDigest(this); render()
            })
        }
    }

    private val notifRedraw = Runnable { if (tab == "notif") render() }

    // ================================================================== sejf i blokada aplikacji
    private var vaultOpen = false
    private var vaultPicking = false
    private var vaultDelete = true

    private fun renderVault() {
        if (!vaultOpen) { privSub = ""; return renderPriv() }
        privBack("Sejf")
        val l = Vault.items(this).sortedByDescending { it.optLong("d") }
        col.addView(tv("${l.size} plików · ${Mem.fmt(l.sumOf { it.optLong("s") })}. Szyfrowanie AES-256; klucz w bezpiecznym układzie telefonu, działa tylko po odblokowaniu odciskiem lub kodem. Sejf zamyka się przy wyjściu z aplikacji.", 13f, color = DIM), lp(top = 4))
        col.addView(btn("Dodaj pliki", true) {
            vaultPicking = true
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), 41)
        }, lp(-2, -2, top = 8))
        val dr = hbox()
        dr.addView(tv(if (vaultDelete) "☑ Usuń oryginały po dodaniu" else "☐ Usuń oryginały po dodaniu", 14f, color = DIM).apply { setOnClickListener { vaultDelete = !vaultDelete; render() } })
        col.addView(dr, lp(top = 8))
        val df = java.text.SimpleDateFormat("d.MM.yyyy", Locale("pl"))
        for (o in l) {
            val r = hbox().apply { setPadding(0, dp(10), 0, dp(10)) }
            val v = vbox()
            v.addView(tv(o.optString("n"), 15f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
            v.addView(tv(Mem.fmt(o.optLong("s")) + " · dodano " + df.format(java.util.Date(o.optLong("d"))), 12f, color = DIM))
            r.addView(v, lp(0, -2, 1f))
            r.setOnClickListener { vaultItem(o) }
            col.addView(r); col.addView(line(), lp(-1, 1))
        }
        if (l.isEmpty()) col.addView(tv("Sejf jest pusty.", 14f, color = DIM), lp(top = 12))
        col.addView(tv("Ważne: sejf jest związany z tym telefonem. Po przywróceniu ustawień fabrycznych albo odinstalowaniu Bastionu plików nie da się odzyskać – trzymaj kopię najważniejszych dokumentów także gdzie indziej.", 12f, color = WARN), lp(top = 14))
    }

    private fun vaultItem(o: org.json.JSONObject) {
        sheet(o.optString("n")) { c, d ->
            c.addView(btn("Otwórz", true) {
                d.dismiss()
                vaultPicking = true
                work("Odszyfrowuję…") {
                    val f = Vault.view(this, o)
                    h.post {
                        try {
                            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
                            val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
                            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        } catch (e: Exception) { toast("Brak aplikacji do otwarcia tego pliku") }
                    }
                    null
                }
            }, lp(top = 10))
            c.addView(btn("Wyjmij z sejfu (do Pobranych)") {
                d.dismiss()
                work("Odszyfrowuję…") {
                    val dir = java.io.File(Mem.root, "Download/Sejf").apply { mkdirs() }
                    val f = java.io.File(dir, o.optString("n").replace('/', '_'))
                    f.outputStream().use { Vault.decrypt(this, o.optString("id"), it) }
                    Vault.remove(this, o.optString("id"))
                    "Zapisano w Pobrane/Sejf"
                }
            }, lp(top = 8))
            c.addView(btn("Usuń na zawsze") {
                d.dismiss()
                android.app.AlertDialog.Builder(this).setTitle("Usunąć na zawsze?").setMessage(o.optString("n"))
                    .setPositiveButton("Usuń") { _, _ -> Vault.remove(this, o.optString("id")); render() }.setNegativeButton("Anuluj", null).show()
            }, lp(top = 8))
        }
    }

    private fun renderAppLock() {
        privBack("Blokada aplikacji")
        col.addView(tv("Zaznaczone aplikacje poproszą o odcisk palca (albo kod telefonu) przy każdym otwarciu. Blokada wraca po wyjściu z aplikacji i po wygaszeniu ekranu.", 13f, color = DIM), lp(top = 4))
        if (!Sh.granted() && Cfg.locked.isEmpty()) col.addView(tv("Pierwsze włączenie wymaga działającego Shizuku (Bastion nadaje sobie prawo wyświetlenia ekranu blokady nad inną aplikacją).", 12f, true, WARN), lp(top = 6))
        if (apps.isEmpty()) { col.addView(tv("Wczytuję aplikacje…", 14f, color = DIM), lp(top = 12)); loadAppsFor("applock"); return }
        for (a in apps.sortedWith(compareBy<App> { it.pkg !in Cfg.locked }.thenBy { it.label.lowercase() })) {
            val on = a.pkg in Cfg.locked
            col.addView(appIconRow(a.pkg, a.label, "", if (on) "🔒 ZABLOKOWANA" else "", ACC) {
                if (on) Cfg.locked.remove(a.pkg) else Cfg.locked.add(a.pkg)
                Cfg.save()
                if (!on) Thread { Sys.grantAll(this) }.start()
                render()
            })
        }
    }

    private fun loadAppsFor(sub: String) {
        if (appsLoading) return
        appsLoading = true
        Thread { val l = Apps.list(this, false); h.post { apps = l; appsLoading = false; if (privSub == sub) render() } }.start()
    }

    // ================================================================== linki, kradzież, Wi-Fi, hasła
    private var linkText = ""
    private var lanList: List<String>? = null
    private var passText = ""

    private fun field(text: String, hint: String, password: Boolean = false, onChange: (String) -> Unit) = EditText(this).apply {
        setText(text); this.hint = hint; setTextColor(FG); setHintTextColor(DIM); textSize = 15f
        background = round(SURF, 14); setPadding(dp(12), dp(10), dp(12), dp(10))
        if (password) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { onChange(s?.toString() ?: "") }
        })
    }

    private fun renderLinks() {
        privBack("Fałszywe linki")
        col.addView(tv("Wklej link albo całą wiadomość. Możesz też w dowolnej aplikacji wybrać „Udostępnij → Bastion”. Sprawdzam w telefonie: listy ostrzeżeń CERT Polska, bazę złośliwych domen, podszywanie się pod banki, kurierów i urzędy, znaki udające litery.", 13f, color = DIM), lp(top = 4))
        col.addView(field(linkText, "https://… albo treść SMS-a") { linkText = it }, lp(top = 8))
        val r = hbox()
        r.addView(btn("Sprawdź", true) { render() }, lp(-2, -2).apply { rightMargin = dp(8) })
        r.addView(btn("Wklej ze schowka") {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            linkText = cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""; render()
        })
        col.addView(r, lp(top = 8))
        if (Dns.lists.isEmpty()) col.addView(tv("⚠ Listy ostrzeżeń nie są pobrane – Strażnik sieci → „Pobierz listy”. Bez nich działają tylko moje reguły.", 12f, true, WARN), lp(top = 8))
        for (u in Links.extract(linkText).ifEmpty { if (linkText.contains('.') && !linkText.contains(' ')) listOf(linkText.trim()) else emptyList() }) {
            val v = Links.check(this, u)
            val box = vbox().apply { background = round(SURF, 16); setPadding(dp(14), dp(12), dp(14), dp(12)) }
            box.addView(tv(when (v.level) { 2 -> "⛔ NIEBEZPIECZNY"; 1 -> "⚠ Podejrzany"; else -> "✓ Nie widzę zagrożeń" }, 17f, true, when (v.level) { 2 -> 0xFFFF453A.toInt(); 1 -> WARN; else -> ACC }))
            box.addView(tv(v.host, 14f), lp(top = 2))
            for (reason in v.reasons) box.addView(tv("• $reason", 13f, color = DIM), lp(top = 2))
            if (v.level == 0) box.addView(tv("To nie gwarancja – nowe oszustwa pojawiają się codziennie. Nie podawaj danych karty ani kodów BLIK po kliknięciu w link z wiadomości.", 12f, color = DIM), lp(top = 4))
            col.addView(box, lp(top = 10))
        }
        toggle("Sprawdzaj linki w SMS-ach", "Każdy nowy SMS z linkiem jest oceniany od razu; przy zagrożeniu dostajesz ostrzeżenie. Treść SMS-ów nie opuszcza telefonu.", Cfg.smsGuard) {
            Cfg.smsGuard = it; Cfg.save()
            if (it && checkSelfPermission(android.Manifest.permission.RECEIVE_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(android.Manifest.permission.RECEIVE_SMS), 31)
        }
    }

    private fun renderTheft() {
        privBack("Ochrona przed kradzieżą")
        Theft.markSeen(this)
        val active = Theft.active(this)
        col.addView(tv("Gdy ktoś kilka razy pomyli kod odblokowania: głośna syrena (nawet przy wyciszonym telefonie), wpis w dzienniku i próba zdjęcia przednim aparatem.", 13f, color = DIM), lp(top = 4))
        if (!active) col.addView(btn("Włącz (wymaga uprawnienia administratora)", true) {
            startActivityForResult(Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, Theft.admin(this))
                .putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Bastion musi wiedzieć o błędnych próbach odblokowania."), 32)
        }, lp(-2, -2, top = 8))
        toggle("Ochrona włączona", if (active) "Bastion widzi błędne próby odblokowania" else "Najpierw nadaj uprawnienie powyżej", Cfg.antiTheft && active) {
            Cfg.antiTheft = it; Cfg.save()
            if (it && checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 33)
        }
        toggle("Syrena", "Dźwięk alarmu na pełnej głośności przez minutę albo do poprawnego odblokowania", Cfg.theftSiren) { Cfg.theftSiren = it; Cfg.save() }
        val tr = hbox()
        tr.addView(tv("Po ilu błędnych próbach", 16f), lp(0, -2, 1f))
        for (n in listOf(2, 3, 5)) tr.addView(btn("$n", Cfg.theftTries == n) { Cfg.theftTries = n; Cfg.save(); render() }, lp(-2, -2).apply { leftMargin = dp(6) })
        col.addView(tr, lp(top = 10))
        col.addView(tv("Zdjęcie: Android nie zawsze pozwala aplikacji użyć aparatu w tle – jeśli się nie uda, zostaje syrena i wpis w dzienniku.", 12f, color = DIM), lp(top = 8))
        header("Dziennik")
        val df = java.text.SimpleDateFormat("d.MM.yyyy HH:mm:ss", Locale("pl"))
        val l = Theft.entries(this).reversed()
        if (l.isEmpty()) col.addView(tv("Brak zdarzeń.", 14f, color = DIM), lp(top = 4))
        for (o in l.take(40)) {
            col.addView(tv(df.format(java.util.Date(o.optLong("t"))) + " – " + o.optString("s"), 13f), lp(top = 6))
            val ph = o.optString("p")
            if (ph.isNotEmpty() && java.io.File(ph).exists()) {
                val iv = ImageView(this).apply { adjustViewBounds = true }
                try { iv.setImageBitmap(android.graphics.BitmapFactory.decodeFile(ph, android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 })) } catch (e: Throwable) {}
                col.addView(iv, lp(-1, -2, top = 4))
            }
        }
    }

    private fun renderWifi() {
        privBack("Sieć Wi-Fi")
        val cur = WifiGuard.current(this)
        if (cur == null) col.addView(tv("Nie jesteś połączony z Wi-Fi.", 15f), lp(top = 8))
        else {
            val box = vbox().apply { background = round(SURF, 16); setPadding(dp(14), dp(12), dp(14), dp(12)) }
            box.addView(tv(when (cur.first) { 2 -> "⛔ Niebezpieczna sieć"; 1 -> "⚠ Uwaga"; else -> "✓ Sieć zabezpieczona" }, 17f, true, when (cur.first) { 2 -> 0xFFFF453A.toInt(); 1 -> WARN; else -> ACC }))
            box.addView(tv(cur.second, 13f, color = DIM), lp(top = 4))
            col.addView(box, lp(top = 8))
        }
        toggle("Ostrzegaj przy niebezpiecznej sieci", "Powiadomienie po połączeniu z siecią otwartą lub ze starym zabezpieczeniem WEP.", Cfg.wifiGuard) { Cfg.wifiGuard = it; Cfg.save() }
        if (cur != null) {
            header("Urządzenia w tej sieci")
            col.addView(btn("Wyszukaj urządzenia", lanList == null) {
                privWork("Szukam urządzeń…") { p -> lanList = WifiGuard.scanLan(this, p); null }
            }, lp(-2, -2, top = 6))
            lanList?.let { l ->
                col.addView(tv("Odpowiedziało ${l.size} urządzeń. Nieznane urządzenie w domowej sieci to sygnał, żeby zmienić hasło do Wi-Fi.", 13f, color = DIM), lp(top = 8))
                for (d in l) col.addView(tv(d, 14f).apply { setTextIsSelectable(true) }, lp(top = 6))
            }
        }
    }

    private fun renderPass() {
        privBack("Sprawdź hasło")
        col.addView(tv("Hasło jest oceniane wyłącznie w telefonie i nigdzie nie jest zapisywane ani wysyłane. Sprawdzenie, czy konkretne hasło wyciekło w konkretnym serwisie, wymagałoby wysłania go (choćby we fragmencie) do zewnętrznej bazy – tego celowo nie robię.", 13f, color = DIM), lp(top = 4))
        if (!Pass.ready(this)) col.addView(btn("Pobierz listę najczęściej łamanych haseł (raz)", true) {
            work("Pobieram…") { "Lista gotowa: ${Pass.download(this)} haseł" }
        }, lp(-2, -2, top = 8))
        col.addView(field(passText, "Wpisz hasło do sprawdzenia", true) { passText = it; h.removeCallbacks(passRedraw); h.postDelayed(passRedraw, 700) }, lp(top = 8))
        if (passText.isNotEmpty()) {
            val v = Pass.check(this, passText)
            col.addView(tv("${v.score} / 100", 36f, true, if (v.score >= 70) ACC else if (v.score >= 45) WARN else 0xFFFF453A.toInt()), lp(top = 10))
            for (n in v.notes) col.addView(tv("• $n", 14f), lp(top = 4))
        }
    }

    private val passRedraw = Runnable { if (privSub == "pass") render() }

    /** Liczy ranking w tle i odświeża ekran. */
    private fun loadRanking(force: Boolean) {
        if (rankBusy) return
        if (!Sys.canDump(this) && !Sh.granted()) { rankErr = "Brak dostępu do statystyk – uruchom raz Shizuku, żeby nadać stałe uprawnienia"; render(); return }
        rankBusy = true; rankErr = ""
        if (force) { ranking = null; render() }
        Thread {
            val r = try { Engine.ranking(this) } catch (e: Exception) { rankErr = e.message ?: "błąd"; null }
            h.post { ranking = r; rankBusy = false; if (tab == "use") render() }
        }.start()
    }

    /** Pasek na dole przy zaznaczonych aplikacjach: przenieś do kategorii jednym stuknięciem. */
    private fun drawActionBar() {
        actionBar.removeAllViews()
        if (tab != "apps" || sel.isEmpty()) { actionBar.visibility = View.GONE; return }
        actionBar.visibility = View.VISIBLE
        actionBar.addView(tv("Zaznaczono: ${sel.size}", 15f, true), lp(0, -2, 1f))
        actionBar.addView(btn("Przenieś do…", true) { moveSheet(sel.toList()) }, lp(-2, -2).apply { rightMargin = dp(8) })
        actionBar.addView(btn("✕") { sel.clear(); render() })
    }
}

/** Wykres poziomu baterii z ostatnich 24 h (zielone tło = ładowanie, jaśniejsze = włączony ekran). */
class Chart(c: Context, private val pts: List<Engine.Point>) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(cv: Canvas) {
        super.onDraw(cv)
        val w = width.toFloat(); val hh = height.toFloat()
        val now = System.currentTimeMillis(); val from = now - 86_400_000L
        p.style = Paint.Style.FILL; p.color = Theme.surface
        cv.drawRoundRect(0f, 0f, w, hh, 24f, 24f, p)
        p.color = Theme.line; p.strokeWidth = 2f
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
            p.color = when { a.charging -> Theme.withAlpha(Theme.accent, 0.20f); a.screen -> Theme.withAlpha(Theme.fg, 0.08f); else -> 0 }
            if (p.color != 0) cv.drawRect(x(a.t), 0f, x(b.t), hh, p)
        }
        val path = Path()
        list.forEachIndexed { i, pt -> if (i == 0) path.moveTo(x(pt.t), y(pt.level)) else path.lineTo(x(pt.t), y(pt.level)) }
        path.lineTo(w, y(list.last().level))
        p.style = Paint.Style.STROKE; p.strokeWidth = 5f; p.color = Theme.accent
        cv.drawPath(path, p)
        p.style = Paint.Style.FILL; p.color = 0xFF9A9A9E.toInt(); p.textSize = 26f
        cv.drawText("-24 h", 12f, hh - 10f, p); cv.drawText("teraz", w - 70f, hh - 10f, p); cv.drawText("100%", 12f, 30f, p)
    }
}

@Suppress("unused")
private fun keep(v: ViewGroup, f: FrameLayout) = Unit
