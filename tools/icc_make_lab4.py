#!/usr/bin/env python3
"""Erzeugt mit LittleCMS ein v4-Lab-Profil (mAB/mBA-Tabellen) als Testmaterial."""
import ctypes, ctypes.util, sys
lcms = ctypes.CDLL(ctypes.util.find_library("lcms2") or "liblcms2.so.2")
lcms.cmsCreateLab4Profile.restype = ctypes.c_void_p
lcms.cmsCreateLab4Profile.argtypes = [ctypes.c_void_p]
lcms.cmsSaveProfileToFile.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
h = lcms.cmsCreateLab4Profile(None)
assert lcms.cmsSaveProfileToFile(h, sys.argv[1].encode())
print("ok")

# Abstraktes v4-Profil mit Farbtabelle (mAB mit CLUT) + Referenzwerte über eine LittleCMS-Transformation
lcms.cmsCreateBCHSWabstractProfile.restype = ctypes.c_void_p
lcms.cmsCreateBCHSWabstractProfile.argtypes = [ctypes.c_uint32, ctypes.c_double, ctypes.c_double, ctypes.c_double, ctypes.c_double, ctypes.c_uint32, ctypes.c_uint32]
abst = lcms.cmsCreateBCHSWabstractProfile(17, 8.0, 1.15, 25.0, 1.3, 5000, 5000)
out_dir = sys.argv[1].rsplit("/", 1)[0]
assert lcms.cmsSaveProfileToFile(abst, (out_dir + "/bchsw-v2.icc").encode())
TYPE_Lab_DBL = (1 << 22) | (10 << 16) | (3 << 3)
lcms.cmsCreateMultiprofileTransform.restype = ctypes.c_void_p
lcms.cmsCreateMultiprofileTransform.argtypes = [ctypes.POINTER(ctypes.c_void_p), ctypes.c_uint32, ctypes.c_uint32, ctypes.c_uint32, ctypes.c_uint32, ctypes.c_uint32]
lcms.cmsDoTransform.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_uint32]
lab = lcms.cmsCreateLab4Profile(None)
arr = (ctypes.c_void_p * 3)(lab, abst, lab)
t = lcms.cmsCreateMultiprofileTransform(arr, 3, TYPE_Lab_DBL, TYPE_Lab_DBL, 0, 0x0100)
with open(out_dir + "/bchsw-reference.txt", "w") as f:
    for L in range(0, 101, 10):
        for a in range(-100, 101, 25):
            for b in range(-100, 101, 25):
                src = (ctypes.c_double * 3)(L, a, b); dst = (ctypes.c_double * 3)()
                lcms.cmsDoTransform(t, src, dst, 1)
                f.write("%g,%g,%g;%.4f,%.4f,%.4f\n" % (L, a, b, dst[0], dst[1], dst[2]))
print("Referenz geschrieben")
