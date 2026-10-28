#!/usr/bin/env bash
# Screenshots für Website und README mit Beispieldokumenten (keine eigenen Fotos):
#   1. Beispiel-PSDs erzeugen:  PG_DEMO_IN=<Ordner mit dolomiten.jpg, studio.jpg, neon.jpg> PG_DEMO_OUT=<Ordner> \
#                               ./gradlew :core:test --tests "*DemoDocuments*"
#   2. tools/screenshots.sh <Ordner mit den PSDs> [Zielordner, Standard docs/screenshots]
# Die App öffnet die Dateien aus ihrem eigenen Ordner, ohne sie unter „Zuletzt verwendet“ einzutragen.
# Status- und Navigationsleiste werden abgeschnitten (Uhrzeit, Akku, Benachrichtigungen).
set -euo pipefail
ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
SRC="${1:?Ordner mit den Beispiel-PSDs angeben}"
OUT="${2:-$(dirname "$0")/../docs/screenshots}"
"$ADB" get-state >/dev/null 2>&1 || { echo "Kein Gerät verbunden."; exit 2; }
DIR=/sdcard/Android/data/de.photographics.app/files
"$ADB" shell mkdir -p "$DIR"
for f in "$SRC"/*.psd; do "$ADB" push -q "$f" "$DIR/"; done
mkdir -p "$OUT"
TMP=$(mktemp -d)

shot() {  # Name, Datei, weitere am-start-Argumente
    local name=$1 file=$2; shift 2
    "$ADB" shell input keyevent KEYCODE_WAKEUP
    "$ADB" shell am force-stop de.photographics.app
    "$ADB" shell am start -n de.photographics.app/.MainActivity --es demo "$file" "$@" >/dev/null
    sleep 10
    "$ADB" exec-out screencap -p > "$TMP/$name.png"
    python3 -I - "$TMP/$name.png" "$OUT/$name.png" <<'EOF'
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert("RGB")
w, h = im.size
top = round(h * 0.04); bottom = round(h * 0.025)   # Statusleiste, Navigationsleiste
im.crop((0, top, w, h - bottom)).save(sys.argv[2], optimize=True)
EOF
    echo "  $OUT/$name.png"
}

shot editor-ebenen   Dolomiten.psd --es tool MOVE
shot editor-kurven   Dolomiten.psd --es panel adjust --es tool MOVE
shot editor-retusche Studio.psd    --es tool HEAL
shot editor-looks    Osaka.psd     --es panel adjust --es tool MOVE
"$ADB" shell am force-stop de.photographics.app
rm -rf "$TMP"
