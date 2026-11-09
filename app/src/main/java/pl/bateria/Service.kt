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
                    log(false)
                    Thread { Engine.onScreenOff(c) }.start()
                }
                Intent.ACTION_SCREEN_ON -> {
                    log(true)
                    Thread { Engine.onScreenOn(c) }.start()
                }
                Intent.ACTION_BATTERY_CHANGED -> {
                    level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                    plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
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
        nm?.createNotificationChannel(NotificationChannel("svc", "Bateria w tle", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, "svc")
            .setSmallIcon(Icon.createWithResource(this, R.drawable.ic_bolt))
            .setContentTitle("Bateria czuwa")
            .setContentText("Oszczędzanie po wygaszeniu ekranu")
            .setContentIntent(PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
        registerReceiver(rx, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_BATTERY_CHANGED)
        })
        alive = true
        Thread {
            Engine.reapply()
            Engine.scheduleNight(this)
            Engine.autoByUsage(this)
        }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        alive = false
        try { unregisterReceiver(rx) } catch (e: Exception) {}
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

/** Blokada internetu w tle: lokalny VPN, przez który puszczamy tylko wybrane aplikacje i odrzucamy ich ruch. */
class NetBlock : VpnService() {
    companion object {
        @Volatile private var fd: ParcelFileDescriptor? = null

        fun start(c: Context) {
            if (VpnService.prepare(c) != null) return // brak zgody – użytkownik musi włączyć w aplikacji
            try { c.startService(Intent(c, NetBlock::class.java).setAction("start")) } catch (e: Exception) {}
        }

        fun stop(c: Context) {
            if (fd == null) return
            try { c.startService(Intent(c, NetBlock::class.java).setAction("stop")) } catch (e: Exception) {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Cfg.init(this)
        if (intent?.action == "stop") {
            try { fd?.close() } catch (e: Exception) {}
            fd = null
            stopSelf()
            return START_NOT_STICKY
        }
        if (fd != null) return START_NOT_STICKY
        val blocked = (Engine.pkgs(Cat.BG) + Engine.pkgs(Cat.OFF)).distinct()
        if (blocked.isEmpty()) { stopSelf(); return START_NOT_STICKY }
        val b = Builder().setSession("Bateria – blokada sieci w tle")
            .addAddress("10.111.0.1", 32).addAddress("fd00:111::1", 128)
            .addRoute("0.0.0.0", 0).addRoute("::", 0)
            .setBlocking(true)
        var any = false
        for (p in blocked) try { b.addAllowedApplication(p); any = true } catch (e: Exception) {}
        if (!any) { stopSelf(); return START_NOT_STICKY }
        val f = try { b.establish() } catch (e: Exception) { null } ?: run { stopSelf(); return START_NOT_STICKY }
        fd = f
        // czytamy i wyrzucamy pakiety – aplikacje na liście nie mają internetu
        Thread {
            val buf = ByteArray(32767)
            try {
                FileInputStream(f.fileDescriptor).use { inp -> while (fd === f) { if (inp.read(buf) < 0) break } }
            } catch (e: Exception) {
            }
        }.start()
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        try { fd?.close() } catch (e: Exception) {}
        fd = null
        super.onRevoke()
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

/** Aktualizacje z GitHub Releases (instalacja bez pytania przez Shizuku). */
object Updater {
    const val REPO = "Bartoszelek/bateria"

    class Info(val number: Int, val assetUrl: String)

    private fun open(url: String, token: String?, accept: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000; c.instanceFollowRedirects = false
        if (!token.isNullOrBlank()) c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept", accept); c.setRequestProperty("User-Agent", "Bateria")
        return c
    }

    fun check(token: String): Info? {
        val c = open("https://api.github.com/repos/$REPO/releases/latest", token, "application/vnd.github+json")
        if (c.responseCode != 200) throw IOException("GitHub: kod ${c.responseCode}")
        val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val n = o.optString("tag_name").substringAfter("build-").toIntOrNull() ?: return null
        val assets = o.optJSONArray("assets") ?: return null
        for (k in 0 until assets.length()) {
            val a = assets.getJSONObject(k)
            if (a.optString("name").endsWith(".apk")) return Info(n, a.getString("url"))
        }
        return null
    }

    fun install(ctx: Context, token: String, info: Info): String {
        var c = open(info.assetUrl, token, "application/octet-stream")
        var code = c.responseCode
        var hops = 0
        while (code in 300..399 && hops < 5) {
            val loc = c.getHeaderField("Location") ?: break
            c.disconnect(); c = open(loc, null, "application/octet-stream"); code = c.responseCode; hops++
        }
        if (code != 200) throw IOException("Pobieranie nieudane (kod $code)")
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val f = File(dir, "bateria.apk")
        c.inputStream.use { inp -> f.outputStream().use { inp.copyTo(it) } }
        return Sh.run("pm install -r \"${f.path}\"")
    }
}

@Suppress("unused")
private fun unusedRef() = ComponentName("", "")
