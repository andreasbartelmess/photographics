#!/usr/bin/env python3
"""Erzeugt Referenzwerte mit LittleCMS (Pillow ImageCms) für die Tests der eigenen ICC-Engine.
Die Profile selbst werden nicht kopiert; die Tests lesen sie aus den Systempfaden."""
import json, itertools, sys
from PIL import Image, ImageCms

GS = "/usr/share/ghostscript/iccprofiles/"
CD = "/usr/share/color/icc/colord/"
F = ImageCms.Flags

def conv(src, dst, in_mode, out_mode, values, intent, bpc):
    flags = F.NOOPTIMIZE | (F.BLACKPOINTCOMPENSATION if bpc else 0)
    t = ImageCms.buildTransform(ImageCms.getOpenProfile(src), ImageCms.getOpenProfile(dst), in_mode, out_mode,
                                renderingIntent=ImageCms.Intent(intent), flags=flags)
    im = Image.new(in_mode, (len(values), 1))
    im.putdata([tuple(v) if len(v) > 1 else v[0] for v in values])
    out = ImageCms.applyTransform(im, t)
    res = list(out.getdata())
    return [list(r) if isinstance(r, tuple) else [r] for r in res]

rgb = [list(v) for v in itertools.product([0, 64, 128, 192, 255], repeat=3)]
cmyk = [list(v) for v in itertools.product([0, 85, 170, 255], repeat=4)]
gray = [[v] for v in range(0, 256, 17)]
cases = []
def add(src, dst, im, om, vals, intent, bpc):
    cases.append(dict(src=src, dst=dst, inMode=im, outMode=om, intent=intent, bpc=bpc, input=vals,
                      output=conv(src, dst, im, om, vals, intent, bpc)))

for intent in (0, 1):
    for bpc in (False, True):
        add(GS + "srgb.icc", GS + "default_cmyk.icc", "RGB", "CMYK", rgb, intent, bpc)
add(GS + "default_cmyk.icc", GS + "srgb.icc", "CMYK", "RGB", cmyk, 1, False)
add(GS + "default_cmyk.icc", GS + "srgb.icc", "CMYK", "RGB", cmyk, 0, True)
add(GS + "ps_cmyk.icc", GS + "srgb.icc", "CMYK", "RGB", cmyk, 1, False)
add(CD + "AdobeRGB1998.icc", GS + "srgb.icc", "RGB", "RGB", rgb, 1, False)
add(CD + "ProPhotoRGB.icc", CD + "sRGB.icc", "RGB", "RGB", rgb, 1, False)
add(GS + "srgb.icc", CD + "AdobeRGB1998.icc", "RGB", "RGB", rgb, 3, False)
add(GS + "a98.icc", GS + "srgb.icc", "RGB", "RGB", rgb, 1, True)
add(GS + "default_gray.icc", GS + "srgb.icc", "L", "RGB", gray, 1, False)
with open(sys.argv[1], "w") as f:
    for c in cases:
        f.write("CASE %s %s %s %s %d %d\n" % (c["src"], c["dst"], c["inMode"], c["outMode"], c["intent"], 1 if c["bpc"] else 0))
        for i, o in zip(c["input"], c["output"]):
            f.write(",".join(map(str, i)) + ";" + ",".join(map(str, o)) + "\n")
print(len(cases), "Fälle")
