# Nxtes

Nxtes is a free, open-source handwriting and PDF annotation app for Android tablets, with a sync
server you can host yourself. Every feature is free, with no ads.

**Status:** early development. You can import PDFs and create notes from generated templates (or
your own images and PDFs), organise them in folders, open several at once in tabs, and write on them
with a stylus or finger: pens, highlighter, eraser, undo and redo, saved automatically. The page
navigator adds, reorders, turns, duplicates, copies and moves pages; bookmarks, outlines, covers,
duplicate and merge are in too. The screens follow Flexcil's layout ([UI](docs/ui.md)). Editing tools
(lasso, shapes, text, images) come in milestone 4.

Debug builds of each version are attached to the [GitHub releases](https://github.com/AallynReed/Nxtes/releases).

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

```powershell
cd server; cargo test
```

The server listens on `127.0.0.1:8080` by default. Set `NXTES_BIND` to change it.

## License

[AGPL-3.0](LICENSE). If you run a modified Nxtes server for other people, you must publish your
changes.
