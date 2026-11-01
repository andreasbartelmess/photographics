#!/usr/bin/env bash
# Flatpak einer veröffentlichten Version ausliefern: baut und signiert (build.sh), hängt Bundle und
# Repository (photographics-flatpak-repo.tar) an das GitHub-Release vX.Y.Z und stößt die Website an,
# die das Repository unter https://photographics.andreasbartelmess.de/flatpak/repo/ bereitstellt.
#   gnome/flatpak/publish.sh            Version aus Main.kt
set -euo pipefail
cd "$(dirname "$0")/../.."
VERSION=$(sed -n 's/^const val VERSION = "\(.*\)"/\1/p' gnome/src/main/kotlin/de/photographics/gnome/Main.kt)
GH="${GH:-gh}"
[ -d "${PG_FLATPAK_GPG_HOME:-flatpak-signing}" ] || { echo "Signierschlüssel fehlt (flatpak-signing/)." >&2; exit 1; }
"$GH" release view "v$VERSION" >/dev/null || { echo "Release v$VERSION gibt es nicht." >&2; exit 1; }
gnome/flatpak/build.sh
tar -C gnome/build/flatpak -cf gnome/build/flatpak/photographics-flatpak-repo.tar repo
"$GH" release upload "v$VERSION" --clobber "dist/photographics-$VERSION.flatpak" gnome/build/flatpak/photographics-flatpak-repo.tar
"$GH" workflow run pages.yml
echo "Release v$VERSION aktualisiert; die Website veröffentlicht das Repository in wenigen Minuten."
