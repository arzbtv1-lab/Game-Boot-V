package com.ignis.deck

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import java.util.concurrent.Executors

/** Game Mode: applies the profile, shows a live stats overlay, restores everything when the game closes. */
class OverlayService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private var wm: WindowManager? = null
    private var view: TextView? = null
    private var pkg = ""
    private var running = false
    private var startT = 0L
    private var ticks = 0
    private var idle = 0
    private var away = 0
    private var lastFg: String? = null
    @Volatile private var pingMs = -1
    private var prevFilter = -1
    private var prevMode = -1
    private var prevBright = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        pkg = intent.getStringExtra("pkg") ?: ""
        away = 0
        idle = 0
        if (running) return START_NOT_STICKY
        running = true
        startT = SystemClock.elapsedRealtime()
        startForeground(1, notification())
        applyProfile()
        if (getSharedPreferences("ignis", 0).getBoolean("overlay", true)) addOverlay()
        h.post(tick)
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("game", "Game Mode", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, OverlayService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, "game")
            .setSmallIcon(R.drawable.ic_flame)
            .setContentTitle("Ignis Deck - Game Mode on")
            .setContentText("Tap to stop and restore settings")
            .setContentIntent(stop)
            .build()
    }

    private fun applyProfile() {
        val sp = getSharedPreferences("ignis", 0)
        val nm = getSystemService(NotificationManager::class.java)
        if (sp.getBoolean("dnd", true) && Sys.canDnd(this)) {
            prevFilter = nm.currentInterruptionFilter
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        }
        if (sp.getBoolean("bright", false) && Sys.canWrite(this)) {
            try {
                val cr = contentResolver
                prevMode = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE)
                prevBright = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS)
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, 200)
            } catch (_: Exception) {
            }
        }
        if (sp.getBoolean("kill", true)) exec.execute { Boost.clean(this, pkg) }
    }

    private fun restoreProfile() {
        val nm = getSystemService(NotificationManager::class.java)
        if (prevFilter != -1 && Sys.canDnd(this)) nm.setInterruptionFilter(prevFilter)
        if (prevBright != -1 && Sys.canWrite(this)) {
            try {
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, prevBright)
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, prevMode)
            } catch (_: Exception) {
            }
        }
    }

    private fun addOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        val w = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = w
        val t = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(18, 10, 18, 10)
            background = GradientDrawable().apply {
                setColor(0xCC120A0A.toInt())
                cornerRadius = 20f
                setStroke(2, 0xFFE53935.toInt())
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 90
        }
        var sx = 0
        var sy = 0
        var tx = 0f
        var ty = 0f
        t.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = sx + (e.rawX - tx).toInt()
                    lp.y = sy + (e.rawY - ty).toInt()
                    w.updateViewLayout(v, lp)
                }
            }
            true
        }
        try {
            w.addView(t, lp)
            view = t
        } catch (_: Exception) {
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val m = Sys.memory(this@OverlayService)
            val (lvl, temp) = Sys.battery(this@OverlayService)
            val sec = (SystemClock.elapsedRealtime() - startT) / 1000
            val ping = if (pingMs < 0) "--" else "$pingMs ms"
            view?.text = "RAM  ${m.pct}%  free ${Sys.mb(m.avail)}\n" +
                "CPU  ${Sys.cpuMhz()}\n" +
                "BAT  $lvl%  ${"%.1f".format(temp)}C\n" +
                "PING $ping\n" +
                "TIME ${sec / 60}:${"%02d".format(sec % 60)}"
            if (ticks % 3 == 0) exec.execute { pingMs = Sys.ping() }
            ticks++
            checkForeground()
            h.postDelayed(this, 1500)
        }
    }

    /** Stops Game Mode ~9s after the user leaves the game (needs Usage access; otherwise stop via notification). */
    private fun checkForeground() {
        if (pkg.isEmpty() || !Sys.canUsage(this)) return
        try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val ev = usm.queryEvents(now - 10000, now)
            val e = UsageEvents.Event()
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                @Suppress("DEPRECATION")
                if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) lastFg = e.packageName
            }
        } catch (_: Exception) {
            return
        }
        val fg = lastFg
        if (fg == null) {
            if (++idle > 25) stopSelf()
        } else if (fg == pkg || fg == packageName) {
            away = 0
        } else if (++away >= 6) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        h.removeCallbacks(tick)
        try {
            view?.let { wm?.removeView(it) }
        } catch (_: Exception) {
        }
        restoreProfile()
        exec.shutdown()
        running = false
        super.onDestroy()
    }
}
