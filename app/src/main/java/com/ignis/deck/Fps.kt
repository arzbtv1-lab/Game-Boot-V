package com.ignis.deck

import java.util.concurrent.TimeUnit

/** Real FPS of another app, read from SurfaceFlinger frame timestamps. Needs the DUMP permission (one-time adb grant). */
object Fps {
    private const val INVALID = Long.MAX_VALUE
    @Volatile var lastError = ""

    private fun sh(vararg cmd: String): List<String>? {
        return try {
            val p = ProcessBuilder(cmd.toList()).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readLines()
            p.waitFor(3, TimeUnit.SECONDS)
            if (out.any { it.contains("Permission Denial", true) || it.contains("Permission denied", true) || it.contains("avc:", true) }) {
                lastError = "no permission (grant DUMP)"
                return null
            }
            out
        } catch (e: Exception) {
            lastError = (e.message ?: "exec failed").take(40)
            null
        }
    }

    fun layers(pkg: String): List<String> {
        val all = sh("dumpsys", "SurfaceFlinger", "--list") ?: return emptyList()
        if (all.isEmpty()) {
            lastError = "dumpsys returned nothing"
            return emptyList()
        }
        val mine = all.filter { it.contains(pkg) }
        val sv = mine.filter { it.contains("SurfaceView") }
        return if (sv.isNotEmpty()) sv else mine
    }

    private fun fpsOf(layer: String): Int {
        val out = sh("dumpsys", "SurfaceFlinger", "--latency", layer) ?: return -1
        val now = System.nanoTime()
        var valid = 0
        var count = 0
        for (line in out.drop(1)) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 3) continue
            val t = parts[1].toLongOrNull() ?: continue
            if (t <= 0L || t == INVALID) continue
            valid++
            if (t > now - 1_000_000_000L && t < now + 300_000_000L) count++
        }
        return if (valid == 0) -1 else count
    }

    /** Frames drawn in the last second by the game's layer; -1 if it cannot be read (reason in lastError). */
    fun measure(pkg: String): Int {
        lastError = ""
        val ls = layers(pkg)
        if (ls.isEmpty()) {
            if (lastError.isEmpty()) lastError = "game layer not found"
            return -1
        }
        var best = -1
        for (l in ls) best = maxOf(best, fpsOf(l))
        if (best < 0) lastError = "no frame data"
        return best
    }
}
