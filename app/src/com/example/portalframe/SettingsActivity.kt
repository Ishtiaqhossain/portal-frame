package com.example.portalframe

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Photos setup/settings screen, in Jetpack Compose (migration Milestone 3).
 * Replaces the view-based status screen; reuses the existing prefs
 * ({@link ConfigReceiver}) and hands off to the Java {@link PhotosActivity} for
 * the camera scanner / manual entry (those flows are ported in a later milestone).
 */
class SettingsActivity : ComponentActivity() {

    private val prefs: SharedPreferences
        get() = getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = PortalColors.Blue,
                    onPrimary = PortalColors.OnPrimary,
                    background = PortalColors.Bg,
                    surface = PortalColors.Surface,
                ),
                typography = rememberInterTypography(this),
            ) {
                SettingsScreen()
            }
        }
    }

    private fun openScreensaver() {
        try {
            startActivity(Intent(Settings.ACTION_DREAM_SETTINGS))
        } catch (_: Exception) {
        }
    }

    private fun gotoPhotos(goto: String) {
        startActivity(Intent(this, PhotosActivity::class.java).putExtra("goto", goto))
    }

    private fun isOurScreensaver(): Boolean = try {
        val enabled = Settings.Secure.getInt(contentResolver, "screensaver_enabled", 0) == 1
        val comp = Settings.Secure.getString(contentResolver, "screensaver_components")
        enabled && comp != null && comp.contains(packageName)
    } catch (_: Exception) {
        false
    }

    // ----------------------------------------------------------------- UI

    @Composable
    private fun SettingsScreen() {
        val ctx = LocalContext.current
        var tick by remember { mutableIntStateOf(0) } // bump to recompose after pref writes
        val album = prefs.getString(ConfigReceiver.KEY_ALBUM, "") ?: ""
        val hasAlbum = album.isNotEmpty()
        tick // read so writes that bump it recompose

        Box(
            Modifier.fillMaxSize().background(PortalColors.Bg),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier.widthIn(max = 760.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 40.dp, vertical = 72.dp),
            ) {
                Text(
                    if (hasAlbum) "Your photos" else "Show your Google Photos",
                    color = PortalColors.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(16.dp))

                // Screensaver
                Card("Screensaver") {
                    val active = isOurScreensaver()
                    Body(
                        if (active)
                            "✓ Portal Frame is your screensaver. Your photos appear when the Portal is idle."
                        else
                            "Tap below, then choose “Portal Frame” so your photos show when the Portal is idle.",
                    )
                    Spacer(Modifier.height(12.dp))
                    if (active) OutlineBtn("Change screensaver") { openScreensaver() }
                    else PrimaryBtn("Use as screensaver") { openScreensaver() }
                }

                // Album
                Card(if (hasAlbum) "Album" else "No album yet") {
                    if (hasAlbum) {
                        Text(
                            album, color = PortalColors.Blue, fontSize = 16.sp,
                            maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
                        )
                    } else {
                        Body("Add a Google Photos shared album to show your own photos.")
                    }
                    Spacer(Modifier.height(12.dp))
                    PrimaryBtn(if (hasAlbum) "Change album" else "Add album") { gotoPhotos("scan") }
                    Spacer(Modifier.height(10.dp))
                    OutlineBtn("Enter link manually") { gotoPhotos("manual") }
                    if (hasAlbum) {
                        Spacer(Modifier.height(10.dp))
                        var armed by remember { mutableStateOf(false) }
                        OutlinedButton(
                            onClick = {
                                if (!armed) armed = true
                                else {
                                    prefs.edit().remove(ConfigReceiver.KEY_ALBUM).apply()
                                    tick++
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) {
                            Text(
                                if (armed) "Tap again to confirm" else "Stop showing photos",
                                color = PortalColors.Red, fontSize = 18.sp,
                            )
                        }
                    }
                }

                // Slideshow
                Card("Slideshow") {
                    CycleRow("Seconds per photo", fmtDelay(getLong(ConfigReceiver.KEY_DELAY_MS, 6000))) {
                        val next = cycle(DELAY_CHOICES, getLong(ConfigReceiver.KEY_DELAY_MS, 6000), 0)
                        setLong(ConfigReceiver.KEY_DELAY_MS, next); tick++
                    }
                    Divider()
                    ToggleRow("Shuffle photos", ConfigReceiver.KEY_SHUFFLE, false) { tick++ }
                    Divider()
                    CycleRow("Transition", fadeLabel(getLong(ConfigReceiver.KEY_FADE_MS, 1200))) {
                        val next = cycle(FADE_CHOICES, getLong(ConfigReceiver.KEY_FADE_MS, 1200), 1)
                        setLong(ConfigReceiver.KEY_FADE_MS, next); tick++
                    }
                    Divider()
                    ToggleRow("Side-by-side portraits", ConfigReceiver.KEY_PAIRS, true) { tick++ }
                    Divider()
                    ToggleRow("Cinematic motion", ConfigReceiver.KEY_KEN_BURNS, true) { tick++ }
                    Divider()
                    ToggleRow("Photo captions", ConfigReceiver.KEY_CAPTIONS, true) { tick++ }
                }

                // Ambient intelligence
                Card("Ambient intelligence") {
                    ToggleRow("Face-aware framing", ConfigReceiver.KEY_FACE, true) { tick++ }
                    Divider()
                    ToggleRow("Auto-enhance photos", ConfigReceiver.KEY_ENHANCE, true) { tick++ }
                    Divider()
                    ToggleRow("Ambient color glow", ConfigReceiver.KEY_AMBIENT, true) { tick++ }
                    Divider()
                    ToggleRow("Clock & weather", ConfigReceiver.KEY_CLOCK, true) { tick++ }
                    Divider()
                    ToggleRow("Night warmth", ConfigReceiver.KEY_NIGHT, true) { tick++ }
                    Divider()
                    ToggleRow("On This Day memories", ConfigReceiver.KEY_ON_THIS_DAY, true) { tick++ }
                }

                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { finish() },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PortalColors.Surface),
                ) { Text("Done", color = PortalColors.Text, fontSize = 18.sp) }
            }
        }
    }

    @Composable
    private fun Card(title: String, content: @Composable () -> Unit) {
        Column(
            Modifier.fillMaxWidth().padding(top = 16.dp)
                .clip(RoundedCornerShape(20.dp)).background(PortalColors.Surface)
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Text(
                title.uppercase(), color = PortalColors.TextMuted, fontSize = 13.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }

    @Composable
    private fun Body(text: String) =
        Text(text, color = PortalColors.TextBody, fontSize = 17.sp)

    @Composable
    private fun Divider() = Box(
        Modifier.fillMaxWidth().height(1.dp).padding(vertical = 0.dp).background(PortalColors.Hairline),
    )

    @Composable
    private fun PrimaryBtn(label: String, onClick: () -> Unit) = Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(64.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PortalColors.Blue),
    ) { Text(label, color = PortalColors.OnPrimary, fontSize = 18.sp) }

    @Composable
    private fun OutlineBtn(label: String, onClick: () -> Unit) = OutlinedButton(
        onClick = onClick, modifier = Modifier.fillMaxWidth().height(56.dp),
    ) { Text(label, color = PortalColors.Text, fontSize = 18.sp) }

    @Composable
    private fun ToggleRow(label: String, key: String, def: Boolean, onChanged: () -> Unit) {
        var on by remember(key) { mutableStateOf(prefs.getBoolean(key, def)) }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = PortalColors.Text, fontSize = 18.sp, modifier = Modifier.weight(1f))
            Switch(
                checked = on,
                onCheckedChange = {
                    on = it
                    prefs.edit().putBoolean(key, it).apply()
                    onChanged()
                },
                colors = SwitchDefaults.colors(checkedTrackColor = PortalColors.Blue),
            )
        }
    }

    @Composable
    private fun CycleRow(label: String, value: String, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = PortalColors.Text, fontSize = 18.sp, modifier = Modifier.weight(1f))
            Text("$value  ›", color = PortalColors.Blue, fontSize = 18.sp)
        }
    }

    // ----------------------------------------------------------------- prefs/helpers

    private fun getLong(key: String, def: Long) = prefs.getLong(key, def)
    private fun setLong(key: String, v: Long) = prefs.edit().putLong(key, v).apply()

    companion object {
        private val DELAY_CHOICES = longArrayOf(4000, 6000, 10000, 30000, 60000)
        private val FADE_CHOICES = longArrayOf(2000, 1200, 500)
        private val FADE_LABELS = arrayOf("Slow", "Normal", "Fast")

        private fun cycle(choices: LongArray, cur: Long, fallback: Int): Long {
            for (i in choices.indices) if (choices[i] == cur) return choices[(i + 1) % choices.size]
            return choices[fallback]
        }

        private fun fadeLabel(ms: Long): String {
            for (i in FADE_CHOICES.indices) if (FADE_CHOICES[i] == ms) return FADE_LABELS[i]
            return "Normal"
        }

        private fun fmtDelay(ms: Long): String {
            val s = ms / 1000
            return if (s >= 60) "${s / 60}m" else "${s}s"
        }
    }
}
