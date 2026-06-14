package com.portalhacks.frame

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import java.util.Calendar

/**
 * Full-screen interactive slideshow.
 *
 * When a Google Photos album is configured we start straight from it — the last
 * fetched photo list is cached in prefs and the images are disk-cached by
 * [ImageLoader], so the first frame is an album photo (no bundled-sample
 * flash). A fresh fetch then runs in the background, and the album is
 * re-checked periodically so newly added photos appear during a long session.
 * Bundled samples are only shown when no album is configured.
 */
class MainActivity : Activity() {

    private lateinit var loader: ImageLoader
    private lateinit var controller: SlideshowController
    private val handler = Handler(Looper.getMainLooper())

    private var albumUrl = ""
    private var currentIds: List<String> = ArrayList() // ids currently shown

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
        )

        val root = FrameLayout(this)
        setContentView(root)

        loader = ImageLoader(this)
        controller = SlideshowController(this, root, loader)
        controller.setOnDismiss {
            val home = Intent(Intent.ACTION_MAIN)
            home.addCategory(Intent.CATEGORY_HOME)
            home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(home)
            finish()
        }
        // Portal's launcher won't show sideloaded app icons, so long-press the
        // slideshow to reach the Photos setup screen (pick album / settings).
        controller.setOnSettings {
            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }

    override fun onResume() {
        super.onResume()
        // Clear any photo retained from a previous run so re-entering the frame
        // doesn't flash the old image before the first new frame loads.
        controller.blank()
        startDimming() // ease screen brightness down at night, up in the morning
        val prefs = getSharedPreferences(ConfigReceiver.PREFS, MODE_PRIVATE)
        albumUrl = prefs.getString(ConfigReceiver.KEY_ALBUM, "") ?: ""

        if (albumUrl.isEmpty()) {
            // No album configured: show the bundled samples.
            controller.start()
            return
        }

        // Album configured: start straight from the cached album if we have it
        // (disk-cached images make the first photo appear near-instantly);
        // otherwise show a black "Loading…" screen — never the samples.
        val cached = AlbumCache.read(prefs, albumUrl)
        if (cached != null && cached.isNotEmpty()) {
            currentIds = idsOf(cached)
            controller.setItems(cached)
        } else {
            currentIds = ArrayList()
            controller.setStatusHint("Loading Google Photos…")
        }

        // Refresh now, then keep checking periodically while we're on screen.
        fetchAndApply(cached == null || cached.isEmpty())
        handler.removeCallbacks(refreshTick)
        handler.postDelayed(refreshTick, REFRESH_INTERVAL_MS)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshTick)
        handler.removeCallbacks(dimTick)
        controller.stop()
    }

    // --- night dimming -------------------------------------------------------

    private val dimTick = object : Runnable {
        override fun run() {
            applyBrightness()
            handler.postDelayed(this, DIM_INTERVAL_MS)
        }
    }

    private fun startDimming() {
        handler.removeCallbacks(dimTick)
        applyBrightness()
        handler.postDelayed(dimTick, DIM_INTERVAL_MS)
    }

    /** Set this window's brightness from the time of day (doesn't touch system settings). */
    private fun applyBrightness() {
        val c = Calendar.getInstance()
        val h = c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60f
        val lp = window.attributes
        lp.screenBrightness = brightnessForHour(h)
        window.attributes = lp
    }

    private val refreshTick = object : Runnable {
        override fun run() {
            fetchAndApply(false)
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    /**
     * Fetch the album in the background; on success persist it and apply it only
     * if the photo set actually changed (avoids a redundant restart/flicker).
     */
    private fun fetchAndApply(showHint: Boolean) {
        val url = albumUrl
        if (url.isEmpty()) {
            return
        }
        loader.executor().execute {
            try {
                val album = GooglePhotosSource.fetch(url)
                val photos = album.slides
                runOnUiThread {
                    // album may have changed while fetching
                    if (url != albumUrl) {
                        return@runOnUiThread
                    }
                    if (photos.isEmpty()) {
                        if (showHint) {
                            controller.setStatusHint(
                                "Album returned no photos (check sharing/link)",
                            )
                        }
                        return@runOnUiThread
                    }
                    AlbumCache.write(
                        getSharedPreferences(ConfigReceiver.PREFS, MODE_PRIVATE),
                        url, photos, album.title,
                    )
                    val newIds = idsOf(photos)
                    if (newIds != currentIds) {
                        currentIds = newIds
                        controller.setItems(photos)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "album fetch failed", e)
                if (showHint) {
                    runOnUiThread {
                        controller.setStatusHint("Couldn't load album — retrying later")
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "PortalFrame"
        private const val REFRESH_INTERVAL_MS = 20 * 60 * 1000L // 20 min
        private const val DIM_INTERVAL_MS = 5 * 60 * 1000L // re-check brightness

        /**
         * Full brightness through the day, eased down to a soft glow overnight so the
         * frame isn't a lighthouse in a dark room. Ramps 21:00→23:00 down and
         * 06:00→08:00 up; deep night 23:00→06:00.
         */
        private fun brightnessForHour(h: Float): Float {
            val day = 1.0f
            val night = 0.07f
            if (h >= 8f && h < 21f) {
                return day
            }
            if (h >= 21f && h < 23f) {
                return lerp(day, night, (h - 21f) / 2f)
            }
            if (h >= 23f || h < 6f) {
                return night
            }
            return lerp(night, day, (h - 6f) / 2f) // 06:00–08:00
        }

        private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

        // --- photo-list cache (prefs) -------------------------------------------

        private fun idsOf(slides: List<Slide>): List<String> {
            val ids = ArrayList<String>(slides.size)
            for (s in slides) {
                ids.add(s.id)
            }
            return ids
        }
    }
}
