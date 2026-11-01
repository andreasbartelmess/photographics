#!/usr/bin/env bash
# Installiert die GNOME-Version für den aktuellen Benutzer (ohne Root):
#   ~/.local/share/photographics   Programm
#   ~/.local/bin/photographics     Startbefehl
#   Startmenü-Eintrag und Icon unter ~/.local/share
# Voraussetzung: GTK 4 und libadwaita (bei GNOME vorhanden) sowie Java 25 (Fedora: java-25-openjdk).
#   ./gnome/install.sh            bauen und installieren
#   ./gnome/install.sh --remove   wieder entfernen
set -euo pipefail
cd "$(dirname "$0")/.."
APP_ID=de.andreasbartelmess.Photographics
DATA="${XDG_DATA_HOME:-$HOME/.local/share}"
PREFIX="$DATA/photographics"
BIN="$HOME/.local/bin/photographics"

if [ "${1:-}" = "--remove" ]; then
    rm -rf "$PREFIX" "$BIN" "$DATA/applications/$APP_ID.desktop" \
        "$DATA/icons/hicolor/scalable/apps/$APP_ID.svg" "$DATA/icons/hicolor/symbolic/apps/$APP_ID-symbolic.svg"
    echo "photographics (GNOME) entfernt."
    exit 0
fi

export JAVA_HOME="${JAVA_HOME_PHOTOGRAPHICS:-$HOME/Android/jdk}"
./gradlew :gnome:installDist --console=plain -q

rm -rf "$PREFIX"
mkdir -p "$PREFIX" "$(dirname "$BIN")" "$DATA/applications" "$DATA/icons/hicolor/scalable/apps" "$DATA/icons/hicolor/symbolic/apps"
cp -r gnome/build/install/photographics/. "$PREFIX/"

# Startbefehl: sucht ein Java ab Version 25 (java-gi braucht die Foreign-Function-API)
cat > "$BIN" <<'SH'
#!/usr/bin/env bash
java_ok() { [ -x "$1" ] && [ "$("$1" -XshowSettings:properties -version 2>&1 | sed -n 's/.*java.specification.version = //p')" -ge 25 ] 2>/dev/null; }
for j in "${JAVA_HOME:-}/bin/java" "$(command -v java 2>/dev/null)" /usr/lib/jvm/jre-25/bin/java /usr/lib/jvm/java-25-openjdk/bin/java /usr/lib/jvm/java-25-openjdk-amd64/bin/java; do
    if java_ok "$j"; then export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$j")")")"; break; fi
done
java_ok "${JAVA_HOME:-}/bin/java" || { echo "photographics braucht Java 25 oder neuer (Fedora: sudo dnf install java-25-openjdk)" >&2; exit 1; }
SH
echo "exec \"$PREFIX/bin/photographics\" \"\$@\"" >> "$BIN"
chmod +x "$BIN"

sed "s|^Exec=photographics|Exec=$BIN|" gnome/data/$APP_ID.desktop > "$DATA/applications/$APP_ID.desktop"
cp gnome/src/main/resources/icons/hicolor/scalable/apps/$APP_ID.svg "$DATA/icons/hicolor/scalable/apps/"
cp gnome/src/main/resources/icons/hicolor/symbolic/apps/$APP_ID-symbolic.svg "$DATA/icons/hicolor/symbolic/apps/"
command -v update-desktop-database >/dev/null && update-desktop-database -q "$DATA/applications" || true
command -v gtk-update-icon-cache >/dev/null && gtk-update-icon-cache -q -t "$DATA/icons/hicolor" 2>/dev/null || true
echo "Installiert: photographics im GNOME-Menü, im Terminal: $BIN [datei]"
