"""Erzeugt XCF-Testdateien mit GIMP 3:
    gimp-console-3 -i --quit --batch-interpreter python-fu-eval -b "import sys; sys.argv=['x', '<ziel>']; exec(open('tools/make_xcf_fixtures.py').read())"
Je Datei: <name>.xcf, <name>.layers.txt (Struktur laut GIMP), <name>.flat.png (von GIMP zusammengefügt)
sowie <name>.layer<i>.png / <name>.mask<i>.png (Pixel bzw. Maske der Ebene i in Dateireihenfolge)."""
import gi, sys, os
gi.require_version('Gimp', '3.0'); gi.require_version('Gegl', '0.4')
from gi.repository import Gimp, Gio, Gegl
out = sys.argv[-1]
os.makedirs(out, exist_ok=True)
pdb = Gimp.get_pdb()

def color(r, g, b, a=1.0):
    c = Gegl.Color.new('black'); c.set_rgba(r, g, b, a); return c

def fill_rect(img, layer, x, y, w, h, c):
    img.select_rectangle(Gimp.ChannelOps.REPLACE, x, y, w, h)
    Gimp.context_set_foreground(c)
    layer.edit_fill(Gimp.FillType.FOREGROUND)
    Gimp.Selection.none(img)

def gradient_layer(img, name, w, h):
    l = Gimp.Layer.new(img, name, w, h, Gimp.ImageType.RGBA_IMAGE, 100, Gimp.LayerMode.NORMAL)
    img.insert_layer(l, None, 0)
    Gimp.context_set_foreground(color(0.1, 0.3, 0.8)); Gimp.context_set_background(color(0.95, 0.85, 0.4))
    Gimp.context_set_gradient_fg_bg_rgb()
    l.edit_gradient_fill(Gimp.GradientType.LINEAR, 0, False, 1, 0, True, 0, 0, w, h)
    return l

def build(name, precision=None, base=Gimp.ImageBaseType.RGB):
    W, H = 160, 120
    img = Gimp.Image.new(W, H, Gimp.ImageBaseType.RGB)
    bg = gradient_layer(img, "Hintergrund", W, H)
    red = Gimp.Layer.new(img, "Rot Multiplizieren", 70, 50, Gimp.ImageType.RGBA_IMAGE, 60, Gimp.LayerMode.MULTIPLY)
    img.insert_layer(red, None, 0); red.set_offsets(-15, 10)
    fill_rect(img, red, -15, 10, 70, 50, color(0.85, 0.15, 0.1))
    grp = Gimp.GroupLayer.new(img, "Gruppe")
    img.insert_layer(grp, None, 0)
    g1 = Gimp.Layer.new(img, "Grün", 60, 60, Gimp.ImageType.RGBA_IMAGE, 100, Gimp.LayerMode.SCREEN)
    img.insert_layer(g1, grp, 0); g1.set_offsets(70, 40)
    fill_rect(img, g1, 70, 40, 60, 60, color(0.1, 0.7, 0.2))
    mask = g1.create_mask(Gimp.AddMaskType.WHITE); g1.add_mask(mask)
    img.select_rectangle(Gimp.ChannelOps.REPLACE, 100, 40, 30, 60)
    Gimp.context_set_foreground(color(0, 0, 0)); mask.edit_fill(Gimp.FillType.FOREGROUND); Gimp.Selection.none(img)
    g2 = Gimp.Layer.new(img, "Blau unsichtbar", 40, 40, Gimp.ImageType.RGBA_IMAGE, 100, Gimp.LayerMode.NORMAL)
    img.insert_layer(g2, grp, 1); g2.set_offsets(20, 60)
    fill_rect(img, g2, 20, 60, 40, 40, color(0.1, 0.2, 0.9)); g2.set_visible(False)
    img.add_vguide(40); img.add_hguide(90)
    img.set_resolution(300.0, 300.0)
    if base == Gimp.ImageBaseType.GRAY: img.convert_grayscale()
    if base == Gimp.ImageBaseType.INDEXED:
        # GIMP wandelt Bilder mit Ebenengruppen nicht in indizierte Farben um → vorher zusammenfügen
        img.merge_visible_layers(Gimp.MergeType.EXPAND_AS_NECESSARY)
        img.convert_indexed(Gimp.ConvertDitherType.NONE, Gimp.ConvertPaletteType.GENERATE, 64, False, False, Gimp.palettes_get_list("")[0].get_name())
    if precision is not None: img.convert_precision(precision)
    path = os.path.join(out, name + ".xcf")
    Gimp.file_save(Gimp.RunMode.NONINTERACTIVE, img, Gio.File.new_for_path(path), None)
    # Struktur und Pixel jeder Ebene (auf Arbeitsfläche, als RGBA-PNG)
    lines = []
    def walk(items, depth):
        for it in items:
            mode = it.get_mode()
            ox, oy = it.get_offsets()[1:]
            lines.append("%s%s|mode=%d|opacity=%.1f|visible=%d|offset=%d,%d|size=%dx%d|group=%d|mask=%d" % (
                "  " * depth, it.get_name(), int(mode), it.get_opacity(), it.get_visible(), ox, oy, it.get_width(), it.get_height(),
                1 if it.is_group() else 0, 1 if it.get_mask() else 0))
            if it.is_group(): walk(it.get_children(), depth + 1)
    walk(img.get_layers(), 0)
    open(os.path.join(out, name + ".layers.txt"), "w").write("\n".join(lines) + "\n")
    # Pixel jeder Ebene einzeln (Dateireihenfolge, ohne Maske, volle Deckkraft) und jede Maske als Graubild
    index = [0]
    def export_layers(items):
        for it in items:
            i = index[0]; index[0] += 1
            if it.is_group(): export_layers(it.get_children()); continue
            for src, suffix in ((it, "layer"), (it.get_mask(), "mask")):
                if src is None: continue
                one = Gimp.Image.new(img.get_width(), img.get_height(), Gimp.ImageBaseType.RGB)
                l2 = Gimp.Layer.new_from_drawable(src, one)
                one.insert_layer(l2, None, 0)
                l2.set_offsets(*it.get_offsets()[1:])
                if l2.get_mask(): l2.remove_mask(Gimp.MaskApplyMode.DISCARD)
                l2.set_opacity(100); l2.set_mode(Gimp.LayerMode.NORMAL); l2.set_visible(True)
                one.convert_precision(Gimp.Precision.U8_NON_LINEAR)
                Gimp.file_save(Gimp.RunMode.NONINTERACTIVE, one, Gio.File.new_for_path(os.path.join(out, "%s.%s%d.png" % (name, suffix, i))), None)
    export_layers(img.get_layers())
    flat = img.duplicate(); flat.merge_visible_layers(Gimp.MergeType.CLIP_TO_IMAGE)
    if flat.get_base_type() != Gimp.ImageBaseType.RGB: flat.convert_rgb()
    flat.convert_precision(Gimp.Precision.U8_NON_LINEAR)
    Gimp.file_save(Gimp.RunMode.NONINTERACTIVE, flat, Gio.File.new_for_path(os.path.join(out, name + ".flat.png")), None)

build("rgb8")
build("rgb16lin", Gimp.Precision.U16_LINEAR)
build("float32", Gimp.Precision.FLOAT_LINEAR)
build("gray8", None, Gimp.ImageBaseType.GRAY)
build("indexed", None, Gimp.ImageBaseType.INDEXED)
print("fertig")
