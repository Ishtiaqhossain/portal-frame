package com.example.portalframe;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

/**
 * Crossfading slideshow. Items are image IDs — either bundled asset paths
 * ("slides/01.png") or remote URLs ("https://...") — loaded asynchronously via
 * {@link ImageLoader}. The source can be swapped at runtime with
 * {@link #setItems} (used to switch from bundled samples to a Google Photos
 * shared album once it loads).
 *
 * Touch handling is distance-based (works for slow finger swipes):
 *   - drag left  -> next image
 *   - drag right -> previous image
 *   - tap        -> dismiss (runs onDismiss)
 */
public class SlideshowController {

    private static final String TAG = "PortalFrame";
    private static final String SLIDES_DIR = "slides";
    private static final long SWIPE_FADE_MS = 300; // manual swipe fade
    private static final float SWIPE_MIN_DISTANCE = 60f;
    private static final float TAP_SLOP = 30f;
    private static final long TAP_TIMEOUT_MS = 350;
    private static final long LONG_PRESS_MS = 700; // hold to open Photos setup
    private static final long WEATHER_INTERVAL_MS = 30 * 60 * 1000L; // refresh weather

    private final Context context;
    private final ImageLoader loader;
    private final ImageView back;
    private final ImageView front;
    private final TextView status;
    private final TextView info;
    private final TextView clock;
    private final TextView dateLine;
    private final ShimmerView shimmer;
    private final java.text.DateFormat timeFmt;
    private final java.text.SimpleDateFormat dateFmt =
            new java.text.SimpleDateFormat("EEE, MMM d", java.util.Locale.getDefault());
    private final boolean fahrenheit =
            "US".equals(java.util.Locale.getDefault().getCountry());
    private String weatherText = ""; // e.g. "☀️ 72°" once loaded
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int reqW;
    private final int reqH;

    // Smart-shuffle memory: ids shown recently (most-recent first) so a reshuffle
    // doesn't immediately replay them, and the last id to avoid a back-to-back repeat.
    private final LinkedList<String> recentIds = new LinkedList<>();
    private String lastShownId;
    private boolean shimmerHidden;

    // User-tunable settings (read from prefs in the constructor; see PhotosActivity).
    private final long intervalMs;  // time each slide is held
    private final long autoFadeMs;  // auto crossfade duration
    private final boolean shuffle;  // play photos in random order

    private List<Slide> items = new ArrayList<>();
    private boolean remote = false;
    private int index = 0;
    private boolean running = false;
    private long animGen = 0;
    private Runnable onDismiss;
    private Runnable onSettings;

    public SlideshowController(Context context, FrameLayout root, ImageLoader loader) {
        this.context = context;
        this.loader = loader;
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        this.reqW = dm.widthPixels > 0 ? dm.widthPixels : 1280;
        this.reqH = dm.heightPixels > 0 ? dm.heightPixels : 800;

        SharedPreferences prefs =
                context.getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE);
        this.intervalMs = prefs.getLong(
                ConfigReceiver.KEY_DELAY_MS, ConfigReceiver.DEFAULT_DELAY_MS);
        this.autoFadeMs = prefs.getLong(
                ConfigReceiver.KEY_FADE_MS, ConfigReceiver.DEFAULT_FADE_MS);
        this.shuffle = prefs.getBoolean(ConfigReceiver.KEY_SHUFFLE, false);

        root.setBackgroundColor(Color.BLACK);
        back = newImageView();
        front = newImageView();
        front.setAlpha(0f);

        int margin = Ui.dp(context, 28);

        // Gradient scrims so the white system-overlay pills (top) and our caption
        // text (bottom) stay legible over bright photos — per the Portal design rules.
        View topScrim = new View(context);
        topScrim.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x99000000, 0x00000000}));
        FrameLayout.LayoutParams tsp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, Ui.dp(context, 96));
        tsp.gravity = Gravity.TOP;
        topScrim.setLayoutParams(tsp);

        View bottomScrim = new View(context);
        bottomScrim.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xB3000000, 0x00000000}));
        FrameLayout.LayoutParams bsp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, Ui.dp(context, 150));
        bsp.gravity = Gravity.BOTTOM;
        bottomScrim.setLayoutParams(bsp);

        // Loading / error hint — moved to the top so it doesn't fight the clock.
        status = new TextView(context);
        status.setTextColor(Ui.TEXT_MUTED);
        status.setTypeface(Ui.medium(context));
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        status.setShadowLayer(6f, 0f, 1f, Color.BLACK);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        sp.gravity = Gravity.TOP | Gravity.START;
        sp.leftMargin = margin;
        sp.topMargin = Ui.dp(context, 24);
        status.setLayoutParams(sp);

        // Lower-right: photo date (and location when available).
        info = new TextView(context);
        info.setTextColor(0xFFF0F0F0);
        info.setTypeface(Ui.medium(context));
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        info.setShadowLayer(8f, 0f, 1f, Color.BLACK);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        ip.gravity = Gravity.BOTTOM | Gravity.END;
        ip.rightMargin = margin;
        ip.bottomMargin = margin;
        info.setLayoutParams(ip);

        // Bottom-left: a large clock with a "day, date · weather" line beneath it
        // (Nest/Portal photo-frame style). Time uses a clean, AM/PM-free format.
        this.timeFmt = new java.text.SimpleDateFormat(
                android.text.format.DateFormat.is24HourFormat(context) ? "H:mm" : "h:mm",
                java.util.Locale.getDefault());
        clock = new TextView(context);
        clock.setTextColor(Color.WHITE);
        clock.setTypeface(Ui.regular(context));
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 52);
        clock.setShadowLayer(12f, 0f, 2f, Color.BLACK);
        clock.setIncludeFontPadding(false);
        dateLine = new TextView(context);
        dateLine.setTextColor(0xFFF0F0F0);
        dateLine.setTypeface(Ui.medium(context));
        dateLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        dateLine.setShadowLayer(8f, 0f, 1f, Color.BLACK);
        LinearLayout clockBox = new LinearLayout(context);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        clockBox.addView(clock);
        clockBox.addView(dateLine);
        FrameLayout.LayoutParams cbp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        cbp.gravity = Gravity.BOTTOM | Gravity.START;
        cbp.leftMargin = margin;
        cbp.bottomMargin = margin;
        clockBox.setLayoutParams(cbp);

        // Shimmer over the dark first frame so it never looks "stuck" while loading.
        shimmer = new ShimmerView(context);
        shimmer.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        root.addView(back);
        root.addView(front);
        root.addView(shimmer);
        root.addView(topScrim);
        root.addView(bottomScrim);
        root.addView(status);
        root.addView(info);
        root.addView(clockBox);
        root.addView(buildTouchOverlay());

        // Run clock + weather + shimmer from the start so they're alive even during
        // the initial "Loading…" wait before the first photo arrives.
        startClock();
        startWeather();
        shimmer.startSweep();
    }

    public void setOnDismiss(Runnable onDismiss) {
        this.onDismiss = onDismiss;
    }

    /** Long-press anywhere on the slideshow runs this (used to open Photos setup). */
    public void setOnSettings(Runnable onSettings) {
        this.onSettings = onSettings;
    }

    public void setStatusHint(String text) {
        status.setText(text);
    }

    private ImageView newImageView() {
        ImageView iv = new ImageView(context);
        iv.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return iv;
    }

    private View buildTouchOverlay() {
        View overlay = new View(context);
        overlay.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        overlay.setClickable(true);
        overlay.setFocusable(true);
        overlay.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private long downTime;
            private boolean handled;
            private Runnable pendingLong;

            private void cancelLong() {
                if (pendingLong != null) {
                    handler.removeCallbacks(pendingLong);
                    pendingLong = null;
                }
            }

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getX();
                        downY = e.getY();
                        downTime = e.getEventTime();
                        handled = false;
                        if (v.getParent() != null) {
                            v.getParent().requestDisallowInterceptTouchEvent(true);
                        }
                        cancelLong();
                        if (onSettings != null) {
                            pendingLong = new Runnable() {
                                @Override
                                public void run() {
                                    pendingLong = null;
                                    handled = true; // suppress the tap-dismiss on release
                                    onSettings.run();
                                }
                            };
                            handler.postDelayed(pendingLong, LONG_PRESS_MS);
                        }
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!handled) {
                            float dx = e.getX() - downX;
                            float dy = e.getY() - downY;
                            if (Math.abs(dx) > TAP_SLOP || Math.abs(dy) > TAP_SLOP) {
                                cancelLong(); // finger moved — not a long press
                            }
                            if (Math.abs(dx) > SWIPE_MIN_DISTANCE
                                    && Math.abs(dx) > Math.abs(dy)) {
                                handled = true;
                                if (dx < 0) {
                                    showNext();
                                } else {
                                    showPrevious();
                                }
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP: {
                        cancelLong();
                        float dx = e.getX() - downX;
                        float dy = e.getY() - downY;
                        long dt = e.getEventTime() - downTime;
                        if (!handled) {
                            if (Math.abs(dx) > SWIPE_MIN_DISTANCE
                                    && Math.abs(dx) > Math.abs(dy)) {
                                if (dx < 0) {
                                    showNext();
                                } else {
                                    showPrevious();
                                }
                            } else if (Math.abs(dx) < TAP_SLOP && Math.abs(dy) < TAP_SLOP
                                    && dt < TAP_TIMEOUT_MS && onDismiss != null) {
                                onDismiss.run();
                            }
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_CANCEL:
                        cancelLong();
                        return true;
                    default:
                        return true;
                }
            }
        });
        return overlay;
    }

    public void start() {
        running = true;
        startClock();
        startWeather();
        if (!shimmerHidden) {
            shimmer.startSweep();
        }
        if (items.isEmpty()) {
            items = assetItems();
            remote = false;
            if (shuffle) {
                smartShuffle(items);
            }
        }
        promoteOnThisDay(items); // no-op for bundled samples (no dates)
        if (items.isEmpty()) {
            status.setText("No slides found in assets/" + SLIDES_DIR);
            back.setBackgroundColor(Color.DKGRAY);
            return;
        }
        index = 0;
        Log.i(TAG, "Slideshow started with " + items.size()
                + (remote ? " album photos" : " bundled slides"));
        showImmediate(0);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(autoTick);
        handler.removeCallbacks(clockTick);
        handler.removeCallbacks(weatherTick);
        shimmer.stopSweep();
        front.animate().cancel();
    }

    /** Swap the photo source at runtime (e.g. bundled samples -> album). */
    public void setItems(List<Slide> newItems) {
        if (newItems == null || newItems.isEmpty()) {
            return;
        }
        items = new ArrayList<>(newItems);
        if (shuffle) {
            smartShuffle(items);
        }
        promoteOnThisDay(items);
        remote = true;
        handler.removeCallbacks(autoTick);
        startClock();
        startWeather();
        if (!shimmerHidden) {
            shimmer.startSweep();
        }
        front.animate().cancel();
        front.setAlpha(0f);
        index = 0;
        running = true;
        Log.i(TAG, "Source switched to " + items.size() + " album photos");
        showImmediate(0);
    }

    public void showNext() {
        if (!items.isEmpty()) {
            transitionTo((index + 1) % items.size(), SWIPE_FADE_MS);
        }
    }

    public void showPrevious() {
        if (!items.isEmpty()) {
            transitionTo((index - 1 + items.size()) % items.size(), SWIPE_FADE_MS);
        }
    }

    private void scheduleAuto() {
        handler.removeCallbacks(autoTick);
        if (running && items.size() > 1) {
            handler.postDelayed(autoTick, intervalMs);
        }
    }

    private final Runnable autoTick = new Runnable() {
        @Override
        public void run() {
            if (!running || items.isEmpty()) {
                return;
            }
            int next;
            if (index + 1 >= items.size()) {
                // Wrapped a full loop: reshuffle so the next loop differs and
                // doesn't open with a photo we just showed.
                if (shuffle && items.size() > 2) {
                    smartShuffle(items);
                }
                next = 0;
            } else {
                next = index + 1;
            }
            transitionTo(next, autoFadeMs);
        }
    };

    /** Show item i directly (no crossfade) — used for the first frame. */
    private void showImmediate(final int i) {
        final long gen = ++animGen;
        loader.load(items.get(i).id, reqW, reqH, new ImageLoader.Callback() {
            @Override
            public void onLoaded(Bitmap b) {
                if (gen != animGen) {
                    return;
                }
                if (b != null) {
                    back.setImageBitmap(b);
                    front.setImageDrawable(null);
                    front.setAlpha(0f);
                    index = i;
                    status.setText("");
                    info.setText(captionOf(i));
                    noteShown(i);
                    hideShimmer();
                }
                prefetchNext(i);
                scheduleAuto();
            }
        });
    }

    /** Crossfade to item {@code next}; loads async, safe to call mid-fade. */
    private void transitionTo(final int next, final long fadeMs) {
        if (items.isEmpty()) {
            return;
        }
        handler.removeCallbacks(autoTick);
        final long gen = ++animGen;
        loader.load(items.get(next).id, reqW, reqH, new ImageLoader.Callback() {
            @Override
            public void onLoaded(final Bitmap bmp) {
                if (gen != animGen) {
                    return; // superseded by a newer request
                }
                if (bmp == null) {
                    index = next;
                    scheduleAuto();
                    return;
                }
                front.animate().cancel();
                front.setImageBitmap(bmp);
                front.setAlpha(0f);
                index = next;
                status.setText("");
                info.setText(captionOf(next));
                noteShown(next);
                hideShimmer();
                front.animate().alpha(1f).setDuration(fadeMs).withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != animGen) {
                            return;
                        }
                        back.setImageBitmap(bmp);
                        front.setAlpha(0f);
                        prefetchNext(next);
                        scheduleAuto();
                    }
                });
            }
        });
    }

    private void prefetchNext(int from) {
        if (items.size() > 1) {
            loader.prefetch(items.get((from + 1) % items.size()).id, reqW, reqH);
        }
    }

    private String captionOf(int i) {
        String cap = items.get(i).caption;
        return cap == null ? "" : cap;
    }

    // ---------------------------------------------------------------- smart shuffle

    /** Remember a shown id so a reshuffle de-weights it (most-recent first). */
    private void noteShown(int i) {
        if (items.isEmpty()) {
            return;
        }
        String id = items.get(i).id;
        lastShownId = id;
        recentIds.remove(id);
        recentIds.addFirst(id);
        int cap = Math.max(1, items.size() / 3);
        while (recentIds.size() > cap) {
            recentIds.removeLast();
        }
    }

    /**
     * Shuffle, then push recently shown photos toward the back (stable sort keeps
     * the shuffled order within each group) and make sure we don't open with the
     * photo just shown — so it never feels like "didn't I just see that?".
     */
    private void smartShuffle(List<Slide> list) {
        java.util.Collections.shuffle(list);
        if (recentIds.isEmpty() && lastShownId == null) {
            return;
        }
        final Set<String> recent = new HashSet<>(recentIds);
        java.util.Collections.sort(list, new Comparator<Slide>() {
            @Override
            public int compare(Slide a, Slide b) {
                return Integer.compare(recent.contains(a.id) ? 1 : 0,
                        recent.contains(b.id) ? 1 : 0);
            }
        });
        if (list.size() > 1 && lastShownId != null && list.get(0).id.equals(lastShownId)) {
            int swap = 1;
            for (int i = 1; i < list.size(); i++) {
                if (!recent.contains(list.get(i).id)) {
                    swap = i;
                    break;
                }
            }
            Slide tmp = list.get(0);
            list.set(0, list.get(swap));
            list.set(swap, tmp);
        }
    }

    // ---------------------------------------------------------------- On this day

    /**
     * Move photos taken on today's month+day in a past year to the front (most
     * recent memory first), re-captioned "N years ago today ✨". No-op for items
     * without a capture date (bundled samples).
     */
    private void promoteOnThisDay(List<Slide> list) {
        Calendar now = Calendar.getInstance();
        int curMonth = now.get(Calendar.MONTH);
        int curDay = now.get(Calendar.DAY_OF_MONTH);
        int curYear = now.get(Calendar.YEAR);

        // timeMs is the capture wall-clock expressed in UTC, so read it back in UTC.
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        List<Slide> memories = new ArrayList<>();
        for (Iterator<Slide> it = list.iterator(); it.hasNext(); ) {
            Slide s = it.next();
            if (s.timeMs == Slide.NO_DATE) {
                continue;
            }
            c.setTimeInMillis(s.timeMs);
            if (c.get(Calendar.MONTH) != curMonth || c.get(Calendar.DAY_OF_MONTH) != curDay) {
                continue;
            }
            int yearsAgo = curYear - c.get(Calendar.YEAR);
            if (yearsAgo < 1) {
                continue; // taken today this year, not a memory
            }
            String badge = yearsAgo == 1
                    ? "1 year ago today ✨"
                    : yearsAgo + " years ago today ✨";
            memories.add(new Slide(s.id, badge, s.timeMs));
            it.remove();
        }
        if (memories.isEmpty()) {
            return;
        }
        java.util.Collections.sort(memories, new Comparator<Slide>() {
            @Override
            public int compare(Slide a, Slide b) {
                return Long.compare(b.timeMs, a.timeMs); // most recent first
            }
        });
        list.addAll(0, memories);
        Log.i(TAG, "On this day: promoted " + memories.size() + " memory photo(s)");
    }

    // ---------------------------------------------------------------- clock + date

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            handler.postDelayed(this, 60000 - (System.currentTimeMillis() % 60000));
        }
    };

    private void startClock() {
        handler.removeCallbacks(clockTick);
        updateClock();
        handler.postDelayed(clockTick, 60000 - (System.currentTimeMillis() % 60000));
    }

    private void updateClock() {
        Calendar c = Calendar.getInstance();
        clock.setText(timeFmt.format(c.getTime()));
        String date = dateFmt.format(c.getTime());
        dateLine.setText(weatherText.isEmpty() ? date : date + "   " + weatherText);
    }

    // ---------------------------------------------------------------- weather

    private final Runnable weatherTick = new Runnable() {
        @Override
        public void run() {
            refreshWeather();
            handler.postDelayed(this, WEATHER_INTERVAL_MS);
        }
    };

    private void startWeather() {
        handler.removeCallbacks(weatherTick);
        // Fetch immediately if we have nothing yet; otherwise keep the periodic cadence.
        handler.postDelayed(weatherTick, weatherText.isEmpty() ? 0 : WEATHER_INTERVAL_MS);
    }

    private void refreshWeather() {
        loader.executor().execute(new Runnable() {
            @Override
            public void run() {
                final Weather.Now now = Weather.fetch(fahrenheit);
                if (now == null) {
                    return;
                }
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        weatherText = now.label();
                        updateClock();
                    }
                });
            }
        });
    }

    // ---------------------------------------------------------------- loading shimmer

    private void hideShimmer() {
        if (shimmerHidden) {
            return;
        }
        shimmerHidden = true;
        shimmer.animate().alpha(0f).setDuration(400).withEndAction(new Runnable() {
            @Override
            public void run() {
                shimmer.stopSweep();
                shimmer.setVisibility(View.GONE);
            }
        });
    }

    /** A soft diagonal light band sweeping across a dark surface — a calm "loading". */
    private static final class ShimmerView extends View {
        private final Paint paint = new Paint();
        private final ValueAnimator anim;
        private float pos = -0.3f; // sweep centre as a fraction of width

        ShimmerView(Context c) {
            super(c);
            setBackgroundColor(0xFF1A1A1A);
            anim = ValueAnimator.ofFloat(-0.3f, 1.3f);
            anim.setDuration(1600);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    pos = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
        }

        void startSweep() {
            if (!anim.isStarted()) {
                anim.start();
            }
        }

        void stopSweep() {
            anim.cancel();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w == 0 || h == 0) {
                return;
            }
            float band = w * 0.35f;
            float cx = pos * w;
            paint.setShader(new LinearGradient(cx - band, 0, cx + band, 0,
                    new int[]{0x00FFFFFF, 0x14FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
        }
    }

    private List<Slide> assetItems() {
        List<String> names = new ArrayList<>();
        try {
            AssetManager am = context.getAssets();
            String[] list = am.list(SLIDES_DIR);
            if (list != null) {
                for (String n : list) {
                    String lower = n.toLowerCase();
                    if (lower.endsWith(".png") || lower.endsWith(".jpg")
                            || lower.endsWith(".jpeg") || lower.endsWith(".webp")) {
                        names.add(SLIDES_DIR + "/" + n);
                    }
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to list slides", e);
        }
        java.util.Collections.sort(names);
        List<Slide> found = new ArrayList<>();
        for (String n : names) {
            found.add(new Slide(n, null));
        }
        return found;
    }
}
