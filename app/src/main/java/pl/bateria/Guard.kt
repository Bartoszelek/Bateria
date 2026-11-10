package pl.bateria

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ImageReader
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.os.Handler
import android.os.HandlerThread
import android.provider.Telephony
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.IDN
import java.net.InetAddress
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Fałszywe linki (phishing): ocena adresu w telefonie – listy CERT Polska, baza złośliwych domen, własne reguły. */
object Links {
    class Verdict(val level: Int, val reasons: List<String>, val host: String) // 0 OK, 1 podejrzany, 2 niebezpieczny

    private val BRANDS = mapOf(
        "inpost" to listOf("inpost.pl"), "dpd" to listOf("dpd.com.pl", "dpd.com"), "dhl" to listOf("dhl.com", "dhl.pl", "dhlparcel.pl"),
        "poczta" to listOf("poczta-polska.pl"), "pocztex" to listOf("pocztex.pl"), "allegro" to listOf("allegro.pl", "allegrolokalnie.pl"),
        "olx" to listOf("olx.pl"), "vinted" to listOf("vinted.pl"), "pkobp" to listOf("pkobp.pl", "ipko.pl"), "ipko" to listOf("ipko.pl", "pkobp.pl"),
        "mbank" to listOf("mbank.pl"), "santander" to listOf("santander.pl"), "pekao" to listOf("pekao.com.pl", "pekao24.pl"), "ing" to listOf("ing.pl"),
        "millennium" to listOf("bankmillennium.pl"), "alior" to listOf("aliorbank.pl"), "blik" to listOf("blik.com"), "przelewy24" to listOf("przelewy24.pl"),
        "payu" to listOf("payu.pl", "payu.com"), "pge" to listOf("pge.pl", "gkpge.pl"), "tauron" to listOf("tauron.pl"), "orange" to listOf("orange.pl"),
        "play" to listOf("play.pl"), "tmobile" to listOf("t-mobile.pl"), "plus" to listOf("plus.pl"), "gov" to listOf("gov.pl"), "podatki" to listOf("podatki.gov.pl"),
        "zus" to listOf("zus.pl"), "mobywatel" to listOf("gov.pl"), "netflix" to listOf("netflix.com"), "facebook" to listOf("facebook.com", "fb.com"),
        "paypal" to listOf("paypal.com"), "apple" to listOf("apple.com", "icloud.com"), "google" to listOf("google.com", "google.pl"), "microsoft" to listOf("microsoft.com", "live.com"),
        "revolut" to listOf("revolut.com"), "booking" to listOf("booking.com"), "lidl" to listOf("lidl.pl"), "biedronka" to listOf("biedronka.pl")
    )
    private val SHORTENERS = setOf("bit.ly", "tinyurl.com", "t.co", "cutt.ly", "is.gd", "rb.gy", "tiny.pl", "shorturl.at", "ow.ly", "goo.su", "t.ly", "lnk.to", "urlz.fr")
    private val BAD_TLD = setOf("top", "xyz", "click", "icu", "cfd", "sbs", "buzz", "shop", "live", "rest", "quest", "cyou", "monster", "link", "work", "gq", "tk", "ml", "cf", "ga")
    private val URL_RX = Regex("""(?i)\b((?:https?://|www\.)[^\s<>"']+|[a-z0-9][a-z0-9-]{1,40}(?:\.[a-z0-9-]{1,40}){1,4}/[^\s<>"']*)""")

    fun extract(text: String): List<String> = URL_RX.findAll(text).map { it.value.trimEnd('.', ',', ')', '!', '?') }.distinct().toList()

    fun check(c: Context, url: String): Verdict {
        Dns.loadLists(c); Sig.load(c)
        val reasons = ArrayList<String>()
        var level = 0
        fun hit(l: Int, r: String) { reasons += r; if (l > level) level = l }
        val u = if (url.contains("://")) url else "http://$url"
        var host = try { URL(u).host.lowercase() } catch (e: Exception) { "" }
        if (host.isEmpty()) return Verdict(1, listOf("nie da się odczytać adresu"), url)
        if (host.startsWith("www.")) host = host.substring(4)
        if (Dns.listed(host)) hit(2, "domena jest na liście ostrzeżeń (CERT Polska / listy blokowania)")
        if (Sig.badDomain(host)) hit(2, "domena jest w bazie złośliwych adresów")
        if (host.contains("xn--") || host != (try { IDN.toASCII(host) } catch (e: Exception) { host })) hit(2, "znaki udające inne litery (np. cyrylica zamiast łacińskich)")
        if (Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(host)) hit(2, "adres to same cyfry (IP) zamiast nazwy")
        if (u.startsWith("http://")) hit(1, "brak szyfrowania (http zamiast https)")
        if (host in SHORTENERS) hit(1, "skrócony link – nie widać, dokąd prowadzi")
        if (host.substringAfterLast('.') in BAD_TLD) hit(1, "końcówka domeny często używana przez oszustów (.${host.substringAfterLast('.')})")
        if (host.count { it == '.' } >= 4 || host.count { it == '-' } >= 3) hit(1, "nietypowo złożona nazwa domeny")
        val flat = host.replace("-", "").replace(".", "")
        for ((brand, real) in BRANDS) {
            if (brand.length < 4 && !host.split('.', '-').contains(brand)) continue
            if (flat.contains(brand) && real.none { host == it || host.endsWith(".$it") }) {
                hit(2, "nazwa udaje „$brand”, ale to nie jest prawdziwa strona (${real.first()})"); break
            }
        }
        if (Regex("""(?i)(login|logowanie|verify|weryfik|platnosc|doplat|dopłat|secure|confirm|potwierdz|odbior|paczk)""").containsMatchIn(u) && level >= 1 && level < 2)
            hit(2, "podejrzany adres z prośbą o logowanie, płatność lub potwierdzenie")
        return Verdict(level, reasons, host)
    }
}

/** SMS-y: każdy link w nowej wiadomości jest sprawdzany w telefonie. */
class SmsGuard : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        Cfg.init(c)
        if (!Cfg.smsGuard) return
        val msgs = try { Telephony.Sms.Intents.getMessagesFromIntent(i) } catch (e: Exception) { null } ?: return
        val from = msgs.firstOrNull()?.displayOriginatingAddress ?: "?"
        val body = msgs.joinToString("") { it.displayMessageBody ?: "" }
        val pending = goAsync()
        Thread {
            try {
                for (url in Links.extract(body)) {
                    val v = Links.check(c, url)
                    if (v.level >= 1) {
                        Alerts.plain(c, ("sms" + url).hashCode(), if (v.level == 2) "⛔ Niebezpieczny link w SMS od $from" else "⚠ Podejrzany link w SMS od $from",
                            "${v.host}: ${v.reasons.joinToString("; ")}. Nie klikaj i nie podawaj danych.")
                        break
                    }
                }
            } catch (e: Exception) {
            }
            pending.finish()
        }.start()
    }
}

/** Hasła: sprawdzane w telefonie – siła i obecność na liście najczęściej łamanych haseł. Nic nie jest wysyłane. */
object Pass {
    private fun file(c: Context) = File(c.filesDir, "common-pass.txt")
    private var set: HashSet<String>? = null

    fun ready(c: Context) = file(c).length() > 10_000

    /** Jednorazowe pobranie listy 100 tys. najpopularniejszych haseł (zwykły plik tekstowy). */
    fun download(c: Context): Int {
        for (u in listOf(
            "https://raw.githubusercontent.com/danielmiessler/SecLists/master/Passwords/Common-Credentials/100k-most-used-passwords-NCSC.txt",
            "https://raw.githubusercontent.com/danielmiessler/SecLists/master/Passwords/Common-Credentials/10k-most-common.txt"
        )) try {
            val con = URL(u).openConnection() as HttpURLConnection
            con.connectTimeout = 20_000; con.readTimeout = 60_000
            if (con.responseCode != 200) continue
            con.inputStream.use { inp -> file(c).outputStream().use { inp.copyTo(it) } }
            set = null
            return file(c).readLines().size
        } catch (e: Exception) {
        }
        throw IllegalStateException("Nie udało się pobrać listy haseł")
    }

    class Verdict(val score: Int, val notes: List<String>)

    fun check(c: Context, p: String): Verdict {
        val notes = ArrayList<String>()
        if (set == null && ready(c)) set = try { file(c).readLines().map { it.trim().lowercase() }.toHashSet() } catch (e: Exception) { null }
        var score = 0
        val common = set?.contains(p.lowercase()) == true
        if (common) notes += "⛔ To hasło jest na liście najczęściej łamanych – atakujący sprawdzają je w pierwszej kolejności"
        val classes = listOf(p.any { it.isLowerCase() }, p.any { it.isUpperCase() }, p.any { it.isDigit() }, p.any { !it.isLetterOrDigit() }).count { it }
        val pool = (if (p.any { it.isLowerCase() }) 26 else 0) + (if (p.any { it.isUpperCase() }) 26 else 0) + (if (p.any { it.isDigit() }) 10 else 0) + (if (p.any { !it.isLetterOrDigit() }) 33 else 0)
        val bits = if (pool > 0) p.length * (Math.log(pool.toDouble()) / Math.log(2.0)) else 0.0
        if (p.length < 10) notes += "za krótkie – minimum 12 znaków" else if (p.length < 12) notes += "lepiej 12+ znaków"
        if (classes < 3) notes += "użyj małych i wielkich liter, cyfr oraz znaku specjalnego"
        if (Regex("""(.)\1{2,}""").containsMatchIn(p)) notes += "powtarzające się znaki"
        if (Regex("""(?i)(123|abc|qwe|asd|zaq|haslo|password|admin|19\d\d|20\d\d)""").containsMatchIn(p)) notes += "zawiera przewidywalny ciąg (123, qwe, rok, „haslo”)"
        score = when { common -> 0; bits < 40 -> 20; bits < 60 -> 45; bits < 80 -> 70; else -> 95 } - (notes.size - (if (common) 1 else 0)) * 5
        if (notes.isEmpty()) notes += "Mocne hasło. Pamiętaj: inne do każdego serwisu."
        return Verdict(score.coerceIn(0, 100), notes)
    }

    @Suppress("unused")
    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02X".format(it) }
}

/** Ochrona przed kradzieżą: po kilku błędnych próbach odblokowania – syrena, zapis w dzienniku, próba zdjęcia przednim aparatem. */
class TheftAdmin : DeviceAdminReceiver() {
    override fun onPasswordFailed(c: Context, intent: Intent) {
        Cfg.init(c)
        if (!Cfg.antiTheft) return
        val dpm = c.getSystemService(DevicePolicyManager::class.java) ?: return
        val n = try { dpm.currentFailedPasswordAttempts } catch (e: Exception) { 0 }
        Theft.log(c, "Błędna próba odblokowania nr $n")
        if (n >= Cfg.theftTries) {
            Theft.photo(c)
            if (Cfg.theftSiren) Theft.siren(c)
        }
    }

    override fun onPasswordSucceeded(c: Context, intent: Intent) {
        Theft.stopSiren()
        Cfg.init(c)
        val l = Theft.entries(c)
        val recent = l.count { System.currentTimeMillis() - it.optLong("t") < 12 * 3600_000L && !it.optBoolean("seen") }
        if (recent >= Cfg.theftTries) Alerts.plain(c, 4711, "Ktoś próbował odblokować telefon", "$recent błędnych prób. Szczegóły i zdjęcia: Bastion → Prywatność → Ochrona przed kradzieżą.")
    }
}

object Theft {
    fun admin(c: Context) = ComponentName(c, TheftAdmin::class.java)
    fun active(c: Context) = c.getSystemService(DevicePolicyManager::class.java)?.isAdminActive(admin(c)) == true

    private fun file(c: Context) = File(c.filesDir, "theft.json")
    fun dir(c: Context) = File(c.filesDir, "intruz").apply { mkdirs() }

    fun entries(c: Context): List<JSONObject> = try { JSONArray(file(c).readText()).let { a -> (0 until a.length()).map { a.getJSONObject(it) } } } catch (e: Exception) { emptyList() }

    @Synchronized
    fun log(c: Context, text: String, photo: String = "") {
        val l = entries(c).takeLast(60).toMutableList()
        l.add(JSONObject().put("t", System.currentTimeMillis()).put("s", text).put("p", photo))
        file(c).writeText(JSONArray(l).toString())
    }

    fun markSeen(c: Context) {
        val l = entries(c); l.forEach { it.put("seen", true) }
        file(c).writeText(JSONArray(l).toString())
    }

    private var ring: android.media.Ringtone? = null

    fun siren(c: Context) {
        try {
            val am = c.getSystemService(AudioManager::class.java)
            am?.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            stopSiren()
            ring = RingtoneManager.getRingtone(c, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                isLooping = true
                play()
            }
            Handler(c.mainLooper).postDelayed({ stopSiren() }, 60_000)
        } catch (e: Exception) {
        }
    }

    fun stopSiren() { try { ring?.stop() } catch (e: Exception) {}; ring = null }

    /** Zdjęcie przednim aparatem (działa, gdy system pozwala aplikacji użyć aparatu w tle). */
    fun photo(c: Context) {
        try {
            val cm = c.getSystemService(CameraManager::class.java) ?: return
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT } ?: return
            val ht = HandlerThread("intruz").apply { start() }
            val h = Handler(ht.looper)
            val reader = ImageReader.newInstance(1280, 960, ImageFormat.JPEG, 1)
            val out = File(dir(c), "intruz_${System.currentTimeMillis()}.jpg")
            reader.setOnImageAvailableListener({ r ->
                try {
                    r.acquireLatestImage()?.use { img ->
                        val buf = img.planes[0].buffer
                        val b = ByteArray(buf.remaining()); buf.get(b)
                        out.writeBytes(b)
                        log(c, "Zdjęcie przy błędnym odblokowaniu", out.path)
                    }
                } catch (e: Exception) {
                }
            }, h)
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    try {
                        @Suppress("DEPRECATION")
                        cam.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                try {
                                    val rq = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply { addTarget(reader.surface) }
                                    h.postDelayed({ try { s.capture(rq.build(), null, h) } catch (e: Exception) {} }, 700)
                                    h.postDelayed({ try { cam.close(); reader.close(); ht.quitSafely() } catch (e: Exception) {} }, 4000)
                                } catch (e: Exception) { cam.close() }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) { cam.close() }
                        }, h)
                    } catch (e: Exception) { cam.close() }
                }
                override fun onDisconnected(cam: CameraDevice) { cam.close() }
                override fun onError(cam: CameraDevice, error: Int) { cam.close() }
            }, h)
        } catch (e: SecurityException) {
        } catch (e: Exception) {
        }
    }
}

/** Sieć Wi-Fi: czy jest bezpieczna i jakie urządzenia są w tej samej sieci. */
object WifiGuard {
    /** Opis zabezpieczeń aktualnej sieci: (poziom 0–2, opis). */
    fun current(c: Context): Pair<Int, String>? {
        val cm = c.getSystemService(ConnectivityManager::class.java) ?: return null
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val wi = caps.transportInfo as? WifiInfo
        val type = if (android.os.Build.VERSION.SDK_INT >= 31) wi?.currentSecurityType ?: -1 else -1
        return when (type) {
            WifiInfo.SECURITY_TYPE_OPEN -> Pair(2, "Sieć otwarta, bez hasła – każdy w pobliżu może podsłuchiwać ruch. Nie loguj się do banku ani poczty.")
            WifiInfo.SECURITY_TYPE_OWE -> Pair(1, "Sieć otwarta z szyfrowaniem (OWE) – bez hasła, ale ruch jest szyfrowany.")
            WifiInfo.SECURITY_TYPE_WEP -> Pair(2, "Stare zabezpieczenie WEP – łamane w kilka minut. Traktuj jak sieć otwartą.")
            WifiInfo.SECURITY_TYPE_PSK -> Pair(0, "WPA/WPA2 z hasłem – w porządku (o ile hasło nie jest publiczne, jak w kawiarni).")
            WifiInfo.SECURITY_TYPE_SAE -> Pair(0, "WPA3 – najlepsze zabezpieczenie.")
            WifiInfo.SECURITY_TYPE_EAP, WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE -> Pair(0, "Sieć firmowa (logowanie indywidualne) – w porządku.")
            else -> Pair(0, "Zabezpieczenie sieci: nieznane (typ $type).")
        }
    }

    /** Urządzenia odpowiadające w tej samej sieci (przeszukanie /24). */
    fun scanLan(c: Context, progress: (String) -> Unit): List<String> {
        val cm = c.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return emptyList()
        val me = lp.linkAddresses.map { it.address }.firstOrNull { it is java.net.Inet4Address } ?: return emptyList()
        val base = me.hostAddress!!.substringBeforeLast('.')
        val gw = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is java.net.Inet4Address }?.gateway?.hostAddress
        val found = java.util.Collections.synchronizedList(ArrayList<String>())
        val pool = Executors.newFixedThreadPool(48)
        for (i in 1..254) pool.execute {
            val ip = "$base.$i"
            try {
                val a = InetAddress.getByName(ip)
                if (a.isReachable(600)) {
                    val name = try { a.canonicalHostName.takeIf { it != ip } } catch (e: Exception) { null }
                    found.add(ip + (if (ip == me.hostAddress) "  (ten telefon)" else if (ip == gw) "  (router)" else "") + (name?.let { "  $it" } ?: ""))
                }
            } catch (e: Exception) {
            }
        }
        pool.shutdown()
        progress("Szukam urządzeń w sieci $base.0/24…")
        pool.awaitTermination(25, TimeUnit.SECONDS)
        return found.sortedBy { it.substringBefore(' ').substringAfterLast('.').toIntOrNull() ?: 0 }
    }
}
