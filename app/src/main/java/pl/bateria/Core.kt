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

    fun init(c: Context) {
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
