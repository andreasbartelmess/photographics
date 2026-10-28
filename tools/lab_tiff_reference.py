"""Referenz für unkomprimierte CIELab-TIFFs (8 Bit, ein Streifen): exakte Umrechnung in Gleitkomma,
Lab relativ zu D50 (wie Photoshop/ICC) → XYZ → sRGB (Bradford-adaptiert). ImageMagick rechnet Lab mit
D65, Little CMS interpoliert bei 8-Bit-Lab über ein grobes Gitter – beide taugen hier nicht als Referenz."""
import struct, sys
from PIL import Image

src, dst = sys.argv[1], sys.argv[2]
b = open(src, 'rb').read()
E = '<' if b[:2] == b'II' else '>'
off = struct.unpack(E + 'I', b[4:8])[0]
n = struct.unpack(E + 'H', b[off:off + 2])[0]
tags = {}
for i in range(n):
    t, ty, c, v = struct.unpack(E + 'HHI4s', b[off + 2 + 12 * i:off + 14 + 12 * i])
    tags[t] = (ty, c, v)
def one(t):
    ty, c, v = tags[t]
    return struct.unpack(E + ('H' if ty == 3 else 'I'), v[:2 if ty == 3 else 4])[0]
w, h, spp = one(256), one(257), one(277)
assert one(262) == 8 and one(259) == 1, 'nur unkomprimiertes CIELab'
raw = b[one(273):one(273) + one(279)]
lab, alpha = bytearray(), bytearray()
for i in range(w * h):
    lab += raw[spp * i:spp * i + 3]
    alpha.append(raw[spp * i + 3] if spp > 3 else 255)
M = [[3.1338561, -1.6168667, -0.4906146], [-0.9787684, 1.9161415, 0.0334540], [0.0719453, -0.2289914, 1.4052427]]
WHITE = (0.9642, 1.0, 0.8249)

def finv(t):
    return t ** 3 if t ** 3 > 216 / 24389 else (116 * t - 16) / (24389 / 27)

def gamma(v):
    v = max(0.0, min(1.0, v))
    return round(255 * (12.92 * v if v <= 0.0031308 else 1.055 * v ** (1 / 2.4) - 0.055))

out = bytearray()
for i in range(w * h):
    L = lab[3 * i] * 100 / 255
    a = struct.unpack('b', lab[3 * i + 1:3 * i + 2])[0]
    bb = struct.unpack('b', lab[3 * i + 2:3 * i + 3])[0]
    fy = (L + 16) / 116
    xyz = (WHITE[0] * finv(fy + a / 500), WHITE[1] * finv(fy), WHITE[2] * finv(fy - bb / 200))
    out += bytes(gamma(sum(M[r][c] * xyz[c] for c in range(3))) for r in range(3)) + bytes([alpha[i]])
Image.frombytes('RGBA', (w, h), bytes(out)).save(dst)
