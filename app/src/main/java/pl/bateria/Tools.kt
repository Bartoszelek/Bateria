package pl.bateria

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/** Powiadomienia: historia, paczki zbiorcze, ciche godziny, blokowanie po słowach. */
object Notif {
    private fun file(c: Context) = File(c.filesDir, "notif.json")
    private var cache: ArrayList<JSONObject>? = null

    @Synchronized
    fun all(c: Context): ArrayList<JSONObject> = cache ?: (try {
        JSONArray(file(c).readText()).let { a -> ArrayList((0 until a.length()).map { a.getJSONObject(it) }) }
    } catch (e: Exception) { ArrayList() }).also { cache = it }

    @Synchronized
    fun add(c: Context, o: JSONObject) {
        val l = all(c)
        l.add(o)
        while (l.size > 3000) l.removeAt(0)
        if (l.size % 10 == 0) flush(c)
    }

    @Synchronized fun flush(c: Context) { try { file(c).writeText(JSONArray(all(c)).toString()) } catch (e: Exception) {} }

    @Synchronized fun clear(c: Context) { all(c).clear(); flush(c) }

    fun enabled(c: Context): Boolean {
        val v = Sys.get(c, "secure", "enabled_notification_listeners")
        return v.contains(ComponentName(c, NotifListener::class.java).flattenToString())
    }

    /** Włącza dostęp do powiadomień (Shizuku) albo otwiera ekran systemu. */
    fun enable(c: Context) {
        if (Sh.granted()) Sh.run("cmd notification allow_listener ${ComponentName(c, NotifListener::class.java).flattenToString()}")
        else try { c.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}
    }

    private fun quietNow(): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return if (Cfg.quietFrom > Cfg.quietTo) h >= Cfg.quietFrom || h < Cfg.quietTo else h in Cfg.quietFrom until Cfg.quietTo
    }

    /** Decyzja dla nowego powiadomienia: "" przepuść, inaczej powód zatrzymania. */
    fun verdict(pkg: String, text: String): String {
        val low = text.lowercase()
        val word = Cfg.notifWords.firstOrNull { it.isNotBlank() && low.contains(it.lowercase()) }
        return when {
            word != null -> "słowo „$word”"
            pkg in Cfg.quietApps && quietNow() -> "ciche godziny"
            pkg in Cfg.bundleApps -> "paczka"
            else -> ""
        }
    }

    /** Zbiorcze powiadomienie z zatrzymanych od ostatniej paczki. */
    fun digest(c: Context) {
        val since = Cfg.lastDigest
        val held = all(c).filter { it.optLong("t") > since && it.optString("b") == "paczka" }
        Cfg.lastDigest = System.currentTimeMillis(); Cfg.save()
        if (held.isEmpty()) return
        val pm = c.packageManager
        val by = held.groupBy { it.optString("p") }
        val lines = by.entries.sortedByDescending { it.value.size }.take(8).joinToString("\n") { (p, l) ->
            val name = try { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() } catch (e: Exception) { p }
            "$name (${l.size}): " + l.last().optString("ti").take(40)
        }
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel("digest", "Paczki powiadomień", NotificationManager.IMPORTANCE_DEFAULT))
        try {
            nm.notify(6001, Notification.Builder(c, "digest").setSmallIcon(android.graphics.drawable.Icon.createWithResource(c, R.drawable.ic_bolt))
                .setContentTitle("${held.size} powiadomień z ${by.size} aplikacji").setContentText(lines.lineSequence().first())
                .setStyle(Notification.BigTextStyle().bigText(lines)).setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(c, 6001, Intent(c, MainActivity::class.java).putExtra("tab", "notif"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                .build())
        } catch (e: SecurityException) {
        }
    }
}

class NotifListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            Cfg.init(this)
            if (sbn.packageName == packageName || sbn.isOngoing) return
            val ex = sbn.notification.extras
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
            if (title.isEmpty() && text.isEmpty()) return
            if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
            val why = if (Cfg.cat(sbn.packageName) == Cat.PROTECT && sbn.packageName !in Cfg.bundleApps && sbn.packageName !in Cfg.quietApps) "" else Notif.verdict(sbn.packageName, "$title $text")
            if (Cfg.notifHistory || why.isNotEmpty())
                Notif.add(this, JSONObject().put("t", System.currentTimeMillis()).put("p", sbn.packageName).put("ti", title).put("tx", text.take(500)).put("b", why))
            if (why.isNotEmpty()) cancelNotification(sbn.key)
        } catch (e: Exception) {
        }
    }

    override fun onListenerDisconnected() { Notif.flush(this) }
}

/** Narzędzia pamięci i wydajności. */
object Tools {
    // ------------------------------------------------------------ autostart
    class Boot(val pkg: String, val label: String, val receivers: List<String>, val enabled: Boolean, val system: Boolean)

    fun autostart(c: Context, withSystem: Boolean): List<Boot> {
        val pm = c.packageManager
        val by = HashMap<String, ArrayList<android.content.pm.ActivityInfo>>()
        for (ri in pm.queryBroadcastReceivers(Intent(Intent.ACTION_BOOT_COMPLETED), PackageManager.MATCH_DISABLED_COMPONENTS)) by.getOrPut(ri.activityInfo.packageName) { ArrayList() }.add(ri.activityInfo)
        val out = ArrayList<Boot>()
        for ((p, l) in by) {
            if (p == c.packageName) continue
            val ai = try { pm.getApplicationInfo(p, 0) } catch (e: Exception) { continue }
            val sys = ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0 && ai.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
            if (sys && !withSystem) continue
            val comp = l.any { pm.getComponentEnabledSetting(ComponentName(p, it.name)).let { st -> st != PackageManager.COMPONENT_ENABLED_STATE_DISABLED && st != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER } }
            // aplikacja ograniczona w tle albo zamrożona nie dostaje sygnału startu telefonu
            val on = comp && Cfg.cat(p).let { it != Cat.BG && it != Cat.FZP } && p !in Cfg.bootOff
            out.add(Boot(p, pm.getApplicationLabel(ai).toString(), l.map { it.name }, on, sys))
        }
        return out.sortedWith(compareBy<Boot> { !it.enabled }.thenBy { it.label.lowercase() })
    }

    /**
     * Android nie pozwala już wyłączać pojedynczych składników cudzych aplikacji. Autostart blokujemy więc ograniczeniem tła:
     * od Androida 13 taka aplikacja nie dostaje sygnału „telefon się uruchomił”, dopóki sam jej nie otworzysz.
     */
    fun setAutostart(c: Context, b: Boot, on: Boolean) {
        if (on) {
            Cfg.bootOff.remove(b.pkg)
            if (Cfg.cat(b.pkg) == Cat.BG) Engine.setCat(c, listOf(b.pkg), Cat.NORMAL)
            else Sh.runAll(listOf("cmd appops set ${b.pkg} RUN_ANY_IN_BACKGROUND default", "am set-standby-bucket ${b.pkg} active"))
        } else {
            Cfg.bootOff.add(b.pkg)
            Sh.runAll(listOf("cmd appops set ${b.pkg} RUN_ANY_IN_BACKGROUND ignore", "am set-standby-bucket ${b.pkg} restricted", "am force-stop ${b.pkg}"))
        }
        Cfg.save()
    }

    // ------------------------------------------------------------ mapa pamięci (foldery wg rozmiaru)
    fun folders(path: String): List<Pair<String, Long>> {
        val base = path.trimEnd('/') + "/"
        val m = HashMap<String, Long>()
        for (f in Mem.files) {
            if (!f.path.startsWith(base)) continue
            val rest = f.path.substring(base.length)
            val name = rest.substringBefore('/')
            val key = if (rest.contains('/')) "$name/" else name
            m[key] = (m[key] ?: 0L) + f.size
        }
        return m.entries.map { it.key to it.value }.sortedByDescending { it.second }
    }

    // ------------------------------------------------------------ WhatsApp według rodzaju
    fun whatsapp(): List<Pair<String, List<FileItem>>> {
        val base = Mem.files.filter { it.path.contains("/WhatsApp/Media/") || it.path.contains("/WhatsApp Business/Media/") }
        fun g(name: String, vararg dirs: String) = name to base.filter { f -> dirs.any { f.path.contains("/$it/") } && !f.name.startsWith(".") }.sortedByDescending { it.size }
        return listOf(g("Filmy", "WhatsApp Video"), g("Zdjęcia", "WhatsApp Images"), g("Wiadomości głosowe", "WhatsApp Voice Notes"),
            g("Dokumenty", "WhatsApp Documents"), g("Animacje GIF", "WhatsApp Animated Gifs"), g("Dźwięki", "WhatsApp Audio"),
            g("Naklejki", "WhatsApp Stickers"), g("Statusy", ".Statuses")).filter { it.second.isNotEmpty() }
    }

    // ------------------------------------------------------------ pamięć RAM
    fun ram(c: Context): List<Triple<String, String, Long>> {
        val txt = Sys.dump(c, "meminfo -s")
        val out = ArrayList<Triple<String, String, Long>>()
        val pm = c.packageManager
        val sec = txt.substringAfter("Total PSS by process", txt.substringAfter("Total RSS by process", "")).substringBefore("Total PSS by OOM").substringBefore("Total RSS by OOM")
        for (line in sec.lines()) {
            val m = Regex("""^\s*([\d,]+)K: (\S+) \(pid""").find(line) ?: continue
            val kb = m.groupValues[1].replace(",", "").toLongOrNull() ?: continue
            val proc = m.groupValues[2]
            val pkg = proc.substringBefore(':')
            val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { proc }
            out.add(Triple(pkg, label, kb * 1024))
        }
        return out.groupBy { it.first }.map { (p, l) -> Triple(p, l.first().second, l.sumOf { it.third }) }.sortedByDescending { it.third }.take(25)
    }

    // ------------------------------------------------------------ kopia zdjęć do wybranego folderu (pendrive, karta, dysk sieciowy)
    private fun doneFile(c: Context) = File(c.filesDir, "backup-done.txt")

    fun backupPending(c: Context): List<FileItem> {
        val done = try { doneFile(c).readLines().toHashSet() } catch (e: Exception) { HashSet() }
        return Mem.files.filter { (it.ext in Mem.IMG || it.ext in Mem.VID) && (it.path.contains("/DCIM/") || it.path.contains("/Pictures/")) && !it.path.contains("/.") && "${it.path}|${it.size}" !in done }
    }

    /** Kopiuje nowe zdjęcia i filmy do folderu wybranego w systemie (drzewo SAF). Zwraca (pliki, bajty). */
    fun backup(c: Context, tree: Uri, progress: (String) -> Unit): Pair<Int, Long> {
        val list = backupPending(c)
        val cr = c.contentResolver
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val dirs = HashMap<String, Uri>()
        fun dir(name: String): Uri = dirs.getOrPut(name) {
            // istniejący podfolder albo nowy
            val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            var found: Uri? = null
            cr.query(kids, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { q ->
                while (q.moveToNext()) if (q.getString(1) == name) found = DocumentsContract.buildDocumentUriUsingTree(tree, q.getString(0))
            }
            found ?: DocumentsContract.createDocument(cr, root, DocumentsContract.Document.MIME_TYPE_DIR, name)!!
        }
        var n = 0; var bytes = 0L
        val log = doneFile(c)
        for ((i, f) in list.withIndex()) {
            if (i % 5 == 0) progress("Kopiuję ${i + 1}/${list.size}: ${f.name}")
            try {
                val month = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US).format(java.util.Date(f.mod))
                val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.ext) ?: "application/octet-stream"
                val dst = DocumentsContract.createDocument(cr, dir(month), mime, f.name) ?: continue
                File(f.path).inputStream().use { inp -> cr.openOutputStream(dst)!!.use { inp.copyTo(it, 1 shl 16) } }
                log.appendText("${f.path}|${f.size}\n")
                n++; bytes += f.size
            } catch (e: Exception) {
            }
        }
        return Pair(n, bytes)
    }
}


/**
 * Moje aplikacje: instalacja i aktualizacja wszystkich własnych aplikacji kanałem ADB (przez Shizuku).
 * Taka instalacja nie pokazuje okna instalatora ani ostrzeżenia Play Protect i – według zasad Google –
 * jest wyłączona z wymogu weryfikacji dewelopera, więc będzie działać także po zmianach w 2027 r.
 */
object MyApps {
    class A(val name: String, val pkg: String, val repo: String)

    val LIST = listOf(
        A("Bastion", "pl.bateria", "Bartoszelek/bateria"),
        A("Audioteka", "pl.audioteka", "Bartoszelek/audioteka"),
        A("Karty", "pl.karty", "Bartoszelek/karty"),
        A("Notatnik", "pl.notatnik", "Bartoszelek/notatnik"),
        A("Player", "pl.konwerter", "Bartoszelek/konwerter"),
        A("Biuro", "pl.biuro", "Bartoszelek/biuro"),
        A("Świt", "pl.swit", "Bartoszelek/swit"),
        A("E-ink Launcher", "pl.einklauncher", "Bartoszelek/einklauncher")
    )

    class State(val app: A, val installed: Long, val latest: Updater.Info?, val error: String)

    fun installed(c: Context, pkg: String): Long = try { c.packageManager.getPackageInfo(pkg, 0).longVersionCode } catch (e: Exception) { -1L }

    fun check(c: Context): List<State> = LIST.map { a ->
        val inst = installed(c, a.pkg)
        try { State(a, inst, Updater.check(Cfg.token, a.repo), "") } catch (e: Exception) { State(a, inst, null, e.message ?: "błąd") }
    }

    /** Pobiera i instaluje kanałem ADB. Zwraca opis wyniku. */
    fun install(c: Context, st: State, progress: (String) -> Unit): String {
        val info = st.latest ?: return "${st.app.name}: brak wydania"
        if (!Sh.granted()) return "Potrzebne działające Shizuku"
        val dir = c.getExternalFilesDir("apk") ?: return "Brak miejsca na pliki"
        val f = File(dir, st.app.pkg + ".apk")
        Updater.download(Cfg.token, info, f) { p -> progress("${st.app.name}: pobieram $p%") }
        progress("${st.app.name}: instaluję…")
        Sys.put(c, "global", "verifier_verify_adb_installs", "0")
        val out = Sh.run("pm install -r -i ${c.packageName} \"${f.path}\"")
        f.delete()
        return if (out.contains("Success")) "${st.app.name}: zainstalowano 1.${info.number}"
        else "${st.app.name}: " + out.lines().firstOrNull { it.contains("Failure") }.orEmpty().ifEmpty { "instalacja nieudana" }
    }

    /** W tle (raz dziennie): aktualizuje zainstalowane aplikacje, gdy jest nowsza wersja. */
    fun autoUpdate(c: Context) {
        if (!Sh.granted() || Cfg.token.isBlank()) return
        val done = ArrayList<String>()
        for (st in check(c)) {
            val l = st.latest ?: continue
            if (st.installed < 0 || l.number <= st.installed || st.app.pkg == c.packageName) continue
            val r = install(c, st) { }
            if (r.contains("zainstalowano")) done += r
        }
        if (done.isNotEmpty()) Alerts.plain(c, 7001, "Zaktualizowano ${done.size} aplikacji", done.joinToString("\n"))
    }
}
