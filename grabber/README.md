# Grabber

An Android video downloader. Paste a link or share one in from any app, pick a
quality, and the file lands in your gallery. It knows what VR footage is and
labels it so headset players open it correctly.

Built on [yt-dlp](https://github.com/yt-dlp/yt-dlp), which handles roughly
1,800 sites.

## Installing

There is no Play Store listing — Google does not allow downloader apps — so the
APK is installed directly.

1. Download `app-debug.apk` from the latest CI build.
2. Tap it on the phone and allow installing from that source when Android asks.

Every build is signed with the same key (`grabber-debug.keystore`), so a new APK
installs over the old one as an upgrade and keeps your history. That key is a
debug key carrying Android's published default password. It is not a secret, and
it must never be used to publish to a store.

The APK is built for **arm64** phones, which is everything sold since roughly
2016. For an older 32-bit phone, add `"armeabi-v7a"` to `abiFilters` in
`app/build.gradle.kts`.

## Using it

**The normal way** is not to open the app at all. Find a video in any app —
YouTube, a browser, Instagram — tap **Share**, and pick **Grabber**. It resolves
the link and asks what quality you want.

**From the app**, paste a link into the box on the Downloads tab, or go to
**Sites**, open one in the built-in browser, and tap **Grab** on any page with a
video on it.

Finished files go to **Movies/Grabber** (audio to **Music/Grabber**), which is a
normal public folder — the gallery lists them, and any player can open them.

## VR and 360

A 360 video is an ordinary mp4 whose frames are an unwrapped sphere. Nothing in
the file reliably says so: YouTube strips the spherical metadata from the streams
it serves, and most VR sites never set it. What survives is the shape of the
frame, so that is what the app reads.

| Frame shape | What it means |
|---|---|
| 2:1 | one unwrapped sphere (360 mono), or two 180° eyes side by side |
| 4:1 | two full spheres side by side — 360 in 3D |
| 1:1 | two spheres stacked — 360 over-under |
| 1:2 | two 180° eyes stacked |

The 2:1 case is genuinely ambiguous, so words from the title and description
break the tie. Where the evidence is thin the app says so and asks you to
confirm the layout rather than guessing silently. Every detection can be
overridden before the download starts.

Two things then happen for VR:

- **The quality cap is ignored.** A 4K sphere wrapped around your head looks
  about like 1080p does on a screen, so VR always takes the highest resolution
  available. Turn this off in Settings if you are short of space.
- **The layout is written into the file name** — `_360`, `_360_TB`,
  `_180x180_3dh` and so on. This is the convention DeoVR, Skybox and Pigasus all
  read, and it is what makes a headset show the video wrapped around you instead
  of flat on a screen.

## Sites

The **Sites** tab ships with a starting set of tiles, grouped into Video, Music,
Films and VR & 360. It is only a starting point — add any site with the **+**
button, and remember that a site does not need a tile at all, since sharing a
link in from its own app works just as well.

## What it cannot do

- **DRM-protected services.** Netflix, Disney+, Prime Video, Apple TV+ and the
  like encrypt their video. Breaking that encryption is illegal in most
  countries and is deliberately not implemented. No amount of engine updating
  will change this.
- **Torrent sites.** Sites that distribute films as `.torrent` files or magnet
  links serve no video stream at all, so there is nothing for this engine to
  fetch. A different kind of program entirely.
- **Sites that need you signed in** will refuse unless you are.

Downloading is for video you have the right to keep: your own uploads, material
published under a licence that allows it, and anything a site offers for
download itself.

## When a link stops working

Sites change how they serve video constantly, and an out-of-date engine is
almost always the reason something that worked last month has stopped. Go to
**Settings → Update engine**. That pulls a fresh yt-dlp without needing a new
version of the app.

## How it is put together

| File | |
|---|---|
| `Engine.kt` | yt-dlp: starting it, probing links, choosing formats, downloading |
| `Model.kt` | the job and probe types, and the VR frame-shape detection |
| `Downloads.kt` | the queue and history, shared between the service and the UI |
| `DownloadService.kt` | runs the queue in the foreground so it survives leaving the app |
| `Media.kt` | publishes finished files into the phone's media library |
| `ui/` | the interface: downloads, sites, the built-in browser, settings |

Downloads run one at a time. Several 8K downloads at once only divide the same
connection between them while multiplying the ways it can fail.

## Building

There is no Gradle wrapper; CI calls `gradle` directly.

```
cd grabber
gradle assembleDebug
```

The APK is about 60 MB, nearly all of it the bundled Python runtime and ffmpeg
that yt-dlp needs.

## Licence

GPL-3.0, inherited from the engine it is built on. See `LICENSE.md`.
