#!/usr/bin/env bash
# Erzeugt die TIFF-Testdateien in core/src/test/resources/tiff mit ImageMagick (libtiff).
# Referenz je Datei: <name>.ref.png (8 Bit sRGB mit Alpha), von ImageMagick dekodiert.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=core/src/test/resources/tiff
SRC=core/src/test/resources/psd
mkdir -p "$OUT"; rm -f "$OUT"/*.tif "$OUT"/*.png
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
magick "$SRC/ref-alpha.png" -resize 96x64! "$T/alpha.png"
magick "$T/alpha.png" -background white -flatten "$T/opaque.png"

mk() { # name, Quelle, Optionen …
    local name=$1 src=$2; shift 2
    magick "$src" "$@" "$OUT/$name.tif"
}
mk rgb8-none-mm    "$T/opaque.png" -compress none -define tiff:endian=msb
mk rgb8-lzw-ii     "$T/opaque.png" -compress lzw -define tiff:endian=lsb
mk rgb8-lzw-pred   "$T/opaque.png" -compress lzw -define tiff:predictor=2 -define tiff:rows-per-strip=7
mk rgb8-zip-tiled  "$T/opaque.png" -compress zip -define tiff:predictor=2 -define tiff:tile-geometry=32x32
mk rgb16-zip-pred  "$T/opaque.png" -depth 16 -compress zip -define tiff:predictor=2 -define tiff:endian=lsb
mk rgb16-rle-planar-mm "$T/opaque.png" -depth 16 -compress rle -interlace plane -define tiff:endian=msb
mk rgba8-unassoc   "$T/alpha.png" -compress lzw -define tiff:alpha=unassociated
mk rgba8-assoc     "$T/alpha.png" -compress zip -define tiff:alpha=associated
mk gray8           "$T/opaque.png" -colorspace gray -compress lzw
mk gray16-mm       "$T/opaque.png" -colorspace gray -depth 16 -compress none -define tiff:endian=msb
mk gray1-white     "$T/opaque.png" -colorspace gray -threshold 50% -type bilevel -compress none -define tiff:photometric=min-is-white
mk palette8        "$T/opaque.png" -colors 200 -type palette -compress lzw
mk palette4        "$T/opaque.png" -colors 16 -type palette -depth 4 -compress none
mk lab8            "$T/opaque.png" -colorspace Lab -compress none
# 32 Bit Gleitkomma wie Photoshop: linear
mk float32-pred    "$T/opaque.png" -colorspace RGB -depth 32 -define quantum:format=floating-point -compress zip -define tiff:predictor=3
# CMYK mit eingebettetem Profil (Ghostscript, AGPL – siehe THIRD_PARTY_NOTICES.md)
magick "$T/opaque.png" -profile /usr/share/ghostscript/iccprofiles/srgb.icc -intent relative -black-point-compensation \
    -profile /usr/share/ghostscript/iccprofiles/default_cmyk.icc -compress lzw "$OUT/cmyk8-icc.tif"
# Photoshop-Ebenen (Tag 37724) in beiden Bytefolgen: drei Ebenen mit Versatz, Transparenz und Namen (16 Bit)
# (alle Ebenen 16 Bit wie bei Photoshop; keine einfarbigen Flächen – die schreibt ImageMagick fehlerhaft)
magick -size 120x80 gradient:'#3060c0'-'#f0d080' -depth 16 -set label Hintergrund "$T/bg.png"
magick -size 50x40 gradient:'#d02020'-'#e05030' -alpha set -channel A -evaluate set 70% +channel -depth 16 -set label "Rot 70%" "$T/l1.png"
magick -size 40x30 radial-gradient:'#20c040'-none -depth 16 -set label "Grün weich" "$T/l2.png"
LAYERS=("$T/bg.png" \( "$T/l1.png" -repage +10+12 \) \( "$T/l2.png" -repage +70+40 \))
magick "${LAYERS[@]}" -background none -flatten "$T/comp.png"
for e in msb lsb; do
    magick "$T/comp.png" "${LAYERS[@]}" -type TrueColorAlpha -depth 16 -define tiff:write-layers=true -define tiff:endian=$e -compress lzw "$OUT/layers-$e.tif"
done
magick "$T/comp.png" -depth 8 "PNG32:$OUT/layers.ref.png"

for f in "$OUT"/*.tif; do
    case "$(basename "$f")" in
        cmyk8-icc.tif) magick "$f" -intent relative -black-point-compensation -profile /usr/share/ghostscript/iccprofiles/srgb.icc -depth 8 "PNG32:${f%.tif}.ref.png" ;;
        float32-pred.tif) magick "$T/opaque.png" "PNG32:${f%.tif}.ref.png" ;;
        layers-*.tif) ;;   # Referenz: layers.ref.png
        lab8.tif) python3 tools/lab_tiff_reference.py "$f" "${f%.tif}.ref.png" ;;
        *) magick "$f[0]" -colorspace sRGB -depth 8 "PNG32:${f%.tif}.ref.png" ;;
    esac
done
ls -la "$OUT" | awk '{s+=$5} END {print "Gesamt", s, "Bytes"}'
