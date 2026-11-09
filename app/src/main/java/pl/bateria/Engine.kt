package pl.bateria

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

object Engine {
    // ------------------------------------------------------------ ustawienia systemu
    private fun get(ns: String, k: String) = Sh.run("settings get $ns $k").trim()

    /** Zmienia ustawienie, zapamiętując poprzednią wartość (do przywrócenia). */
    private fun setRemember(ns: String, k: String, v: String) {
        val key = "$ns/$k"
        if (!Cfg.prev.containsKey(key)) Cfg.prev[key] = get(ns, k)
        Sh.run("settings put $ns $k $v")
    }

    private fun restore(ns: String, k: String) {
        val key = "$ns/$k"
        val old = Cfg.prev.remove(key) ?: return
        if (old.isBlank() || old == "null") Sh.run("settings delete $ns $k") else Sh.run("settings put $ns $k $old")
    }

    fun applyHz(on: Boolean) {
        if (on) {
            setRemember("secure", "refresh_rate_mode", "0")
            setRemember("system", "peak_refresh_rate", "60.0")
            setRemember("system", "min_refresh_rate", "60.0")
        } else {
            restore("secure", "refresh_rate_mode"); restore("system", "peak_refresh_rate"); restore("system", "min_refresh_rate")
        }
    }

    fun applyDark(on: Boolean) {
        if (on) {
            if (!Cfg.prev.containsKey("uimode")) Cfg.prev["uimode"] = if (Sh.run("cmd uimode night").contains("yes")) "yes" else "no"
            Sh.runAll(listOf("cmd uimode night yes", "setprop debug.hwui.force_dark true"))
        } else {
            val old = Cfg.prev.remove("uimode") ?: "no"
            Sh.runAll(listOf("cmd uimode night $old", "setprop debug.hwui.force_dark false"))
        }
    }

    fun applyAod(off: Boolean) {
        if (off) { setRemember("system", "aod_mode", "0"); setRemember("secure", "doze_always_on", "0") }
        else { restore("system", "aod_mode"); restore("secure", "doze_always_on") }
    }

    fun applyScan(off: Boolean) {
        if (off) {
            setRemember("global", "wifi_scan_always_enabled", "0")
            setRemember("global", "ble_scan_always_enabled", "0")
            setRemember("system", "nearby_scanning_enabled", "0")
        } else {
            restore("global", "wifi_scan_always_enabled"); restore("global", "ble_scan_always_enabled"); restore("system", "nearby_scanning_enabled")
        }
    }

    fun saver(on: Boolean) = Sh.run("settings put global low_power ${if (on) 1 else 0}")

    fun saverOn(c: Context) = android.provider.Settings.Global.getInt(c.contentResolver, "low_power", 0) == 1

    /** Wszystko naraz („Ustaw maksymalną oszczędność”). */
    fun maxSaving(c: Context) {
        Cfg.hz60 = true; Cfg.dark = true; Cfg.aodOff = true; Cfg.scanOff = true; Cfg.deepDoze = true
        Cfg.guard = true; Cfg.autoUsage = true
        applyHz(true); applyDark(true); applyAod(true); applyScan(true)
        restrictVendor(c)
        Cfg.save()
        autoByUsage(c, force = true)
    }

    /** Po restarcie telefonu część ustawień (np. wymuszona ciemność) znika – przywróć. */
    fun reapply() {
        if (Cfg.dark) Sh.run("setprop debug.hwui.force_dark true")
    }

    // ------------------------------------------------------------ kategorie
    fun restrict(p: String, on: Boolean) {
        val m = if (on) "ignore" else "default"
        Sh.runAll(listOf(
            "cmd appops set $p RUN_ANY_IN_BACKGROUND $m",
            "cmd appops set $p RUN_IN_BACKGROUND $m",
            "cmd appops set $p WAKE_LOCK $m",
            "am set-standby-bucket $p ${if (on) "restricted" else "active"}"
        ))
    }

    fun freeze(p: String, on: Boolean) {
        Sh.run(if (on) "pm disable-user --user 0 $p" else "pm enable $p")
    }

    /** Zmiana kategorii: cofnij skutki starej, nałóż stałe skutki nowej. */
    fun setCat(c: Context, pkgs: Collection<String>, cat: String, byAuto: Boolean = false) {
        val cmds = ArrayList<String>()
        for (p in pkgs) {
            val old = Cfg.cat(p)
            if (old == cat) continue
            if (old == Cat.BG || old == Cat.OFF) cmds += listOf("cmd appops set $p RUN_ANY_IN_BACKGROUND default", "cmd appops set $p RUN_IN_BACKGROUND default",
                "cmd appops set $p WAKE_LOCK default", "am set-standby-bucket $p active")
            if (old == Cat.FZP || old == Cat.FZ1 || old == Cat.FZ2) cmds += "pm enable $p"
            if (cat == Cat.BG) cmds += listOf("cmd appops set $p RUN_ANY_IN_BACKGROUND ignore", "cmd appops set $p RUN_IN_BACKGROUND ignore",
                "cmd appops set $p WAKE_LOCK ignore", "am set-standby-bucket $p restricted")
            if (cat == Cat.FZP) cmds += "pm disable-user --user 0 $p"
            if (cat == Cat.NORMAL) Cfg.cats.remove(p) else Cfg.cats[p] = cat
            if (byAuto) Cfg.auto.add(p) else Cfg.auto.remove(p)
        }
        // w paczkach, żeby nie przekroczyć długości polecenia
        cmds.chunked(40).forEach { Sh.runAll(it) }
        Cfg.save()
    }

    fun restrictVendor(c: Context) {
        val pm = c.packageManager
        val list = Apps.VENDOR_SAFE.map { it.first }.filter { Apps.installed(pm, it) && Cfg.cat(it) == Cat.NORMAL }
        setCat(c, list, Cat.BG)
    }

    // ------------------------------------------------------------ ekran wygaszony / włączony
    private val appliedOff = HashSet<String>()
    private val frozenNow = HashSet<String>()

    private fun pi(c: Context, action: String, code: Int) = PendingIntent.getBroadcast(c, code,
        Intent(c, AlarmReceiver::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun later(c: Context, action: String, code: Int, minutes: Int) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, android.os.SystemClock.elapsedRealtime() + minutes * 60_000L + 1000, pi(c, action, code))
    }

    private fun cancel(c: Context, action: String, code: Int) {
        c.getSystemService(AlarmManager::class.java)?.cancel(pi(c, action, code))
    }

    fun charging(c: Context) = c.getSystemService(BatteryManager::class.java)?.isCharging == true

    fun onScreenOff(c: Context) {
        val d = Cfg.delay
        if (pkgs(Cat.OFF).isNotEmpty()) { if ((d[Cat.OFF] ?: 0) <= 0) runOff(c, Cat.OFF) else later(c, "off_" + Cat.OFF, 61, d[Cat.OFF] ?: 0) }
        if (pkgs(Cat.FZ1).isNotEmpty()) later(c, "off_" + Cat.FZ1, 62, d[Cat.FZ1] ?: 5)
        if (pkgs(Cat.FZ2).isNotEmpty()) later(c, "off_" + Cat.FZ2, 63, d[Cat.FZ2] ?: 30)
        if (Cfg.deepDoze && !charging(c)) later(c, "doze", 64, 1)
        if (Cfg.netBlock) NetBlock.start(c)
        if (Cfg.guard) later(c, "guard", 65, 15)
    }

    /** Wykonuje działanie kategorii po wygaszeniu (alarm). */
    fun runOff(c: Context, cat: String) {
        val list = pkgs(cat)
        when (cat) {
            Cat.OFF -> { list.forEach { restrict(it, true) }; appliedOff.addAll(list) }
            Cat.FZ1, Cat.FZ2 -> { Sh.runAll(list.map { "pm disable-user --user 0 $it" }); frozenNow.addAll(list) }
        }
    }

    fun onScreenOn(c: Context) {
        cancel(c, "off_" + Cat.OFF, 61); cancel(c, "off_" + Cat.FZ1, 62); cancel(c, "off_" + Cat.FZ2, 63)
        cancel(c, "doze", 64); cancel(c, "guard", 65)
        val cmds = ArrayList<String>()
        // najpierw odmrażanie – żeby ikony były od razu
        val fz = frozenNow.toList() + pkgs(Cat.FZ1) + pkgs(Cat.FZ2)
        val pm = c.packageManager
        fz.distinct().filter { Apps.frozen(pm, it) }.forEach { cmds += "pm enable $it" }
        frozenNow.clear()
        if (Cfg.deepDoze) cmds += "dumpsys deviceidle unforce"
        for (p in (appliedOff + pkgs(Cat.OFF)).distinct()) cmds += listOf("cmd appops set $p RUN_ANY_IN_BACKGROUND default", "cmd appops set $p RUN_IN_BACKGROUND default",
            "cmd appops set $p WAKE_LOCK default", "am set-standby-bucket $p active")
        appliedOff.clear()
        cmds.chunked(40).forEach { Sh.runAll(it) }
        NetBlock.stop(c)
    }

    fun doze() = Sh.run("dumpsys deviceidle force-idle")

    fun pkgs(cat: String) = Cfg.cats.filter { it.value == cat }.keys.toList()

    // ------------------------------------------------------------ noc i weekend
    fun scheduleNight(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pi(c, "night_on", 71)); am.cancel(pi(c, "night_off", 72))
        if (!Cfg.night) return
        fun next(h: Int): Long {
            val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
            if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
            return cal.timeInMillis
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next(Cfg.nightFrom), pi(c, "night_on", 71))
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next(Cfg.nightTo), pi(c, "night_off", 72))
    }

    fun inNight(): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return if (Cfg.nightFrom > Cfg.nightTo) h >= Cfg.nightFrom || h < Cfg.nightTo else h in Cfg.nightFrom until Cfg.nightTo
    }

    fun weekend(): Boolean = Calendar.getInstance().get(Calendar.DAY_OF_WEEK).let { it == Calendar.SATURDAY || it == Calendar.SUNDAY }

    fun night(c: Context, on: Boolean) {
        if (on) {
            if (Cfg.nightSync) ContentResolver.setMasterSyncAutomatically(false)
            if (Cfg.nightData && !charging(c)) Sh.run("svc data disable")
            if (Cfg.nightSaver) saver(true)
        } else {
            if (Cfg.nightSync) ContentResolver.setMasterSyncAutomatically(true)
            if (Cfg.nightData) Sh.run("svc data enable")
            if (Cfg.nightSaver && !(Cfg.weekendSaver && weekend())) saver(false)
        }
        scheduleNight(c)
    }

    // ------------------------------------------------------------ automat według używania
    fun autoByUsage(c: Context, force: Boolean = false) {
        if (!Cfg.autoUsage && !force) return
        if (!force && System.currentTimeMillis() - Cfg.lastAuto < 12 * 3600_000L) return
        Sh.run("appops set ${c.packageName} GET_USAGE_STATS allow")
        val use = Apps.usage(c)
        if (use.isEmpty()) return
        val now = System.currentTimeMillis()
        val skip = Apps.untouchable(c)
        val toBg = ArrayList<String>()
        val toFz = ArrayList<String>()
        val back = ArrayList<String>()
        for (a in Apps.list(c, false)) {
            if (a.pkg in skip) continue
            val cat = Cfg.cat(a.pkg)
            val last = use[a.pkg] ?: 0L
            val days = if (last > 0) (now - last) / 86_400_000L else 99
            if (cat == Cat.NORMAL) {
                if (Cfg.autoFreeze && days >= 30) toFz += a.pkg else if (days >= 7) toBg += a.pkg
            } else if (a.pkg in Cfg.auto && days < 2 && (cat == Cat.BG || cat == Cat.FZP)) back += a.pkg
            else if (a.pkg in Cfg.auto && cat == Cat.BG && Cfg.autoFreeze && days >= 30) toFz += a.pkg
        }
        setCat(c, toBg, Cat.BG, true)
        setCat(c, toFz, Cat.FZP, true)
        setCat(c, back, Cat.NORMAL, false)
        Cfg.lastAuto = now
        Cfg.save()
    }

    // ------------------------------------------------------------ ranking zużycia
    class Use(val pkg: String, val label: String, val mah: Double, val bg: Double, val fgMin: Long)

    fun ranking(c: Context): List<Use> {
        val txt = Sh.run("dumpsys batterystats --charged")
        val pm = c.packageManager
        val map = HashMap<String, DoubleArray>()
        val rx = Regex("""^\s*UID (u\d+a\d+|\d+):\s*([\d.]+)(.*)$""", RegexOption.MULTILINE)
        val bgRx = Regex("""bg:\s*([\d.]+)""")
        for (m in rx.findAll(txt)) {
            val u = m.groupValues[1]
            val uid = if (u.startsWith("u")) 10000 + u.substringAfter('a').toInt() + u.substring(1).substringBefore('a').toInt() * 100000 else u.toIntOrNull() ?: continue
            if (uid < 10000) continue
            val pkg = pm.getPackagesForUid(uid)?.firstOrNull() ?: continue
            val bg = bgRx.find(m.groupValues[3])?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            val a = map.getOrPut(pkg) { DoubleArray(2) }
            a[0] += m.groupValues[2].toDoubleOrNull() ?: 0.0; a[1] += bg
        }
        val fg = Apps.screenTime(c, System.currentTimeMillis() - 86_400_000L)
        return map.map { (p, v) ->
            val label = try { pm.getApplicationLabel(pm.getApplicationInfo(p, PackageManager.MATCH_DISABLED_COMPONENTS)).toString() } catch (e: Exception) { p }
            Use(p, label, v[0], v[1], (fg[p] ?: 0L) / 60_000)
        }.filter { it.mah > 0.05 }.sortedByDescending { it.mah }.take(40)
    }

    // ------------------------------------------------------------ strażnik
    private val warned = HashMap<String, Long>()

    fun guardCheck(c: Context) {
        if (!Cfg.guard) return
        val txt = Sh.run("dumpsys power")
        val rx = Regex("""PARTIAL_WAKE_LOCK\s+'([^']*)'.*?ACQ=-([0-9hdms]+)\s.*?uid=(\d+)""")
        val pm = c.packageManager
        val skip = Apps.untouchable(c)
        for (m in rx.findAll(txt)) {
            val ms = dur(m.groupValues[2])
            val uid = m.groupValues[3].toIntOrNull() ?: continue
            if (uid < 10000 || ms < 20 * 60_000L) continue
            val pkg = pm.getPackagesForUid(uid)?.firstOrNull() ?: continue
            if (pkg in skip || Cfg.cat(pkg) == Cat.PROTECT || Cfg.cat(pkg) == Cat.BG) continue
            if (System.currentTimeMillis() - (warned[pkg] ?: 0L) < 6 * 3600_000L) continue
            warned[pkg] = System.currentTimeMillis()
            notifyGuard(c, pkg, ms)
        }
        if (Cfg.guard) later(c, "guard", 65, 15)
    }

    private fun dur(s: String): Long {
        var t = 0L
        Regex("""(\d+)(ms|d|h|m|s)""").findAll(s).forEach {
            val v = it.groupValues[1].toLong()
            t += when (it.groupValues[2]) { "d" -> v * 86_400_000L; "h" -> v * 3600_000L; "m" -> v * 60_000L; "s" -> v * 1000L; else -> v }
        }
        return t
    }

    private fun notifyGuard(c: Context, pkg: String, ms: Long) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel("guard", "Strażnik baterii", NotificationManager.IMPORTANCE_DEFAULT))
        val label = try { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
        val act = PendingIntent.getBroadcast(c, pkg.hashCode(), Intent(c, AlarmReceiver::class.java).setAction("limit").putExtra("pkg", pkg),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(c, 80, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        try {
            nm.notify(pkg.hashCode(), android.app.Notification.Builder(c, "guard")
                .setSmallIcon(android.graphics.drawable.Icon.createWithResource(c, R.drawable.ic_bolt))
                .setContentTitle("$label nie pozwala telefonowi spać")
                .setContentText("Trzyma go obudzonego od ${ms / 60_000} min przy wygaszonym ekranie.")
                .setContentIntent(open).setAutoCancel(true)
                .addAction(android.app.Notification.Action.Builder(null, "Ogranicz w tle", act).build())
                .build())
        } catch (e: SecurityException) {
        }
    }

    // ------------------------------------------------------------ historia baterii
    class Point(val t: Long, val level: Int, val charging: Boolean, val screen: Boolean)

    private fun histFile(c: Context) = File(c.filesDir, "history.json")

    @Synchronized
    fun log(c: Context, level: Int, charging: Boolean, screen: Boolean) {
        val list = history(c).toMutableList()
        val last = list.lastOrNull()
        if (last != null && last.level == level && last.charging == charging && last.screen == screen) return
        list.add(Point(System.currentTimeMillis(), level, charging, screen))
        val cut = System.currentTimeMillis() - 7 * 86_400_000L
        val a = JSONArray()
        list.filter { it.t >= cut }.forEach { a.put(JSONArray().put(it.t).put(it.level).put(if (it.charging) 1 else 0).put(if (it.screen) 1 else 0)) }
        histFile(c).writeText(a.toString())
    }

    fun history(c: Context): List<Point> = try {
        val a = JSONArray(histFile(c).readText())
        (0 until a.length()).map { val x = a.getJSONArray(it); Point(x.getLong(0), x.getInt(1), x.getInt(2) == 1, x.getInt(3) == 1) }
    } catch (e: Exception) {
        emptyList()
    }

    /** Średni spadek %/h przy wygaszonym i włączonym ekranie (ostatnie 24 h, bez ładowania). */
    fun drain(c: Context): Pair<Double, Double> {
        val h = history(c).filter { it.t > System.currentTimeMillis() - 86_400_000L }
        var offPct = 0.0; var offMs = 0.0; var onPct = 0.0; var onMs = 0.0
        for (i in 1 until h.size) {
            val a = h[i - 1]; val b = h[i]
            if (a.charging || b.charging) continue
            val dp = (a.level - b.level).toDouble()
            val dt = (b.t - a.t).toDouble()
            if (dt <= 0 || dp < 0) continue
            if (a.screen) { onPct += dp; onMs += dt } else { offPct += dp; offMs += dt }
        }
        return Pair(if (offMs > 0) offPct / (offMs / 3600_000.0) else 0.0, if (onMs > 0) onPct / (onMs / 3600_000.0) else 0.0)
    }

    // ------------------------------------------------------------ lokalizacja w tle
    /** Aplikacje proszące o lokalizację w tle: pakiet → czy ma ją teraz. */
    fun bgLocationApps(c: Context): List<Pair<String, Boolean>> {
        val pm = c.packageManager
        return pm.getInstalledPackages(PackageManager.GET_PERMISSIONS).filter { pi ->
            pi.requestedPermissions?.contains("android.permission.ACCESS_BACKGROUND_LOCATION") == true && pi.packageName != c.packageName &&
                pm.getLaunchIntentForPackage(pi.packageName) != null
        }.map { Pair(it.packageName, pm.checkPermission("android.permission.ACCESS_BACKGROUND_LOCATION", it.packageName) == PackageManager.PERMISSION_GRANTED) }
    }

    /** Odbiera lokalizację w tle wszystkim poza dozwolonymi. */
    fun revokeAllBgLocation(c: Context): Int {
        val list = bgLocationApps(c).filter { it.second && it.first !in Cfg.bgLocOk && Cfg.cat(it.first) != Cat.PROTECT }
        Sh.runAll(list.map { "pm revoke ${it.first} android.permission.ACCESS_BACKGROUND_LOCATION" })
        return list.size
    }

    fun setBgLocation(p: String, allow: Boolean) {
        Sh.run(if (allow) "pm grant $p android.permission.ACCESS_BACKGROUND_LOCATION" else "pm revoke $p android.permission.ACCESS_BACKGROUND_LOCATION")
    }

    @Suppress("unused")
    fun debugJson(): String = JSONObject().put("cats", Cfg.cats.size).toString()
}
