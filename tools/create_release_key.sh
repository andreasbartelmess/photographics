#!/usr/bin/env bash
# Erzeugt einen eigenen Release-Schlüssel und keystore.properties (beides bleibt lokal, siehe .gitignore).
# Den Schlüssel sicher aufbewahren (Backup!): Updates einer veröffentlichten App müssen immer mit
# demselben Schlüssel signiert sein.
set -euo pipefail
cd "$(dirname "$0")/.."
JAVA_HOME="${JAVA_HOME_PHOTOGRAPHICS:-${JAVA_HOME:-$HOME/Android/jdk}}"
KEYSTORE=photographics-release.jks
ALIAS=photographics

if [ -e "$KEYSTORE" ] || [ -e keystore.properties ]; then
    echo "$KEYSTORE oder keystore.properties existiert bereits – nichts überschrieben." >&2
    exit 1
fi

read -r -s -p "Passwort für den Schlüssel (mind. 6 Zeichen): " PW; echo
read -r -s -p "Passwort wiederholen: " PW2; echo
[ "$PW" = "$PW2" ] || { echo "Passwörter stimmen nicht überein." >&2; exit 1; }
read -r -p "Name für das Zertifikat (z. B. dein Name oder GitHub-Name): " CN

"$JAVA_HOME/bin/keytool" -genkeypair -v -keystore "$KEYSTORE" -alias "$ALIAS" -keyalg RSA -keysize 4096 \
    -validity 10000 -storepass "$PW" -keypass "$PW" -dname "CN=$CN"

cat > keystore.properties <<EOF
storeFile=$KEYSTORE
storePassword=$PW
keyAlias=$ALIAS
keyPassword=$PW
EOF
chmod 600 keystore.properties "$KEYSTORE"
echo
echo "Fertig. Release-Builds werden jetzt mit $KEYSTORE signiert."
echo "Für GitHub Actions: Base64 des Schlüssels als Secret PG_KEYSTORE_BASE64 hinterlegen:"
echo "  base64 -w0 $KEYSTORE"
echo "sowie PG_KEYSTORE_PASSWORD, PG_KEY_ALIAS ($ALIAS) und PG_KEY_PASSWORD."
