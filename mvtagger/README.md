# MV Tagger

An Android app for a folder of downloaded videos. It works out what each one
is, looks the details up online, writes them **into the file**, renames it and
files it into a tidy output folder.

The point of writing the metadata inside the file is that it travels: copy the
result to an iPad, a laptop or a NAS and the artwork, artist, album, year and
lyrics are still there, with no database and no sidecar files to keep track of.

It is a separate app from Deck Log in this repo and shares nothing with it.

## What it does

- **Library.** Point it at one or more folders. It scans them, including
  subfolders, and lists every video it finds.
- **Recognises three kinds of file** from the name: music videos, films, and
  TV episodes (`S01E02`, `1x02`, `Season 1 Episode 2`).
- **Looks up the details** — song, artist, album, year, genre — from iTunes,
  MusicBrainz, TVmaze and Wikipedia. None of them needs an account.
- **Downloads and embeds cover art.** For an English song, the album front. For
  a Hindi song, the film's cover, because a Hindi song's "album" is its film
  soundtrack and that is what its artwork is.
- **Fetches lyrics** from LRCLIB, plain and timestamped.
- **Fetches background** on the artist and on the album or film, and embeds
  that too.
- **Subtitles for films and episodes.** Ones already in the file, or in an
  `.srt` beside it, are kept and written *into* the MP4 as a `tx3g` text track
  -- the format Apple's own players read. Missing ones can be fetched from
  OpenSubtitles, which needs a free account of theirs. An `.srt` is written
  beside the video as well, because some players ignore a text track inside an
  MP4.
- **Handles Hindi, English and thirty-odd other languages,** including titles
  written in Devanagari, Tamil, Telugu, Bengali, Gurmukhi and more. Filenames
  keep their own script rather than being transliterated.
- **Renames and organises.** You choose the pattern; the tidied copy goes into
  the output folder under `Music Videos/Artist/`, `Movies/Title (Year)/` or
  `TV Shows/Series/Season 01/`.

**Your originals are never modified or moved.** Every result is a new file in
the output folder, so a wrong match costs you a delete and nothing else.

## Which files it can work with

| | Listed and renamed | Tags written inside the file |
|---|---|---|
| `.mp4`, `.m4v`, `.mov` | yes | **yes**, directly |
| `.mkv`, `.ts`, `.avi`, `.flv`, `.3gp` carrying H.264/H.265 video and AAC audio | yes | **yes**, by repackaging into MP4 first |
| `.webm` and anything carrying VP9, AV1, Opus, Vorbis, AC-3 or DTS | yes | no — details written to `.json`, `.lrc` and a poster file alongside |

Only the MP4 family has a standard place for this metadata, and it is what
Apple devices read, so that is what the app writes into.

**Repackaging is not re-encoding.** When a file cannot hold tags, its existing
audio and video streams are moved into an MP4 container exactly as they are --
the same operation as `ffmpeg -c copy`. Not one pixel is decoded or
recompressed, so there is no quality loss, and it runs at disk speed. A 4K file
stays 4K. Subtitle tracks are dropped, because Android's muxer cannot write
them into MP4.

What cannot be repackaged is a codec MP4 has no slot for. VP9 or AV1 video and
Opus or Vorbis audio -- which is what a `.webm` from YouTube usually holds --
would have to be decoded and re-encoded to get into an MP4, which does cost
quality and time. The app says which codec stopped it rather than failing
vaguely. For those, re-downloading as MP4 is faster and lossless:

```
yt-dlp -f "bv*[ext=mp4]+ba[ext=m4a]" <url>
```

A fragmented MP4 (built for streaming, with a `moof` box) is copied and renamed
but cannot be tagged; the app says so rather than pretending.

## Setting it up

1. Install the APK from the repo's `mvtagger-latest` release.
2. Open it and choose two folders: the one your videos are in, and the one you
   want tidied copies written to. Android grants access to just those two.
3. Tap a video to identify it one at a time, or **Auto-tag everything new** to
   let it work through the whole folder, stopping on anything it is not sure
   enough about.

Optional: a free [TMDb](https://www.themoviedb.org/settings/api) key in
Settings gets genuine film posters instead of soundtrack covers. Everything
works without one.

## How it is built

Two Gradle modules:

- **`core/`** — a plain JVM library, no Android on the classpath. The MP4 atom
  reader and writer, the filename parsers, the language detection, the result
  scoring and every provider's URL building and response parsing live here, so
  all of it runs under `gradle :core:test` on any machine with a JDK. That test
  suite is what stands behind the claim that tagging does not corrupt a video.
- **`app/`** — the Android app: Compose UI, Storage Access Framework, and the
  HTTP calls. Thin on purpose.

### The part that is easy to get wrong

`stco`/`co64` inside an MP4's `moov` box hold the **absolute file offset** of
every chunk of audio and video. Adding metadata makes `moov` bigger, which
moves all of that media, and unless every one of those offsets is corrected the
file still opens and still parses — it just plays nothing.

`Mp4Metadata.write` rebuilds the file, works out where each original box ends
up, and rewrites the offsets through that map. The tests build synthetic MP4s
with each chunk filled with a recognisable byte, tag them, then follow the new
offsets to check they still land on the same chunk — with `moov` at the front
and at the end, with a `free` box to reclaim, and over repeated re-tagging.

Nothing is ever written in place. The tagger streams to a new file, and a
failure deletes the partial rather than leaving something that looks finished.

## What it deliberately does not do

- **No player.** Your phone already has good ones; the app hands the file to
  whichever you pick.
- **No re-encoding.** Repackaging into MP4 is lossless and supported; actually
  transcoding one codec into another is not. A VP9/Opus `.webm` cannot become an
  MP4 without it, and doing it on a phone would take far longer than
  re-downloading the file and would look worse. The only route to it is bundling
  FFmpeg, which is tens of megabytes and a project of its own.
- **Subtitles are not styled.** A `tx3g` track carries the words and their
  timings, not fonts, colours or positioning, so anything fancy in an ASS
  subtitle is flattened to plain text. The `.srt` written alongside has the same
  limitation. Styling would mean a different format that Apple devices do not
  read, which defeats the point.
- **No upscaling.** This one is not a matter of effort. A 1080p file does not
  contain 4K detail; nothing can recover what was never recorded. Scaling it up
  produces a file four times the size that looks the same at best. Televisions
  and iPads already scale on playback, which is the right place for it.

## Changing it

`gradle :core:test` from `mvtagger/` runs the whole test suite and needs no
Android SDK. CI runs it before every APK build, so a change that breaks the
tagger cannot reach a release.

`python3 check-regexes.py core/src/main app/src/main` runs alongside it, and
exists because of how v1 shipped broken. The JVM and Android do not use the
same regex engine: Android's is ICU's, and ICU is stricter. This pattern

```kotlin
Regex("""\{(\w+)}""")     // the closing } is not escaped
```

is fine on a JVM and a syntax error on ICU. It sat in a static initialiser that
runs on first launch, so the app died before drawing anything while all 59
tests passed. The script compiles every regex literal in the sources against
ICU itself, which is the only way to catch that class of bug without a phone.

It is the standing hazard of testing off-device: the tests are fast because
they do not need an emulator, and the price is that engine differences only
show up on the phone. Anything else that differs between the two deserves the
same treatment.
