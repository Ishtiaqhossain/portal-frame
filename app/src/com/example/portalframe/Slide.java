package com.example.portalframe;

/**
 * One slideshow item: an image id (asset path or URL) plus an optional caption
 * (e.g. the photo's date / location) shown in the lower-right corner, and the
 * capture instant in epoch millis (timezone-adjusted to the photo's local wall
 * clock, or {@link #NO_DATE} when unknown) used for the "On this day" feature.
 */
public class Slide {
    /** Sentinel for "no capture date available" (e.g. bundled sample slides). */
    public static final long NO_DATE = Long.MIN_VALUE;

    public final String id;
    public final String caption; // may be null
    public final long timeMs;    // capture instant (tz-adjusted) or NO_DATE

    public Slide(String id, String caption) {
        this(id, caption, NO_DATE);
    }

    public Slide(String id, String caption, long timeMs) {
        this.id = id;
        this.caption = caption;
        this.timeMs = timeMs;
    }
}
