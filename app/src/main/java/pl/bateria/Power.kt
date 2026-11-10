package pl.bateria

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Zaawansowane zarządzanie baterią: tryb ultra, kondycja, strażnik ładowania, profile sieci. */
object Power {
    // ------------------------------------------------------------ tryb ultra
    fun ultra(c: Context, on: Boolean) {
        val keys = listOf("secure" to "accessibility_display_daltonizer_enabled", "secure" to "accessibility_display_daltonizer",
            "global" to "window_animation_scale", "global" to "transition_animation_scale", "global" to "animator_duration_scale")
        if (on) {
            for ((ns, k) in keys) if (!Cfg.prev.containsKey("ultra/$ns/$k")) Cfg.prev["ultra/$ns/$k"] = Sys.get(c, ns, k)
            Sys.put(c, "secure", "accessibility_display_daltonizer", "0")          // odcienie szarości
            Sys.put(c, "secure", "accessibility_display_daltonizer_enabled", "1")
            for (k in listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")) Sys.put(c, "global", k, "0")
            if (!Cfg.hz60) Engine.applyHz(true)
            Engine.saver(true)
            if (Sh.granted()) Sh.run("cmd uimode night yes")
        } else {
            for ((ns, k) in keys) Cfg.prev.remove("ultra/$ns/$k")?.let { Sys.put(c, ns, k, if (it == "null") null else it) }
            if (!Cfg.hz60) Engine.applyHz(false)
            Engine.saver(false)
        }
        Cfg.ultraOn = on
        Cfg.save()
    }

    fun animations(c: Context, scale: String) {
        for (k in listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")) Sys.put(c, "global", k, scale)
    }

    fun animScale(c: Context) = Sys.get(c, "global", "animator_duration_scale").let { if (it == "null") "1" else it }

    // ------------------------------------------------------------ kondycja: pomiar podczas ładowania
    private fun file(c: Context) = File(c.filesDir, "charge.json")

    fun sessions(c: Context): List<JSONObject> = try { JSONArray(file(c).readText()).let { a -> (0 until a.length()).map { a.getJSONObject(it) } } } catch (e: Exception) { emptyList() }

    private var startLevel = -1
    private var startUah = 0L
    private var startAt = 0L
    private var maxTemp = 0
    private var maxW = 0.0
    private var notified80 = false
    private var notifiedHot = false
    private var notifiedSlow = false
    private var wasPlugged = false

    /** Wywoływane przy każdej zmianie stanu baterii. temp w dziesiątych °C, napięcie w mV. */
    fun onBattery(c: Context, level: Int, plugged: Boolean, temp: Int, mv: Int) {
        val bm = c.getSystemService(BatteryManager::class.java) ?: return
        val uah = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val ua = Math.abs(bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))
        val amps = if (ua < 20_000) ua / 1000.0 else ua / 1_000_000.0 // część telefonów (Samsung) podaje mA zamiast µA
        val watts = amps * (mv / 1000.0)
        if (plugged && !wasPlugged) {
            startLevel = level; startUah = uah; startAt = System.currentTimeMillis(); maxTemp = temp; maxW = 0.0
            notified80 = false; notifiedHot = false; notifiedSlow = false
        }
        if (plugged) {
            if (temp > maxTemp) maxTemp = temp
            if (watts > maxW && watts < 120) maxW = watts
            if (Cfg.chargeGuard) {
                if (level >= Cfg.chargeLimit && !notified80 && startLevel in 0 until Cfg.chargeLimit) {
                    notified80 = true; Alerts.plain(c, 5001, "Bateria ma $level% – odłącz ładowarkę", "Ładowanie do ${Cfg.chargeLimit}% zamiast do pełna wydłuża życie baterii o lata.")
                }
                if (temp >= 420 && !notifiedHot) { notifiedHot = true; Alerts.plain(c, 5002, "Bateria się przegrzewa (${temp / 10} °C)", "Zdejmij etui, odłóż telefon z dala od słońca albo odłącz ładowarkę. Ciepło najszybciej niszczy baterię.") }
                if (!notifiedSlow && System.currentTimeMillis() - startAt > 180_000L && maxW in 0.1..4.5 && level < 80) {
                    notifiedSlow = true; Alerts.plain(c, 5003, "Wolne ładowanie (%.1f W)".format(maxW), "Ta ładowarka lub kabel ładuje bardzo wolno. Twój telefon przyjmuje do 45 W.")
                }
            }
        }
        if (!plugged && wasPlugged && startLevel >= 0) {
            val dPct = level - startLevel
            val dUah = uah - startUah
            if (dPct >= 15 && dUah > 0) {
                val cap = (dUah / 1000.0) / dPct * 100.0  // mAh przy 100%
                if (cap in 1500.0..8000.0) {
                    val l = sessions(c).takeLast(60).toMutableList()
                    l.add(JSONObject().put("t", System.currentTimeMillis()).put("from", startLevel).put("to", level).put("cap", cap.toInt())
                        .put("temp", maxTemp).put("w", Math.round(maxW * 10) / 10.0).put("min", (System.currentTimeMillis() - startAt) / 60000))
                    file(c).writeText(JSONArray(l).toString())
                }
            }
            startLevel = -1
        }
        wasPlugged = plugged
        // tryb ultra automatycznie
        if (Cfg.ultraAuto) {
            if (!plugged && level <= Cfg.ultraAt && !Cfg.ultraOn) Thread { ultra(c, true); Alerts.plain(c, 5004, "Tryb ultra włączony", "Bateria ma $level%. Ekran czarno-biały, bez animacji, 60 Hz.") }.start()
            else if (Cfg.ultraOn && (plugged || level >= Cfg.ultraAt + 10)) Thread { ultra(c, false) }.start()
        }
    }

    /** Szacowana pojemność (mediana z ostatnich pomiarów) w mAh; 0 gdy brak danych. */
    fun capacity(c: Context): Int {
        val v = sessions(c).takeLast(8).map { it.optInt("cap") }.filter { it > 0 }.sorted()
        return if (v.isEmpty()) 0 else v[v.size / 2]
    }

    fun nowWatts(c: Context): Double {
        val bm = c.getSystemService(BatteryManager::class.java) ?: return 0.0
        val ua = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).toDouble()
        val a = if (Math.abs(ua) < 20_000) ua / 1000.0 else ua / 1_000_000.0
        val bi = c.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val v = (bi?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0) / 1000.0
        return a * v
    }

    // ------------------------------------------------------------ profile według sieci Wi-Fi
    /** Odcisk sieci bez lokalizacji: adres bramy + podsieć + domena. */
    fun netId(c: Context): String {
        val cm = c.getSystemService(ConnectivityManager::class.java) ?: return ""
        val n = cm.activeNetwork ?: return ""
        if (cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) return ""
        val lp = cm.getLinkProperties(n) ?: return ""
        val gw = lp.routes.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress ?: ""
        val dns = lp.dnsServers.joinToString(",") { it.hostAddress ?: "" }
        return "$gw|${lp.domains ?: ""}|$dns"
    }

    /** Profil: nazwa, odcisk sieci, ustawienia (oszczędzanie, Bluetooth, synchronizacja). */
    fun profiles(): List<JSONObject> = try { JSONArray(Cfg.profiles).let { a -> (0 until a.length()).map { a.getJSONObject(it) } } } catch (e: Exception) { emptyList() }

    fun saveProfile(name: String, net: String, saver: Boolean, bt: Boolean, sync: Boolean) {
        val l = profiles().filter { it.optString("n") != name }.toMutableList()
        l.add(JSONObject().put("n", name).put("net", net).put("saver", saver).put("bt", bt).put("sync", sync))
        Cfg.profiles = JSONArray(l).toString(); Cfg.save()
    }

    fun removeProfile(name: String) { Cfg.profiles = JSONArray(profiles().filter { it.optString("n") != name }).toString(); Cfg.save() }

    /** Po połączeniu z Wi-Fi: zastosuj profil tej sieci. */
    fun onWifi(c: Context) {
        val id = netId(c)
        if (id.isEmpty()) return
        val p = profiles().firstOrNull { it.optString("net") == id } ?: return
        if (Cfg.prev["profile"] == p.optString("n")) return
        Cfg.prev["profile"] = p.optString("n")
        Engine.saver(p.optBoolean("saver"))
        if (Sh.granted()) Engine.bluetooth(p.optBoolean("bt"))
        android.content.ContentResolver.setMasterSyncAutomatically(p.optBoolean("sync"))
        Cfg.save()
        Alerts.plain(c, 5005, "Profil: ${p.optString("n")}", listOfNotNull(if (p.optBoolean("saver")) "oszczędzanie" else null,
            if (p.optBoolean("bt")) "Bluetooth włączony" else "Bluetooth wyłączony", if (p.optBoolean("sync")) "synchronizacja" else "bez synchronizacji").joinToString(" · "))
    }

    fun onWifiLost() { Cfg.prev.remove("profile") }

    // ------------------------------------------------------------ rzeczy niedostępne zwykłemu użytkownikowi
    /** Android 12+ ubija procesy potomne (Termux, Shizuku) – wyłączamy ten limit. */
    fun phantomFix(on: Boolean) {
        if (on) Sh.runAll(listOf("device_config set_sync_disabled_for_tests persistent", "device_config put activity_manager max_phantom_processes 2147483647",
            "settings put global settings_enable_monitor_phantom_procs false"))
        else Sh.runAll(listOf("device_config set_sync_disabled_for_tests none", "device_config delete activity_manager max_phantom_processes",
            "settings delete global settings_enable_monitor_phantom_procs"))
    }
}
