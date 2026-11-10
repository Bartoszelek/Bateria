package pl.bateria

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/** Prywatność: kto ma dostęp do czego, internet w tle, trackery, usługi śledzące producenta. */
object Priv {
    // ------------------------------------------------------------ grupy pozwoleń
    /**
     * Grupa dostępu: pozwolenia (runtime), dodatkowe pozwolenia tylko do wykrycia aplikacji,
     * operacje systemowe (appops) do blokowania także tam, gdzie pozwolenia się nie odbiera; all = każda aplikacja z ikoną.
     */
    class Group(val key: String, val name: String, val desc: String, val perms: List<String>, val op: String? = null,
                val extra: List<String> = emptyList(), val ops: List<String> = emptyList(), val all: Boolean = false)

    private const val P = "android.permission."

    val GROUPS = listOf(
        Group("loc", "Lokalizacja", "Gdzie jesteś – dokładnie lub w przybliżeniu", listOf("${P}ACCESS_FINE_LOCATION", "${P}ACCESS_COARSE_LOCATION"), "FINE_LOCATION"),
        Group("locbg", "Lokalizacja w tle", "Śledzenie położenia, gdy aplikacja jest zamknięta", listOf("${P}ACCESS_BACKGROUND_LOCATION")),
        Group("mic", "Mikrofon", "Nagrywanie dźwięku i rozmów", listOf("${P}RECORD_AUDIO"), "RECORD_AUDIO"),
        Group("cam", "Aparat", "Zdjęcia i filmy z aparatu", listOf("${P}CAMERA"), "CAMERA"),
        Group("media", "Zdjęcia i filmy (galeria)", "Twoja galeria, w tym zdjęcia i filmy z WhatsAppa", listOf("${P}READ_MEDIA_IMAGES", "${P}READ_MEDIA_VIDEO", "${P}READ_MEDIA_VISUAL_USER_SELECTED", "${P}READ_EXTERNAL_STORAGE", "${P}WRITE_EXTERNAL_STORAGE")),
        Group("audio", "Muzyka i nagrania", "Pliki audio, w tym notatki głosowe", listOf("${P}READ_MEDIA_AUDIO")),
        Group("sms", "SMS-y i MMS-y", "Czytanie i wysyłanie wiadomości tekstowych (także kodów z banku)", listOf("${P}READ_SMS", "${P}RECEIVE_SMS", "${P}SEND_SMS", "${P}RECEIVE_MMS", "${P}RECEIVE_WAP_PUSH")),
        Group("calllog", "Rejestr połączeń", "Z kim, kiedy i jak długo rozmawiałeś", listOf("${P}READ_CALL_LOG", "${P}WRITE_CALL_LOG")),
        Group("phone", "Telefon i rozmowy", "Numer telefonu, stan rozmów, dzwonienie, odbieranie", listOf("${P}READ_PHONE_STATE", "${P}READ_PHONE_NUMBERS", "${P}CALL_PHONE", "${P}ANSWER_PHONE_CALLS", "${P}ADD_VOICEMAIL", "${P}USE_SIP")),
        Group("contacts", "Kontakty i konta", "Twoja książka adresowa i lista kont", listOf("${P}READ_CONTACTS", "${P}WRITE_CONTACTS", "${P}GET_ACCOUNTS")),
        Group("cal", "Kalendarz", "Spotkania, uczestnicy, miejsca", listOf("${P}READ_CALENDAR", "${P}WRITE_CALENDAR")),
        Group("wifi", "Wi-Fi", "Skanowanie sieci w pobliżu, włączanie i wyłączanie Wi-Fi – z listy sieci da się ustalić, gdzie jesteś",
            listOf("${P}NEARBY_WIFI_DEVICES"), null, extra = listOf("${P}CHANGE_WIFI_STATE", "${P}CHANGE_WIFI_MULTICAST_STATE"), ops = listOf("CHANGE_WIFI_STATE")),
        Group("bt", "Bluetooth", "Wyszukiwanie i łączenie z urządzeniami, nadawanie sygnału – pozwala śledzić położenie w sklepach i budynkach",
            listOf("${P}BLUETOOTH_SCAN", "${P}BLUETOOTH_CONNECT", "${P}BLUETOOTH_ADVERTISE", "${P}UWB_RANGING"), null,
            extra = listOf("${P}BLUETOOTH_ADMIN", "${P}BLUETOOTH"), ops = listOf("BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "BLUETOOTH_ADVERTISE")),
        Group("body", "Aktywność i czujniki ciała", "Kroki, sposób poruszania się, tętno", listOf("${P}ACTIVITY_RECOGNITION", "${P}BODY_SENSORS", "${P}BODY_SENSORS_BACKGROUND")),
        Group("clip", "Schowek", "Kto może odczytać to, co skopiowałeś – hasła, kody, numery kont",
            emptyList(), null, ops = listOf("READ_CLIPBOARD"), all = true),
        Group("notif", "Powiadomienia", "Które aplikacje mogą wyświetlać powiadomienia – z liczbą powiadomień z ostatnich 24 h",
            listOf("${P}POST_NOTIFICATIONS"), null, ops = listOf("POST_NOTIFICATION"), all = true)
    )

    class Entry(val pkg: String, val label: String, val granted: Boolean, val system: Boolean, var lastUse: String = "", var count: Int = 0)

    private fun label(pm: PackageManager, ai: ApplicationInfo) = try { pm.getApplicationLabel(ai).toString() } catch (e: Exception) { ai.packageName }

    private fun isSys(ai: ApplicationInfo) = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0 && ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0

    /** Aplikacje, które proszą o daną grupę – z informacją, czy mają dostęp. */
    fun groupApps(c: Context, g: Group, withSystem: Boolean): List<Entry> {
        val pm = c.packageManager
        // aplikacje z zablokowaną operacją systemową
        val blockedOps = HashSet<String>()
        for (op in g.ops) for (line in Sh.run("appops query-op $op ignore").lines()) line.trim().takeIf { it.contains('.') && !it.contains(' ') }?.let { blockedOps.add(it) }
        val out = ArrayList<Entry>()
        for (pi in pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)) {
            val req = pi.requestedPermissions?.toSet() ?: emptySet()
            val ai = pi.applicationInfo ?: continue
            if (pi.packageName == c.packageName) continue
            val wanted = g.perms.filter { it in req }
            val listed = wanted.isNotEmpty() || g.extra.any { it in req } || (g.all && pm.getLaunchIntentForPackage(pi.packageName) != null)
            if (!listed) continue
            val sys = isSys(ai)
            if (sys && !withSystem) continue
            val permOk = wanted.isEmpty() || wanted.any { pm.checkPermission(it, pi.packageName) == PackageManager.PERMISSION_GRANTED }
            val granted = permOk && pi.packageName !in blockedOps
            out.add(Entry(pi.packageName, label(pm, ai), granted, sys))
        }
        if (g.key == "notif") {
            val cnt = notifCounts(c)
            out.forEach { e -> cnt[e.pkg]?.let { e.lastUse = "$it powiadomień / 24 h"; e.count = it } }
            return out.sortedWith(compareBy<Entry> { !it.granted }.thenByDescending { it.count }.thenBy { it.label.lowercase() })
        }
        return out.sortedWith(compareBy<Entry> { !it.granted }.thenBy { it.label.lowercase() })
    }

    /** Ile powiadomień wysłała każda aplikacja w ostatnich 24 h. */
    fun notifCounts(c: Context): Map<String, Int> {
        val um = c.getSystemService(android.app.usage.UsageStatsManager::class.java) ?: return emptyMap()
        val out = HashMap<String, Int>()
        try {
            val ev = um.queryEvents(System.currentTimeMillis() - 86_400_000L, System.currentTimeMillis())
            val e = android.app.usage.UsageEvents.Event()
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                if (e.eventType == 12) out[e.packageName] = (out[e.packageName] ?: 0) + 1 // NOTIFICATION_INTERRUPTION
            }
        } catch (e: Exception) {
        }
        return out
    }

    /** Ostatnie użycie (mikrofon, aparat, lokalizacja) – z rejestru systemu. */
    fun lastUse(op: String, pkgs: List<String>): Map<String, String> {
        if (!Sh.granted() || pkgs.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        for (chunk in pkgs.chunked(25)) {
            val txt = Sh.runAll(chunk.map { "echo \"@@$it\"; cmd appops get $it $op" })
            var cur = ""
            for (line in txt.lines()) {
                if (line.startsWith("@@")) { cur = line.removePrefix("@@").trim(); continue }
                val m = Regex("""time=\+([0-9dhms]+?)(\d+ms)? ago""").find(line) ?: continue
                if (cur.isNotEmpty() && !out.containsKey(cur)) out[cur] = m.groupValues[1].replace("d", " d ").replace("h", " h ").replace("m", " min ").replace("s", " s").trim() + " temu"
            }
        }
        return out
    }

    /** Zabiera albo oddaje dostęp (i blokuje ponowne pytanie po zabraniu). */
    fun set(c: Context, pkg: String, g: Group, allow: Boolean) {
        val pm = c.packageManager
        val req = try { pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions?.toList() ?: emptyList() } catch (e: Exception) { emptyList() }
        val cmds = ArrayList<String>()
        for (p in g.perms.filter { it in req }) {
            if (allow) cmds += listOf("pm clear-permission-flags $pkg $p user-fixed", "pm grant $pkg $p")
            else cmds += listOf("pm revoke $pkg $p", "pm set-permission-flags $pkg $p user-set user-fixed")
        }
        if (g.op != null) cmds += "appops set $pkg ${g.op} ${if (allow) "allow" else "ignore"}"
        for (op in g.ops) cmds += "appops set $pkg $op ${if (allow) "allow" else "ignore"}"
        Sh.runAll(cmds)
    }

    // ------------------------------------------------------------ internet w tle
    fun netApps(c: Context, withSystem: Boolean): List<Entry> {
        val pm = c.packageManager
        val out = ArrayList<Entry>()
        for (pi in pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)) {
            if (pi.requestedPermissions?.contains("${P}INTERNET") != true) continue
            val ai = pi.applicationInfo ?: continue
            if (pi.packageName == c.packageName) continue
            val sys = isSys(ai)
            if (sys && !withSystem) continue
            if (!sys && pm.getLaunchIntentForPackage(pi.packageName) == null) continue
            out.add(Entry(pi.packageName, label(pm, ai), pi.packageName !in Cfg.netDeny, sys))
        }
        return out.sortedWith(compareBy<Entry> { it.granted }.thenBy { it.label.lowercase() })
    }

    /**
     * Blokada internetu w tle: dane komórkowe w tle wyłączone systemowo (jak „Dane w tle” w ustawieniach aplikacji),
     * a po wygaszeniu ekranu cały ruch tej aplikacji (Wi-Fi też) przez lokalny filtr.
     */
    fun setNet(c: Context, pkg: String, allow: Boolean) {
        val uid = try { c.packageManager.getApplicationInfo(pkg, 0).uid } catch (e: Exception) { return }
        if (allow) Cfg.netDeny.remove(pkg) else Cfg.netDeny.add(pkg)
        Cfg.save()
        Sh.run("cmd netpolicy ${if (allow) "remove" else "add"} restrict-background-blacklist $uid")
    }

    // ------------------------------------------------------------ dostępy specjalne (najgroźniejsze)
    class Special(val key: String, val name: String, val desc: String)

    val SPECIAL = listOf(
        Special("listener", "Czytanie powiadomień", "Widzi treść WSZYSTKICH powiadomień – wiadomości z WhatsAppa, SMS-y, kody z banku"),
        Special("a11y", "Ułatwienia dostępu", "Widzi wszystko na ekranie i może klikać za Ciebie"),
        Special("admin", "Administratorzy urządzenia", "Mogą blokować i czyścić telefon, wymuszać hasła"),
        Special("usage", "Dostęp do danych o użyciu", "Wie, jakich aplikacji i jak długo używasz"),
        Special("overlay", "Wyświetlanie nad aplikacjami", "Może nakładać okna na inne aplikacje (np. fałszywe logowanie)"),
        Special("install", "Instalowanie aplikacji", "Może instalować inne aplikacje")
    )

    class SpecialEntry(val pkg: String, val label: String, val component: String)

    fun specialApps(c: Context, key: String): List<SpecialEntry> {
        val pm = c.packageManager
        fun lab(p: String) = try { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() } catch (e: Exception) { p }
        val out = ArrayList<SpecialEntry>()
        when (key) {
            "listener", "a11y" -> {
                val k = if (key == "listener") "enabled_notification_listeners" else "enabled_accessibility_services"
                val v = Sys.get(c, "secure", k)
                if (v != "null") for (comp in v.split(':').filter { it.contains('/') }) out.add(SpecialEntry(comp.substringBefore('/'), lab(comp.substringBefore('/')), comp))
            }
            "admin" -> {
                val dpm = c.getSystemService(DevicePolicyManager::class.java)
                dpm?.activeAdmins?.forEach { out.add(SpecialEntry(it.packageName, lab(it.packageName), it.flattenToString())) }
            }
            else -> {
                val op = when (key) { "usage" -> "GET_USAGE_STATS"; "overlay" -> "SYSTEM_ALERT_WINDOW"; else -> "REQUEST_INSTALL_PACKAGES" }
                val txt = Sh.run("appops query-op $op allow")
                for (p in txt.lines().map { it.trim() }.filter { it.contains('.') && !it.contains(' ') }) if (p != c.packageName) out.add(SpecialEntry(p, lab(p), op))
            }
        }
        return out.distinctBy { it.component }
    }

    fun revokeSpecial(c: Context, key: String, e: SpecialEntry): Boolean {
        return when (key) {
            "listener" -> { Sh.run("cmd notification disallow_listener ${e.component}"); true }
            "a11y" -> {
                val v = Sys.get(c, "secure", "enabled_accessibility_services")
                val nv = v.split(':').filter { it.isNotBlank() && it != e.component && it != "null" }.joinToString(":")
                Sys.put(c, "secure", "enabled_accessibility_services", nv.ifEmpty { null })
            }
            "admin" -> { Sh.run("dpm remove-active-admin ${e.component}"); true }
            else -> { Sh.run("appops set ${e.pkg} ${e.component} ignore"); true }
        }
    }

    // ------------------------------------------------------------ blokada trackerów w całym telefonie (prywatny DNS)
    fun dnsOn(c: Context) = Sys.get(c, "global", "private_dns_mode") == "hostname" && Sys.get(c, "global", "private_dns_specifier").contains("adguard")

    fun setDns(c: Context, on: Boolean) {
        if (on) {
            Sys.put(c, "global", "private_dns_specifier", "dns.adguard-dns.com")
            Sys.put(c, "global", "private_dns_mode", "hostname")
        } else Sys.put(c, "global", "private_dns_mode", "opportunistic")
    }

    // ------------------------------------------------------------ usługi śledzące producenta
    class Vendor(val pkg: String, val name: String, val what: String, val risk: String)

    val VENDOR = listOf(
        Vendor("com.samsung.android.rubin.app", "Samsung Customization Service", "Profiluje Cię na podstawie używania telefonu – do reklam i „spersonalizowanych” treści", "bezpieczne"),
        Vendor("com.samsung.android.dqagent", "Samsung Device Quality Agent", "Wysyła dane o używaniu i jakości urządzenia do Samsunga", "bezpieczne"),
        Vendor("com.sec.android.diagmonagent", "Samsung Diagnostic Monitoring", "Wysyła raporty diagnostyczne i logi do Samsunga", "bezpieczne"),
        Vendor("com.samsung.android.app.spage", "Samsung Free / News", "Treści i reklamy na pulpicie, śledzi zainteresowania", "bezpieczne"),
        Vendor("com.samsung.android.ipsgeofence", "Samsung Visit In", "Wykrywa odwiedzane miejsca (geofencing) do powiadomień marketingowych", "bezpieczne"),
        Vendor("com.samsung.android.smartsuggestions", "Samsung Smart Suggestions", "Analizuje Twoje nawyki, żeby podsuwać działania", "bezpieczne"),
        Vendor("com.samsung.android.bixby.agent", "Bixby", "Asystent głosowy – przetwarza głos i dane na serwerach Samsunga", "bezpieczne"),
        Vendor("com.samsung.android.visionintelligence", "Bixby Vision", "Analiza obrazu z aparatu na serwerach", "bezpieczne"),
        Vendor("com.samsung.android.mateagent", "Galaxy Friends", "Pomocnik marketingowy Samsunga", "bezpieczne"),
        Vendor("com.samsung.android.app.tips", "Porady Samsunga", "Powiadomienia z poradami i promocjami", "bezpieczne"),
        Vendor("com.samsung.android.da.daagent", "Samsung DA Agent", "Usługa zbierająca dane urządzenia", "sprawdź"),
        Vendor("com.samsung.klmsagent", "Knox Licence Manager", "Licencje Knox (potrzebne Bezpiecznemu folderowi)", "ostrożnie"),
        Vendor("com.samsung.android.mobileservice", "Samsung Experience Service", "Konto Samsung, synchronizacja – wyłączenie psuje konto i Znajdź mój telefon", "nie wyłączaj"),
        Vendor("com.facebook.appmanager", "Facebook App Manager", "Preinstalowany przez Facebooka – aktualizuje i zbiera dane nawet bez konta FB", "bezpieczne"),
        Vendor("com.facebook.services", "Facebook Services", "Usługi Facebooka w tle, telemetria", "bezpieczne"),
        Vendor("com.facebook.system", "Facebook App Installer", "Instalator Facebooka działający w tle", "bezpieczne"),
        Vendor("com.google.android.feedback", "Google Feedback", "Wysyła raporty o błędach do Google", "bezpieczne"),
        Vendor("com.google.android.partnersetup", "Google Partner Setup", "Konfiguracja partnerska Google, część telemetrii", "bezpieczne"),
        Vendor("com.google.android.apps.turbo", "Device Health Services", "Przewiduje zużycie baterii na serwerach Google", "bezpieczne"),
        Vendor("com.google.android.adservices.api", "Google Ad Services (Privacy Sandbox)", "Reklamy oparte na zainteresowaniach liczone w telefonie", "bezpieczne"),
        Vendor("com.google.android.as", "Android System Intelligence", "Funkcje AI (podpowiedzi, Live Caption) – analiza w telefonie", "ostrożnie"),
        Vendor("com.microsoft.appmanager", "Łącze z Windows", "Synchronizacja z Windows, telemetria Microsoftu", "bezpieczne"),
        Vendor("com.microsoft.skydrive", "OneDrive (preinstalowany)", "Chmura Microsoftu", "bezpieczne")
    )

    fun vendorState(c: Context): List<Pair<Vendor, Boolean?>> = VENDOR.map { v ->
        val st = try { c.packageManager.getApplicationInfo(v.pkg, PackageManager.MATCH_DISABLED_COMPONENTS).enabled } catch (e: Exception) { null }
        Pair(v, st)
    }

    fun setVendor(pkg: String, on: Boolean) = Sh.run(if (on) "pm enable $pkg" else "pm disable-user --user 0 $pkg")

    // ------------------------------------------------------------ trackery w aplikacjach (jak Exodus Privacy)
    class Tracker(val name: String, val kind: String, val prefixes: List<String>)

    val TRACKERS = listOf(
        Tracker("Google Firebase Analytics", "analityka", listOf("com.google.firebase.analytics")),
        Tracker("Google Analytics", "analityka", listOf("com.google.android.gms.analytics")),
        Tracker("Google AdMob", "reklamy", listOf("com.google.android.gms.ads")),
        Tracker("Google Tag Manager", "analityka", listOf("com.google.android.gms.tagmanager")),
        Tracker("Google Crashlytics", "raporty błędów", listOf("com.google.firebase.crashlytics", "com.crashlytics")),
        Tracker("Facebook Analytics", "analityka", listOf("com.facebook.appevents")),
        Tracker("Facebook Ads", "reklamy", listOf("com.facebook.ads")),
        Tracker("AppsFlyer", "profilowanie", listOf("com.appsflyer")),
        Tracker("Adjust", "profilowanie", listOf("com.adjust.sdk")),
        Tracker("Branch", "profilowanie", listOf("io.branch")),
        Tracker("Kochava", "profilowanie", listOf("com.kochava")),
        Tracker("Singular", "profilowanie", listOf("com.singular.sdk")),
        Tracker("Amplitude", "analityka", listOf("com.amplitude")),
        Tracker("Mixpanel", "analityka", listOf("com.mixpanel")),
        Tracker("Segment", "analityka", listOf("com.segment.analytics")),
        Tracker("Microsoft App Center", "analityka", listOf("com.microsoft.appcenter.analytics")),
        Tracker("Microsoft Clarity", "nagrywanie ekranu", listOf("com.microsoft.clarity")),
        Tracker("Smartlook", "nagrywanie ekranu", listOf("com.smartlook")),
        Tracker("Instabug", "raporty błędów", listOf("com.instabug")),
        Tracker("Sentry", "raporty błędów", listOf("io.sentry")),
        Tracker("Bugsnag", "raporty błędów", listOf("com.bugsnag")),
        Tracker("New Relic", "analityka", listOf("com.newrelic")),
        Tracker("Dynatrace", "analityka", listOf("com.dynatrace")),
        Tracker("Datadog", "analityka", listOf("com.datadog")),
        Tracker("AppDynamics", "analityka", listOf("com.appdynamics")),
        Tracker("Flurry", "analityka", listOf("com.flurry")),
        Tracker("Yandex AppMetrica", "analityka", listOf("com.yandex.metrica", "io.appmetrica")),
        Tracker("Huawei Analytics", "analityka", listOf("com.huawei.hms.analytics")),
        Tracker("Samsung Analytics", "analityka", listOf("com.samsung.context.sdk.samsunganalytics")),
        Tracker("OneSignal", "profilowanie", listOf("com.onesignal")),
        Tracker("Braze", "profilowanie", listOf("com.appboy", "com.braze")),
        Tracker("CleverTap", "profilowanie", listOf("com.clevertap")),
        Tracker("Airship", "profilowanie", listOf("com.urbanairship")),
        Tracker("Leanplum", "profilowanie", listOf("com.leanplum")),
        Tracker("Adobe Experience Platform", "analityka", listOf("com.adobe.marketing.mobile")),
        Tracker("Tealium", "analityka", listOf("com.tealium")),
        Tracker("Optimizely", "analityka", listOf("com.optimizely")),
        Tracker("Gemius", "analityka", listOf("com.gemius")),
        Tracker("comScore", "analityka", listOf("com.comscore")),
        Tracker("Nielsen", "analityka", listOf("com.nielsen")),
        Tracker("Unity Ads", "reklamy", listOf("com.unity3d.ads", "com.unity3d.services")),
        Tracker("AppLovin", "reklamy", listOf("com.applovin")),
        Tracker("ironSource", "reklamy", listOf("com.ironsource")),
        Tracker("Vungle / Liftoff", "reklamy", listOf("com.vungle")),
        Tracker("Chartboost", "reklamy", listOf("com.chartboost")),
        Tracker("InMobi", "reklamy", listOf("com.inmobi")),
        Tracker("Pangle (ByteDance)", "reklamy", listOf("com.bytedance.sdk")),
        Tracker("MoPub", "reklamy", listOf("com.mopub")),
        Tracker("Criteo", "reklamy", listOf("com.criteo")),
        Tracker("Taboola", "reklamy", listOf("com.taboola")),
        Tracker("Outbrain", "reklamy", listOf("com.outbrain")),
        Tracker("Matomo", "analityka", listOf("org.matomo")),
        Tracker("Countly", "analityka", listOf("ly.count.android"))
    )

    private val pats: List<Pair<ByteArray, Int>> by lazy {
        TRACKERS.flatMapIndexed { i, t -> t.prefixes.map { Pair(("L" + it.replace('.', '/') + "/").toByteArray(), i) } }
    }

    /** Szuka sygnatur trackerów w kodzie (DEX) aplikacji – bez uruchamiania jej. */
    fun scanApk(paths: List<String>): Set<Int> {
        val found = HashSet<Int>()
        val buf = ByteArray(1 shl 16)
        val keep = 64
        for (path in paths) {
            try {
                ZipFile(path).use { z ->
                    for (e in z.entries()) {
                        if (!e.name.endsWith(".dex")) continue
                        z.getInputStream(e).use { inp ->
                            var carry = ByteArray(0)
                            while (true) {
                                val n = inp.read(buf)
                                if (n <= 0) break
                                val data = ByteArray(carry.size + n)
                                System.arraycopy(carry, 0, data, 0, carry.size)
                                System.arraycopy(buf, 0, data, carry.size, n)
                                var i = 0
                                val end = data.size - 8
                                while (i < end) {
                                    if (data[i] == 'L'.code.toByte()) {
                                        for ((p, idx) in pats) {
                                            if (idx in found || i + p.size > data.size) continue
                                            var ok = true
                                            for (k in 1 until p.size) if (data[i + k] != p[k]) { ok = false; break }
                                            if (ok) found.add(idx)
                                        }
                                    }
                                    i++
                                }
                                carry = if (data.size > keep) data.copyOfRange(data.size - keep, data.size) else data
                                if (found.size == TRACKERS.size) return found
                            }
                        }
                    }
                }
            } catch (e: Exception) {
            }
        }
        return found
    }

    /** Składniki aplikacji należące do bibliotek śledzących (usługi i odbiorniki) – nazwa → czy włączony. */
    fun trackerComponents(c: Context, pkg: String): List<Pair<String, Boolean>> {
        val pm = c.packageManager
        val pi = try {
            pm.getPackageInfo(pkg, PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.MATCH_DISABLED_COMPONENTS)
        } catch (e: Exception) { return emptyList() }
        // Firebase Analytics ma składniki w pakiecie „measurement”
        val prefixes = TRACKERS.flatMap { it.prefixes } + listOf("com.google.android.gms.measurement", "com.google.android.datatransport", "com.facebook.CampaignTrackingReceiver",
            "com.google.android.gms.ads", "com.android.installreferrer")
        val names = ((pi.services?.map { it.name } ?: emptyList()) + (pi.receivers?.map { it.name } ?: emptyList())).filter { n -> prefixes.any { n.startsWith(it) } }
        return names.distinct().map { n ->
            val st = pm.getComponentEnabledSetting(ComponentName(pkg, n))
            Pair(n, st != PackageManager.COMPONENT_ENABLED_STATE_DISABLED && st != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)
        }
    }

    fun setTrackerComponents(pkg: String, names: List<String>, enable: Boolean) =
        names.chunked(20).forEach { ch -> Sh.runAll(ch.map { "pm ${if (enable) "enable" else "disable"} '$pkg/$it'" }) }

    class AppTrackers(val pkg: String, val label: String, val trackers: List<Tracker>, val system: Boolean)

    private fun cacheFile(c: Context) = File(c.filesDir, "trackers.json")

    /** Skan wszystkich aplikacji (wynik zapamiętany wg wersji – ponowny skan tylko zmienionych). */
    fun scanAll(c: Context, withSystem: Boolean, progress: (String) -> Unit): List<AppTrackers> {
        val pm = c.packageManager
        val cache = try { JSONObject(cacheFile(c).readText()) } catch (e: Exception) { JSONObject() }
        val apps = pm.getInstalledPackages(0).filter { pi ->
            val ai = pi.applicationInfo ?: return@filter false
            pi.packageName != c.packageName && (withSystem || !isSys(ai))
        }
        val out = ArrayList<AppTrackers>()
        for ((n, pi) in apps.withIndex()) {
            val ai = pi.applicationInfo ?: continue
            val key = pi.packageName + "@" + pi.longVersionCode
            val ids: List<Int> = cache.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: run {
                progress("Sprawdzam aplikacje: ${n + 1}/${apps.size} – ${label(pm, ai)}")
                val f = scanApk(listOf(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList())).sorted()
                cache.put(key, JSONArray(f))
                f
            }
            out.add(AppTrackers(pi.packageName, label(pm, ai), ids.mapNotNull { TRACKERS.getOrNull(it) }, isSys(ai)))
        }
        cacheFile(c).writeText(cache.toString())
        return out.sortedByDescending { it.trackers.size }
    }

    @Suppress("unused")
    private fun keep(cn: ComponentName, i: Intent) = Unit
}
