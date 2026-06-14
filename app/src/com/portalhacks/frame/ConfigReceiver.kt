package com.portalhacks.frame

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Lets the Google Photos shared-album URL be set over ADB without rebuilding:
 *
 *   adb shell am broadcast -n com.portalhacks.frame/.ConfigReceiver \
 *       --es url "https://photos.app.goo.gl/XXXXXXXX"
 *
 * Clear it (revert to bundled samples) with:
 *   adb shell am broadcast -n com.portalhacks.frame/.ConfigReceiver --es url ""
 */
class ConfigReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent?) {
        if (intent == null) {
            return
        }
        val ed = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        var any = false
        if (intent.hasExtra("url")) {
            val url = intent.getStringExtra("url")?.trim() ?: ""
            // This receiver is exported (so the album can be set over ADB), which means
            // any installed app could broadcast to it. Only persist an empty value
            // (clears the album, reverting to the bundled samples) or a real Google
            // Photos HTTPS link; ignore anything else so a hostile broadcast can't point
            // the frame at an arbitrary URL.
            if (url.isEmpty() || isAlbumUrl(url)) {
                ed.putString(KEY_ALBUM, url)
                Log.i("PortalFrame", "album_url set to: '$url'")
                any = true
            } else {
                Log.w("PortalFrame", "ignoring non-Google-Photos album_url")
            }
        }
        for (e in BOOL_EXTRAS) {
            if (intent.hasExtra(e[0])) {
                val value = intent.getBooleanExtra(e[0], true)
                ed.putBoolean(e[1], value)
                Log.i("PortalFrame", "${e[1]} set to: $value")
                any = true
            }
        }
        if (any) {
            ed.apply()
        }
    }

    companion object {
        const val PREFS = "portalframe"
        const val KEY_ALBUM = "album_url"

        // Slideshow settings (written by PhotosActivity, read by SlideshowController).
        const val KEY_DELAY_MS = "delay_ms"     // ms each photo is held
        const val KEY_SHUFFLE = "shuffle"       // boolean: random order
        const val KEY_FADE_MS = "fade_ms"       // ms auto crossfade duration
        const val KEY_PAIRS = "pairs"           // boolean: pair portraits side-by-side
        const val KEY_KEN_BURNS = "ken_burns"   // boolean: cinematic pan/zoom
        const val KEY_CLOCK = "clock"           // boolean: clock + weather overlay
        const val KEY_NIGHT = "night"           // boolean: warm night dimming
        const val KEY_ON_THIS_DAY = "on_this_day" // boolean: surface memories
        const val KEY_CAPTIONS = "captions"     // boolean: photo date captions
        const val KEY_FACE = "face_framing"     // boolean: face-aware Ken Burns target
        const val KEY_AMBIENT = "ambient_color" // boolean: per-photo color glow
        const val KEY_ENHANCE = "auto_enhance"  // boolean: on-device auto-levels + vibrance
        const val DEFAULT_DELAY_MS = 6000L
        const val DEFAULT_FADE_MS = 1200L
        const val DEFAULT_PAIRS = true
        const val DEFAULT_KEN_BURNS = true
        const val DEFAULT_CLOCK = true
        const val DEFAULT_NIGHT = true
        const val DEFAULT_ON_THIS_DAY = true
        const val DEFAULT_CAPTIONS = true
        const val DEFAULT_FACE = true
        const val DEFAULT_AMBIENT = true
        const val DEFAULT_ENHANCE = true

        // ADB-settable boolean extras (extra name -> pref key) for quick testing, e.g.
        //   adb shell am broadcast -n com.portalhacks.frame/.ConfigReceiver --ez ken_burns false
        private val BOOL_EXTRAS = arrayOf(
            arrayOf("shuffle", KEY_SHUFFLE), arrayOf("pairs", KEY_PAIRS), arrayOf("ken_burns", KEY_KEN_BURNS),
            arrayOf("clock", KEY_CLOCK), arrayOf("night", KEY_NIGHT), arrayOf("on_this_day", KEY_ON_THIS_DAY),
            arrayOf("captions", KEY_CAPTIONS), arrayOf("face_framing", KEY_FACE), arrayOf("ambient_color", KEY_AMBIENT),
            arrayOf("auto_enhance", KEY_ENHANCE),
        )

        // Cached photo list so the screensaver starts straight from the album (no
        // bundled-sample flash). KEY_PHOTO_CACHE_URL records which album it's for.
        // v3: bumped for the new per-photo schema (capture time + portrait flag) and
        // the album title; captions are now derived at display time, not cached.
        const val KEY_PHOTO_CACHE = "photo_cache_v3"
        const val KEY_PHOTO_CACHE_URL = "photo_cache_url_v3"
        const val KEY_ALBUM_TITLE = "album_title_v3"

        /** True for a Google Photos shared-album HTTPS link (the only URLs we'll fetch). */
        fun isAlbumUrl(s: String?): Boolean =
            s != null &&
                (s.startsWith("https://photos.app.goo.gl/") ||
                    s.startsWith("https://photos.google.com/share/"))
    }
}
