package pl.bateria

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/** Dziennik zapytań DNS: kto (aplikacja) pytał o jaką domenę i czy została zablokowana. */
object Dns {
    class Hit(val t: Long, val pkg: String, val domain: String, val state: Int) // 0 ok, 1 tracker zablokowany, 2 aplikacja zablokowana, 3 tracker przepuszczony, 4 Twoja blokada, 5 złośliwa domena

    /** Domeny reklam i śledzenia (wraz z poddomenami). */
    val TRACKERS = setOf(
        "doubleclick.net", "google-analytics.com", "googleadservices.com", "googlesyndication.com", "googletagmanager.com", "googletagservices.com",
        "app-measurement.com", "firebase-settings.crashlytics.com", "crashlytics.com", "adservice.google.com", "pagead2.googlesyndication.com",
        "admob.com", "app-analytics-services.com", "ssl.google-analytics.com", "analytics.google.com",
        "graph.facebook.com.analytics", "an.facebook.com", "connect.facebook.net", "pixel.facebook.com", "ads.facebook.com",
        "appsflyer.com", "appsflyersdk.com", "adjust.com", "adjust.io", "branch.io", "app.link", "kochava.com", "singular.net",
        "amplitude.com", "mixpanel.com", "segment.io", "segment.com", "api.segment.io", "onesignal.com", "braze.com", "appboy.com",
        "clevertap-prod.com", "wzrkt.com", "urbanairship.com", "leanplum.com", "localytics.com", "flurry.com", "data.flurry.com",
        "appcenter.ms", "in.appcenter.ms", "clarity.ms", "smartlook.com", "smartlook.cloud", "instabug.com", "bugsnag.com", "sessions.bugsnag.com",
        "newrelic.com", "nr-data.net", "dynatrace.com", "datadoghq.com", "appdynamics.com", "mc.yandex.ru", "appmetrica.yandex.net", "report.appmetrica.yandex.net",
        "gemius.pl", "hit.gemius.pl", "gemius.com", "scorecardresearch.com", "comscore.com", "nielsen.com", "imrworldwide.com",
        "unityads.unity3d.com", "config.unityads.unity3d.com", "applovin.com", "applvn.com", "ironsrc.com", "ironsrc.mobi", "supersonicads.com",
        "vungle.com", "chartboost.com", "inmobi.com", "mopub.com", "criteo.com", "criteo.net", "taboola.com", "outbrain.com", "adnxs.com",
        "rubiconproject.com", "pubmatic.com", "openx.net", "moatads.com", "adsrvr.org", "casalemedia.com", "smaato.net", "bidswitch.net",
        "samsungads.com", "samsungadhub.com", "samsungacr.com", "samsung-analytics.com", "analytics.samsungknox.com",
        "pangle.io", "pangleglobal.com", "byteoversea.com", "tiktokv.com.analytics", "log.byteoversea.com",
        "hotjar.com", "hotjar.io", "quantserve.com", "quantcount.com", "bluekai.com", "demdex.net", "omtrdc.net", "everesttech.net",
        "tealiumiq.com", "optimizely.com", "branch.io", "adcolony.com", "startappservice.com", "tapjoy.com", "fyber.com", "mintegral.com", "rayjump.com",
        "huawei-ads.com", "hicloud-analytics.com", "metrics.icloud.com.analytics", "xiaomi-analytics.com", "tracking.miui.com"
    )

    fun isTracker(d: String): Boolean {
        var x = d
        while (true) {
            if (x in TRACKERS) return true
            val i = x.indexOf('.')
            if (i < 0 || x.indexOf('.', i + 1) < 0) return false
            x = x.substring(i + 1)
        }
    }

    // ------------------------------------------------------------ duże listy blokowania (reklamy, śledzenie, phishing)
    val SOURCES = listOf(
        "AdGuard DNS (reklamy i śledzenie)" to "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt",
        "StevenBlack (reklamy i złośliwe)" to "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
        "Polskie filtry reklam" to "https://raw.githubusercontent.com/MajkiIT/polish-ads-filter/master/polish-pihole-filters/hostfile.txt",
        "CERT Polska (wyłudzenia i oszustwa)" to "https://hole.cert.pl/domains/v2/domains_hosts.txt"
    )

    @Volatile var lists: HashSet<String> = HashSet()

    private fun listFile(c: Context) = File(c.filesDir, "blocklist.txt")

    fun loadLists(c: Context) {
        if (lists.isNotEmpty()) return
        val f = listFile(c)
        if (!f.exists()) return
        val h = HashSet<String>(300_000)
        try { f.forEachLine { if (it.isNotEmpty()) h.add(it) } } catch (e: Exception) {}
        lists = h
    }

    /** Pobiera i scala listy. Zwraca liczbę domen. */
    fun updateLists(c: Context, progress: (String) -> Unit): Int {
        val h = HashSet<String>(300_000)
        for ((name, url) in SOURCES) {
            progress("Pobieram: $name")
            try {
                val con = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                con.connectTimeout = 20_000; con.readTimeout = 60_000
                con.inputStream.bufferedReader().useLines { lines ->
                    for (raw in lines) {
                        val l = raw.trim()
                        if (l.isEmpty() || l[0] == '#' || l[0] == '!') continue
                        val d = when {
                            l.startsWith("||") -> l.substring(2).substringBefore('^').substringBefore('$')
                            l.startsWith("0.0.0.0 ") || l.startsWith("127.0.0.1 ") -> l.substringAfter(' ').trim().substringBefore(' ').substringBefore('#')
                            !l.contains(' ') && l.contains('.') && !l.contains('/') && !l.startsWith("@@") -> l
                            else -> ""
                        }.lowercase()
                        if (d.length > 3 && d.contains('.') && !d.contains('*') && d != "localhost" && d != "0.0.0.0") h.add(d)
                    }
                }
            } catch (e: Exception) {
            }
        }
        if (h.size < 1000) throw IllegalStateException("Nie udało się pobrać list – sprawdź internet")
        listFile(c).bufferedWriter().use { w -> for (d in h) { w.write(d); w.newLine() } }
        lists = h
        Cfg.listsAt = System.currentTimeMillis(); Cfg.save()
        return h.size
    }

    /** Czy domena (albo domena nadrzędna) jest na listach. */
    fun listed(d: String): Boolean {
        val l = lists
        if (l.isEmpty()) return false
        var x = d
        while (true) {
            if (x in l) return true
            val i = x.indexOf('.')
            if (i < 0 || x.indexOf('.', i + 1) < 0) return false
            x = x.substring(i + 1)
        }
    }

    private val log = ArrayDeque<Hit>()
    val stats = HashMap<String, HashMap<String, IntArray>>() // pakiet → domena → [liczba, stan]

    @Synchronized
    fun add(h: Hit) {
        log.addLast(h)
        while (log.size > 3000) log.removeFirst()
        val m = stats.getOrPut(h.pkg) { HashMap() }
        val a = m.getOrPut(h.domain) { IntArray(2) }
        a[0]++; a[1] = h.state
    }

    @Synchronized fun recent(n: Int = 200): List<Hit> = log.toList().takeLast(n).reversed()

    @Synchronized
    fun summary(): List<Triple<String, Int, Int>> = stats.map { (p, m) ->
        Triple(p, m.values.sumOf { it[0] }, m.count { isTracker(it.key) })
    }.sortedByDescending { it.second }

    @Synchronized
    fun domains(pkg: String): List<Pair<String, IntArray>> = (stats[pkg] ?: HashMap()).entries.map { it.key to it.value }.sortedByDescending { it.second[0] }

    @Synchronized
    fun clear(c: Context) { log.clear(); stats.clear(); File(c.filesDir, "dns.json").delete() }

    @Synchronized
    fun save(c: Context) {
        val o = JSONObject()
        for ((p, m) in stats) {
            val j = JSONObject()
            for ((d, a) in m) j.put(d, JSONArray().put(a[0]).put(a[1]))
            o.put(p, j)
        }
        File(c.filesDir, "dns.json").writeText(o.toString())
    }

    @Synchronized
    fun load(c: Context) {
        if (stats.isNotEmpty()) return
        try {
            val o = JSONObject(File(c.filesDir, "dns.json").readText())
            for (p in o.keys()) {
                val j = o.getJSONObject(p); val m = HashMap<String, IntArray>()
                for (d in j.keys()) { val a = j.getJSONArray(d); m[d] = intArrayOf(a.getInt(0), a.getInt(1)) }
                stats[p] = m
            }
        } catch (e: Exception) {
        }
    }
}

/**
 * Strażnik sieci (lokalny VPN, nic nie wychodzi poza telefon):
 *  – tryb monitora: przez filtr przechodzą tylko zapytania DNS wszystkich aplikacji – zapisujemy, kto łączy się z czym,
 *    blokujemy trackery i (przy wygaszonym ekranie) aplikacje bez internetu w tle;
 *  – bez monitora: przy wygaszonym ekranie cały ruch wybranych aplikacji jest odrzucany.
 */
class NetBlock : VpnService() {
    companion object {
        @Volatile private var fd: ParcelFileDescriptor? = null
        @Volatile private var mode = ""
        @Volatile var screenOff = false

        val active get() = fd != null
        val monitoring get() = fd != null && mode == "dns"

        private fun send(c: Context, a: String) {
            try { c.startService(Intent(c, NetBlock::class.java).setAction(a)) } catch (e: Exception) {}
        }

        /** Zmiana stanu ekranu. */
        fun screen(c: Context, off: Boolean) {
            screenOff = off
            if (VpnService.prepare(c) != null) return
            if (Cfg.monitor) { if (!monitoring) send(c, "dns"); return }
            val need = Cfg.netBlock || Cfg.netDeny.isNotEmpty()
            if (off && need) send(c, "drop") else if (!off && fd != null) send(c, "stop")
        }

        fun monitor(c: Context, on: Boolean) {
            if (on) send(c, "dns") else send(c, "stop")
        }

        fun start(c: Context) = screen(c, true)
        fun stop(c: Context) = screen(c, false)
    }

    private val pool = Executors.newFixedThreadPool(6)
    private var uids: Map<Int, String> = emptyMap()
    private var uidsAt = 0L
    private var lastSave = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Cfg.init(this)
        when (intent?.action) {
            "stop" -> { close(); restoreDns(); stopSelf() }
            "dns" -> if (!monitoring) { close(); startDns() }
            "drop" -> if (fd == null) startDrop()
        }
        return START_STICKY
    }

    private fun close() {
        try { fd?.close() } catch (e: Exception) {}
        fd = null; mode = ""
    }

    // ---------------------------------------------------------------- tryb: odrzucanie ruchu wybranych aplikacji
    private fun startDrop() {
        val blocked = ((if (Cfg.netBlock) Engine.pkgs(Cat.BG) + Engine.pkgs(Cat.OFF) else emptyList()) + Cfg.netDeny).distinct()
        if (blocked.isEmpty()) { stopSelf(); return }
        val b = Builder().setSession("Bastion – blokada sieci w tle")
            .addAddress("10.111.0.1", 32).addAddress("fd00:111::1", 128)
            .addRoute("0.0.0.0", 0).addRoute("::", 0).setBlocking(true)
        var any = false
        for (p in blocked) try { b.addAllowedApplication(p); any = true } catch (e: Exception) {}
        if (!any) { stopSelf(); return }
        val f = try { b.establish() } catch (e: Exception) { null } ?: run { stopSelf(); return }
        fd = f; mode = "drop"
        Thread {
            val buf = ByteArray(32767)
            try { FileInputStream(f.fileDescriptor).use { inp -> while (fd === f) { if (inp.read(buf) < 0) break } } } catch (e: Exception) {}
        }.start()
    }

    // ---------------------------------------------------------------- tryb: monitor DNS
    /** Zapytania idą do tego samego serwera DNS, którego telefon używa bez filtra (operatora / routera) – bez żadnych dodatkowych usług. */
    private fun upstream(): InetAddress {
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) || !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                val d = cm.getLinkProperties(n)?.dnsServers?.firstOrNull { it is java.net.Inet4Address } ?: cm.getLinkProperties(n)?.dnsServers?.firstOrNull()
                if (d != null) return d
            }
        } catch (e: Exception) {
        }
        return InetAddress.getByName("1.1.1.1")
    }

    private fun startDns() {
        // prywatny DNS (DoT) omijałby filtr – na czas monitora wyłączamy, blokowanie robi Bastion (opcjonalnie przez AdGuard)
        val pm = Sys.get(this, "global", "private_dns_mode")
        if (pm == "hostname") {
            Cfg.prev["dns_mode"] = pm
            Sys.put(this, "global", "private_dns_mode", "off"); Cfg.save()
        }
        Dns.load(this)
        Dns.loadLists(this)
        Sig.load(this)
        val b = Builder().setSession("Bastion – strażnik sieci")
            .addAddress("10.111.0.1", 32).addRoute("10.111.0.2", 32).addDnsServer("10.111.0.2")
            .addAddress("fd00:111::1", 128).addRoute("fd00:111::2", 128).addDnsServer("fd00:111::2")
            .setBlocking(true)
        try { b.addDisallowedApplication(packageName) } catch (e: Exception) {}
        val f = try { b.establish() } catch (e: Exception) { null } ?: run { stopSelf(); return }
        fd = f; mode = "dns"
        val out = FileOutputStream(f.fileDescriptor)
        Thread {
            val buf = ByteArray(32767)
            try {
                FileInputStream(f.fileDescriptor).use { inp ->
                    while (fd === f) {
                        val n = inp.read(buf)
                        if (n <= 0) break
                        val pkt = buf.copyOf(n)
                        pool.execute { try { handle(pkt, out) } catch (e: Throwable) {} }
                    }
                }
            } catch (e: Exception) {
            }
            Dns.save(this)
        }.start()
    }

    private fun restoreDns() {
        val old = Cfg.prev.remove("dns_mode") ?: return
        Sys.put(this, "global", "private_dns_mode", old)
        Cfg.save()
    }

    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)

    private fun pkgOf(uid: Int): String {
        if (uid < 0) return "?"
        if (System.currentTimeMillis() - uidsAt > 600_000L) { uids = Engine.uidMap(this); uidsAt = System.currentTimeMillis() }
        return uids[uid % 100000] ?: if (uid % 100000 < 10000) "system" else "uid$uid"
    }

    private fun handle(p: ByteArray, out: FileOutputStream) {
        val ver = (p[0].toInt() shr 4) and 0xf
        val v6 = ver == 6
        val (src, dst, udp) = when (ver) {
            4 -> { if (p[9].toInt() != 17) return; val ihl = (p[0].toInt() and 0xf) * 4; Triple(p.copyOfRange(12, 16), p.copyOfRange(16, 20), ihl) }
            6 -> { if (p[6].toInt() != 17) return; Triple(p.copyOfRange(8, 24), p.copyOfRange(24, 40), 40) }
            else -> return
        }
        val sport = u16(p, udp); val dport = u16(p, udp + 2)
        if (dport != 53) return
        val q = p.copyOfRange(udp + 8, p.size)
        if (q.size < 17) return
        // nazwa domeny z pytania
        val sb = StringBuilder()
        var i = 12
        while (i < q.size) {
            val l = q[i].toInt() and 0xff
            if (l == 0 || l >= 0xc0) break
            if (sb.isNotEmpty()) sb.append('.')
            for (k in 1..l) if (i + k < q.size) sb.append((q[i + k].toInt() and 0xff).toChar())
            i += l + 1
        }
        val qEnd = (i + 5).coerceAtMost(q.size)
        val domain = sb.toString().lowercase()
        val uid = try {
            getSystemService(ConnectivityManager::class.java).getConnectionOwnerUid(OsConstants.IPPROTO_UDP,
                InetSocketAddress(InetAddress.getByAddress(src), sport), InetSocketAddress(InetAddress.getByAddress(dst), 53))
        } catch (e: Exception) { -1 }
        val pkg = pkgOf(uid)
        val allowed = domain in Cfg.allowDomains || Cfg.allowDomains.any { domain.endsWith(".$it") }
        val tracker = !allowed && (Dns.isTracker(domain) || Dns.listed(domain))
        val appBlocked = screenOff && (pkg in Cfg.netDeny || (Cfg.netBlock && Cfg.cat(pkg).let { it == Cat.BG || it == Cat.OFF }))
        val state = when {
            !allowed && Sig.badDomain(domain) -> 5
            domain in Cfg.blockDomains || Cfg.blockDomains.any { domain.endsWith(".$it") } -> 4
            appBlocked -> 2
            tracker && Cfg.blockTrackers -> 1
            tracker -> 3
            else -> 0
        }
        if (domain.isNotEmpty()) Dns.add(Dns.Hit(System.currentTimeMillis(), pkg, domain, state))
        val answer: ByteArray = if (state == 1 || state == 2 || state == 4 || state == 5) {
            // NXDOMAIN – domena „nie istnieje”
            val r = q.copyOf(qEnd)
            r[2] = (r[2].toInt() or 0x80).toByte()
            r[3] = 0x83.toByte()
            for (k in 6 until 12) r[k] = 0
            r
        } else {
            DatagramSocket().use { s ->
                protect(s)
                s.soTimeout = 5000
                s.send(DatagramPacket(q, q.size, upstream(), 53))
                val rb = ByteArray(4096)
                val rp = DatagramPacket(rb, rb.size)
                s.receive(rp)
                rb.copyOf(rp.length)
            }
        }
        val pkt = if (v6) ip6(dst, src, 53, sport, answer) else ip4(dst, src, 53, sport, answer)
        synchronized(out) { out.write(pkt) }
        if (System.currentTimeMillis() - lastSave > 120_000L) { lastSave = System.currentTimeMillis(); Dns.save(this) }
    }

    private fun ip4(src: ByteArray, dst: ByteArray, sp: Int, dp: Int, data: ByteArray): ByteArray {
        val total = 28 + data.size
        val b = ByteArray(total)
        b[0] = 0x45; b[2] = (total shr 8).toByte(); b[3] = total.toByte(); b[6] = 0x40; b[8] = 64; b[9] = 17
        System.arraycopy(src, 0, b, 12, 4); System.arraycopy(dst, 0, b, 16, 4)
        var sum = 0
        for (k in 0 until 20 step 2) sum += u16(b, k)
        while (sum shr 16 != 0) sum = (sum and 0xffff) + (sum shr 16)
        val cs = sum.inv() and 0xffff
        b[10] = (cs shr 8).toByte(); b[11] = cs.toByte()
        b[20] = (sp shr 8).toByte(); b[21] = sp.toByte(); b[22] = (dp shr 8).toByte(); b[23] = dp.toByte()
        val ul = 8 + data.size
        b[24] = (ul shr 8).toByte(); b[25] = ul.toByte()
        System.arraycopy(data, 0, b, 28, data.size)
        return b
    }

    private fun ip6(src: ByteArray, dst: ByteArray, sp: Int, dp: Int, data: ByteArray): ByteArray {
        val ul = 8 + data.size
        val b = ByteArray(40 + ul)
        b[0] = 0x60; b[4] = (ul shr 8).toByte(); b[5] = ul.toByte(); b[6] = 17; b[7] = 64
        System.arraycopy(src, 0, b, 8, 16); System.arraycopy(dst, 0, b, 24, 16)
        b[40] = (sp shr 8).toByte(); b[41] = sp.toByte(); b[42] = (dp shr 8).toByte(); b[43] = dp.toByte()
        b[44] = (ul shr 8).toByte(); b[45] = ul.toByte()
        System.arraycopy(data, 0, b, 48, data.size)
        // suma kontrolna UDP (w IPv6 obowiązkowa): pseudo-nagłówek + UDP
        var sum = 0L
        for (k in 8 until 40 step 2) sum += u16(b, k)
        sum += ul + 17
        var k = 40
        while (k < b.size) { sum += if (k + 1 < b.size) u16(b, k) else ((b[k].toInt() and 0xff) shl 8); k += 2 }
        while (sum shr 16 != 0L) sum = (sum and 0xffff) + (sum shr 16)
        var cs = (sum.inv() and 0xffff).toInt()
        if (cs == 0) cs = 0xffff
        b[46] = (cs shr 8).toByte(); b[47] = cs.toByte()
        return b
    }

    override fun onRevoke() {
        close(); restoreDns()
        super.onRevoke()
    }

    override fun onDestroy() {
        Dns.save(this)
        super.onDestroy()
    }
}
