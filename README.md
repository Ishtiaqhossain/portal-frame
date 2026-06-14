# Frame

An open-source slideshow **screensaver for the Meta Portal Go** (Android 10 / API 29) that shows
your **Google Photos** shared albums. Set it up on-device by scanning the album's QR code (or
pasting the link), and it plays your photos whenever the Portal is idle — with a clock, captions,
cinematic motion, and ambient color.

> Repo name: **PortalFrame** (`com.portalhacks.frame`). The app displays as **Frame**.

## Install it on a Portal

Download the latest `Frame-*.apk` from **[Releases](https://github.com/Ishtiaqhossain/Portal-Frame/releases/latest)**,
then follow the **[Install & User Guide](INSTALL.md)** (install the APK, add your album, turn it on
as the screensaver).

## Features

- Plays a public Google Photos **shared album**; new photos appear automatically.
- On-device setup: **QR scan** or paste the link — no computer needed after install.
- Clock & weather, photo date captions, shuffle, adjustable timing and transitions.
- Side-by-side portraits, cinematic pan/zoom, auto-enhance, ambient color, night dimming,
  and "On This Day" memories — all toggleable.
- Touch: **swipe** to change photo, **tap** to dismiss, **long-press** to open setup.

## For developers

Kotlin app (Jetpack Compose settings UI + Android Views slideshow), built with Gradle:

```
./gradlew assembleDebug      # -> app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17–21 and an Android SDK (`ANDROID_SDK_ROOT`, or a git-ignored `local.properties`
with `sdk.dir=…`). See **[CONTRIBUTING.md](CONTRIBUTING.md)** for details and project layout, and
**[RELEASING.md](RELEASING.md)** for cutting a signed release (a `v*` tag builds and publishes the
APK to GitHub Releases).

## License & security

[MIT](LICENSE) — third-party attributions in [NOTICE](NOTICE). See [SECURITY.md](SECURITY.md) for
the trust model and how to report issues.
