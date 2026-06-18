package com.portalhacks.frame

import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Random

/**
 * A short "fortune cookie" wisdom line for the sticky note. No API key: a random
 * piece of advice from the Advice Slip API (https://api.adviceslip.com/advice, HTTPS,
 * tiny JSON), with a bundled offline list as a fail-closed fallback so the note still
 * has something to say with no network. Best-effort — [fetch] returns null on any
 * failure and the caller falls back to [bundled].
 */
internal object Fortune {

    private const val TAG = "PortalFrame"
    private const val ENDPOINT = "https://api.adviceslip.com/advice"
    private const val MAX_BYTES = 16 * 1024 // the payload is ~60 bytes; cap defensively
    private val rnd = Random()

    /** A random wisdom line from the network, or null on any failure. */
    @JvmStatic
    fun fetch(): String? {
        return try {
            val slip = JSONObject(httpGet(ENDPOINT)).optJSONObject("slip") ?: return null
            slip.optString("advice", "").trim().ifEmpty { null }
        } catch (e: Exception) {
            Log.w(TAG, "fortune fetch failed", e)
            null
        }
    }

    /** A random line from the offline list (used when the network is unavailable). */
    @JvmStatic
    fun bundled(): String = OFFLINE[rnd.nextInt(OFFLINE.size)]

    private val OFFLINE = arrayOf(
        "The journey of a thousand miles begins with a single step.",
        "A calm mind brings inner strength and confidence.",
        "Fortune favors the prepared.",
        "The best time to plant a tree was 20 years ago. The second best is now.",
        "Patience is bitter, but its fruit is sweet.",
        "Believe you can, and you're halfway there.",
        "Small steps every day add up to a long journey.",
        "Luck is what happens when preparation meets opportunity.",
        "Be the reason someone smiles today.",
        "Slow down — the moment is the gift.",
        "Your future is created by what you do today.",
        "A beautiful day begins with a beautiful mindset.",
        "Kindness is a language everyone understands.",
        "Today is a good day to try something new.",
        "What you appreciate, appreciates.",
        "Still waters run deep.",
    )

    @Throws(Exception::class)
    private fun httpGet(urlStr: String): String {
        var c: HttpURLConnection? = null
        try {
            c = URL(urlStr).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = true
            c.connectTimeout = 10000
            c.readTimeout = 12000
            c.setRequestProperty("User-Agent", ImageLoader.UA)
            val input = BufferedInputStream(c.inputStream)
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            var total = 0
            var n: Int
            while (input.read(buf).also { n = it } != -1) {
                total += n
                if (total > MAX_BYTES) {
                    throw IOException("fortune response too large")
                }
                bos.write(buf, 0, n)
            }
            input.close()
            return bos.toString("UTF-8")
        } finally {
            c?.disconnect()
        }
    }
}
