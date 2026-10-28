import gi, sys
gi.require_version('Gimp', '3.0')
from gi.repository import Gimp, Gio
path = sys.argv[-1]
img = Gimp.file_load(Gimp.RunMode.NONINTERACTIVE, Gio.File.new_for_path(path))
def walk(layers, d=0):
    for l in layers:
        x, y = l.get_offsets()[1:]
        print('  ' * d + f'- {l.get_name()} {l.get_width()}x{l.get_height()}+{x}+{y} mode={l.get_mode().value_nick} opac={l.get_opacity():.0f} vis={l.get_visible()} mask={l.get_mask() is not None}')
        if l.is_group(): walk(l.get_children(), d + 1)
sys.stdout = open(path + '.txt', 'w')
walk(img.get_layers())
print('GIMP-OK'); sys.stdout.flush()
