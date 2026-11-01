# F-Droid

`de.photographics.app.yml` ist das Rezept für [fdroiddata](https://gitlab.com/fdroid/fdroiddata)
(Datei `metadata/de.photographics.app.yml`). Beschreibung, Icon, Screenshots und Änderungsnotizen kommen
aus `fastlane/metadata/android/` in diesem Repository.

F-Droid baut je Architektur eine unsignierte APK (`-Pphotographics.abi=…`, versionCode = 10 × Basis +
1/2/3) und signiert sie mit eigenem Schlüssel. Lokal nachbauen:

```bash
git clone https://github.com/andreasbartelmess/photographics && cd photographics
rm -rf gnome && sed -i -e '/foojay/d' settings.gradle.kts
./gradlew :app:assembleRelease -Pphotographics.abi=arm64-v8a
```

Für jede neue Version: `versionName`/`baseVersionCode` erhöhen, `fastlane/metadata/android/*/changelogs/<versionCode>.txt`
für alle drei versionCodes anlegen, Tag `vX.Y.Z` pushen – F-Droid findet ihn automatisch.
