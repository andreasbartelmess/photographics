# Contributing to Photographics

Thanks for your interest! Bug reports, PSD compatibility reports, translations and pull requests
are all welcome. English or German – both are fine.

## Reporting problems

- **Bugs:** use the *Bug report* template and include your device, Android version and app version
  (*Settings → Über*).
- **PSD files that look wrong:** use the *PSD compatibility* template. The PSD check
  (`./gradlew :core:psdCheck -PpsdDir=<folder>`) produces side-by-side previews that make the
  problem visible without sharing the file. Only attach files you are allowed to share.

## Development

```bash
./gradlew :core:test :core:memoryTest   # must pass before every pull request
./gradlew :app:assembleDebug
```

- `core/` is pure Kotlin/JVM without Android dependencies. Put image processing, file formats and
  editing logic there so it can be tested on the desktop; `app/` contains only the Android UI.
- **Memory:** Android apps get a small Java heap (often 512 MB) even on 8 GB tablets. Pixel data
  belongs in `TileGrid` (native tile memory), and large operations work in bands
  (`Parallel.bands`). Never allocate a buffer the size of the whole document. The memory stress test
  runs a full editing session with a 192 MB heap and fails if a function loads an entire image into
  the Java heap – **add new editing functions to `MemorySelfTest`**.
- Every editing command goes through `Editor` and must be undoable.
- Code style: Kotlin official style. Code comments and the UI are currently German; English
  comments in new code are fine. UI strings will move to Android resources for translation.
- Use release builds to judge performance; debug builds are several times slower on Android.

## Licence

By contributing you agree that your contribution is licensed under the
[GNU General Public License v3.0 or later](LICENSE), like the rest of the project.
