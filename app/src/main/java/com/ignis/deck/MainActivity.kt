package com.ignis.deck

import android.Manifest
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.concurrent.Executors

private val BG = 0xFF0B0B0F.toInt()
private val CARD = 0xFF16161D.toInt()
private val RED = 0xFFE53935.toInt()
private val TXT = 0xFFF2F2F2.toInt()
private val MUTED = 0xFF9A9AA5.toInt()
private val GREEN = 0xFF4CAF50.toInt()

class MainActivity : AppCompatActivity() {
    private val h = Handler(Looper.getMainLooper())
    private val exec = Executors.newCachedThreadPool()
    private val prefs by lazy { getSharedPreferences("ignis", 0) }
    private lateinit var scroll: ScrollView
    private lateinit var root: LinearLayout
    private lateinit var ramTv: TextView
    private lateinit var ramBar: ProgressBar
    private lateinit var stoTv: TextView
    private lateinit var stoBar: ProgressBar
    private lateinit var batTv: TextView

    private val loop = object : Runnable {
        override fun run() {
            updateStats()
            h.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scroll = ScrollView(this).apply { setBackgroundColor(BG) }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(28), dp(16), dp(28))
        }
        scroll.addView(root)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        val y = scroll.scrollY
        build()
        scroll.post { scroll.scrollTo(0, y) }
        h.removeCallbacks(loop)
        h.post(loop)
    }

    override fun onPause() {
        super.onPause()
        h.removeCallbacks(loop)
    }

    // ---------- UI helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(s: String, size: Float = 14f, color: Int = TXT, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s
        setTextColor(Color.WHITE)
        isAllCaps = false
        background = GradientDrawable().apply {
            setColor(RED)
            cornerRadius = dp(12).toFloat()
        }
        setOnClickListener { onClick() }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            setColor(CARD)
            cornerRadius = dp(16).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
    }

    private fun bar() = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progressTintList = ColorStateList.valueOf(RED)
        progressBackgroundTintList = ColorStateList.valueOf(0xFF2A2A33.toInt())
        layoutParams = LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(6) }
    }

    private fun space(hDp: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(hDp)) }

    private fun sw(title: String, key: String, def: Boolean) = SwitchCompat(this).apply {
        text = title
        setTextColor(TXT)
        isChecked = prefs.getBoolean(key, def)
        setPadding(0, dp(6), 0, dp(6))
        setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean(key, v).apply() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun open(i: Intent) {
        try {
            startActivity(i)
        } catch (e: Exception) {
            toast("Not available on this phone")
        }
    }

    // ---------- screen ----------
    private fun build() {
        root.removeAllViews()
        root.addView(tv("IGNIS DECK", 28f, RED, true))
        root.addView(tv("Game Space - real boosts, no fake numbers", 13f, MUTED))

        // live stats
        val c1 = card()
        ramTv = tv("")
        ramBar = bar()
        stoTv = tv("")
        stoBar = bar()
        batTv = tv("")
        c1.addView(ramTv); c1.addView(ramBar); c1.addView(space(12))
        c1.addView(stoTv); c1.addView(stoBar); c1.addView(space(12))
        c1.addView(batTv)
        root.addView(c1)
        updateStats()

        // boost
        val c2 = card()
        val res = tv("Asks Android to close background apps, then measures the RAM actually recovered.", 12f, MUTED)
        c2.addView(button("Boost RAM now") {
            res.text = "Working..."
            exec.execute {
                val r = Boost.clean(this)
                runOnUiThread {
                    res.text = "Scanned ${r.scanned} apps - freed ${r.freedMb} MB (measured). Android may restart some apps."
                    updateStats()
                }
            }
        })
        c2.addView(space(8))
        c2.addView(res)
        root.addView(c2)

        // game mode profile
        val c3 = card()
        c3.addView(tv("Game Mode profile", 16f, TXT, true))
        c3.addView(tv("Applied when you press Play, restored when the game closes.", 12f, MUTED))
        c3.addView(sw("Do Not Disturb while playing", "dnd", true))
        c3.addView(sw("Close background apps", "kill", true))
        c3.addView(sw("Stats overlay (RAM / CPU / battery / ping)", "overlay", true))
        c3.addView(sw("Raise brightness while playing", "bright", false))
        root.addView(c3)

        // permissions
        val c4 = card()
        c4.addView(tv("Permissions", 16f, TXT, true))
        c4.addView(permRow("Draw over other apps (overlay)", Sys.canOverlay(this)) {
            open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        c4.addView(permRow("Do Not Disturb access", Sys.canDnd(this)) {
            open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        })
        c4.addView(permRow("Modify system settings (brightness)", Sys.canWrite(this)) {
            open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
        })
        c4.addView(permRow("Usage access (play time, auto-stop)", Sys.canUsage(this)) {
            open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        })
        if (Build.VERSION.SDK_INT >= 33) {
            val ok = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            c4.addView(permRow("Notifications", ok) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            })
        }
        root.addView(c4)

        // games
        val c5 = card()
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(tv("Game Space", 16f, TXT, true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(button("+ Add app") { addDialog() })
        c5.addView(head)
        val games = Lib.games(this)
        val times = playTimes()
        val usageOk = Sys.canUsage(this)
        if (games.isEmpty()) c5.addView(tv("No games detected. Tap + Add app to add one.", 13f, MUTED))
        for (g in games) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            val icon = ImageView(this).apply {
                setImageDrawable(try { packageManager.getApplicationIcon(g.pkg) } catch (e: Exception) { null })
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)))
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
            }
            col.addView(tv(g.label, 15f, TXT, true))
            val played = if (usageOk) "Today: ${(times[g.pkg] ?: 0L) / 60000} min" else "Grant usage access to see play time"
            col.addView(tv(played, 12f, MUTED))
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(button("Play") { play(g.pkg) })
            row.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setMessage("Remove ${g.label} from Game Space?")
                    .setPositiveButton("Remove") { _, _ -> Lib.remove(this, g.pkg); build() }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
            c5.addView(row)
        }
        if (games.isNotEmpty()) c5.addView(tv("Long-press a game to remove it.", 11f, MUTED))
        root.addView(c5)

        // tools
        val c6 = card()
        c6.addView(tv("Tools", 16f, TXT, true))
        c6.addView(space(8))
        c6.addView(button("Network ping test") { pingTest() })
        c6.addView(space(8))
        c6.addView(button("Stop Game Mode") {
            startService(Intent(this, OverlayService::class.java).setAction("STOP"))
            toast("Game Mode stopped")
        })
        c6.addView(space(8))
        c6.addView(button("Developer options (animation speed)") {
            open(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        })
        c6.addView(tv("Tip: set Window / Transition / Animator scale to 0.5x for a snappier phone. If the menu is empty, enable Developer options by tapping Build number 7 times.", 11f, MUTED))
        c6.addView(space(8))
        c6.addView(button("Battery optimization") { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) })
        c6.addView(space(8))
        c6.addView(button("Storage cleaner (system)") { open(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) })
        root.addView(c6)
    }

    private fun permRow(name: String, ok: Boolean, grant: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(tv((if (ok) "OK  " else "--  ") + name, 13f, if (ok) GREEN else TXT), LinearLayout.LayoutParams(0, -2, 1f))
        if (!ok) row.addView(button("Grant") { grant() })
        return row
    }

    private fun updateStats() {
        val m = Sys.memory(this)
        ramTv.text = "RAM  ${Sys.mb(m.used)} / ${Sys.mb(m.total)}  (${m.pct}%)  free ${Sys.mb(m.avail)}"
        ramBar.progress = m.pct
        val (tot, free) = Sys.storage()
        val usedPct = if (tot > 0) ((tot - free) * 100 / tot).toInt() else 0
        stoTv.text = "Storage  ${Sys.gb(tot - free)} / ${Sys.gb(tot)}  ($usedPct%)"
        stoBar.progress = usedPct
        val (lvl, temp) = Sys.battery(this)
        batTv.text = "Battery  $lvl%  ${"%.1f".format(temp)}C   CPU ${Sys.cpuMhz()}"
    }

    private fun playTimes(): Map<String, Long> {
        if (!Sys.canUsage(this)) return emptyMap()
        return try {
            val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, cal.timeInMillis, System.currentTimeMillis())
                .groupBy { it.packageName }
                .mapValues { e -> e.value.sumOf { it.totalTimeInForeground } }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun play(pkg: String) {
        if (prefs.getBoolean("overlay", true) && !Sys.canOverlay(this)) {
            toast("Grant overlay permission below to see live stats")
        }
        ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java).putExtra("pkg", pkg))
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch != null) startActivity(launch) else toast("Cannot open this app")
    }

    private fun addDialog() {
        val all = Sys.launchable(this)
        val names = all.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Add to Game Space")
            .setItems(names) { _, i -> Lib.add(this, all[i].pkg); build() }
            .show()
    }

    private fun pingTest() {
        toast("Testing...")
        exec.execute {
            val sb = StringBuilder()
            for (host in listOf("1.1.1.1", "8.8.8.8")) {
                val r = (1..3).map { Sys.ping(host) }.filter { it >= 0 }
                sb.append(host).append(": ")
                sb.append(if (r.isEmpty()) "no response" else "avg ${r.average().toInt()} ms (min ${r.min()}, max ${r.max()})")
                sb.append("\n")
            }
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("Network latency (TCP)")
                    .setMessage(sb.toString().trim())
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }
}
