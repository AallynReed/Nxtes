# Nxtes

Nxtes is a free, open-source handwriting and PDF annotation app for Android tablets, with a sync
server you can host yourself. Every feature is free, with no ads.

**Status:** early development. Milestones 1 and 2 are done: import PDFs, organise them in folders, read them
with scrolling and pinch zoom, and write on them with a stylus. Pens, highlighter, eraser, and undo and redo
all work, and ink saves automatically. Templates and blank notes come in milestone 3.

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
