import gi, sys
gi.require_version('Gimp', '3.0'); gi.require_version('Gegl', '0.4')
from gi.repository import Gimp, Gio, Gegl
out = sys.argv[-1] if len(sys.argv) > 1 else '/tmp/x.psd'
W, H = 400, 300
img = Gimp.Image.new(W, H, Gimp.ImageBaseType.RGB)
def color(r, g, b):
    c = Gegl.Color.new('black'); c.set_rgba(r, g, b, 1.0); return c
def solid(name, x, y, w, h, rgb, opacity=100.0, mode=Gimp.LayerMode.NORMAL, parent=None, pos=0):
    l = Gimp.Layer.new(img, name, w, h, Gimp.ImageType.RGBA_IMAGE, opacity, mode)
    img.insert_layer(l, parent, pos)
    l.set_offsets(x, y)
    Gimp.context_set_foreground(color(*rgb))
    l.fill(Gimp.FillType.FOREGROUND)
    return l
bg = solid('Hintergrund', 0, 0, W, H, (0.2, 0.45, 0.8))
red = solid('Rot 70%', 40, 30, 160, 120, (1, 0, 0), opacity=70.0)
mul = solid('Gelb Multiplizieren', 150, 60, 200, 200, (1, 0.9, 0), mode=Gimp.LayerMode.MULTIPLY)
grp = Gimp.GroupLayer.new(img, 'Gruppe Ä')
img.insert_layer(grp, None, 0)
scr = solid('Grün Negativ mult.', 220, 150, 150, 120, (0, 0.8, 0.2), mode=Gimp.LayerMode.SCREEN, parent=grp)
masked = solid('Lila mit Maske', 20, 180, 200, 100, (0.6, 0.1, 0.8), parent=grp)
mask = masked.create_mask(Gimp.AddMaskType.WHITE)
masked.add_mask(mask)
mask.fill(Gimp.FillType.WHITE)
Gimp.context_set_foreground(color(0, 0, 0))
img.select_rectangle(Gimp.ChannelOps.REPLACE, 20, 180, 100, 100)
mask.edit_fill(Gimp.FillType.FOREGROUND)
Gimp.Selection.none(img)
hidden = solid('Ausgeblendet', 0, 0, 50, 50, (1, 1, 1))
hidden.set_visible(False)
Gimp.file_save(Gimp.RunMode.NONINTERACTIVE, img, Gio.File.new_for_path(out), None)
flat = img.duplicate(); flat.flatten()
Gimp.file_save(Gimp.RunMode.NONINTERACTIVE, flat, Gio.File.new_for_path(out.replace('.psd', '_ref.png')), None)
print('OK', out)
