package com.portalhacks.frame

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The live slideshow screensaver, hosted in Jetpack Compose.
 *
 * The slideshow's rendering is a deeply imperative, custom-animated View stack —
 * crossfading [android.widget.ImageView]s, a [android.animation.ValueAnimator]
 * Ken Burns engine, a `Canvas` shimmer, an `ImageSpan` weather glyph, distance-based
 * touch gestures — exactly the case Compose's `AndroidView` interop exists for. So we
 * keep the battle-tested [SlideshowController] and bridge it into `setContent`, rather
 * than re-deriving the animation engine in Compose (which would risk regressing the
 * marquee features for no user-facing gain). The album fetch/cache/refresh and
 * night-dimming logic live here in Kotlin.
 *
 * This is the screensaver target ([FrameDreamService] launches it).
 */
class SlideshowComposeActivity : ComponentActivity() {

    private lateinit var loader: ImageLoader
    private lateinit var controller: SlideshowController
    private val handler = Handler(Looper.getMainLooper())

    private var currentAlbums: List<String> = emptyList()
    private var currentIds: List<String> = ArrayList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
        )
        // Honor the Portal's own brightness: don't override the window brightness, so the
        // system's adaptive/manual brightness (and its light sensor) governs the frame.
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }

        loader = ImageLoader(this)
        // Build the slideshow's View hierarchy, then host it inside Compose.
        val root = FrameLayout(this)
        controller = SlideshowController(this, root, loader).apply {
            setOnDismiss {
                val home = Intent(Intent.ACTION_MAIN)
                home.addCategory(Intent.CATEGORY_HOME)
                home.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(home)
                finish()
            }
            // Portal's launcher won't show sideloaded app icons, so long-press the
            // slideshow to reach the setup/settings screen.
            setOnSettings {
                startActivity(Intent(this@SlideshowComposeActivity, SettingsActivity::class.java))
            }
        }
        setContent {
            AndroidView(factory = { root }, modifier = Modifier.fillMaxSize())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            @Suppress("DEPRECATION")
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
        val prefs = getSharedPreferences(ConfigReceiver.PREFS, MODE_PRIVATE)
        currentAlbums = Albums.enabled(prefs)

        if (currentAlbums.isEmpty()) {
            // No albums playing (none configured, or all stopped): show the bundled samples.
            controller.start()
            return
        }

        // Albums configured: start straight from their merged caches if we have them
        // (disk-cached images make the first photo appear near-instantly); otherwise
        // show a black "Loading…" screen — never the samples.
        val cached = currentAlbums.flatMap { AlbumCache.read(prefs, it) ?: emptyList() }
        if (cached.isNotEmpty()) {
            currentIds = idsOf(cached)
            controller.setItems(cached)
        } else {
            currentIds = ArrayList()
            controller.setStatusHint("Loading photos…")
        }

        // Refresh now, then keep checking periodically while we're on screen.
        fetchAllAndApply(cached.isEmpty())
        handler.removeCallbacks(refreshTick)
        handler.postDelayed(refreshTick, REFRESH_INTERVAL_MS)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshTick)
        controller.stop()
    }

    private val refreshTick = object : Runnable {
        override fun run() {
            fetchAllAndApply(false)
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    /**
     * Fetch every configured album in the background; cache each and re-apply the merged
     * photo set as each one lands (only when the set actually changed, to avoid flicker).
     */
    private fun fetchAllAndApply(showHint: Boolean) {
        val albums = currentAlbums
        if (albums.isEmpty()) {
            return
        }
        val prefs = getSharedPreferences(ConfigReceiver.PREFS, MODE_PRIVATE)
        for (url in albums) {
            loader.executor().execute {
                try {
                    val album = PhotoSources.fetch(url)
                    if (album.slides.isNotEmpty()) {
                        AlbumCache.write(prefs, url, album.slides, album.title)
                    }
                    runOnUiThread { rebuildFromCaches(showHint) }
                } catch (e: Exception) {
                    Log.e(TAG, "album fetch failed: $url", e)
                    if (showHint) {
                        runOnUiThread { rebuildFromCaches(true) }
                    }
                }
            }
        }
    }

    /** Recompute the slideshow from all albums' caches and apply it if it changed. */
    private fun rebuildFromCaches(showHint: Boolean) {
        val prefs = getSharedPreferences(ConfigReceiver.PREFS, MODE_PRIVATE)
        if (currentAlbums != Albums.enabled(prefs)) {
            return // the playing album set changed while fetching
        }
        val merged = currentAlbums.flatMap { AlbumCache.read(prefs, it) ?: emptyList() }
        if (merged.isEmpty()) {
            if (showHint) controller.setStatusHint("Couldn't load photos — retrying later")
            return
        }
        val ids = idsOf(merged)
        if (ids != currentIds) {
            currentIds = ids
            controller.setItems(merged)
        }
    }

    companion object {
        private const val TAG = "PortalFrame"
        private const val REFRESH_INTERVAL_MS = 20 * 60 * 1000L // 20 min

        private fun idsOf(slides: List<Slide>): List<String> {
            val ids = ArrayList<String>(slides.size)
            for (s in slides) {
                ids.add(s.id)
            }
            return ids
        }
    }
}
