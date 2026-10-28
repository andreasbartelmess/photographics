#!/usr/bin/env bash
# Speicher-Rauchtest auf dem verbundenen Gerät: harte Sitzung mit 12-MP-Dokument (3 Ebenen,
# Filter, Drehen, Bildgröße 140 %, PSD speichern/öffnen, alles rückgängig/wiederholen).
# Voraussetzung: App installiert, USB-Debugging aktiv.
set -uo pipefail
ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
"$ADB" get-state >/dev/null 2>&1 || { echo "Kein Gerät verbunden."; exit 2; }
echo "Gerät: $("$ADB" shell getprop ro.product.model | tr -d '\r'), RAM $("$ADB" shell cat /proc/meminfo | awk '/MemTotal/ {printf "%.1f GB", $2/1048576}')"
"$ADB" shell am force-stop de.photographics.app
"$ADB" logcat -c
"$ADB" shell am start -n de.photographics.app/.MainActivity --ez selbsttest true >/dev/null
echo "Selbsttest läuft (einige Minuten) …"
for _ in $(seq 1 900); do
    sleep 1
    if "$ADB" logcat -d -s PG-Selbsttest:* | grep -q "ERGEBNIS"; then break; fi
    if "$ADB" logcat -d -b crash | grep -q "de.photographics.app"; then break; fi
done
"$ADB" logcat -d -s PG-Selbsttest:* | sed -n 's/^.*PG-Selbsttest: //p'
if "$ADB" logcat -d -b crash | grep -q "de.photographics.app"; then
    echo "✗ App abgestürzt:"; "$ADB" logcat -d -b crash | tail -20; exit 1
fi
"$ADB" logcat -d -s PG-Selbsttest:* | grep -q "ERGEBNIS: OK" && { echo "✓ Rauchtest bestanden"; exit 0; }
echo "✗ Rauchtest fehlgeschlagen oder nicht beendet"; exit 1
