#!/usr/bin/env bash
# Bedien-Benchmark auf dem verbundenen Gerät: Verschieben, Regler, Einstellungsebene, Transformieren
# und Pinsel auf einem großen Dokument, je Einzelbild gemessen (Logcat-Tag PG-Bench).
#   tools/device_bench.sh [Megapixel, Standard 24]
set -uo pipefail
ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
"$ADB" get-state >/dev/null 2>&1 || { echo "Kein Gerät verbunden."; exit 2; }
"$ADB" shell input keyevent KEYCODE_WAKEUP   # schlafendes Gerät misst gedrosselt
"$ADB" shell am force-stop de.photographics.app
"$ADB" logcat -c
"$ADB" shell am start -n de.photographics.app/.MainActivity --ez benchmark true --ei megapixel "${1:-24}" --ei skip "${PG_SKIP:-0}" >/dev/null
GFX=""
for _ in $(seq 1 3000); do
    sleep 0.2
    if [ -z "$GFX" ] && "$ADB" logcat -d -s PG-Bench:* | grep -q "UI-PHASE: Start"; then
        "$ADB" shell dumpsys gfxinfo de.photographics.app reset >/dev/null; GFX=läuft
    fi
    if [ "$GFX" = läuft ] && "$ADB" logcat -d -s PG-Bench:* | grep -q "UI-PHASE: Ende"; then
        STATS=$("$ADB" shell dumpsys gfxinfo de.photographics.app framestats)
        GFX=$(echo "$STATS" | grep -E "Total frames rendered|Janky frames|percentile|Number (Slow|Frozen)" | sed 's/^ *//' | awk '!seen[$0]++')
    fi
    "$ADB" logcat -d -s PG-Bench:* | grep -q "ERGEBNIS" && break
    "$ADB" logcat -d -b crash | grep -q "de.photographics.app" && break
done
"$ADB" logcat -d -s PG-Bench:* | sed -n 's/^.*PG-Bench: //p' | grep -v "UI-PHASE" | sed 's/^BILD /  /'
[ -n "$GFX" ] && [ "$GFX" != läuft ] && { echo "Bildzeiten der Oberfläche (Verschieben + Malen in Echtzeit):"; echo "$GFX" | sed 's/^/  /'; }
"$ADB" logcat -d -b crash | grep -q "de.photographics.app" && { echo "✗ App abgestürzt:"; "$ADB" logcat -d -b crash | tail -20; exit 1; }
exit 0
