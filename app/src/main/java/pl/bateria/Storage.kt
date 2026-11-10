package pl.bateria

import android.app.ActivityManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.os.BatteryManager
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.os.storage.StorageManager
import androidx.exifinterface.media.ExifInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Plik na liście wyników. */
class FileItem(val path: String, val size: Long, val mod: Long) {
    val name get() = path.substringAfterLast('/')
    val ext get() = name.substringAfterLast('.', "").lowercase()
}

/** Pamięć telefonu: analiza, czyszczenie, kosz, optymalizacja zdjęć. */
object Mem {
    val root: File get() = Environment.getExternalStorageDirectory()
    private val trashDir get() = File(root, ".BateriaKosz")

    fun allFiles() = Environment.isExternalStorageManager()

    val IMG = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "dng")
    val VID = setOf("mp4", "mkv", "mov", "3gp", "webm", "avi", "m4v", "ts")
    val AUD = setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "amr", "m4b")
    val DOCS = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "txt", "csv", "epub", "zip", "rar", "7z")

    // ------------------------------------------------------------ skan
    @Volatile var files: List<FileItem> = emptyList()
    @Volatile var emptyDirs: List<String> = emptyList()
    @Volatile var scannedAt = 0L

    /** Przegląda całą pamięć (bez Android/data i obb – tam zagląda Shizuku). */
    fun scan(progress: (String) -> Unit) {
        val out = ArrayList<FileItem>(20000)
        val empty = ArrayList<String>()
        val stack = ArrayDeque<File>()
        stack.add(root)
        var n = 0
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            val p = d.path
            if (p.endsWith("/Android/data") || p.endsWith("/Android/obb") || p == trashDir.path) continue
            val list = d.listFiles() ?: continue
            if (list.isEmpty() && d != root && !p.endsWith("/Android")) empty.add(p)
            for (f in list) {
                if (f.isDirectory) stack.add(f)
                else {
                    out.add(FileItem(f.path, f.length(), f.lastModified()))
                    if (++n % 2000 == 0) progress("Przeglądam pliki: $n")
                }
            }
        }
        files = out
        emptyDirs = empty
        scannedAt = System.currentTimeMillis()
    }

    // ------------------------------------------------------------ przegląd
    class Overview(val total: Long, val free: Long, val cats: List<Pair<String, Long>>)

    fun overview(c: Context): Overview {
        val st = StatFs(Environment.getDataDirectory().path)
        val total = st.totalBytes
        val free = st.availableBytes
        var img = 0L; var vid = 0L; var aud = 0L; var doc = 0L; var apk = 0L; var other = 0L
        for (f in files) when (f.ext) {
            in IMG -> img += f.size; in VID -> vid += f.size; in AUD -> aud += f.size; in DOCS -> doc += f.size; "apk" -> apk += f.size
            else -> other += f.size
        }
        val apps = appsSize(c)
        val used = total - free
        val sys = (used - img - vid - aud - doc - apk - other - apps).coerceAtLeast(0)
        return Overview(total, free, listOf("Zdjęcia" to img, "Filmy" to vid, "Muzyka i nagrania" to aud, "Dokumenty i archiwa" to doc,
            "Aplikacje" to apps, "Pliki instalacyjne APK" to apk, "Inne pliki" to other, "System i reszta" to sys))
    }

    // ------------------------------------------------------------ aplikacje: rozmiary i pamięć podręczna
    class AppSize(val pkg: String, val label: String, val app: Long, val data: Long, val cache: Long, val system: Boolean, var lastUsed: Long) {
        val total get() = app + data
    }

    fun appSizes(c: Context): List<AppSize> {
        val ssm = c.getSystemService(StorageStatsManager::class.java) ?: return emptyList()
        val pm = c.packageManager
        val use = Apps.usage(c, 60)
        val out = ArrayList<AppSize>()
        for (ai in pm.getInstalledApplications(0)) {
            try {
                val s = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, ai.packageName, Process.myUserHandle())
                val sys = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0 && ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
                out.add(AppSize(ai.packageName, pm.getApplicationLabel(ai).toString(), s.appBytes, s.dataBytes, s.cacheBytes, sys, use[ai.packageName] ?: 0L))
            } catch (e: Exception) {
            }
        }
        return out
    }

    fun appsSize(c: Context): Long = try { appSizes(c).sumOf { it.total } } catch (e: Exception) { 0L }

    /** Czyści pamięć podręczną wszystkich aplikacji (Shizuku). */
    fun trimAllCaches(): Boolean {
        if (!Sh.granted()) return false
        Sh.run("pm trim-caches 999G")
        return true
    }

    fun clearCache(pkgs: List<String>) = Sh.runAll(pkgs.map { "pm clear --cache-only $it" })

    fun hibernate(pkgs: List<String>) = Sh.runAll(pkgs.map { "am force-stop $it" })

    fun uninstall(pkgs: List<String>): String = Sh.runAll(pkgs.map { "pm uninstall --user 0 $it" })

    // ------------------------------------------------------------ śmieci
    class Junk(val label: String, val hint: String, val items: List<FileItem>, val dirs: List<String> = emptyList(), val shellDirs: List<Pair<String, Long>> = emptyList()) {
        val size get() = items.sumOf { it.size } + shellDirs.sumOf { it.second }
        val count get() = items.size + dirs.size + shellDirs.size
    }

    fun junk(c: Context): List<Junk> {
        val pm = c.packageManager
        val thumbs = files.filter { it.path.contains("/.thumbnails/") || it.path.contains("/.Thumbnails/") }
        val tmp = files.filter { it.ext in setOf("tmp", "temp", "log", "bak", "dmp") || it.name.startsWith(".trashed-") || it.name == ".nomedia.tmp" }
        val apks = files.filter { it.ext == "apk" && !it.path.contains("/Android/") }.filter { f ->
            try {
                val info = pm.getPackageArchiveInfo(f.path, 0) ?: return@filter true
                val inst = pm.getPackageInfo(info.packageName, 0)
                inst.longVersionCode >= info.longVersionCode
            } catch (e: Exception) {
                false
            }
        }
        // pozostałości po odinstalowanych aplikacjach (Android/data, Android/media) – przez Shizuku
        val residual = ArrayList<Pair<String, Long>>()
        if (Sh.granted()) {
            for (base in listOf("/sdcard/Android/data", "/sdcard/Android/media", "/sdcard/Android/obb")) {
                for (line in Sh.run("ls $base").lines()) {
                    val p = line.trim()
                    if (p.isEmpty() || !p.contains('.') || Apps.installed(pm, p)) continue
                    val kb = Sh.run("du -sk $base/$p").trim().substringBefore('\t').substringBefore(' ').toLongOrNull() ?: 0L
                    residual.add("$base/$p" to kb * 1024)
                }
            }
        }
        return listOf(
            Junk("Miniatury", "Odtworzą się same, gdy będą potrzebne", thumbs),
            Junk("Pliki tymczasowe i logi", ".tmp, .log, .bak, pozostałości po usuniętych zdjęciach", tmp),
            Junk("Zbędne pliki APK", "Instalatory aplikacji, które już masz w tej lub nowszej wersji", apks),
            Junk("Pozostałości po aplikacjach", "Foldery odinstalowanych aplikacji (Android/data, media, obb)", emptyList(), shellDirs = residual),
            Junk("Puste foldery", "Nie zawierają żadnych plików", emptyList(), dirs = emptyDirs)
        )
    }

    /** Usuwa śmieci (bez kosza – to pliki bez wartości). Zwraca zwolnione bajty. */
    fun cleanJunk(c: Context, js: List<Junk>): Long {
        var freed = 0L
        for (j in js) {
            for (f in j.items) if (File(f.path).delete()) freed += f.size
            for (d in j.dirs.sortedByDescending { it.length }) File(d).delete()
            if (j.shellDirs.isNotEmpty()) {
                Sh.runAll(j.shellDirs.map { "rm -rf '${it.first}'" })
                freed += j.shellDirs.sumOf { it.second }
            }
        }
        rescan(c, js.flatMap { j -> j.items.map { it.path } })
        files = files.filter { File(it.path).exists() }
        emptyDirs = emptyDirs.filter { File(it).exists() }
        return freed
    }

    // ------------------------------------------------------------ media
    fun bigFiles(min: Long = 50L * 1024 * 1024) = files.filter { it.size >= min }.sortedByDescending { it.size }

    fun oldDownloads(days: Int = 30): List<FileItem> {
        val lim = System.currentTimeMillis() - days * 86_400_000L
        return files.filter { it.path.contains("/Download/") && it.mod < lim }.sortedByDescending { it.size }
    }

    fun oldScreenshots(days: Int = 14): List<FileItem> {
        val lim = System.currentTimeMillis() - days * 86_400_000L
        return files.filter { (it.path.contains("/Screenshots/") || it.path.contains("/Screen recordings/")) && it.mod < lim }.sortedBy { it.mod }
    }

    fun messengerMedia(): List<FileItem> = files.filter {
        it.path.contains("/WhatsApp/Media/") || it.path.contains("/Telegram/") || it.path.contains("/Messenger/") || it.path.contains("/Viber/")
    }.filter { !it.name.startsWith(".") }.sortedByDescending { it.size }

    fun largeVideos() = files.filter { it.ext in VID && it.size >= 100L * 1024 * 1024 }.sortedByDescending { it.size }

    private fun md5(f: File, partial: Boolean): String {
        val md = MessageDigest.getInstance("MD5")
        if (partial) {
            RandomAccessFile(f, "r").use { r ->
                val b = ByteArray(65536)
                val n = r.read(b); if (n > 0) md.update(b, 0, n)
                if (r.length() > 131072) { r.seek(r.length() - 65536); val m = r.read(b); if (m > 0) md.update(b, 0, m) }
            }
        } else FileInputStream(f).use { inp ->
            val b = ByteArray(1 shl 16)
            while (true) { val n = inp.read(b); if (n <= 0) break; md.update(b, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Duplikaty: ten sam rozmiar → ta sama próbka → ta sama suma kontrolna. */
    fun duplicates(progress: (String) -> Unit): List<List<FileItem>> {
        val bySize = files.filter { it.size >= 20 * 1024 && !it.path.contains("/.") }.groupBy { it.size }.values.filter { it.size > 1 }
        val out = ArrayList<List<FileItem>>()
        var k = 0
        for (g in bySize) {
            if (++k % 50 == 0) progress("Porównuję pliki: $k/${bySize.size}")
            val part = g.groupBy { try { md5(File(it.path), true) } catch (e: Exception) { it.path } }.values.filter { it.size > 1 }
            for (pg in part) {
                val full = if (pg.first().size > 262144) pg.groupBy { try { md5(File(it.path), false) } catch (e: Exception) { it.path } }.values.filter { it.size > 1 } else listOf(pg)
                out.addAll(full.map { it.sortedBy { f -> f.mod } })
            }
        }
        return out.sortedByDescending { it.first().size * (it.size - 1) }
    }

    // ------------------------------------------------------------ zdjęcia: podobne, nieostre, ciemne
    class Photo(val f: FileItem, val hash: Long, val sharp: Double, val bright: Double)

    @Volatile var photos: List<Photo> = emptyList()

    private fun gray(path: String, side: Int): Pair<IntArray, Int>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        if (o.outWidth <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= side && o.outHeight / (s * 2) >= side) s *= 2
        val bm = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s; inPreferredConfig = Bitmap.Config.RGB_565 }) ?: return null
        val w = side
        val h = (side * bm.height / bm.width.coerceAtLeast(1)).coerceIn(8, side * 2)
        val sc = Bitmap.createScaledBitmap(bm, w, h, true)
        val px = IntArray(w * h)
        sc.getPixels(px, 0, w, 0, 0, w, h)
        if (sc !== bm) sc.recycle()
        bm.recycle()
        for (i in px.indices) { val c = px[i]; px[i] = (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000 }
        return Pair(px, w)
    }

    /** Odcisk obrazu (dHash 64 bity), ostrość (wariancja Laplace’a), jasność. */
    fun analyzePhotos(progress: (String) -> Unit) {
        val list = files.filter { it.ext in setOf("jpg", "jpeg", "png", "webp", "heic") && (it.path.contains("/DCIM/") || it.path.contains("/Pictures/")) && !it.path.contains("/.") }
        val out = ArrayList<Photo>(list.size)
        for ((i, f) in list.withIndex()) {
            if (i % 25 == 0) progress("Analizuję zdjęcia: $i/${list.size}")
            try {
                val g = gray(f.path, 96) ?: continue
                val px = g.first; val w = g.second; val h = px.size / w
                // jasność
                val bright = px.average()
                // ostrość
                var sum = 0.0; var sq = 0.0; var n = 0
                for (y in 1 until h - 1) for (x in 1 until w - 1) {
                    val l = 4 * px[y * w + x] - px[y * w + x - 1] - px[y * w + x + 1] - px[(y - 1) * w + x] - px[(y + 1) * w + x]
                    sum += l; sq += l.toDouble() * l; n++
                }
                val sharp = if (n > 0) sq / n - (sum / n) * (sum / n) else 0.0
                // dHash 9×8
                val dh = IntArray(72)
                for (yy in 0 until 8) for (xx in 0 until 9) dh[yy * 9 + xx] = px[(yy * h / 8).coerceAtMost(h - 1) * w + (xx * w / 9).coerceAtMost(w - 1)]
                var hash = 0L
                for (yy in 0 until 8) for (xx in 0 until 8) if (dh[yy * 9 + xx] > dh[yy * 9 + xx + 1]) hash = hash or (1L shl (yy * 8 + xx))
                out.add(Photo(f, hash, sharp, bright))
            } catch (e: Throwable) {
            }
        }
        photos = out
    }

    /** Grupy podobnych zdjęć (serie, prawie takie same ujęcia). */
    fun similar(): List<List<FileItem>> {
        val ps = photos.sortedBy { it.f.mod }
        val parent = IntArray(ps.size) { it }
        fun find(x: Int): Int { var a = x; while (parent[a] != a) { parent[a] = parent[parent[a]]; a = parent[a] }; return a }
        for (i in ps.indices) for (j in i + 1 until minOf(ps.size, i + 40)) {
            if (ps[j].f.mod - ps[i].f.mod > 3600_000L) break
            if (java.lang.Long.bitCount(ps[i].hash xor ps[j].hash) <= 6) parent[find(j)] = find(i)
        }
        return ps.indices.groupBy { find(it) }.values.filter { it.size > 1 }.map { g -> g.map { ps[it] }.sortedByDescending { it.sharp }.map { it.f } }
    }

    fun blurry() = photos.filter { it.sharp < 30 }.sortedBy { it.sharp }.map { it.f }
    fun dark() = photos.filter { it.bright < 28 }.sortedBy { it.bright }.map { it.f }

    // ------------------------------------------------------------ kosz (30 dni)
    private fun idx() = File(trashDir, "index.json")

    fun trash(): List<JSONObject> = try {
        val a = JSONArray(idx().readText()); (0 until a.length()).map { a.getJSONObject(it) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun saveTrash(l: List<JSONObject>) { trashDir.mkdirs(); File(trashDir, ".nomedia").createNewFile(); idx().writeText(JSONArray(l).toString()) }

    fun trashSize() = trash().sumOf { it.optLong("s") }

    /** Przenosi pliki do kosza (do przywrócenia przez 30 dni). Zwraca bajty. */
    fun toTrash(c: Context, paths: List<String>): Long {
        trashDir.mkdirs()
        File(trashDir, ".nomedia").createNewFile()
        val l = trash().toMutableList()
        var moved = 0L
        for (p in paths) {
            val f = File(p)
            if (!f.exists()) continue
            val dest = File(trashDir, "${System.currentTimeMillis()}_${f.name}")
            val size = f.length()
            val ok = f.renameTo(dest) || try { f.copyTo(dest, true); f.delete() } catch (e: Exception) { false }
            if (ok) {
                l.add(JSONObject().put("o", p).put("t", dest.path).put("s", size).put("d", System.currentTimeMillis()))
                moved += size
            }
        }
        saveTrash(l)
        rescan(c, paths)
        val gone = paths.toSet()
        files = files.filter { it.path !in gone }
        photos = photos.filter { it.f.path !in gone }
        return moved
    }

    fun restore(c: Context, items: List<JSONObject>) {
        val l = trash().toMutableList()
        for (o in items) {
            val src = File(o.optString("t")); val dst = File(o.optString("o"))
            dst.parentFile?.mkdirs()
            if (src.renameTo(dst) || try { src.copyTo(dst, true); src.delete() } catch (e: Exception) { false }) l.removeAll { it.optString("t") == o.optString("t") }
        }
        saveTrash(l)
        rescan(c, items.map { it.optString("o") })
    }

    fun emptyTrash(olderThanDays: Int = 0): Long {
        val lim = System.currentTimeMillis() - olderThanDays * 86_400_000L
        val l = trash()
        var freed = 0L
        val keep = l.filter { o ->
            if (o.optLong("d") <= lim) { if (File(o.optString("t")).delete()) freed += o.optLong("s"); false } else true
        }
        saveTrash(keep)
        return freed
    }

    private fun rescan(c: Context, paths: List<String>) {
        if (paths.isEmpty()) return
        try { MediaScannerConnection.scanFile(c, paths.toTypedArray(), null, null) } catch (e: Exception) {}
    }

    // ------------------------------------------------------------ optymalizator zdjęć
    fun optimizable(min: Long = 2_500_000L) = files.filter { it.ext in setOf("jpg", "jpeg") && it.size >= min && it.path.contains("/DCIM/") }.sortedByDescending { it.size }

    /** Zmniejsza zdjęcie (maks. 3000 px, jakość 82), zachowuje datę i obrót; oryginał trafia do kosza. */
    fun optimize(c: Context, list: List<FileItem>, progress: (String) -> Unit): Long {
        var saved = 0L
        for ((i, f) in list.withIndex()) {
            progress("Optymalizuję ${i + 1}/${list.size}")
            try {
                val src = File(f.path)
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(src.path, o)
                var s = 1
                while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= 3000) s *= 2
                val bm = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: continue
                val tmp = File(c.cacheDir, "opt.jpg")
                tmp.outputStream().use { bm.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                bm.recycle()
                if (tmp.length() >= src.length() * 0.85) { tmp.delete(); continue }
                // przepisz najważniejsze dane EXIF
                try {
                    val a = ExifInterface(src.path); val b = ExifInterface(tmp.path)
                    for (t in listOf(ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
                        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
                        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_OFFSET_TIME_ORIGINAL)) a.getAttribute(t)?.let { b.setAttribute(t, it) }
                    b.saveAttributes()
                } catch (e: Exception) {
                }
                val before = src.length()
                val mod = src.lastModified()
                toTrash(c, listOf(src.path))
                tmp.copyTo(src, true)
                src.setLastModified(mod)
                tmp.delete()
                saved += before - src.length()
                rescan(c, listOf(src.path))
            } catch (e: Throwable) {
            }
        }
        return saved
    }

    // ------------------------------------------------------------ informacje o telefonie
    fun systemInfo(c: Context): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val am = c.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        out += "Pamięć RAM" to "${fmt(mi.totalMem - mi.availMem)} z ${fmt(mi.totalMem)} zajęte"
        val st = StatFs(Environment.getDataDirectory().path)
        out += "Pamięć wewnętrzna" to "${fmt(st.totalBytes - st.availableBytes)} z ${fmt(st.totalBytes)} · wolne ${fmt(st.availableBytes)}"
        val cores = Runtime.getRuntime().availableProcessors()
        val maxF = (0 until cores).mapNotNull { File("/sys/devices/system/cpu/cpu$it/cpufreq/cpuinfo_max_freq").takeIf { f -> f.canRead() }?.readText()?.trim()?.toLongOrNull() }.maxOrNull()
        out += "Procesor" to "${(if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MODEL else "").ifBlank { android.os.Build.HARDWARE }} · $cores rdzeni" + (maxF?.let { " · do ${it / 1000} MHz" } ?: "")
        val bi = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (bi != null) {
            val t = bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
            val v = bi.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
            val health = when (bi.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "dobra"; BatteryManager.BATTERY_HEALTH_OVERHEAT -> "przegrzana"; BatteryManager.BATTERY_HEALTH_DEAD -> "zużyta"
                BatteryManager.BATTERY_HEALTH_COLD -> "zimna"; else -> "nieznana"
            }
            out += "Bateria" to "kondycja: $health · ${"%.1f".format(t)} °C · $v mV"
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                val cyc = bi.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)
                if (cyc >= 0) out += "Cykle ładowania" to "$cyc"
            }
        }
        val up = SystemClock.elapsedRealtime() / 1000
        out += "Czas od restartu" to "${up / 86400} d ${up / 3600 % 24} h ${up / 60 % 60} min"
        out += "System" to "Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MODEL}"
        return out
    }

    fun fmt(b: Long): String = when {
        b >= 1L shl 30 -> "%.1f GB".format(b / 1073741824.0)
        b >= 1L shl 20 -> "%.0f MB".format(b / 1048576.0)
        b >= 1024 -> "%.0f KB".format(b / 1024.0)
        else -> "$b B"
    }

    @Suppress("unused")
    private fun keep(p: PackageManager) = p
}
