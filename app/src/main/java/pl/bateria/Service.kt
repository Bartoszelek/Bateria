package pl.bateria

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Stała usługa: reaguje na wygaszenie/włączenie ekranu i zapisuje historię baterii. */
class BatteryService : Service() {
    companion object {
        @Volatile var alive = false

        fun start(c: Context) {
            try { c.startForegroundService(Intent(c, BatteryService::class.java)) } catch (e: Exception) {}
        }
    }

    private var level = -1
    private var plugged = false

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    h.removeCallbacks(lockTick)
                    AppLock.reset()
                    Vault.wipeTemp(c)
                    if (Cfg.clipClear) try { getSystemService(android.content.ClipboardManager::class.java)?.clearPrimaryClip() } catch (e: Exception) {}
                    log(false)
                    Thread { Engine.onScreenOff(c) }.start()
                }
                Intent.ACTION_SCREEN_ON -> {
                    h.removeCallbacks(lockTick); h.postDelayed(lockTick, 500)
                    log(true)
                    Thread { Engine.onScreenOn(c) }.start()
                }
                Intent.ACTION_BATTERY_CHANGED -> {
                    level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                    plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                    try { Power.onBattery(c, level, plugged, i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0), i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)) } catch (e: Exception) {}
                    log(getSystemService(PowerManager::class.java)?.isInteractive == true)
                }
            }
        }
    }

    private fun log(screen: Boolean) {
        if (level >= 0) Thread { Engine.log(this, level, plugged, screen) }.start()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Cfg.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel("svc", "Bastion w tle", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, "svc")
            .setSmallIcon(Icon.createWithResource(this, R.drawable.ic_bolt))
            .setContentTitle("Bastion czuwa")
            .setContentText("Oszczędzanie po wygaszeniu ekranu")
            .setContentIntent(PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
        registerReceiver(rx, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_BATTERY_CHANGED)
        })
        alive = true
        // nowe aplikacje: trackery, uprawnienia, antywirus
        registerReceiver(pkgRx, IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") })
        watchDownloads()
        h.postDelayed(lockTick, 1000)
        if (Cfg.monitor) NetBlock.monitor(this, true)
        try {
            Shizuku.addBinderReceivedListenerSticky(onUp)
            Shizuku.addBinderDeadListener(onDown)
        } catch (e: Throwable) {}
        h.postDelayed(watch, 60_000)
        // gdy telefon złapie Wi-Fi, a Shizuku nie działa – przygotuj uruchomienie i przypomnij
        try {
            getSystemService(android.net.ConnectivityManager::class.java)?.registerNetworkCallback(
                android.net.NetworkRequest.Builder().addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build(), wifiCb)
        } catch (e: Exception) {}
        Sys.keepAdb(this)
        Thread {
            Engine.reapply()
            Engine.scheduleNight(this)
            Engine.scheduleClean(this)
            Engine.scheduleDigest(this)
            if (Cfg.phantom) Power.phantomFix(true)
            Engine.autoByUsage(this)
        }.start()
    }

    private val h = android.os.Handler(android.os.Looper.getMainLooper())

    /** Blokada aplikacji: co pół sekundy sprawdzamy, co jest na ekranie (tylko gdy coś jest zablokowane). */
    private val lockTick = object : Runnable {
        override fun run() {
            if (Cfg.locked.isNotEmpty()) { try { AppLock.tick(this@BatteryService) } catch (e: Exception) {}; h.postDelayed(this, 500) }
            else h.postDelayed(this, 5000)
        }
    }

    private val pkgRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val pkg = i.data?.schemeSpecificPart ?: return
            if (i.getBooleanExtra(Intent.EXTRA_REPLACING, false) || pkg == packageName) return
            Thread {
                try {
                    if (Cfg.alerts) Alerts.newApp(c, pkg)
                    if (Cfg.avRealtime) {
                        val ai = packageManager.getApplicationInfo(pkg, 0)
                        Av.quick(c, ai.sourceDir, pkg)?.let { r -> if (r.level >= 1) Alerts.threat(c, r) }
                    }
                } catch (e: Exception) {
                }
            }.start()
        }
    }

    private var dlObserver: android.os.FileObserver? = null

    /** Każdy nowy plik w Pobranych jest od razu sprawdzany. */
    private fun watchDownloads() {
        if (!Mem.allFiles()) return
        val dir = java.io.File(Mem.root, "Download")
        if (!dir.isDirectory) return
        dlObserver = object : android.os.FileObserver(dir, android.os.FileObserver.CLOSE_WRITE or android.os.FileObserver.MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null || !Cfg.avRealtime) return
                val f = java.io.File(dir, path)
                val ext = path.substringAfterLast('.', "").lowercase()
                if (ext !in Av.SUSPECT_EXT || path.startsWith(".") || ext == "crdownload") return
                h.postDelayed({
                    Thread { try { if (f.exists()) Av.quick(this@BatteryService, f.path, null)?.let { r -> if (r.level >= 1) Alerts.threat(this@BatteryService, r) } } catch (e: Exception) {} }.start()
                }, 3000)
            }
        }.also { it.startWatching() }
    }

    private val wifiCb = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onLost(network: android.net.Network) { Power.onWifiLost() }

        override fun onAvailable(network: android.net.Network) {
            h.postDelayed({
                Thread { try { Power.onWifi(this@BatteryService) } catch (e: Exception) {} }.start()
                if (Cfg.wifiGuard) try {
                    WifiGuard.current(this@BatteryService)?.let { (lvl, txt) ->
                        if (lvl == 2 && Cfg.travel) {
                            Thread { Sh.run("svc wifi disable") }.start()
                            Alerts.plain(this@BatteryService, 4712, "Tryb podróży: rozłączyłem niebezpieczną sieć Wi-Fi", txt)
                        } else if (lvl == 2) Alerts.plain(this@BatteryService, 4712, "Niebezpieczna sieć Wi-Fi", txt)
                    }
                } catch (e: Exception) {}
                if (!Sh.granted()) shizukuDown(fromWifi = true)
            }, 4000)
        }
    }

    private val onUp = Shizuku.OnBinderReceivedListener { h.postDelayed({ shizukuUp() }, 1500) }
    private val onDown = Shizuku.OnBinderDeadListener { h.post { shizukuDown() } }

    /** Co 5 minut: czy Shizuku żyje (na wypadek, gdyby nie przyszło zdarzenie). */
    private val watch = object : Runnable {
        override fun run() {
            if (Sh.granted()) { if (!Cfg.shizukuWasUp) shizukuUp() } else if (Cfg.shizukuWasUp) shizukuDown()
            h.postDelayed(this, 5 * 60_000L)
        }
    }

    private fun shizukuUp() {
        if (!Sh.granted()) return
        getSystemService(NotificationManager::class.java)?.cancel(2)
        Cfg.shizukuWasUp = true
        Cfg.save()
        Thread {
            Sys.keepAdb(this)
            Sys.grantAll(this)          // stałe uprawnienia – działają potem bez Shizuku
            Engine.applyPending()       // zaległe zmiany kategorii
            Engine.reapply()
            if (getSystemService(PowerManager::class.java)?.isInteractive == true) Engine.onScreenOn(this) // odmroź, co zostało zamrożone
        }.start()
    }

    private fun shizukuDown(fromWifi: Boolean = false) {
        if (!fromWifi) {
            if (!Cfg.shizukuWasUp) return
            Cfg.shizukuWasUp = false
            Cfg.drops.add(System.currentTimeMillis())
            Cfg.save()
        }
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel("shizuku", "Shizuku", NotificationManager.IMPORTANCE_DEFAULT))
        // od razu przygotuj ponowne uruchomienie: debugowanie bezprzewodowe włączone (gdy jest Wi-Fi)
        val ready = Sys.wirelessDebugOn(this)
        val launch = packageManager.getLaunchIntentForPackage(Sys.shizukuPkg(this)) ?: Intent(this, MainActivity::class.java)
        val frozen = Cfg.frozenNow.size
        val text = (if (ready) "Debugowanie bezprzewodowe już włączone – stuknij i w Shizuku wybierz „Uruchom”. "
            else "Połącz się z dowolnym Wi-Fi – Bastion sam włączy debugowanie i da znać. ") +
            "Oszczędzanie działa dalej, czekają tylko zamrażanie i zmiany kategorii." +
            if (frozen > 0) " Zamrożone aplikacje ($frozen) odmrożą się po uruchomieniu." else ""
        try {
            nm.notify(2, Notification.Builder(this, "shizuku")
                .setSmallIcon(Icon.createWithResource(this, R.drawable.ic_bolt))
                .setContentTitle("Shizuku wyłączone")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(PendingIntent.getActivity(this, 3, launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true)
                .build())
        } catch (e: SecurityException) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        alive = false
        h.removeCallbacks(watch)
        try { getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(wifiCb) } catch (e: Exception) {}
        try { Shizuku.removeBinderReceivedListener(onUp); Shizuku.removeBinderDeadListener(onDown) } catch (e: Throwable) {}
        try { unregisterReceiver(rx) } catch (e: Exception) {}
        try { unregisterReceiver(pkgRx) } catch (e: Exception) {}
        try { dlObserver?.stopWatching() } catch (e: Exception) {}
        super.onDestroy()
    }
}

/** Alarmy: działania po wygaszeniu, noc, strażnik, „Ogranicz” z powiadomienia, start po restarcie. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Cfg.init(c)
        val a = i.action ?: return
        if (a == Intent.ACTION_BOOT_COMPLETED || a == Intent.ACTION_MY_PACKAGE_REPLACED) {
            BatteryService.start(c); return
        }
        val pending = goAsync()
        Thread {
            try {
                when {
                    a.startsWith("off_") -> Engine.runOff(c, a.removePrefix("off_"))
                    a == "doze" -> if (!Engine.charging(c)) Engine.doze()
                    a == "guard" -> Engine.guardCheck(c)
                    a == "clean" -> {
                        Engine.dailyClean(c)
                        // listy blokowania odświeżane co tydzień
                        if (Cfg.monitor && System.currentTimeMillis() - Cfg.listsAt > 7 * 86_400_000L) try { Dns.updateLists(c) { } } catch (e: Exception) {}
                        // bazy sygnatur antywirusa – co 3 dni
                        if (Sig.ready(c) && System.currentTimeMillis() - Cfg.sigAt > 3 * 86_400_000L) try { Sig.update(c) { } } catch (e: Exception) {}
                    }
                    a == "digest" -> { Notif.digest(c); Engine.scheduleDigest(c) }
                    a == "netdeny" -> i.getStringExtra("pkg")?.let { p -> Priv.setNet(c, p, false) }
                    a == "night_on" -> Engine.night(c, true)
                    a == "night_off" -> Engine.night(c, false)
                    a == "limit" -> {
                        val p = i.getStringExtra("pkg")
                        if (p != null) {
                            Engine.setCat(c, listOf(p), Cat.BG)
                            c.getSystemService(NotificationManager::class.java)?.cancel(p.hashCode())
                        }
                    }
                }
            } catch (e: Exception) {
            }
            pending.finish()
        }.start()
    }
}

/** Kafelek „Tryb oszczędny” w szybkich ustawieniach. */
class SaverTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let {
            it.state = if (Engine.saverOn(this)) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            it.label = "Oszczędzanie"
            it.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        Cfg.init(this)
        val on = !Engine.saverOn(this)
        Thread {
            Engine.saver(on)
            if (on && !Cfg.hz60) Engine.applyHz(true) else if (!on && !Cfg.hz60) Engine.applyHz(false)
            Cfg.save()
        }.start()
        qsTile?.let { it.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE; it.updateTile() }
    }
}

/** Sprawdza i pobiera nowe wersje z GitHub Releases (prywatne repozytorium, dostęp tokenem) – jak w Kartach i Audiotece. */
object Updater {
    const val REPO = "Bartoszelek/bateria"

    class Info(val number: Int, val assetUrl: String, val title: String)

    private fun open(url: String, token: String?, accept: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = false
        if (!token.isNullOrBlank()) c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept", accept)
        c.setRequestProperty("User-Agent", "Bateria")
        return c
    }

    fun check(token: String): Info? {
        val c = open("https://api.github.com/repos/$REPO/releases/latest", token, "application/vnd.github+json")
        val code = c.responseCode
        if (code == 401) throw IOException("Token jest nieprawidłowy albo wygasł")
        if (code == 404) throw IOException("Nie znaleziono wydań – zbuduj aplikację na GitHubie")
        if (code != 200) throw IOException("GitHub odpowiedział kodem $code")
        val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val n = o.optString("tag_name").substringAfter("build-").toIntOrNull() ?: return null
        val assets = o.optJSONArray("assets") ?: return null
        for (k in 0 until assets.length()) {
            val a = assets.getJSONObject(k)
            if (a.optString("name").endsWith(".apk")) return Info(n, a.getString("url"), o.optString("name"))
        }
        return null
    }

    fun download(token: String, info: Info, dest: File, progress: (Int) -> Unit) {
        var c = open(info.assetUrl, token, "application/octet-stream")
        var code = c.responseCode
        var hops = 0
        while (code in 300..399 && hops < 5) {
            val loc = c.getHeaderField("Location") ?: break
            c.disconnect()
            c = open(loc, null, "application/octet-stream")
            code = c.responseCode
            hops++
        }
        if (code != 200) throw IOException("Pobieranie nieudane (kod $code)")
        val total = c.contentLengthLong
        c.inputStream.use { inp ->
            dest.outputStream().use { out ->
                val buf = ByteArray(65536)
                var done = 0L
                var last = -1
                while (true) {
                    val r = inp.read(buf)
                    if (r < 0) break
                    out.write(buf, 0, r)
                    done += r
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct != last) { last = pct; progress(pct) }
                    }
                }
            }
        }
    }
}

@Suppress("unused")
private fun unusedRef() = ComponentName("", "")
