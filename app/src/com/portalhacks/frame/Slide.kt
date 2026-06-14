package com.portalhacks.frame

/**
 * One slideshow item: an image id (asset path or URL), an optional caption
 * override (e.g. an "On this day" badge — normal captions are derived at display
 * time from [timeMs]), the capture instant in epoch millis (timezone-adjusted to
 * the photo's local wall clock, or [NO_DATE] when unknown), and whether the photo
 * is portrait (taller than wide) for side-by-side pairing.
 */
class Slide @JvmOverloads constructor(
    @JvmField val id: String,
    @JvmField val caption: String?, // caption override, or null to derive from timeMs
    @JvmField val timeMs: Long = NO_DATE, // capture instant (tz-adjusted) or NO_DATE
    @JvmField val portrait: Boolean = false,
) {
    companion object {
        /** Sentinel for "no capture date available" (e.g. bundled sample slides). */
        const val NO_DATE: Long = Long.MIN_VALUE
    }
}
