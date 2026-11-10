package pl.bateria

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.google.common.base.Charsets
import com.google.common.hash.BloomFilter
import com.google.common.hash.Funnels
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Bazy sygnatur trzymane w telefonie (format Hypatia / LoveLaceAV: filtry Blooma z odciskami MD5, SHA-1, SHA-256
 * znanego złośliwego oprogramowania oraz złośliwych domen). Internet służy tylko do pobrania baz – pliki nigdzie nie są wysyłane.
 */
object Sig {
    private val NAMES = listOf("md5", "sha1", "sha256", "domains")
    private val MIRRORS = listOf(
        "https://lav.axpos.org/db/hypatia-%s-bloom.bin",
        "https://raw.githubusercontent.com/MaintainTeam/HypatiaDatabases/main/hypatia-%s-bloom.bin",
        "https://codeberg.org/MaintainTeam/HypatiaDatabases/raw/branch/main/hypatia-%s-bloom.bin"
    )

    @Volatile private var md5: BloomFilter<String>? = null
    @Volatile private var sha1: BloomFilter<String>? = null
    @Volatile private var sha256: BloomFilter<String>? = null
    @Volatile private var domains: BloomFilter<String>? = null
    @Volatile var count = 0L

    private fun file(c: Context, n: String) = File(c.filesDir, "sig-$n.bin")

    fun ready(c: Context) = file(c, "md5").length() > 1000 || file(c, "sha256").length() > 1000

    private fun read(f: File): BloomFilter<String>? = try {
        if (f.length() < 1000) null else FileInputStream(f).buffered().use { BloomFilter.readFrom(it, Funnels.stringFunnel(Charsets.US_ASCII)) }
    } catch (e: Throwable) {
        null
    }

    @Synchronized
    fun load(c: Context) {
        if (md5 != null || sha256 != null) return
        md5 = read(file(c, "md5")); sha1 = read(file(c, "sha1")); sha256 = read(file(c, "sha256")); domains = read(file(c, "domains"))
        count = listOfNotNull(md5, sha1, sha256).sumOf { try { it.approximateElementCount() } catch (e: Throwable) { 0L } }
    }

    /** Pobiera bazy (z pierwszego działającego serwera). Zwraca przybliżoną liczbę sygnatur. */
    fun update(c: Context, progress: (String) -> Unit): Long {
        var got = 0
        for (n in NAMES) {
            for (m in MIRRORS) {
                try {
                    progress("Pobieram bazę $n…")
                    val con = URL(m.format(n)).openConnection() as HttpURLConnection
                    con.connectTimeout = 20_000; con.readTimeout = 120_000
                    con.setRequestProperty("User-Agent", "Bastion")
                    if (con.responseCode != 200) continue
                    val tmp = File(c.filesDir, "sig-$n.part")
                    con.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                    if (tmp.length() < 1000 || read(tmp) == null) { tmp.delete(); continue }
                    tmp.renameTo(file(c, n))
                    got++
                    break
                } catch (e: Exception) {
                }
            }
        }
        if (got == 0) throw IllegalStateException("Nie udało się pobrać baz sygnatur – sprawdź internet")
        md5 = null; sha256 = null
        load(c)
        Cfg.sigAt = System.currentTimeMillis(); Cfg.save()
        return count
    }

    /** Czy którykolwiek odcisk pliku jest w bazie złośliwego oprogramowania. */
    fun match(h: Av.Hashes): Boolean =
        (md5?.mightContain(h.md5) == true) || (sha1?.mightContain(h.sha1) == true) || (sha256?.mightContain(h.sha256) == true)

    /** Czy domena (lub nadrzędna) jest w bazie złośliwych domen. */
    fun badDomain(d: String): Boolean {
        val b = domains ?: return false
        var x = d.lowercase()
        while (true) {
            if (b.mightContain(x)) return true
            val i = x.indexOf('.')
            if (i < 0 || x.indexOf('.', i + 1) < 0) return false
            x = x.substring(i + 1)
        }
    }
}

/** Antywirus działający w całości w telefonie: sygnatury + własna analiza plików APK. */
object Av {
    class Hashes(val md5: String, val sha1: String, val sha256: String)

    class Result(val path: String, val name: String, val pkg: String?, val sha: String, var known: Boolean = false, var heur: List<String> = emptyList()) {
        /** 0 czysty, 1 podejrzany, 2 zagrożenie. */
        val level: Int get() = when { known -> 2; heur.isNotEmpty() -> 1; else -> 0 }
    }

    val SUSPECT_EXT = setOf("apk", "apks", "xapk", "exe", "msi", "bat", "cmd", "scr", "js", "vbs", "jar", "dex", "so", "sh", "ps1",
        "zip", "rar", "7z", "iso", "pdf", "doc", "docx", "docm", "xls", "xlsx", "xlsm", "ppt", "pptm", "rtf", "html", "htm", "lnk")

    /** MD5, SHA-1 i SHA-256 w jednym przejściu po pliku. */
    fun hashes(f: File): Hashes {
        val a = MessageDigest.getInstance("MD5"); val b = MessageDigest.getInstance("SHA-1"); val d = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { inp ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = inp.read(buf); if (n <= 0) break; a.update(buf, 0, n); b.update(buf, 0, n); d.update(buf, 0, n) }
        }
        fun hex(x: ByteArray) = x.joinToString("") { "%02x".format(it) }
        return Hashes(hex(a.digest()), hex(b.digest()), hex(d.digest()))
    }

    // ------------------------------------------------------------ analiza APK
    private const val P = "android.permission."
    private val PACKERS = listOf("libjiagu", "libsecexe", "libsecmain", "libDexHelper", "libprotectClass", "libbaiduprotect", "libtup", "libshell", "libnqshield", "libapkprotect", "libbangcle")

    fun heuristics(c: Context, apkPath: String, installedPkg: String?): List<String> {
        val pm = c.packageManager
        val out = ArrayList<String>()
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS
        val pi = try {
            if (installedPkg != null) pm.getPackageInfo(installedPkg, flags) else pm.getPackageArchiveInfo(apkPath, flags)
        } catch (e: Exception) { null } ?: return out
        val req = pi.requestedPermissions?.toSet() ?: emptySet()
        val ai = pi.applicationInfo
        // aplikacje systemowe i ze Sklepu Play / Galaxy Store mają prawo do szerokich uprawnień – oceniamy je tylko po sygnaturach
        if (installedPkg != null) {
            val sys = ai != null && ai.flags and ApplicationInfo.FLAG_SYSTEM != 0
            val src = try { pm.getInstallSourceInfo(installedPkg).installingPackageName } catch (e: Exception) { null }
            if (sys || src == "com.android.vending" || src == "com.sec.android.app.samsungapps") return out
        }
        fun has(vararg p: String) = p.all { (P + it) in req }
        if (has("RECEIVE_SMS", "INTERNET") && (has("READ_SMS") || has("SEND_SMS"))) out += "czyta lub wysyła SMS-y i ma internet (typowe dla trojanów bankowych)"
        if (pi.services?.any { it.permission == "android.permission.BIND_ACCESSIBILITY_SERVICE" } == true && (P + "SYSTEM_ALERT_WINDOW") in req)
            out += "ułatwienia dostępu + nakładki na ekran (może przejąć sterowanie i podstawiać fałszywe okna)"
        if (pi.receivers?.any { it.permission == "android.permission.BIND_DEVICE_ADMIN" } == true) out += "chce być administratorem urządzenia"
        if (has("REQUEST_INSTALL_PACKAGES") && has("RECEIVE_BOOT_COMPLETED") && has("QUERY_ALL_PACKAGES")) out += "może doinstalowywać inne aplikacje po starcie telefonu"
        if (has("READ_CALL_LOG") && has("RECORD_AUDIO") && has("ACCESS_FINE_LOCATION") && has("READ_CONTACTS")) out += "komplet uprawnień szpiegujących (rozmowy, mikrofon, lokalizacja, kontakty)"
        if (ai != null && ai.targetSdkVersion in 1..22) out += "celuje w bardzo stary Android (omija pytania o zgody)"
        if (installedPkg != null && pm.getLaunchIntentForPackage(installedPkg) == null && ai != null && ai.flags and ApplicationInfo.FLAG_SYSTEM == 0 && (P + "INTERNET") in req)
            out += "nie ma ikony – ukrywa się przed użytkownikiem"
        try {
            ZipFile(apkPath).use { z ->
                val names = z.entries().asSequence().map { it.name }.toList()
                PACKERS.firstOrNull { p -> names.any { it.contains(p) } }?.let { out += "zaszyfrowany kod (paker $it) – ukrywa, co naprawdę robi" }
                if (names.any { it.startsWith("assets/") && (it.endsWith(".dex") || it.endsWith(".apk") || it.endsWith(".jar")) }) out += "ukryty dodatkowy kod w zasobach (ładowany po uruchomieniu)"
            }
        } catch (e: Exception) {
        }
        return out
    }

    // ------------------------------------------------------------ cele i skan
    fun appTargets(c: Context, withSystem: Boolean): List<Result> {
        val pm = c.packageManager
        val out = ArrayList<Result>()
        for (ai in pm.getInstalledApplications(0)) {
            val sys = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0 && ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
            if (sys && !withSystem) continue
            if (ai.packageName == c.packageName) continue
            out += Result(ai.sourceDir, pm.getApplicationLabel(ai).toString(), ai.packageName, "")
        }
        return out
    }

    /** Pliki do sprawdzenia: wszystkie (pełny skan) albo tylko „ryzykowne” typy z typowych miejsc. */
    fun fileTargets(all: Boolean): List<Result> = Mem.files.filter { f ->
        f.size in 1..(500L * 1024 * 1024) && (all || (f.ext in SUSPECT_EXT && (f.path.contains("/Download/") || f.path.contains("/Android/media/") ||
            f.path.contains("/Telegram/") || f.path.contains("/Bluetooth/") || f.path.contains("/Documents/"))))
    }.sortedByDescending { it.mod }.map { Result(it.path, it.name, null, "") }

    val results = ArrayList<Result>()
    @Volatile var scanned = 0

    private fun check(c: Context, path: String, name: String, pkg: String?): Result? {
        val f = File(path)
        if (!f.exists()) return null
        val h = hashes(f)
        val r = Result(path, name, pkg, h.sha256)
        r.known = Sig.match(h)
        if (path.endsWith(".apk")) r.heur = heuristics(c, path, pkg)
        if (!r.known && (r.sha in Cfg.avTrust || (pkg != null && pkg in Cfg.avTrust))) r.heur = emptyList()
        return r
    }

    /** Skanuje listę w telefonie – bez limitów i bez wysyłania czegokolwiek. */
    fun scan(c: Context, targets: List<Result>, progress: (Int, Int, String) -> Unit, stop: () -> Boolean) {
        Sig.load(c)
        for ((i, t) in targets.withIndex()) {
            if (stop()) break
            if (i % 5 == 0) progress(i, targets.size, t.name)
            try {
                val r = check(c, t.path, t.name, t.pkg) ?: continue
                scanned++
                synchronized(results) { results.removeAll { it.path == r.path }; if (r.level > 0) results.add(r) }
            } catch (e: Exception) {
            }
        }
        progress(targets.size, targets.size, "")
        Cfg.avAt = System.currentTimeMillis(); Cfg.save()
    }

    /** Sprawdzenie jednego pliku (nowo pobrany plik, nowa aplikacja). */
    fun quick(c: Context, path: String, pkg: String?): Result? = try {
        Sig.load(c)
        val r = check(c, path, File(path).name, pkg)
        if (r != null && r.level > 0) synchronized(results) { results.removeAll { it.path == path }; results.add(r) }
        r
    } catch (e: Exception) {
        null
    }
}
