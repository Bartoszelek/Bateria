package pl.bateria

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File

/** Polecenia z uprawnieniami ADB przez Shizuku. */
object Sh {
    fun running(): Boolean = try { Shizuku.pingBinder() } catch (e: Throwable) { false }

    fun granted(): Boolean = try { running() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (e: Throwable) { false }

    fun request() {
        try { if (running() && !granted()) Shizuku.requestPermission(7) } catch (e: Throwable) {}
    }

    private val newProcess by lazy {
        Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            .apply { isAccessible = true }
    }

    /** Wykonuje polecenie powłoki; zwraca wyjście (stdout + stderr). */
    fun run(cmd: String): String {
        if (!granted()) return ""
        return try {
            val p = newProcess.invoke(null, arrayOf("sh", "-c", "$cmd 2>&1"), null, null) as Process
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out
        } catch (e: Throwable) {
            ""
        }
    }

    /** Wiele poleceń w jednym procesie (szybciej). */
    fun runAll(cmds: List<String>): String = if (cmds.isEmpty()) "" else run(cmds.joinToString(" ; "))
}

/**
 * Stałe uprawnienia (nadawane raz przez Shizuku, działają po restarcie i bez Shizuku):
 * zmiana ustawień systemu, odczyt statystyk baterii, statystyki użycia.
 */
object Sys {
    const val SHIZUKU = "moe.shizuku.privileged.api"
    /** Shizuku albo Shizuku+ (z autostartem i watchdogiem) – ta, która jest zainstalowana. */
    val SHIZUKU_ALL = listOf("moe.shizuku.privileged.api", "af.shizuku.plus.api")
    fun shizukuPkg(c: Context): String = SHIZUKU_ALL.firstOrNull { Apps.installed(c.packageManager, it) } ?: SHIZUKU

    /**
     * Podtrzymanie Shizuku: debugowanie nie wygasa po 7 dniach, opcje programisty i debugowanie USB zostają włączone.
     * Działa bez Shizuku (stałe uprawnienie do ustawień).
     */
    fun keepAdb(c: Context) {
        if (!canWrite(c)) return
        put(c, "global", "adb_allowed_connection_time", "0")   // „Wyłącz limit czasu autoryzacji ADB”
        put(c, "global", "development_settings_enabled", "1")
        put(c, "global", "adb_enabled", "1")
    }

    /** Włącza debugowanie bezprzewodowe (potrzebne do uruchomienia Shizuku) – tylko gdy jest Wi-Fi. */
    fun wirelessDebugOn(c: Context): Boolean {
        if (!canWrite(c)) return false
        val cm = c.getSystemService(android.net.ConnectivityManager::class.java)
        val wifi = cm?.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        if (!wifi) return false
        keepAdb(c)
        return put(c, "global", "adb_wifi_enabled", "1")
    }

    fun canWrite(c: Context) = c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED
    fun canDump(c: Context) = c.checkSelfPermission("android.permission.DUMP") == PackageManager.PERMISSION_GRANTED

    /** Nadaje stałe uprawnienia (gdy Shizuku działa). */
    fun grantAll(c: Context) {
        if (!Sh.granted()) return
        val p = c.packageName
        Sh.runAll(listOf(
            "pm grant $p android.permission.WRITE_SECURE_SETTINGS",
            "pm grant $p android.permission.DUMP",
            "appops set $p GET_USAGE_STATS allow",
            "appops set $p WRITE_SETTINGS allow",
            "appops set $p MANAGE_EXTERNAL_STORAGE allow",
            "pm grant $p android.permission.BLUETOOTH_CONNECT",
            "pm grant $p android.permission.RECEIVE_SMS",
            "pm grant $p android.permission.CAMERA",
            "appops set $p SYSTEM_ALERT_WINDOW allow",
            // uprawnienia niedostępne zwykłym aplikacjom: dziennik systemu i statystyki użycia czujników
            "pm grant $p android.permission.READ_LOGS",
            "pm grant $p android.permission.GET_APP_OPS_STATS",
            "pm grant $p android.permission.BATTERY_STATS",
            // Shizuku i Bastion – nie usypiać i nie ubijać
            "dumpsys deviceidle whitelist +${shizukuPkg(c)}",
            "cmd appops set ${shizukuPkg(c)} RUN_ANY_IN_BACKGROUND allow",
            "cmd appops set ${shizukuPkg(c)} RUN_IN_BACKGROUND allow",
            "am set-standby-bucket ${shizukuPkg(c)} active",
            "cmd appops set $p RUN_ANY_IN_BACKGROUND allow",
            "am set-standby-bucket $p active",
            "dumpsys deviceidle whitelist +$p"
        ))
    }

    fun get(c: Context, ns: String, k: String): String = try {
        when (ns) {
            "global" -> android.provider.Settings.Global.getString(c.contentResolver, k)
            "secure" -> android.provider.Settings.Secure.getString(c.contentResolver, k)
            else -> android.provider.Settings.System.getString(c.contentResolver, k)
        } ?: "null"
    } catch (e: Exception) {
        Sh.run("settings get $ns $k").trim()
    }

    /** Zapis ustawienia: bezpośrednio (stałe uprawnienie), a gdy się nie da – przez Shizuku. */
    fun put(c: Context, ns: String, k: String, v: String?): Boolean {
        if (canWrite(c)) try {
            val cr = c.contentResolver
            val ok = when (ns) {
                "global" -> android.provider.Settings.Global.putString(cr, k, v)
                "secure" -> android.provider.Settings.Secure.putString(cr, k, v)
                else -> android.provider.Settings.System.putString(cr, k, v)
            }
            if (ok) return true
        } catch (e: Exception) {
        }
        if (!Sh.granted()) return false
        Sh.run(if (v == null) "settings delete $ns $k" else "settings put $ns $k $v")
        return true
    }

    /** dumpsys: przez Shizuku albo własnym uprawnieniem DUMP. */
    fun dump(c: Context, args: String): String {
        if (Sh.granted()) return Sh.run("dumpsys $args")
        if (!canDump(c)) return ""
        return try {
            val pr = Runtime.getRuntime().exec(arrayOf("sh", "-c", "dumpsys $args"))
            val out = pr.inputStream.bufferedReader().readText()
            pr.waitFor(); out
        } catch (e: Exception) {
            ""
        }
    }
}

/** Kategorie aplikacji. */
object Cat {
    const val PROTECT = "protect"
    const val NORMAL = "normal"
    const val BG = "bg"        // ograniczona w tle zawsze
    const val OFF = "off"      // ograniczona po wygaszeniu ekranu
    const val FZ1 = "fz1"      // zamrażana po wygaszeniu (krótko)
    const val FZ2 = "fz2"      // zamrażana po wygaszeniu (długo)
    const val FZP = "fzp"      // zamrożona na stałe

    val ALL = listOf(PROTECT, NORMAL, BG, OFF, FZ1, FZ2, FZP)

    fun name(c: String) = when (c) {
        PROTECT -> "Chronione – nic nie ruszaj"
        NORMAL -> "Normalne"
        BG -> "Ogranicz w tle (zawsze)"
        OFF -> "Ogranicz po wygaszeniu"
        FZ1 -> "Zamrażaj po wygaszeniu – krótko"
        FZ2 -> "Zamrażaj po wygaszeniu – długo"
        FZP -> "Zamrożone na stałe"
        else -> c
    }

    fun hint(c: String) = when (c) {
        PROTECT -> "Komunikatory, poczta, bank, nawigacja, budzik – działają bez ograniczeń."
        NORMAL -> "System decyduje sam."
        BG -> "Działają normalnie po otwarciu, ale w tle nic nie robią i nie budzą telefonu."
        OFF -> "Pracują, gdy używasz telefonu; po wygaszeniu ekranu dostają blokadę tła."
        FZ1, FZ2 -> "Po wygaszeniu ekranu zamrożone, po włączeniu ekranu od razu odmrożone. Ich widżety znikają z pulpitu."
        FZP -> "Nie działają wcale. Uruchomisz je z listy w Baterii."
        else -> ""
    }

    fun hasDelay(c: String) = c == OFF || c == FZ1 || c == FZ2
}

/** Ustawienia i przypisania aplikacji (jeden plik JSON). */
object Cfg {
    private lateinit var f: File
    private var loaded = false

    val cats = HashMap<String, String>()          // pakiet → kategoria
    val auto = HashSet<String>()                  // przypisane przez automat
    val delay = hashMapOf(Cat.OFF to 0, Cat.FZ1 to 5, Cat.FZ2 to 30) // minuty po wygaszeniu
    val prev = HashMap<String, String>()          // poprzednie wartości ustawień systemu
    val bgLocOk = HashSet<String>()               // wolno lokalizację w tle

    var hz60 = false
    var dark = false
    var aodOff = false
    var scanOff = false
    var deepDoze = false
    var guard = true
    var autoUsage = true
    var autoFreeze = false
    var netBlock = false
    var night = false
    var nightFrom = 23
    var nightTo = 6
    var nightData = true
    var nightSync = true
    var nightSaver = true
    var weekendSaver = false
    var token = ""
    var lastAuto = 0L
    val pendingFrom = HashMap<String, String>()   // zmiany kategorii czekające na Shizuku: pakiet → poprzednia kategoria
    val frozenNow = HashSet<String>()             // zamrożone po wygaszeniu (do odmrożenia)
    val drops = ArrayList<Long>()                 // kiedy Shizuku się wyłączało
    var shizukuWasUp = false
    var autoClean = false
    var storageAlert = true
    var lastClean = 0L
    val netDeny = HashSet<String>()               // bez internetu w tle
    var btAuto = false
    var monitor = false                           // strażnik sieci (DNS)
    var blockTrackers = true
    var dnsAdguard = false
    val blockDomains = HashSet<String>()
    var alerts = true
    var sigAt = 0L                                // kiedy pobrano bazy sygnatur
    var avAt = 0L                                 // ostatni skan
    var smsGuard = true
    val locked = HashSet<String>()                // aplikacje wymagające odcisku
    val trackBlock = HashSet<String>()            // aplikacje z zablokowanymi trackerami (internet zostaje)
    val avTrust = HashSet<String>()               // „to fałszywy alarm” – zapamiętane
    var travel = false
    var clipClear = false
    var ultraOn = false
    var ultraAuto = false
    var ultraAt = 15
    var chargeGuard = true
    var chargeLimit = 80
    var designMah = 4700
    var profiles = "[]"
    var phantom = false
    var notifHistory = true
    val bundleApps = HashSet<String>()
    val quietApps = HashSet<String>()
    var quietFrom = 22
    var quietTo = 7
    val notifWords = ArrayList<String>()
    var lastDigest = 0L
    var backupTree = ""
    var dlAuto = false
    var wifiGuard = true
    var antiTheft = false
    var theftTries = 3
    var theftSiren = true
    var avRealtime = true                         // sprawdzaj nowe pliki i aplikacje
    val allowDomains = HashSet<String>()
    var listsAt = 0L
    var wifiAuto = false

    /** Wczytuje ustawienia od nowa (po imporcie kopii). */
    fun reload(c: Context) {
        loaded = false
        cats.clear(); auto.clear(); prev.clear(); bgLocOk.clear(); pendingFrom.clear(); netDeny.clear(); blockDomains.clear(); allowDomains.clear(); locked.clear(); trackBlock.clear(); avTrust.clear(); bundleApps.clear(); quietApps.clear(); notifWords.clear()
        init(c)
    }

    fun file(c: Context) = File(c.filesDir, "cfg.json")

    fun init(c: Context) {
        Engine.init(c)
        if (loaded) return
        f = File(c.filesDir, "cfg.json")
        loaded = true
        if (!f.exists()) { defaults(c); save(); return }
        try {
            val o = JSONObject(f.readText())
            o.optJSONObject("cats")?.let { j -> for (k in j.keys()) cats[k] = j.optString(k) }
            o.optJSONArray("auto")?.let { a -> for (i in 0 until a.length()) auto.add(a.getString(i)) }
            o.optJSONObject("delay")?.let { j -> for (k in j.keys()) delay[k] = j.optInt(k) }
            o.optJSONObject("prev")?.let { j -> for (k in j.keys()) prev[k] = j.optString(k) }
            o.optJSONArray("bgLocOk")?.let { a -> for (i in 0 until a.length()) bgLocOk.add(a.getString(i)) }
            hz60 = o.optBoolean("hz60"); dark = o.optBoolean("dark"); aodOff = o.optBoolean("aodOff")
            scanOff = o.optBoolean("scanOff"); deepDoze = o.optBoolean("deepDoze"); guard = o.optBoolean("guard", true)
            autoUsage = o.optBoolean("autoUsage", true); autoFreeze = o.optBoolean("autoFreeze"); netBlock = o.optBoolean("netBlock")
            night = o.optBoolean("night"); nightFrom = o.optInt("nightFrom", 23); nightTo = o.optInt("nightTo", 6)
            nightData = o.optBoolean("nightData", true); nightSync = o.optBoolean("nightSync", true); nightSaver = o.optBoolean("nightSaver", true)
            weekendSaver = o.optBoolean("weekendSaver"); token = o.optString("token"); lastAuto = o.optLong("lastAuto")
            o.optJSONObject("pendingFrom")?.let { j -> for (k in j.keys()) pendingFrom[k] = j.optString(k) }
            o.optJSONArray("frozenNow")?.let { a -> for (i in 0 until a.length()) frozenNow.add(a.getString(i)) }
            o.optJSONArray("drops")?.let { a -> for (i in 0 until a.length()) drops.add(a.getLong(i)) }
            shizukuWasUp = o.optBoolean("shizukuWasUp")
            o.optJSONArray("netDeny")?.let { a -> for (i in 0 until a.length()) netDeny.add(a.getString(i)) }
            monitor = o.optBoolean("monitor"); blockTrackers = o.optBoolean("blockTrackers", true); dnsAdguard = o.optBoolean("dnsAdguard"); alerts = o.optBoolean("alerts", true)
            ultraOn = o.optBoolean("ultraOn"); ultraAuto = o.optBoolean("ultraAuto"); ultraAt = o.optInt("ultraAt", 15)
            chargeGuard = o.optBoolean("chargeGuard", true); chargeLimit = o.optInt("chargeLimit", 80); designMah = o.optInt("designMah", 4700)
            profiles = o.optString("profiles", "[]"); phantom = o.optBoolean("phantom"); notifHistory = o.optBoolean("notifHistory", true)
            quietFrom = o.optInt("quietFrom", 22); quietTo = o.optInt("quietTo", 7); lastDigest = o.optLong("lastDigest"); backupTree = o.optString("backupTree"); dlAuto = o.optBoolean("dlAuto")
            o.optJSONArray("bundleApps")?.let { a -> for (i in 0 until a.length()) bundleApps.add(a.getString(i)) }
            o.optJSONArray("quietApps")?.let { a -> for (i in 0 until a.length()) quietApps.add(a.getString(i)) }
            o.optJSONArray("notifWords")?.let { a -> for (i in 0 until a.length()) notifWords.add(a.getString(i)) }
            travel = o.optBoolean("travel"); clipClear = o.optBoolean("clipClear")
            o.optJSONArray("trackBlock")?.let { a -> for (i in 0 until a.length()) trackBlock.add(a.getString(i)) }
            o.optJSONArray("avTrust")?.let { a -> for (i in 0 until a.length()) avTrust.add(a.getString(i)) }
            o.optJSONArray("locked")?.let { a -> for (i in 0 until a.length()) locked.add(a.getString(i)) }
            sigAt = o.optLong("sigAt"); avAt = o.optLong("avAt"); smsGuard = o.optBoolean("smsGuard", true); wifiGuard = o.optBoolean("wifiGuard", true)
            antiTheft = o.optBoolean("antiTheft"); theftTries = o.optInt("theftTries", 3); theftSiren = o.optBoolean("theftSiren", true)
            avRealtime = o.optBoolean("avRealtime", true); listsAt = o.optLong("listsAt")
            o.optJSONArray("allowDomains")?.let { a -> for (i in 0 until a.length()) allowDomains.add(a.getString(i)) }
            o.optJSONArray("blockDomains")?.let { a -> for (i in 0 until a.length()) blockDomains.add(a.getString(i)) }
            btAuto = o.optBoolean("btAuto"); wifiAuto = o.optBoolean("wifiAuto")
            autoClean = o.optBoolean("autoClean"); storageAlert = o.optBoolean("storageAlert", true); lastClean = o.optLong("lastClean")
        } catch (e: Exception) {
            defaults(c)
        }
    }

    @Synchronized
    fun save() {
        val o = JSONObject()
        o.put("cats", JSONObject(cats as Map<*, *>))
        o.put("auto", JSONArray(auto.toList()))
        o.put("delay", JSONObject(delay as Map<*, *>))
        o.put("prev", JSONObject(prev as Map<*, *>))
        o.put("bgLocOk", JSONArray(bgLocOk.toList()))
        o.put("hz60", hz60).put("dark", dark).put("aodOff", aodOff).put("scanOff", scanOff).put("deepDoze", deepDoze)
            .put("guard", guard).put("autoUsage", autoUsage).put("autoFreeze", autoFreeze).put("netBlock", netBlock)
            .put("night", night).put("nightFrom", nightFrom).put("nightTo", nightTo).put("nightData", nightData)
            .put("nightSync", nightSync).put("nightSaver", nightSaver).put("weekendSaver", weekendSaver)
            .put("token", token).put("lastAuto", lastAuto)
            .put("pendingFrom", JSONObject(pendingFrom as Map<*, *>)).put("frozenNow", JSONArray(frozenNow.toList()))
            .put("drops", JSONArray(drops.filter { it > System.currentTimeMillis() - 7 * 86_400_000L })).put("shizukuWasUp", shizukuWasUp)
            .put("netDeny", JSONArray(netDeny.toList()))
            .put("monitor", monitor).put("blockTrackers", blockTrackers).put("dnsAdguard", dnsAdguard).put("alerts", alerts)
            .put("blockDomains", JSONArray(blockDomains.toList())).put("allowDomains", JSONArray(allowDomains.toList()))
            .put("ultraOn", ultraOn).put("ultraAuto", ultraAuto).put("ultraAt", ultraAt).put("chargeGuard", chargeGuard).put("chargeLimit", chargeLimit)
            .put("designMah", designMah).put("profiles", profiles).put("phantom", phantom).put("notifHistory", notifHistory)
            .put("quietFrom", quietFrom).put("quietTo", quietTo).put("lastDigest", lastDigest).put("backupTree", backupTree).put("dlAuto", dlAuto)
            .put("bundleApps", JSONArray(bundleApps.toList())).put("quietApps", JSONArray(quietApps.toList())).put("notifWords", JSONArray(notifWords))
            .put("travel", travel).put("clipClear", clipClear).put("locked", JSONArray(locked.toList()))
            .put("trackBlock", JSONArray(trackBlock.toList())).put("avTrust", JSONArray(avTrust.toList()))
            .put("sigAt", sigAt).put("avAt", avAt).put("smsGuard", smsGuard).put("wifiGuard", wifiGuard)
            .put("antiTheft", antiTheft).put("theftTries", theftTries).put("theftSiren", theftSiren).put("avRealtime", avRealtime).put("listsAt", listsAt)
            .put("btAuto", btAuto).put("wifiAuto", wifiAuto)
            .put("autoClean", autoClean).put("storageAlert", storageAlert).put("lastClean", lastClean)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(f)
    }

    fun cat(p: String) = cats[p] ?: Cat.NORMAL

    /** Na start: ważne aplikacje chronione. */
    private fun defaults(c: Context) {
        val pm = c.packageManager
        for (p in Apps.PROTECT_DEFAULT) if (Apps.installed(pm, p)) cats[p] = Cat.PROTECT
        bgLocOk.addAll(listOf("com.google.android.apps.maps", "com.google.android.projection.gearhead", "io.homeassistant.companion.android",
            "com.waze", "com.samsung.android.app.smartcapture", "com.google.android.gms"))
    }
}
