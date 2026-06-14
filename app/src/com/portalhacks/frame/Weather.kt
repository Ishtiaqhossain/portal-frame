package com.portalhacks.frame

import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * Tiny dependency-free current-weather lookup for the clock overlay. No API key:
 * approximate location comes from the IP (GeoJS) and the reading from Open-Meteo.
 * Both endpoints are HTTPS, so no cleartext-traffic manifest change is needed.
 * Best-effort — returns null on any failure and the clock simply shows no weather.
 */
internal object Weather {

    private const val TAG = "PortalFrame"

    /** A current reading: a weather emoji plus a rounded temperature in degrees. */
    class Now(
        @JvmField val emoji: String,
        @JvmField val temp: Int,
        @JvmField val moon: Boolean, // clear/mainly-clear at night → draw a blue crescent
    ) {
        /** e.g. "☀️ 72°" */
        fun label(): String = "$emoji $temp°"
    }

    @JvmStatic
    fun fetch(fahrenheit: Boolean): Now? {
        return try {
            val geo = JSONObject(httpGet("https://get.geojs.io/v1/ip/geo.json"))
            val lat = geo.optString("latitude", "")
            val lon = geo.optString("longitude", "")
            if (lat.isEmpty() || lon.isEmpty()) {
                return null
            }
            val url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat +
                "&longitude=" + lon +
                "&current=temperature_2m,weather_code,is_day" +
                "&temperature_unit=" + (if (fahrenheit) "fahrenheit" else "celsius")
            val cur = JSONObject(httpGet(url)).getJSONObject("current")
            val t = cur.getDouble("temperature_2m")
            val code = cur.optInt("weather_code", 0)
            val day = cur.optInt("is_day", 1) == 1
            val moon = !day && (code == 0 || code == 1) // clear / mainly-clear night
            Now(emojiFor(code, day), t.roundToInt(), moon)
        } catch (e: Exception) {
            Log.w(TAG, "weather fetch failed", e)
            null
        }
    }

    /** Map a WMO weather code (Open-Meteo) to a single emoji, day/night aware. */
    private fun emojiFor(c: Int, day: Boolean): String {
        if (c == 0) return if (day) "☀️" else "🌙"        // clear sky → moon at night
        if (c == 1) return if (day) "🌤️" else "🌙"       // mainly clear
        if (c == 2) return if (day) "⛅" else "☁️"          // partly cloudy
        if (c == 3) return "☁️"                    // overcast
        if (c == 45 || c == 48) return "🌫️"  // fog
        if (c in 51..57) return "🌦️"  // drizzle
        if (c in 61..67) return "🌧️"  // rain
        if (c in 71..77) return "❄️"        // snow
        if (c in 80..82) return "🌦️"  // rain showers
        if (c == 85 || c == 86) return "🌨️"  // snow showers
        if (c >= 95) return "⛈️"                   // thunderstorm
        return "🌡️"                          // fallback: thermometer
    }

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
            var n: Int
            while (input.read(buf).also { n = it } != -1) {
                bos.write(buf, 0, n)
            }
            input.close()
            return bos.toString("UTF-8")
        } finally {
            c?.disconnect()
        }
    }
}
