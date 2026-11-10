package pl.bateria

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/** Aplikacja na liście. */
class App(val pkg: String, val label: String, val system: Boolean, var lastUsed: Long)

object Apps {
    /** Chronione na start: komunikatory, poczta, bank, nawigacja, zegar, własne aplikacje. */
    val PROTECT_DEFAULT = listOf(
        "com.whatsapp", "com.whatsapp.w4b", "com.facebook.orca", "org.telegram.messenger", "org.thoughtcrime.securesms",
        "com.viber.voip", "com.microsoft.teams", "com.Slack", "com.google.android.gm", "com.microsoft.office.outlook",
        "com.samsung.android.email.provider", "com.google.android.apps.maps", "com.waze", "com.google.android.projection.gearhead",
        "com.sec.android.app.clockpackage", "com.google.android.deskclock", "io.homeassistant.companion.android",
        "pl.pkobp.iko", "pl.mbank", "pl.ing.mojeing", "pl.bzwbk.bzwbk24", "pl.pekao.peopay", "pl.millennium.corpApp",
        "pl.aliorbank.aib", "eu.eleader.mobilebanking.pekao", "com.revolut.revolut", "pl.nask.mobywatel",
        "com.google.android.apps.walletnfcrel", "com.samsung.android.spay",
        "pl.notatnik", "pl.audioteka", "pl.konwerter", "pl.karty", "pl.einklauncher", "pl.timegate",
        "moe.shizuku.privileged.api", "af.shizuku.plus.api", "com.google.android.apps.authenticator2", "com.azure.authenticator"
    )

    /** Usługi Samsunga, Google i Facebooka, które można bezpiecznie ograniczyć w tle. */
    val VENDOR_SAFE = listOf(
        "com.samsung.android.app.spage" to "Samsung Free",
        "com.samsung.android.bixby.agent" to "Bixby",
        "com.samsung.android.bixby.wakeup" to "Bixby – nasłuch",
        "com.samsung.android.visionintelligence" to "Bixby Vision",
        "com.sec.android.app.samsungapps" to "Galaxy Store",
        "com.samsung.android.app.tips" to "Porady Samsunga",
        "com.samsung.android.ardrawing" to "AR Doodle",
        "com.samsung.android.aremoji" to "AR Emoji",
        "com.samsung.android.dqagent" to "Agent jakości urządzenia (analityka)",
        "com.sec.android.diagmonagent" to "Diagnostyka Samsunga",
        "com.samsung.android.rubin.app" to "Usługa dopasowywania (analityka)",
        "com.samsung.android.game.gamehome" to "Game Launcher",
        "com.samsung.android.mateagent" to "Galaxy Friends",
        "com.samsung.android.app.sharelive" to "Szybkie udostępnianie (w tle)",
        "com.facebook.appmanager" to "Facebook App Manager",
        "com.facebook.services" to "Facebook Services",
        "com.facebook.system" to "Facebook App Installer",
        "com.google.android.apps.youtube.music" to "YouTube Music",
        "com.google.android.youtube" to "YouTube",
        "com.google.android.apps.photos" to "Zdjęcia Google",
        "com.netflix.mediaclient" to "Netflix",
        "com.spotify.music" to "Spotify (tylko tło)"
    )

    /** Nigdy nie ruszamy (system, launcher, klawiatura, ta aplikacja). */
    fun untouchable(c: Context): Set<String> {
        val s = HashSet<String>()
        s.add(c.packageName)
        s.add("android"); s.add("com.android.systemui"); s.add("com.android.phone"); s.add("com.samsung.android.dialer")
        s.add("com.android.settings"); s.add("com.google.android.gms"); s.add("com.android.vending")
        s.add("com.samsung.android.messaging"); s.add("com.google.android.apps.messaging"); s.add("moe.shizuku.privileged.api"); s.add("af.shizuku.plus.api")
        val pm = c.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0).forEach { s.add(it.activityInfo.packageName) }
        val ime = android.provider.Settings.Secure.getString(c.contentResolver, "default_input_method") ?: ""
        if (ime.contains('/')) s.add(ime.substringBefore('/'))
        return s
    }

    fun installed(pm: PackageManager, p: String) = try { pm.getApplicationInfo(p, 0); true } catch (e: Exception) { false }

    /** Czy aplikacja jest wyłączona (zamrożona). */
    fun frozen(pm: PackageManager, p: String) = try { !pm.getApplicationInfo(p, PackageManager.MATCH_DISABLED_COMPONENTS).enabled } catch (e: Exception) { false }

    private val iconCache = HashMap<String, Drawable?>()

    fun icon(c: Context, p: String): Drawable? = iconCache.getOrPut(p) {
        try { c.packageManager.getApplicationIcon(p) } catch (e: Exception) { null }
    }

    /** Ostatnie użycie (dostęp do statystyk nadaje Shizuku). */
    fun usage(c: Context, days: Int = 40): Map<String, Long> {
        val um = c.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
        val now = System.currentTimeMillis()
        return try {
            um.queryAndAggregateUsageStats(now - days * 86_400_000L, now).mapValues { it.value.lastTimeUsed }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun screenTime(c: Context, from: Long): Map<String, Long> {
        val um = c.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
        return try {
            um.queryAndAggregateUsageStats(from, System.currentTimeMillis()).mapValues { it.value.totalTimeInForeground }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** Aplikacje do wyboru: z ikoną w launcherze (+ opcjonalnie systemowe) oraz zamrożone przez nas. */
    fun list(c: Context, withSystem: Boolean): List<App> {
        val pm = c.packageManager
        val skip = untouchable(c)
        val launchable = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_DISABLED_COMPONENTS)
            .map { it.activityInfo.packageName }.toHashSet()
        val use = usage(c)
        val out = ArrayList<App>()
        for (ai in pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)) {
            val p = ai.packageName
            if (p in skip) continue
            val sys = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0 && ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
            val known = Cfg.cats.containsKey(p)
            if (!known && p !in launchable && !(withSystem && sys)) continue
            if (!withSystem && sys && !known && p !in launchable) continue
            val label = try { pm.getApplicationLabel(ai).toString() } catch (e: Exception) { p }
            out.add(App(p, label, sys, use[p] ?: 0L))
        }
        return out.sortedBy { it.label.lowercase() }
    }
}
