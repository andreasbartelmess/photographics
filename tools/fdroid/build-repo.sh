#!/usr/bin/env bash
# Eigenes F-Droid-Repository aus den signierten APKs der neuesten GitHub-Releases bauen
# (https://photographics.andreasbartelmess.de/fdroid/repo, über .github/workflows/pages.yml).
#   FDROID_KEYSTORE=datei FDROID_STORE_PASSWORD=… tools/fdroid/build-repo.sh <zielordner>
# Braucht fdroid (fdroidserver), gh, ANDROID_HOME mit build-tools und jar (JDK).
# Es sind dieselben APKs wie am Release (gleicher Signierschlüssel): Umstieg zwischen beiden geht ohne Neuinstallation.
set -euo pipefail
cd "$(dirname "$0")/../.."
[ $# -eq 1 ] || { echo "Aufruf: $0 <zielordner>" >&2; exit 2; }
OUT=$(realpath -m "$1")
GH="${GH:-gh}"
REPO="${GITHUB_REPOSITORY:-andreasbartelmess/photographics}"
KEEP="${FDROID_KEEP:-2}"                     # so viele Versionen bleiben installierbar
: "${FDROID_KEYSTORE:?FDROID_KEYSTORE fehlt}" "${FDROID_STORE_PASSWORD:?FDROID_STORE_PASSWORD fehlt}" "${ANDROID_HOME:?ANDROID_HOME fehlt}"
AAPT2="$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)aapt2"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
mkdir -p "$W/repo" "$W/metadata" "$W/dl"

versioncode() { "$AAPT2" dump badging "$1" | sed -n "s/^package:.* versionCode='\([0-9]*\)'.*/\1/p"; }

n=0
for tag in $("$GH" release list -R "$REPO" --exclude-drafts --limit 20 --json tagName --jq '.[].tagName'); do
    d="$W/dl/$tag"; mkdir -p "$d"
    "$GH" release download "$tag" -R "$REPO" --pattern 'app-*-release.apk' --dir "$d" 2>/dev/null || continue
    rm -f "$d/app-universal-release.apk"     # je Architektur eine APK reicht, die universelle ist 4× so groß
    compgen -G "$d/*.apk" >/dev/null || continue
    # Bis 0.5.1 hatten alle APKs eines Releases dieselbe versionCode: dann die 32-Bit-ARM-Fassung weglassen,
    # sonst könnte ein 64-Bit-Gerät sie statt der arm64-Fassung bekommen
    if [ "$(for f in "$d"/*.apk; do versioncode "$f"; done | sort -u | wc -l)" -lt "$(ls "$d"/*.apk | wc -l)" ]; then
        rm -f "$d/app-armeabi-v7a-release.apk"
    fi
    for f in "$d"/*.apk; do a=$(basename "$f" -release.apk); cp "$f" "$W/repo/photographics-${tag#v}-${a#app-}.apk"; done
    echo "F-Droid-Repo: $tag ($(ls "$d" | tr '\n' ' '))"
    n=$((n + 1)); [ "$n" -ge "$KEEP" ] && break
done
[ "$n" -gt 0 ] || { echo "Keine veröffentlichten Release-APKs gefunden" >&2; exit 1; }

# Texte, Symbol, Bildschirmfotos und Changelogs aus fastlane/ (wie für F-Droid selbst)
cp -r fastlane/metadata/android "$W/metadata/de.photographics.app"
cp fastlane/metadata/android/en-US/images/icon.png "$W/icon.png"     # repo_icon: relativ zum Arbeitsordner
{ sed '/^RepoType:/,$d' tools/fdroid/de.photographics.app.yml; printf 'AutoUpdateMode: None\nUpdateCheckMode: None\n'; } > "$W/metadata/de.photographics.app.yml"

(
    umask 077
    cat > "$W/config.yml" <<CONF
repo_url: https://photographics.andreasbartelmess.de/fdroid/repo
repo_name: photographics
repo_description: >-
  Signierte Versionen von photographics direkt vom Entwickler, dieselben APKs wie bei den
  GitHub-Releases. Signed builds of photographics straight from the developer.
repo_icon: icon.png
archive_older: 0
repo_keyalias: fdroid-repo
keystore: $FDROID_KEYSTORE
keystorepass: $FDROID_STORE_PASSWORD
keypass: $FDROID_STORE_PASSWORD
keydname: CN=photographics F-Droid repo
sdk_path: $ANDROID_HOME
CONF
)
(cd "$W" && fdroid update --pretty --delete-unknown)
rm -f "$W/config.yml"
rm -rf "$OUT"; mkdir -p "$OUT"; cp -r "$W/repo" "$OUT/repo"
echo "F-Droid-Repo fertig: $OUT/repo ($(du -sh "$OUT/repo" | cut -f1))"
