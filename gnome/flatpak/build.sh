#!/usr/bin/env bash
# Baut das Flatpak der GNOME-Version als einzelne Datei dist/photographics-<version>.flatpak.
# Gleicher Aufbau wie das Manifest de.andreasbartelmess.Photographics.yml, aber direkt mit den
# flatpak-build-Befehlen (flatpak-builder als Flatpak findet SDKs der Benutzerinstallation nicht).
# Voraussetzung (einmalig, Benutzerinstallation):
#   flatpak install --user flathub org.gnome.Sdk//51 org.freedesktop.Sdk.Extension.openjdk25//26.08
# Installieren:  flatpak install --user dist/photographics-<version>.flatpak
set -euo pipefail
cd "$(dirname "$0")/../.."
APP_ID=de.andreasbartelmess.Photographics
RUNTIME=51
VERSION=$(sed -n 's/^const val VERSION = "\(.*\)"/\1/p' gnome/src/main/kotlin/de/photographics/gnome/Main.kt)
export JAVA_HOME="${JAVA_HOME_PHOTOGRAPHICS:-$HOME/Android/jdk}"
./gradlew :gnome:installDist --console=plain -q

WORK=gnome/build/flatpak
APP="$WORK/app"
rm -rf "$APP" && mkdir -p "$WORK" dist
flatpak build-init --sdk-extension=org.freedesktop.Sdk.Extension.openjdk25 \
    "$APP" $APP_ID org.gnome.Sdk org.gnome.Platform $RUNTIME

# Java-25-Laufzeit nach /app/jre
flatpak build "$APP" /usr/lib/sdk/openjdk25/install.sh

# Programm, Startbefehl, Desktop-Datei, Metadaten, Icons
F="$APP/files"
mkdir -p "$F/share/photographics" "$F/bin"
cp -r gnome/build/install/photographics/. "$F/share/photographics/"
install -Dm755 gnome/flatpak/photographics.sh "$F/bin/photographics"
install -Dm644 gnome/data/$APP_ID.desktop "$F/share/applications/$APP_ID.desktop"
install -Dm644 gnome/data/$APP_ID.metainfo.xml "$F/share/metainfo/$APP_ID.metainfo.xml"
install -Dm644 gnome/src/main/resources/icons/hicolor/scalable/apps/$APP_ID.svg "$F/share/icons/hicolor/scalable/apps/$APP_ID.svg"
install -Dm644 gnome/src/main/resources/icons/hicolor/symbolic/apps/$APP_ID-symbolic.svg "$F/share/icons/hicolor/symbolic/apps/$APP_ID-symbolic.svg"

# Rechte wie im Manifest (finish-args)
flatpak build-finish "$APP" --command=photographics \
    --share=ipc --socket=wayland --socket=fallback-x11 --device=dri --share=network \
    --filesystem=home --filesystem=/media --filesystem=/run/media --env=JAVA_HOME=/app/jre

rm -rf "$WORK/repo"
flatpak build-export "$WORK/repo" "$APP" stable
flatpak build-bundle --runtime-repo=https://dl.flathub.org/repo/flathub.flatpakrepo \
    "$WORK/repo" "dist/photographics-$VERSION.flatpak" $APP_ID stable
echo "Fertig: dist/photographics-$VERSION.flatpak"
