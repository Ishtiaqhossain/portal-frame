package com.portalhacks.frame

import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashSet
import java.util.regex.Pattern

/**
 * Fetches photos from a public Google Photos *shared album* link by scraping the
 * share page (the official Library API was deprecated 2025-03-31). The page
 * embeds each media item as a JS array; we segment by media item and, per item,
 * pull the thumbnail URL, classify photo-vs-video, and read the capture date.
 *
 * Unofficial — can break if Google changes the page format. Callers should fall
 * back to bundled images when this returns empty.
 */
internal object GooglePhotosSource {

    private const val TAG = "PortalFrame"
    private const val IMG_WIDTH = 1280

    // Start of each media item: ["<AF1Qip mediaKey>",["https://lh3...
    private val ITEM: Pattern = Pattern.compile(
        "\\[\"AF1Qip[A-Za-z0-9_\\-]+\",\\[\"https://lh3\\.googleusercontent\\.com/",
    )

    // First thumbnail (base url, width, height) within an item.
    private val THUMB: Pattern = Pattern.compile(
        "\"(https://lh3\\.googleusercontent\\.com/[^\"]+?)\",(\\d{2,5}),(\\d{2,5})",
    )

    // Capture time (ms) , "mediaId" , tzOffset(ms)
    private val DATE: Pattern = Pattern.compile(
        ",(\\d{13}),\"[A-Za-z0-9_\\-]+\",(-?\\d{5,9}),",
    )

    // The only reliable POSITIVE video signal: a transcoded download URL on this
    // host. The earlier heuristics (absence of EXIF/filesize, "video-only"
    // metadata keys) were false-positives that discarded ~95% of a large mixed
    // album as "videos" — see isVideo().
    private const val VIDEO_MARKER = "video-downloads.googleusercontent.com"

    private val SHARE_URL: Pattern = Pattern.compile(
        "(https://photos\\.google\\.com/share/[A-Za-z0-9_\\-]+\\?key=[A-Za-z0-9_\\-]+)",
    )

    // Album name from the share page's Open Graph title (content/property order varies).
    private val OG_TITLE_A: Pattern = Pattern.compile(
        "<meta[^>]+property=\"og:title\"[^>]+content=\"([^\"]*)\"",
    )
    private val OG_TITLE_B: Pattern = Pattern.compile(
        "<meta[^>]+content=\"([^\"]*)\"[^>]+property=\"og:title\"",
    )

    /** An album: its display title (may be empty) and its photos. */
    class Album internal constructor(
        @JvmField val title: String,
        @JvmField val slides: List<Slide>,
    )

    @JvmStatic
    @Throws(Exception::class)
    fun fetch(shareUrl: String): Album {
        var html = httpGet(shareUrl)
        var slides = parse(html)
        if (slides.isEmpty()) {
            val sm = SHARE_URL.matcher(html)
            if (sm.find()) {
                val longUrl = sm.group(1)!!.replace("\\u003d", "=").replace("\\/", "/")
                Log.i(TAG, "following embedded share url: $longUrl")
                html = httpGet(longUrl)
                slides = parse(html)
            }
        }
        val title = parseTitle(html)
        Log.i(TAG, "Google Photos album: ${slides.size} photos, title='$title'")
        return Album(title, slides)
    }

    private fun parseTitle(html: String): String {
        var m = OG_TITLE_A.matcher(html)
        if (!m.find()) {
            m = OG_TITLE_B.matcher(html)
            if (!m.find()) {
                return ""
            }
        }
        val t = m.group(1)!!
            .replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"").trim()
        // The generic site title isn't an album name.
        return if (t.equals("Google Photos", ignoreCase = true)) "" else t
    }

    private fun parse(html: String): List<Slide> {
        val out = ArrayList<Slide>()
        val seen: MutableSet<String> = LinkedHashSet()

        val starts = ArrayList<Int>()
        val im = ITEM.matcher(html)
        while (im.find()) {
            starts.add(im.start())
        }
        var videos = 0
        for (k in starts.indices) {
            val s = starts[k]
            val e = if (k + 1 < starts.size) starts[k + 1] else html.length
            val item = html.substring(s, e)

            val tm = THUMB.matcher(item)
            if (!tm.find()) {
                continue
            }
            val w = tm.group(2)!!.toInt()
            val h = tm.group(3)!!.toInt()
            if (w < 256 || h < 256) {
                continue
            }

            if (isVideo(item)) {
                videos++
                continue
            }

            var base = tm.group(1)!!
            val eq = base.indexOf('=')
            if (eq > 0) {
                base = base.substring(0, eq)
            }
            base = base.replace("\\/", "/")
            if (!seen.add(base)) {
                continue
            }
            val tms = captureMillis(item)
            // Caption is derived at display time (album · relative time); keep only
            // the raw capture instant and the portrait flag here.
            out.add(Slide(base + "=w" + IMG_WIDTH, null, tms, h > w))
        }
        if (videos > 0) {
            Log.i(TAG, "skipped $videos video(s)")
        }
        return out
    }

    /**
     * Treat a media item as a video only on the reliable positive signal — a
     * transcoded `video-downloads` URL. We deliberately do NOT infer "video" from
     * the absence of EXIF/filesize or from "unexpected" metadata keys: those
     * heuristics false-positived on most photos of a large mixed album and dropped
     * them. A video whose marker is lazy-loaded (absent here) just shows its still
     * poster frame, which is fine for a photo frame.
     */
    private fun isVideo(item: String): Boolean = item.contains(VIDEO_MARKER)

    /**
     * Capture instant in epoch millis, shifted by the photo's timezone offset so
     * the value reads as the local wall clock when formatted in UTC. Returns
     * [Slide.NO_DATE] when the item carries no timestamp.
     */
    private fun captureMillis(item: String): Long {
        val dm = DATE.matcher(item)
        if (dm.find()) {
            try {
                val t = dm.group(1)!!.toLong()
                val tz = dm.group(2)!!.toLong()
                return t + tz
            } catch (ignored: NumberFormatException) {
            }
        }
        return Slide.NO_DATE
    }

    // Cap how much of the share page we'll buffer: it's normally a few MB, and an
    // unbounded read of a hostile/oversized response could exhaust memory.
    private const val MAX_HTML_BYTES = 12 * 1024 * 1024

    @Throws(Exception::class)
    private fun httpGet(urlStr: String?): String {
        if (urlStr == null || !urlStr.startsWith("https://")) {
            throw IOException("refusing non-HTTPS album URL")
        }
        var c: HttpURLConnection? = null
        try {
            c = URL(urlStr).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = true
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.setRequestProperty("User-Agent", ImageLoader.UA)
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            val code = c.responseCode
            Log.i(TAG, "album http $code -> ${c.url}")
            val `in` = BufferedInputStream(c.inputStream)
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var n: Int
            var total = 0
            while (`in`.read(buf).also { n = it } != -1) {
                total += n
                if (total > MAX_HTML_BYTES) {
                    `in`.close()
                    throw IOException("album page exceeds $MAX_HTML_BYTES bytes")
                }
                bos.write(buf, 0, n)
            }
            `in`.close()
            return bos.toString("UTF-8")
        } finally {
            c?.disconnect()
        }
    }
}
