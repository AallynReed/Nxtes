<div align="center">

<img src="docs/play/graphics/icon-512.png" width="128" alt="Nxtes icon">

# Nxtes

<!-- Project status -->
[![GitHub Release](https://img.shields.io/github/v/release/AallynReed/Nxtes?style=for-the-badge&color=blue&cacheSeconds=21600)](https://github.com/AallynReed/Nxtes/releases/latest)
![GitHub Repo stars](https://img.shields.io/github/stars/AallynReed/Nxtes?style=for-the-badge&color=gold&cacheSeconds=21600)
[![GitHub Downloads](https://img.shields.io/github/downloads/AallynReed/Nxtes/total?style=for-the-badge&cacheSeconds=21600)](https://github.com/AallynReed/Nxtes/releases)
![GitHub last commit](https://img.shields.io/github/last-commit/AallynReed/Nxtes?style=for-the-badge&color=green&cacheSeconds=21600)
![GitHub issues](https://img.shields.io/github/issues/AallynReed/Nxtes?style=for-the-badge&cacheSeconds=21600)

<!-- Platforms -->
![Android](https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)

<!-- Tech stack -->
![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)
![Rust](https://img.shields.io/badge/Rust-000000?style=for-the-badge&logo=rust&logoColor=white)

<!-- Project meta -->
![License](https://img.shields.io/badge/License-AGPL--3.0-8A2BE2?style=for-the-badge)
[![Discord](https://img.shields.io/badge/Discord-Foxhearth-5865F2?style=for-the-badge&logo=discord&logoColor=white)](https://foxhearth.com/discord)
[![foxhearth.com](https://img.shields.io/badge/foxhearth.com-project_page-E8702A?style=for-the-badge)](https://foxhearth.com/projects/nxtes)

</div>

Nxtes is a free, open-source handwriting and PDF annotation app for Android tablets, with a sync
server you can host yourself. Every feature is free, with no ads.

**Status:** 1.1. You can import PDFs and create notes from generated templates (or
your own images and PDFs), organise them in folders, open several at once in tabs, and write on them
with a stylus or finger: pens, highlighter, eraser, undo and redo, saved automatically. The page
navigator adds, reorders, turns, duplicates, copies and moves pages; bookmarks, outlines, covers,
duplicate and merge are in too. The editing tools are in: lasso, shapes and shape recognition, text
boxes with your own fonts, images with camera and crop, sticky notes, sticker packs, masking with
correct rates, a laser pointer, handwriting-to-text conversion (on request, or by itself whenever
you pause), and a highlighter that snaps to PDF text and to handwriting. The screens follow Flexcil's layout
([UI](docs/ui.md)). The viewer scrolls down or across, page by page or continuously, one or two
pages at a time, with dark pages, read-only mode, full screen, multi-finger undo and redo,
annotation and search panels, and full-text search across the library. Recording audio while
writing works too, in the background, with playback at 0.5× to 2×, replay of the writing in step
with the sound, and tap-on-ink to hear that moment. Documents export as PDFs that keep their
original pages (or flattened) and as pictures, images import as documents, and the whole library
backs up to one zip. Sign in from Settings to sync the library between devices, live while the
app is open and hourly in the background, through the official server or [your own](server/README.md).
Exported PDFs can be shared as web links that open in any browser, and Flexcil backups import with
their folders, pages, writing and links.

Handwriting is read on the device by Google's ML Kit, the one closed-source part of the app: each
language's recognition model downloads from Google the first time it's used.

Signed builds of each version are attached to the [GitHub releases](https://github.com/AallynReed/Nxtes/releases).
1.0.0 and later can't install over the 0.x debug builds: uninstall those first, then sign in or
restore a backup to get the library back.

- [Feature spec](docs/spec.md): what Nxtes will do
- [Architecture and plan](docs/plan.md): how it's built, plus the milestones
- [UI](docs/ui.md): screen layouts, matching what Flexcil users already know

## Layout

| Path | Contents |
|---|---|
| `android/` | The Android app (Kotlin, Jetpack Compose) |
| `server/` | The sync server (Rust, axum) |
| `docs/` | Spec and plan |

## Building

You need JDK 17 or newer, the Android SDK and stable Rust.

```powershell
cd android; .\gradlew.bat assembleDebug
```

A signed release build (`assembleRelease`) reads the keystore from `android/keystore.properties`,
which stays out of git; without it the release APK is unsigned.

```properties
storeFile=nxtes-release.jks
storePassword=...
keyAlias=nxtes
keyPassword=...
```

```powershell
cd server; cargo test
```

The server listens on `127.0.0.1:8080` by default; [server/README.md](server/README.md) covers
its settings, the admin commands and running it with Docker.

## License

[AGPL-3.0](LICENSE). If you run a modified Nxtes server for other people, you must publish your
changes.
