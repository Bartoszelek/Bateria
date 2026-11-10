package pl.bateria

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Raport bezpieczeństwa telefonu z oceną 0–100. */
object Security {
    class Check(val title: String, val level: Int, val detail: String, val fix: String = "") // level: 0 OK, 1 uwaga, 2 ryzyko

    private fun isSys(ai: ApplicationInfo) = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0 && ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0

    fun run(c: Context): List<Check> {
        val out = ArrayList<Check>()
        val pm = c.packageManager
        // poprawki bezpieczeństwa
        val patch = Build.VERSION.SECURITY_PATCH
        val age = try { (System.currentTimeMillis() - SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(patch)!!.time) / 86_400_000L } catch (e: Exception) { -1 }
        out += Check("Poprawki bezpieczeństwa", if (age in 0..90) 0 else if (age in 91..180) 1 else 2,
            "Z dnia $patch" + if (age >= 0) " ($age dni temu)" else "", if (age > 90) "Zainstaluj aktualizację systemu (Ustawienia → Aktualizacja oprogramowania)" else "")
        // blokada ekranu i szyfrowanie
        val km = c.getSystemService(KeyguardManager::class.java)
        out += Check("Blokada ekranu", if (km?.isDeviceSecure == true) 0 else 2, if (km?.isDeviceSecure == true) "Ustawiona" else "Brak – każdy ma dostęp do telefonu", "Ustaw PIN lub hasło (min. 6 cyfr)")
        val dpm = c.getSystemService(DevicePolicyManager::class.java)
        val enc = dpm?.storageEncryptionStatus ?: 0
        out += Check("Szyfrowanie pamięci", if (enc >= DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE) 0 else 2, if (enc >= DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE) "Włączone" else "Wyłączone")
        // treść powiadomień na ekranie blokady
        val priv = Sys.get(c, "secure", "lock_screen_allow_private_notifications")
        out += Check("Treść powiadomień na zablokowanym ekranie", if (priv == "0") 0 else 1,
            if (priv == "0") "Ukryta" else "Widoczna – wiadomości i kody z banku widać bez odblokowania", "Ustawienia → Powiadomienia → Na ekranie blokady → Ukryj treść")
        // debugowanie
        val adb = Sys.get(c, "global", "adb_enabled") == "1"
        val wadb = Sys.get(c, "global", "adb_wifi_enabled") == "1"
        out += Check("Debugowanie (ADB)", if (adb || wadb) 1 else 0, if (adb || wadb) "Włączone – potrzebne dla Shizuku; nie podłączaj telefonu do obcych komputerów i ładowarek z danymi" else "Wyłączone")
        // aplikacje spoza sklepów
        val stores = setOf("com.android.vending", "com.sec.android.app.samsungapps", "com.google.android.packageinstaller.ignored")
        val side = ArrayList<String>()
        for (ai in pm.getInstalledApplications(0)) {
            if (isSys(ai) || ai.packageName == c.packageName) continue
            val inst = try { pm.getInstallSourceInfo(ai.packageName).installingPackageName } catch (e: Exception) { null }
            if (inst == null || inst !in stores) side += pm.getApplicationLabel(ai).toString()
        }
        out += Check("Aplikacje spoza Sklepu Play i Galaxy Store", if (side.size <= 8) 0 else 1, "${side.size}: " + side.take(15).joinToString(", ") + if (side.size > 15) "…" else "",
            "Sprawdź, czy wszystkie znasz – Twoje własne aplikacje też tu są")
        // instalowanie aplikacji
        val inst = Priv.specialApps(c, "install")
        out += Check("Mogą instalować aplikacje", if (inst.size <= 3) 0 else 1, inst.joinToString(", ") { it.label }.ifEmpty { "Żadna" })
        // najgroźniejsze dostępy
        val a11y = Priv.specialApps(c, "a11y")
        out += Check("Ułatwienia dostępu (widzą cały ekran)", if (a11y.isEmpty()) 0 else 1, a11y.joinToString(", ") { it.label }.ifEmpty { "Żadna aplikacja" }, if (a11y.isNotEmpty()) "Prywatność → Ułatwienia dostępu" else "")
        val lis = Priv.specialApps(c, "listener")
        out += Check("Czytanie powiadomień", if (lis.size <= 2) 0 else 1, lis.joinToString(", ") { it.label }.ifEmpty { "Żadna aplikacja" }, if (lis.isNotEmpty()) "Prywatność → Czytanie powiadomień" else "")
        val adm = Priv.specialApps(c, "admin")
        out += Check("Administratorzy urządzenia", if (adm.size <= 2) 0 else 1, adm.joinToString(", ") { it.label }.ifEmpty { "Brak" })
        // wrażliwe pozwolenia
        for (k in listOf("mic", "cam", "locbg", "sms", "calllog")) {
            val g = Priv.GROUPS.first { it.key == k }
            val l = Priv.groupApps(c, g, false).filter { it.granted }
            out += Check("${g.name}: dostęp", if (l.size <= 4) 0 else 1, "${l.size} aplikacji: " + l.take(10).joinToString(", ") { it.label })
        }
        // trackery i DNS
        out += Check("Blokada trackerów", if (Priv.dnsOn(c) || (Cfg.monitor && Cfg.blockTrackers)) 0 else 1,
            if (Cfg.monitor && Cfg.blockTrackers) "Strażnik sieci Bastionu (${Dns.lists.size} domen w telefonie)" else if (Priv.dnsOn(c)) "Prywatny DNS (serwer zewnętrzny)" else "Wyłączona", "Prywatność → Strażnik sieci")
        out += Check("Antywirus", if (Sig.ready(c) && System.currentTimeMillis() - Cfg.sigAt < 8 * 86_400_000L) 0 else 1,
            if (!Sig.ready(c)) "Brak baz sygnatur" else "Bazy z " + SimpleDateFormat("d.MM.yyyy", Locale("pl")).format(Date(Cfg.sigAt)) + (if (Cfg.avAt > 0) " · ostatni skan " + SimpleDateFormat("d.MM", Locale("pl")).format(Date(Cfg.avAt)) else " · nie skanowano"),
            "Prywatność → Antywirus → Pobierz bazy i skanuj")
        out += Check("Ochrona przed kradzieżą", if (Cfg.antiTheft && Theft.active(c)) 0 else 1, if (Cfg.antiTheft && Theft.active(c)) "Włączona" else "Wyłączona", "Prywatność → Ochrona przed kradzieżą")
        val scan = Sys.get(c, "global", "wifi_scan_always_enabled") == "1" || Sys.get(c, "global", "ble_scan_always_enabled") == "1"
        out += Check("Skanowanie Wi-Fi/Bluetooth przy wyłączonych", if (scan) 1 else 0, if (scan) "Włączone – telefon zdradza położenie" else "Wyłączone", "Bateria → Skanowanie Wi-Fi i Bluetooth w tle")
        val vendor = Priv.vendorState(c).filter { it.second == true && it.first.risk == "bezpieczne" }
        out += Check("Usługi śledzące producenta", if (vendor.isEmpty()) 0 else 1, if (vendor.isEmpty()) "Wyłączone" else "Działa ${vendor.size}: " + vendor.joinToString(", ") { it.first.name }, "Prywatność → Usługi producenta")
        return out
    }

    fun score(l: List<Check>): Int = (100 - l.sumOf { when (it.level) { 2 -> 15; 1 -> 5; else -> 0 }.toInt() }).coerceIn(0, 100)

    /** Eksport do PDF (Pobrane). Zwraca adres pliku. */
    fun pdf(c: Context, l: List<Check>): Uri? {
        val doc = PdfDocument()
        val title = Paint().apply { textSize = 18f; isFakeBoldText = true }
        val bold = Paint().apply { textSize = 11f; isFakeBoldText = true }
        val norm = Paint().apply { textSize = 10f }
        val dim = Paint().apply { textSize = 9f; color = 0xFF555555.toInt() }
        var page = 1
        var pg = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, page).create())
        var cv = pg.canvas
        var y = 50f
        fun wrap(t: String, p: Paint, x: Float) {
            val words = t.split(' ')
            var line = ""
            for (w in words) {
                if (p.measureText("$line $w") > 595 - x - 40) {
                    if (y > 800) { doc.finishPage(pg); page++; pg = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, page).create()); cv = pg.canvas; y = 50f }
                    cv.drawText(line.trim(), x, y, p); y += p.textSize + 4; line = w
                } else line += " $w"
            }
            if (line.isNotBlank()) {
                if (y > 800) { doc.finishPage(pg); page++; pg = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, page).create()); cv = pg.canvas; y = 50f }
                cv.drawText(line.trim(), x, y, p); y += p.textSize + 4
            }
        }
        val df = SimpleDateFormat("d MMMM yyyy, HH:mm", Locale("pl"))
        cv.drawText("Raport bezpieczeństwa telefonu", 40f, y, title); y += 26
        wrap("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · ${df.format(Date())}", norm, 40f)
        wrap("Ocena: ${score(l)}/100", title, 40f); y += 6
        for (ch in l) {
            val mark = when (ch.level) { 0 -> "[OK]"; 1 -> "[UWAGA]"; else -> "[RYZYKO]" }
            wrap("$mark ${ch.title}", bold, 40f)
            wrap(ch.detail, norm, 56f)
            if (ch.fix.isNotBlank() && ch.level > 0) wrap("Zalecenie: ${ch.fix}", dim, 56f)
            y += 4
        }
        doc.finishPage(pg)
        val name = "Raport_bezpieczenstwa_${SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())}.pdf"
        val cv2 = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
        }
        val uri = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv2) ?: return null
        c.contentResolver.openOutputStream(uri)?.use { doc.writeTo(it) }
        doc.close()
        return uri
    }
}

/** Alarmy: nowa aplikacja, nowe groźne uprawnienie, mikrofon/aparat w tle. */
object Alerts {
    private fun note(c: Context, id: Int, t: String, s: String, pkg: String? = null) {
        if (!Cfg.alerts) return
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel("alerts", "Alarmy bezpieczeństwa", NotificationManager.IMPORTANCE_HIGH))
        val b = android.app.Notification.Builder(c, "alerts").setSmallIcon(android.graphics.drawable.Icon.createWithResource(c, R.drawable.ic_bolt))
            .setContentTitle(t).setContentText(s).setStyle(android.app.Notification.BigTextStyle().bigText(s)).setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(c, 90, Intent(c, MainActivity::class.java).putExtra("tab", "priv"), PendingIntent.FLAG_IMMUTABLE))
        if (pkg != null) {
            b.addAction(android.app.Notification.Action.Builder(null, "Odetnij internet w tle",
                PendingIntent.getBroadcast(c, ("n" + pkg).hashCode(), Intent(c, AlarmReceiver::class.java).setAction("netdeny").putExtra("pkg", pkg),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
            b.addAction(android.app.Notification.Action.Builder(null, "Szczegóły",
                PendingIntent.getActivity(c, ("d" + pkg).hashCode(), Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")),
                    PendingIntent.FLAG_IMMUTABLE)).build())
        }
        try { nm.notify(id, b.build()) } catch (e: SecurityException) {}
    }

    private fun label(c: Context, p: String) = try { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(p, 0)).toString() } catch (e: Exception) { p }

    /** Nowa aplikacja: trackery i groźne pozwolenia. */
    fun newApp(c: Context, pkg: String) {
        val pm = c.packageManager
        val ai = try { pm.getApplicationInfo(pkg, 0) } catch (e: Exception) { return }
        val tr = Priv.scanApk(listOf(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList())).mapNotNull { Priv.TRACKERS.getOrNull(it)?.name }
        val req = try { pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions?.toSet() ?: emptySet() } catch (e: Exception) { emptySet() }
        val groups = Priv.GROUPS.filter { g -> g.key != "notif" && g.perms.any { it in req } }.map { it.name }
        val s = (if (tr.isEmpty()) "Brak znanych trackerów. " else "Trackery (${tr.size}): ${tr.joinToString(", ")}. ") +
            (if (groups.isEmpty()) "" else "Może prosić o: ${groups.joinToString(", ")}.")
        note(c, pkg.hashCode(), "Nowa aplikacja: ${label(c, pkg)}", s, pkg)
    }

    private fun snapFile(c: Context) = File(c.filesDir, "perms.json")

    /** Porównuje przyznane groźne pozwolenia z poprzednim stanem. */
    fun permCheck(c: Context) {
        val pm = c.packageManager
        val groups = Priv.GROUPS.filter { it.key != "notif" }
        val now = HashSet<String>()
        for (pi in pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)) {
            val req = pi.requestedPermissions ?: continue
            for (g in groups) if (g.perms.any { it in req && pm.checkPermission(it, pi.packageName) == PackageManager.PERMISSION_GRANTED }) now += pi.packageName + "|" + g.key
        }
        val f = snapFile(c)
        val old = try { JSONArray(f.readText()).let { a -> (0 until a.length()).map { a.getString(it) }.toHashSet() } } catch (e: Exception) { null }
        f.writeText(JSONArray(now.toList()).toString())
        if (old == null) return
        val added = (now - old).groupBy({ it.substringBefore('|') }, { k -> groups.first { it.key == k.substringAfter('|') }.name })
        for ((p, gs) in added) {
            if (p == c.packageName) continue
            note(c, ("p" + p).hashCode(), "${label(c, p)} dostała nowy dostęp", gs.joinToString(", "), p)
        }
    }

    private val warned = HashMap<String, Long>()

    /** Mikrofon lub aparat używany przy wygaszonym ekranie (eksperymentalne – odczyt rejestru systemu). */
    fun sensorCheck(c: Context) {
        if (!Sh.granted()) return
        for ((op, name) in listOf("RECORD_AUDIO" to "mikrofonu", "CAMERA" to "aparatu")) {
            val txt = Sh.run("dumpsys appops --op $op")
            var pkg = ""
            for (line in txt.lines()) {
                Regex("""Package (\S+):""").find(line)?.let { pkg = it.groupValues[1] }
                if (pkg.isNotEmpty() && (line.contains("Running start", true) || line.contains("running=true", true))) {
                    if (pkg == c.packageName || Cfg.cat(pkg) == Cat.PROTECT) continue
                    if (System.currentTimeMillis() - (warned[pkg + op] ?: 0L) < 3600_000L) continue
                    warned[pkg + op] = System.currentTimeMillis()
                    note(c, (op + pkg).hashCode(), "${label(c, pkg)} używa $name w tle", "Ekran jest wygaszony, a aplikacja korzysta z $name. Jeśli to nie Ty – zabierz jej dostęp.", pkg)
                }
            }
        }
    }

    /** Wykryte zagrożenie w nowym pliku lub aplikacji. */
    fun threat(c: Context, r: Av.Result) {
        val what = if (r.level == 2) "ZAGROŻENIE" else "Podejrzane"
        val s = (if (r.known) "Plik jest w bazie znanego złośliwego oprogramowania. " else "") + r.heur.joinToString("; ") + " Otwórz Bastion → Prywatność → Antywirus."
        note(c, ("av" + r.path).hashCode(), "$what: ${r.name}", s, r.pkg)
    }

    /** Zwykłe ostrzeżenie (linki, Wi-Fi, próby odblokowania). */
    fun plain(c: Context, id: Int, t: String, s: String) = note(c, id, t, s, null)
}
