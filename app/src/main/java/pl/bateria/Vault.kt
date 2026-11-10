package pl.bateria

import android.app.Activity
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Wspólne odblokowanie odciskiem palca / kodem telefonu. */
object Bio {
    fun ask(a: Activity, title: String, sub: String, ok: () -> Unit, fail: () -> Unit = {}) {
        try {
            val b = BiometricPrompt.Builder(a).setTitle(title).setSubtitle(sub)
            if (android.os.Build.VERSION.SDK_INT >= 30) b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            else @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
            b.build().authenticate(CancellationSignal(), a.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) { ok() }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) { fail() }
            })
        } catch (e: Exception) {
            fail()
        }
    }
}

/**
 * Sejf: pliki szyfrowane AES-256-GCM. Każdy plik ma własny losowy klucz, a ten klucz jest zaszyfrowany kluczem
 * z bezpiecznego magazynu telefonu (Android Keystore), który działa tylko po odblokowaniu odciskiem lub kodem.
 */
object Vault {
    private const val ALIAS = "bastion_sejf"
    private const val CHUNK = 1 shl 20

    private fun master(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256)
            .setUserAuthenticationRequired(true)
        if (android.os.Build.VERSION.SDK_INT >= 30) spec.setUserAuthenticationParameters(300, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
        else @Suppress("DEPRECATION") spec.setUserAuthenticationValidityDurationSeconds(300)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec.build()) }.generateKey()
    }

    fun dir(c: Context) = File(c.filesDir, "sejf").apply { mkdirs() }
    private fun idx(c: Context) = File(dir(c), "index.json")

    fun items(c: Context): List<JSONObject> = try { JSONArray(idx(c).readText()).let { a -> (0 until a.length()).map { a.getJSONObject(it) } } } catch (e: Exception) { emptyList() }
    private fun save(c: Context, l: List<JSONObject>) = idx(c).writeText(JSONArray(l).toString())
    fun size(c: Context) = items(c).sumOf { it.optLong("s") }

    /** Szyfruje strumień do sejfu. Wymaga świeżego odblokowania (Bio.ask). */
    fun add(c: Context, name: String, input: InputStream): Long {
        val rnd = SecureRandom()
        val fileKey = ByteArray(32).also { rnd.nextBytes(it) }
        val wrap = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, master()) }
        val wrapped = wrap.doFinal(fileKey)
        val id = System.currentTimeMillis().toString() + "_" + rnd.nextInt(9999)
        val out = File(dir(c), "$id.bin")
        var total = 0L
        DataOutputStream(out.outputStream().buffered()).use { o ->
            o.writeInt(0x42535431) // „BST1”
            o.writeInt(wrap.iv.size); o.write(wrap.iv)
            o.writeInt(wrapped.size); o.write(wrapped)
            val key = SecretKeySpec(fileKey, "AES")
            val buf = ByteArray(CHUNK)
            while (true) {
                var n = 0
                while (n < CHUNK) { val r = input.read(buf, n, CHUNK - n); if (r <= 0) break; n += r }
                if (n <= 0) break
                val iv = ByteArray(12).also { rnd.nextBytes(it) }
                val ci = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
                val enc = ci.doFinal(buf, 0, n)
                o.write(iv); o.writeInt(enc.size); o.write(enc)
                total += n
                if (n < CHUNK) break
            }
        }
        fileKey.fill(0)
        save(c, items(c) + JSONObject().put("id", id).put("n", name).put("s", total).put("d", System.currentTimeMillis()))
        return total
    }

    /** Odszyfrowuje plik z sejfu do podanego strumienia. */
    fun decrypt(c: Context, id: String, out: OutputStream) {
        DataInputStream(File(dir(c), "$id.bin").inputStream().buffered()).use { i ->
            if (i.readInt() != 0x42535431) throw IllegalStateException("Uszkodzony plik sejfu")
            val wiv = ByteArray(i.readInt()).also { i.readFully(it) }
            val wrapped = ByteArray(i.readInt()).also { i.readFully(it) }
            val fileKey = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, master(), GCMParameterSpec(128, wiv)) }.doFinal(wrapped)
            val key = SecretKeySpec(fileKey, "AES")
            val iv = ByteArray(12)
            while (true) {
                val r = i.read(iv, 0, 12)
                if (r < 12) break
                val enc = ByteArray(i.readInt()).also { i.readFully(it) }
                out.write(Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }.doFinal(enc))
            }
            fileKey.fill(0)
        }
    }

    /** Tymczasowa, odszyfrowana kopia do podglądu (kasowana przy wyjściu z sejfu). */
    fun view(c: Context, o: JSONObject): File {
        val d = File(c.cacheDir, "sejf-podglad").apply { mkdirs() }
        val f = File(d, o.optString("n").replace('/', '_'))
        f.outputStream().use { decrypt(c, o.optString("id"), it) }
        return f
    }

    fun wipeTemp(c: Context) { File(c.cacheDir, "sejf-podglad").listFiles()?.forEach { it.delete() } }

    fun remove(c: Context, id: String) {
        File(dir(c), "$id.bin").delete()
        save(c, items(c).filter { it.optString("id") != id })
    }
}

/** Blokada aplikacji: wybrane aplikacje wymagają odcisku przy otwarciu. */
object AppLock {
    private val unlocked = HashSet<String>()
    @Volatile private var asking = ""

    fun reset() { unlocked.clear(); asking = "" }
    fun pass(pkg: String) { unlocked.add(pkg); asking = "" }
    fun cancelled() { asking = "" }

    /** Wywoływane co pół sekundy przy włączonym ekranie. */
    fun tick(c: Context) {
        if (Cfg.locked.isEmpty()) return
        val um = c.getSystemService(UsageStatsManager::class.java) ?: return
        val now = System.currentTimeMillis()
        var top = ""
        try {
            val ev = um.queryEvents(now - 4000, now)
            val e = UsageEvents.Event()
            while (ev.hasNextEvent()) { ev.getNextEvent(e); if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) top = e.packageName }
        } catch (e: Exception) {
            return
        }
        if (top.isEmpty() || top == c.packageName) return
        // po wyjściu z aplikacji blokada wraca
        unlocked.retainAll { it == top }
        if (top in Cfg.locked && top !in unlocked && asking != top) {
            asking = top
            try {
                c.startActivity(Intent(c, LockActivity::class.java).putExtra("pkg", top)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            } catch (e: Exception) {
                asking = ""
            }
        }
    }
}

/** Ekran blokady nad chronioną aplikacją. */
class LockActivity : Activity() {
    private var pkg = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pkg = intent.getStringExtra("pkg") ?: ""
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xFF000000.toInt()))
        val label = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
        setContentView(android.widget.TextView(this).apply {
            text = "🔒 $label\n\nStuknij, żeby odblokować"
            textSize = 20f; setTextColor(0xFFFFFFFF.toInt()); gravity = android.view.Gravity.CENTER
            setOnClickListener { ask(label) }
        })
        ask(label)
    }

    private fun ask(label: String) {
        Bio.ask(this, "Bastion", "Odblokuj: $label", ok = { AppLock.pass(pkg); finish() }, fail = {})
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { home() }

    private fun home() {
        AppLock.cancelled()
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}

/** Tryb podróży: telefon „uszczelniony” na lotnisku, w hotelu, na konferencji. */
object Travel {
    fun set(c: Context, on: Boolean) {
        Cfg.travel = on
        if (on) {
            Cfg.prev["travel_bt"] = if (c.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.isEnabled == true) "1" else "0"
            Cfg.prev["travel_lock"] = Sys.get(c, "secure", "lock_screen_allow_private_notifications")
            Engine.bluetooth(false)
            Sh.run("svc nfc disable")
            Engine.applyScan(true)
            Sys.put(c, "secure", "lock_screen_allow_private_notifications", "0")
            Sh.run("svc usb setFunctions")                     // USB tylko ładuje – ochrona przed podstawionymi ładowarkami
            Cfg.wifiGuard = true; Cfg.alerts = true; Cfg.smsGuard = true
        } else {
            if (Cfg.prev.remove("travel_bt") == "1") Engine.bluetooth(true)
            Sh.run("svc nfc enable")
            if (!Cfg.scanOff) Engine.applyScan(false)
            Cfg.prev.remove("travel_lock")?.let { Sys.put(c, "secure", "lock_screen_allow_private_notifications", if (it == "null") null else it) }
        }
        Cfg.save()
    }
}
