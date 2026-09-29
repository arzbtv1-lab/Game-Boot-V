package com.ignis.deck

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

data class MemInfo(val total: Long, val avail: Long) {
    val used: Long get() = total - avail
    val pct: Int get() = if (total > 0) (used * 100 / total).toInt() else 0
}

data class GameApp(val pkg: String, val label: String)

/** Real system readings - everything here comes from Android APIs, nothing simulated. */
object Sys {
    fun memory(c: Context): MemInfo {
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return MemInfo(mi.totalMem, mi.availMem)
    }

    /** returns (battery %, temperature in Celsius) */
    fun battery(c: Context): Pair<Int, Float> {
        val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        return Pair(if (level >= 0 && scale > 0) level * 100 / scale else -1, temp)
    }

    /** returns (total bytes, free bytes) of internal storage */
    fun storage(): Pair<Long, Long> {
        val s = StatFs(Environment.getDataDirectory().path)
        return Pair(s.totalBytes, s.availableBytes)
    }

    /** Highest current CPU core clock, read from sysfs (some phones block this -> n/a). */
    fun cpuMhz(): String {
        val out = ArrayList<Int>()
        for (n in 0 until Runtime.getRuntime().availableProcessors()) {
            try {
                val khz = File("/sys/devices/system/cpu/cpu$n/cpufreq/scaling_cur_freq").readText().trim().toInt()
                out.add(khz / 1000)
            } catch (_: Exception) {
            }
        }
        return if (out.isEmpty()) "n/a" else "${out.max()} MHz"
    }

    /** TCP connect time in ms (real latency), -1 on failure. */
    fun ping(host: String = "1.1.1.1", port: Int = 443, timeout: Int = 2000): Int {
        return try {
            val t = SystemClock.elapsedRealtime()
            Socket().use { it.connect(InetSocketAddress(host, port), timeout) }
            (SystemClock.elapsedRealtime() - t).toInt()
        } catch (_: Exception) {
            -1
        }
    }

    fun mb(b: Long) = "${b / 1048576} MB"
    fun gb(b: Long) = String.format("%.1f GB", b / 1073741824.0)

    fun canOverlay(c: Context) = Settings.canDrawOverlays(c)
    fun canDnd(c: Context) =
        (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).isNotificationPolicyAccessGranted
    fun canWrite(c: Context) = Settings.System.canWrite(c)
    fun canUsage(c: Context): Boolean {
        val ops = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    fun launchable(c: Context): List<GameApp> {
        val pm = c.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .map { GameApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.pkg != c.packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    @Suppress("DEPRECATION")
    fun isGame(c: Context, pkg: String): Boolean = try {
        val ai = c.packageManager.getApplicationInfo(pkg, 0)
        ai.category == ApplicationInfo.CATEGORY_GAME || (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0
    } catch (_: Exception) {
        false
    }
}

/** The user's game library: auto-detected games + manually added apps - removed ones. */
object Lib {
    private fun p(c: Context) = c.getSharedPreferences("ignis", 0)
    private fun set(c: Context, k: String) = p(c).getStringSet(k, emptySet())!!.toSet()

    fun games(c: Context): List<GameApp> {
        val added = set(c, "added")
        val removed = set(c, "removed")
        return Sys.launchable(c).filter { (Sys.isGame(c, it.pkg) || it.pkg in added) && it.pkg !in removed }
    }

    fun add(c: Context, pkg: String) {
        p(c).edit().putStringSet("added", set(c, "added") + pkg).putStringSet("removed", set(c, "removed") - pkg).apply()
    }

    fun remove(c: Context, pkg: String) {
        p(c).edit().putStringSet("removed", set(c, "removed") + pkg).putStringSet("added", set(c, "added") - pkg).apply()
    }
}

object Boost {
    data class Result(val scanned: Int, val freedMb: Long)

    /** Asks Android to kill background processes of every launchable app except [keep] and the home launcher.
     *  Freed RAM is measured (available memory after - before), not guessed. Call off the main thread. */
    fun clean(c: Context, keep: String? = null): Result {
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val before = Sys.memory(c).avail
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val launcher = c.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        var n = 0
        for (a in Sys.launchable(c)) {
            if (a.pkg == keep || a.pkg == launcher) continue
            am.killBackgroundProcesses(a.pkg)
            n++
        }
        Thread.sleep(800)
        val after = Sys.memory(c).avail
        return Result(n, maxOf(0L, (after - before) / 1048576))
    }
}
