package com.portalhacks.frame

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Fetches photos from a public iCloud **Shared Album** link via Apple's
 * (undocumented but stable) `sharedstreams` web API:
 *
 *  1. POST `webstream` to find the album's server partition (Apple replies 330 with the
 *     real host) and list its photos + per-photo derivative checksums + the album name.
 *  2. POST `webasseturls` with the photo GUIDs to resolve each derivative to a download URL.
 *
 * Public shared albums only (a `https://www.icloud.com/sharedalbum/#<token>` or
 * `https://share.icloud.com/photos/<token>` link). Fails closed (empty/throws) like the
 * Google provider so callers fall back to the bundled samples.
 */
internal object ApplePhotosSource : PhotoProvider {

    private const val TAG = "PortalFrame"
    private const val MAX_JSON_BYTES = 16 * 1024 * 1024
    private const val GUID_BATCH = 25 // bound each webasseturls request

    override val displayName = "iCloud"

    override fun matches(url: String): Boolean =
        url.startsWith("https://www.icloud.com/sharedalbum/") ||
            url.startsWith("https://share.icloud.com/photos/")

    @Throws(Exception::class)
    override fun fetch(url: String): Album {
        val token = extractToken(url) ?: throw IOException("no iCloud album token in URL")
        var base = "https://p01-sharedstreams.icloud.com/$token/sharedstreams/"
        // Apple 330-redirects to the partition this album actually lives on; the target host
        // comes back in the X-Apple-MMe-Host header (and sometimes also in the JSON body).
        for (attempt in 0 until 3) {
            val (code, body, mmeHost) = post(base + "webstream", "{\"streamCtag\":null}")
            if (code == 330) {
                val host = mmeHost.ifEmpty {
                    try {
                        JSONObject(body).optString("X-Apple-MMe-Host", "")
                    } catch (e: Exception) {
                        ""
                    }
                }
                if (host.isEmpty()) throw IOException("iCloud redirect missing host")
                base = "https://$host/$token/sharedstreams/"
                continue
            }
            if (code != 200) throw IOException("iCloud webstream http $code")
            return parseAlbum(base, JSONObject(body))
        }
        throw IOException("iCloud partition redirect loop")
    }

    private class Item(
        val checksum: String,
        val width: Int,
        val height: Int,
        val timeMs: Long,
    )

    private fun parseAlbum(base: String, ws: JSONObject): Album {
        val title = ws.optString("streamName", "")
        val photosArr = ws.optJSONArray("photos") ?: JSONArray()

        val items = ArrayList<Item>()
        val guids = ArrayList<String>()
        var videos = 0
        for (i in 0 until photosArr.length()) {
            val p = photosArr.optJSONObject(i) ?: continue
            if (p.optString("mediaAssetType") == "video") {
                videos++
                continue
            }
            val guid = p.optString("photoGuid", "")
            val derivs = p.optJSONObject("derivatives") ?: continue
            // Pick the largest derivative (most pixels per module / sharpest on screen).
            var checksum = ""
            var bw = 0
            var bh = 0
            val keys = derivs.keys()
            while (keys.hasNext()) {
                val d = derivs.optJSONObject(keys.next()) ?: continue
                val w = d.optString("width").toIntOrNull() ?: 0
                val h = d.optString("height").toIntOrNull() ?: 0
                val cs = d.optString("checksum", "")
                if (cs.isNotEmpty() && w * h >= bw * bh) {
                    checksum = cs; bw = w; bh = h
                }
            }
            if (guid.isEmpty() || checksum.isEmpty()) continue
            guids.add(guid)
            items.add(Item(checksum, bw, bh, parseDate(p.optString("dateCreated"))))
        }

        // Resolve checksum -> download URL via webasseturls (batched).
        val urlByChecksum = HashMap<String, String>()
        for (batch in guids.chunked(GUID_BATCH)) {
            resolveUrls(base, batch, urlByChecksum)
        }

        val slides = ArrayList<Slide>()
        for (it in items) {
            val u = urlByChecksum[it.checksum] ?: continue
            slides.add(Slide(u, null, it.timeMs, it.height > it.width))
        }
        if (videos > 0) Log.i(TAG, "skipped $videos video(s)")
        Log.i(TAG, "iCloud album: ${slides.size} photos, title='$title'")
        return Album(title, slides)
    }

    private fun resolveUrls(base: String, guids: List<String>, out: MutableMap<String, String>) {
        val body = JSONObject().put("photoGuids", JSONArray(guids)).toString()
        val (code, resp, _) = post(base + "webasseturls", body)
        if (code != 200) throw IOException("iCloud webasseturls http $code")
        val j = JSONObject(resp)
        val items = j.optJSONObject("items") ?: return
        val locations = j.optJSONObject("locations") ?: return
        val keys = items.keys()
        while (keys.hasNext()) {
            val checksum = keys.next()
            val item = items.optJSONObject(checksum) ?: continue
            val loc = locations.optJSONObject(item.optString("url_location")) ?: continue
            val scheme = loc.optString("scheme", "https")
            val host = loc.optJSONArray("hosts")?.optString(0) ?: continue
            val path = item.optString("url_path")
            if (host.isNotEmpty() && path.isNotEmpty()) {
                out[checksum] = "$scheme://$host$path"
            }
        }
    }

    /** Token is the bit after `#` (fragment form) or after `/photos/` (share.icloud.com form). */
    private fun extractToken(url: String): String? {
        val hash = url.indexOf('#')
        if (hash in 0 until url.length - 1) {
            return url.substring(hash + 1).takeWhile { it.isLetterOrDigit() }.ifEmpty { null }
        }
        val marker = "/photos/"
        val idx = url.indexOf(marker)
        if (idx >= 0) {
            return url.substring(idx + marker.length).takeWhile { it.isLetterOrDigit() }.ifEmpty { null }
        }
        return null
    }

    /** Parse Apple's ISO `dateCreated` (e.g. 2024-04-11T10:30:00Z) to epoch millis. */
    private fun parseDate(s: String?): Long {
        if (s.isNullOrEmpty() || s.length < 19) return Slide.NO_DATE
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            fmt.parse(s.substring(0, 19))?.time ?: Slide.NO_DATE
        } catch (e: Exception) {
            Slide.NO_DATE
        }
    }

    /** POST [body] as JSON; returns (status, responseBody, X-Apple-MMe-Host header or ""). */
    private fun post(urlStr: String, body: String): Triple<Int, String, String> {
        if (!urlStr.startsWith("https://")) throw IOException("refusing non-HTTPS iCloud URL")
        var c: HttpURLConnection? = null
        try {
            c = URL(urlStr).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.instanceFollowRedirects = false // handle Apple's 330 partition redirect ourselves
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("User-Agent", ImageLoader.UA)
            c.setRequestProperty("Origin", "https://www.icloud.com")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val mmeHost = c.getHeaderField("X-Apple-MMe-Host") ?: ""
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.let { readCapped(it) } ?: ""
            Log.i(TAG, "iCloud http $code -> $urlStr")
            return Triple(code, text, mmeHost)
        } finally {
            c?.disconnect()
        }
    }

    private fun readCapped(stream: InputStream): String {
        BufferedInputStream(stream).use { input ->
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            var n: Int
            while (input.read(buf).also { n = it } != -1) {
                total += n
                if (total > MAX_JSON_BYTES) throw IOException("iCloud response exceeds $MAX_JSON_BYTES bytes")
                bos.write(buf, 0, n)
            }
            return bos.toString("UTF-8")
        }
    }
}
