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
| `.mp4`, `.m4v`, `.mov` | yes | **yes** |
| `.mkv`, `.webm`, `.avi`, `.wmv`, `.flv`, `.ts`, `.mpg`, `.3gp`, `.ogv`, … | yes | no — details are written to `.json`, `.lrc` and a poster file alongside |

Only the MP4 family has a standard place to put this metadata, and it is the
format Apple devices read, so that is the one the app writes into. A fragmented
MP4 (one built for streaming, with a `moof` box) is copied and renamed but
cannot be tagged; the app says so rather than pretending.

**It does not convert or re-encode anything.** A WebM stays a WebM; a 1080p
file stays 1080p. See "What it deliberately does not do" below.

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
- **No format conversion.** Turning a 4K WebM into a 4K MP4 without re-encoding
  needs a muxer that can put VP9 and Opus into MP4, which Android's own
  `MediaMuxer` cannot do. The only real route is bundling FFmpeg, which is tens
  of megabytes and a project of its own.
- **No upscaling.** Nothing can add detail to a 1080p file that was never
  recorded. Stretching it to 4K makes a bigger file, not a better picture.

## Changing it

`gradle :core:test` from `mvtagger/` runs the whole test suite and needs no
Android SDK. CI runs it before every APK build, so a change that breaks the
tagger cannot reach a release.
