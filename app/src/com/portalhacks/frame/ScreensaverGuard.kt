package com.portalhacks.frame

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log

/**
 * Helpers for owning the system screensaver ("dream") slot.
 *
 * On Portal / Portal+ the Aloha launcher rewrites `screensaver_components` back to its
 * own HomeDreamService whenever HomeActivity is (re)created — e.g. on a screen rotation —
 * silently undoing the user's choice of Frame. There is no dream-priority API, so the
 * only way to make the choice stick is to write it back ourselves whenever it changes.
 *
 * Writing secure settings needs WRITE_SECURE_SETTINGS, a privileged permission that
 * cannot be granted from the UI. Grant it once over ADB:
 *
 *   adb shell pm grant com.portalhacks.frame android.permission.WRITE_SECURE_SETTINGS
 *
 * With that grant in place, [ScreensaverGuardService] re-asserts Frame as the dream
 * whenever the setting is changed away from us. Without it, [claim] is a no-op and the
 * UI falls back to the system Screen-saver picker (which the launcher can still reset).
 */
object Screensaver {
    const val COMPONENT = "com.portalhacks.frame/.FrameDreamService"

    /**
     * The Immortal launcher (github.com/starbrightlab/immortal). It owns the system dream
     * aggressively — re-asserting it on every home resume — and, when its own screensaver is
     * turned off, it disables the whole dream via `screensaver_enabled` (leaving
     * `screensaver_components` alone). Frame watches that extra signal only when Immortal is
     * present so it can still run as the screensaver; see [ScreensaverGuardService].
     */
    const val IMMORTAL_PACKAGE = "com.immortal.launcher"

    private const val ENABLED = "screensaver_enabled"
    private const val COMPONENTS = "screensaver_components"
    private const val TAG = "PortalFrame"

    /** True if the Immortal launcher is installed (it manages the dream itself). */
    fun immortalInstalled(ctx: Context): Boolean = try {
        // Frame targets API 29, so package visibility filtering doesn't apply — no <queries> needed.
        ctx.packageManager.getPackageInfo(IMMORTAL_PACKAGE, 0)
        true
    } catch (_: Exception) {
        false
    }

    /** True if Frame is the enabled system screensaver. */
    fun isOurs(ctx: Context): Boolean = try {
        val enabled = Settings.Secure.getInt(ctx.contentResolver, ENABLED, 0) == 1
        val comp = Settings.Secure.getString(ctx.contentResolver, COMPONENTS)
        enabled && comp != null && comp.contains(ctx.packageName)
    } catch (_: Exception) {
        false
    }

    /** True if we hold WRITE_SECURE_SETTINGS (granted once over ADB). */
    fun canWrite(ctx: Context): Boolean =
        ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Make Frame the screensaver. Returns true if applied. No-op returning false
     * without WRITE_SECURE_SETTINGS — callers fall back to the system picker.
     */
    fun claim(ctx: Context): Boolean {
        if (!canWrite(ctx)) return false
        return try {
            Settings.Secure.putString(ctx.contentResolver, COMPONENTS, COMPONENT)
            Settings.Secure.putInt(ctx.contentResolver, ENABLED, 1)
            true
        } catch (e: Exception) {
            Log.w(TAG, "screensaver claim failed", e)
            false
        }
    }
}

/**
 * Foreground service that keeps Frame as the screensaver. It registers a
 * [ContentObserver] on `screensaver_components`; whenever the launcher (or anything)
 * points the dream slot elsewhere, it writes Frame back. START_STICKY plus a
 * BOOT_COMPLETED start ([BootReceiver]) keep it running across kills and reboots.
 *
 * Only meaningful with WRITE_SECURE_SETTINGS granted (see [Screensaver]); started
 * only when the user has opted in via [ConfigReceiver.KEY_GUARD].
 */
class ScreensaverGuardService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var observer: ContentObserver? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())

        val obs = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                val ctx = this@ScreensaverGuardService
                if (!Screensaver.isOurs(ctx) && Screensaver.claim(ctx)) {
                    Log.i(TAG, "guard: reclaimed the screensaver slot")
                }
            }
        }
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor("screensaver_components"), false, obs,
        )
        // The Immortal launcher turns the system dream OFF via `screensaver_enabled` (it leaves
        // `screensaver_components` alone when its own screensaver is off), so Frame would never
        // notice and its dream would stay disabled. Watch that key too, but ONLY when Immortal is
        // installed — on the stock launcher this observer isn't registered, so behaviour there is
        // exactly as before (no regression).
        if (Screensaver.immortalInstalled(this)) {
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor("screensaver_enabled"), false, obs,
            )
            Log.i(TAG, "guard: Immortal detected — also watching screensaver_enabled")
        }
        observer = obs

        // Claim immediately in case the slot was already taken before we started.
        Screensaver.claim(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Screensaver.claim(this)
        return START_STICKY
    }

    override fun onDestroy() {
        observer?.let { contentResolver.unregisterContentObserver(it) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL, "Screensaver", NotificationManager.IMPORTANCE_MIN,
                    ).apply { description = "Keeps Frame set as the Portal screensaver." },
                )
            }
        }
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Frame screensaver active")
            .setContentText("Keeping Frame as your Portal screensaver.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "PortalFrame"
        private const val CHANNEL = "screensaver_guard"
        private const val NOTIF_ID = 42

        /** Start the guard immediately (caller has just opted in / claimed). */
        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, ScreensaverGuardService::class.java))
        }

        /** Start the guard only if the user opted in and we can write settings. */
        fun startIfEnabled(ctx: Context) {
            val prefs = ctx.getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(ConfigReceiver.KEY_GUARD, false)) return
            if (!Screensaver.canWrite(ctx)) return
            start(ctx)
        }

        /** Stop the guard (the launcher may then reclaim the dream slot). */
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ScreensaverGuardService::class.java))
        }
    }
}
