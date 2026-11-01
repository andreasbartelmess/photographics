#!/bin/sh
# Startbefehl im Flatpak: Java 25 liegt unter /app/jre
export JAVA_HOME=/app/jre
exec /app/share/photographics/bin/photographics "$@"
