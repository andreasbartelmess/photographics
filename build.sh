#!/usr/bin/env bash
# Baut Photographics und installiert es auf einem per USB/WLAN verbundenen Gerät (falls vorhanden).
#   ./build.sh            Tests + Speicher-Sperre, dann Release-APKs (+ passende installieren, wenn ein Gerät verbunden ist)
#   ./build.sh debug      Debug-Version ohne Tests (schnell, mit Diagnose-Protokoll)
#   ./build.sh test       Alle Tests inkl. Speicher-Sperre
#   ./build.sh smoke      Speicher-Rauchtest auf dem verbundenen Gerät (App muss installiert sein)
#   ./build.sh release --ohne-tests   Release ohne Tests (nur für Notfälle)
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME_PHOTOGRAPHICS:-$HOME/Android/jdk}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"

# Release-Sperre: Funktionstests (512 MB Heap) und Speichertest (192 MB Heap, Bilddaten > 700 MB).
# Schlägt einer fehl, wird keine Release-Version gebaut.
run_tests() {
    echo "== Tests und Speicher-Sperre =="
    if ! ./gradlew :core:test :core:memoryTest --console=plain; then
        echo
        echo "✗ Tests fehlgeschlagen – keine Release-Version gebaut."
        echo "  Bericht: core/build/reports/tests/test/index.html und core/build/reports/tests/memoryTest/index.html"
        exit 1
    fi
    echo "✓ Alle Tests bestanden"
}

case "${1:-release}" in
    test)
        run_tests
        exit 0 ;;
    smoke)
        exec tools/device_smoke_test.sh ;;
    debug)
        ./gradlew :app:assembleDebug --console=plain
        APK_DIR=app/build/outputs/apk/debug; KIND=debug ;;
    *)
        [ "${2:-}" = "--ohne-tests" ] || run_tests
        ./gradlew :app:assembleRelease --console=plain
        APK_DIR=app/build/outputs/apk/release; KIND=release ;;
esac

mkdir -p dist
rm -f dist/*-"$KIND".apk
cp "$APK_DIR"/*.apk dist/ 2>/dev/null || true
echo "APKs in: $(pwd)/dist"
ls -1 dist

if "$ADB" get-state >/dev/null 2>&1; then
    ABI=$("$ADB" shell getprop ro.product.cpu.abi | tr -d '\r')
    APK="$APK_DIR/app-$ABI-$KIND.apk"
    [ -f "$APK" ] || APK=$(ls "$APK_DIR"/app-universal-$KIND.apk "$APK_DIR"/app-$KIND.apk 2>/dev/null | head -1)
    echo "Gerät ($ABI) gefunden – installiere $(basename "$APK")"
    "$ADB" install -r "$APK"
    "$ADB" shell am start -n de.photographics.app/.MainActivity
else
    echo "Kein Gerät verbunden – passende APK aus dist/ aufs Tablet kopieren (meist *arm64-v8a*)."
fi
