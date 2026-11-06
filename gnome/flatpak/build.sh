#!/usr/bin/env bash
# Baut das Flatpak der GNOME-Version als einzelne Datei dist/photographics-<version>.flatpak.
# Gleicher Aufbau wie das Manifest de.andreasbartelmess.Photographics.yml, aber direkt mit den
# flatpak-build-Befehlen (flatpak-builder als Flatpak findet SDKs der Benutzerinstallation nicht).
# Voraussetzung (einmalig, Benutzerinstallation):
#   flatpak install --user flathub org.gnome.Sdk//51 org.freedesktop.Sdk.Extension.openjdk25//26.08
# Installieren:  flatpak install --user dist/photographics-<version>.flatpak
# Mit Signierschlüssel (GnuPG-Ordner flatpak-signing/ oder PG_FLATPAK_GPG_HOME) wird das Repository
# signiert, und das Bundle verweist auf das eigene Repository – installierte Fassungen bekommen Updates.
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
# Katalogdaten (Name, Beschreibung, Symbol, Bildschirmfotos) für GNOME Software und KDE Discover:
# build-update-repo übernimmt sie aus share/app-info in den appstream-Zweig des Repositorys.
# Selbst erzeugt statt „appstreamcli compose“: dessen Bildlader (glycin) startet in „flatpak build“ nicht.
for s in 64 128; do
    mkdir -p "$F/share/app-info/icons/flatpak/${s}x${s}"
    flatpak build "$APP" rsvg-convert -w $s -h $s -o "/app/share/app-info/icons/flatpak/${s}x${s}/$APP_ID.png" \
        "/app/share/icons/hicolor/scalable/apps/$APP_ID.svg"
done
mkdir -p "$F/share/app-info/xmls"
python3 - "$F/share/metainfo/$APP_ID.metainfo.xml" "$F/share/app-info/xmls/$APP_ID.xml.gz" "$APP_ID" "$(flatpak --default-arch)" <<'PY'
import gzip, sys, xml.etree.ElementTree as ET
src, out, app, arch = sys.argv[1:]
comp = ET.parse(src).getroot()
# Im Katalog steht jede Sprache in einem eigenen <description xml:lang>, nicht pro Absatz.
LANG = "{http://www.w3.org/XML/1998/namespace}lang"
desc = comp.find("description")
if desc is not None:
    pos = list(comp).index(desc)
    for el in [e for e in desc if e.get(LANG)]:
        lang = el.attrib.pop(LANG)
        desc.remove(el)
        tr = next((d for d in comp.findall("description") if d.get(LANG) == lang), None)
        if tr is None:
            tr = ET.Element("description", {LANG: lang}); pos += 1; comp.insert(pos, tr)
        tr.append(el)
for s in (64, 128):
    ET.SubElement(comp, "icon", type="cached", width=str(s), height=str(s)).text = app + ".png"
ET.SubElement(comp, "bundle", type="flatpak").text = f"app/{app}/{arch}/stable"
root = ET.Element("components", version="1.0", origin=app)
root.append(comp)
with gzip.open(out, "wb") as f:
    ET.ElementTree(root).write(f, encoding="UTF-8", xml_declaration=True)
PY

flatpak build-finish "$APP" --command=photographics \
    --share=ipc --socket=wayland --socket=fallback-x11 --device=dri --share=network \
    --filesystem=home --filesystem=/media --filesystem=/run/media --env=JAVA_HOME=/app/jre

REPO_URL=https://photographics.andreasbartelmess.de/flatpak/repo/
GPG_HOME="${PG_FLATPAK_GPG_HOME:-$PWD/flatpak-signing}"
SIGN=() BUNDLE_SIGN=()
if [ -d "$GPG_HOME" ]; then
    KEY=$(gpg --homedir "$GPG_HOME" --list-keys --with-colons | awk -F: '/^fpr/{print $10; exit}')
    gpg --homedir "$GPG_HOME" --export "$KEY" > "$WORK/key.gpg"
    SIGN=(--gpg-sign="$KEY" --gpg-homedir="$GPG_HOME")
    BUNDLE_SIGN=(--repo-url="$REPO_URL" --gpg-keys="$WORK/key.gpg")
else
    echo "Hinweis: kein Signierschlüssel – Repository und Bundle ohne Update-Quelle." >&2
fi

# Nur die aktuelle Fassung im Repository (Installierte holen einfach den neuen Stand)
rm -rf "$WORK/repo"
flatpak build-export "${SIGN[@]}" "$WORK/repo" "$APP" stable
flatpak build-update-repo "${SIGN[@]}" --title=photographics --default-branch=stable --prune "$WORK/repo"
flatpak build-bundle "${BUNDLE_SIGN[@]}" --runtime-repo=https://dl.flathub.org/repo/flathub.flatpakrepo \
    "$WORK/repo" "dist/photographics-$VERSION.flatpak" $APP_ID stable
echo "Fertig: dist/photographics-$VERSION.flatpak"
