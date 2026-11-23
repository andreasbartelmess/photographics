#!/usr/bin/env python3
"""
LaMa HD für photographics erzeugen: Big-LaMa (Apache 2.0, https://github.com/advimman/lama) als ONNX
mit freier Eingangsgröße.

Die üblichen ONNX-Fassungen (OpenCV Zoo, Carve/LaMa-ONNX) rechnen fest mit 512 × 512 Pixeln; größere
Ausschnitte werden dafür verkleinert und verlieren Schärfe. Dieses Skript

1. lädt die Gewichte aus Carve/LaMa-ONNX (Apache 2.0; reines ONNX, kein Pickle),
2. baut damit den Generator aus dem LaMa-Quelltext (ffc.py) nach – gefaltete BatchNorm-Schichten
   werden als Bias abgebildet –, prüft ihn gegen das Original,
3. ersetzt die FFT der Fourier-Einheiten durch DFT-Matrizen, die im Graphen aus der Eingangsgröße
   entstehen (kein DFT-Operator nötig, jede Größe als Vielfaches von 8),
4. exportiert nach ONNX (opset 17) und speichert die Gewichte als fp16 mit Umwandlung beim Laden
   (halbe Downloadgröße, gerechnet wird in fp32).

Aufruf (Python 3.12, torch CPU, onnx, onnxruntime, numpy, pyyaml):
    python3 export_lama_hd.py <arbeitsordner> <ausgabe.onnx>
Eingaben: image (1×3×H×W, 0..1), mask (1×1×H×W, 1 = Lücke); Ausgabe: output (1×3×H×W, 0..1).
"""
import math, os, sys, types, urllib.request

import numpy as np
import onnx
import torch
from onnx import TensorProto, helper, numpy_helper

LAMA = 'https://raw.githubusercontent.com/advimman/lama/786f5936b27fb3dacd2b1ad799e4de968ea697e7/saicinpainting/training/modules/'
CARVE = 'https://huggingface.co/Carve/LaMa-ONNX/resolve/main/lama_fp32.onnx'


def fetch(url, path):
    if not os.path.exists(path):
        print('lade', url)
        urllib.request.urlretrieve(url, path)
    return path


def lama_modules(work):
    """ffc.py aus dem LaMa-Repository mit minimalen Platzhaltern für die übrigen Module."""
    pkg = os.path.join(work, 'pkg', 'saicinpainting')
    mods = os.path.join(pkg, 'training', 'modules')
    os.makedirs(mods, exist_ok=True)
    for d in (pkg, os.path.join(pkg, 'training'), mods):
        open(os.path.join(d, '__init__.py'), 'a').close()
    for f in ('ffc.py', 'squeeze_excitation.py'):
        fetch(LAMA + f, os.path.join(mods, f))
    with open(os.path.join(mods, 'base.py'), 'w') as f:
        f.write('import torch.nn as nn\nclass BaseDiscriminator(nn.Module): pass\n'
                'def get_activation(kind="tanh"):\n'
                '    return {"tanh": nn.Tanh(), "sigmoid": nn.Sigmoid(), False: nn.Identity()}[kind]\n')
    with open(os.path.join(mods, 'spatial_transform.py'), 'w') as f:
        f.write('class LearnableSpatialTransformWrapper: pass\n')
    with open(os.path.join(pkg, 'utils.py'), 'w') as f:
        f.write('def get_shape(t): return t.shape\n')
    sys.path.insert(0, os.path.join(work, 'pkg'))
    from saicinpainting.training.modules import ffc
    return ffc


def generator(ffc):
    # Konfiguration von big-lama (configs/training/big-lama.yaml)
    return ffc.FFCResNetGenerator(
        input_nc=4, output_nc=3, ngf=64, n_downsampling=3, n_blocks=18, add_out_act='sigmoid',
        init_conv_kwargs=dict(ratio_gin=0, ratio_gout=0, enable_lfu=False),
        downsample_conv_kwargs=dict(ratio_gin=0, ratio_gout=0, enable_lfu=False),
        resnet_conv_kwargs=dict(ratio_gin=0.75, ratio_gout=0.75, enable_lfu=False))


def module_path(node_name):
    segs = node_name.strip('/').split('/')[:-1]
    out = []
    for s in segs[1:]:
        if out and s.startswith(out[-1] + '.'):
            out[-1] = s
        else:
            out.append(s)
    return '.'.join(out)


def bn_partner(p):
    if p.endswith('.ffc.convl2l'): return p[:-len('ffc.convl2l')] + 'bn_l'
    if p.endswith('.ffc.convl2g'): return p[:-len('ffc.convl2g')] + 'bn_g'
    if p.endswith('.fu.conv_layer'): return p[:-len('conv_layer')] + 'bn'
    if p.endswith('.0'): return p[:-1] + '1'
    raise KeyError(p)


def load_weights(g, onnx_path):
    m = onnx.load(onnx_path)
    inits = {i.name: torch.from_numpy(numpy_helper.to_array(i).copy()) for i in m.graph.initializer}
    sd = g.state_dict()
    new = {k[len('model.generator.'):]: v for k, v in inits.items()
           if k.startswith('model.generator.') and k[len('model.generator.'):] in sd}
    mods = dict(g.named_modules())
    for n in m.graph.node:
        if n.op_type not in ('Conv', 'ConvTranspose') or not any(i.startswith('onnx::') for i in n.input[1:]):
            continue
        # Konvolution mit eingefalteter BatchNorm: Gewicht übernehmen, BatchNorm wird zum reinen Bias
        p = module_path(n.name)
        new[p + '.weight'] = inits[n.input[1]]
        bias = inits[n.input[2]]
        bn = bn_partner(p)
        c = bias.shape[0]
        new[bn + '.weight'] = torch.ones(c); new[bn + '.bias'] = bias
        new[bn + '.running_mean'] = torch.zeros(c); new[bn + '.running_var'] = torch.full((c,), 1.0 - mods[bn].eps)
    for k in sd:
        if k.endswith('num_batches_tracked'): new[k] = sd[k]
    g.load_state_dict(new, strict=True)
    return g.eval()


def dft(n, dtype):
    k = torch.arange(n, dtype=dtype)
    a = 2 * math.pi * k[:, None] * k[None, :] / n
    return torch.cos(a), torch.sin(a)


def fourier_unit_forward(self, x):
    """Wie FourierUnit.forward (rfftn/irfftn, norm='ortho'), aber mit DFT-Matrizen."""
    b = x.shape[0]
    h, w = x.shape[-2], x.shape[-1]
    wf = w // 2 + 1
    cw, sw = dft(w, x.dtype)
    a = x @ cw[:, :wf]; bi = -(x @ sw[:, :wf])
    ch, sh = dft(h, x.dtype)
    s = torch.rsqrt(torch.tensor(1.0, dtype=x.dtype) * h * w)
    re = (ch @ a + sh @ bi) * s; im = (ch @ bi - sh @ a) * s
    ff = torch.stack((re, im), dim=2).reshape(b, -1, h, wf)
    ff = self.relu(self.bn(self.conv_layer(ff))).reshape(b, -1, 2, h, wf)
    re, im = ff[:, :, 0], ff[:, :, 1]
    yr = ch @ re - sh @ im; yi = ch @ im + sh @ re
    l = torch.arange(wf, dtype=x.dtype); n = torch.arange(w, dtype=x.dtype)
    ang = 2 * math.pi * l[:, None] * n[None, :] / w
    wt = torch.where((l == 0) | (l * 2 == w), torch.ones_like(l), 2 * torch.ones_like(l))[:, None]
    return (yr @ (wt * torch.cos(ang)) - yi @ (wt * torch.sin(ang))) * s


class Lama(torch.nn.Module):
    def __init__(self, g):
        super().__init__(); self.g = g

    def forward(self, image, mask):
        m = (mask > 0.5).to(image.dtype)
        out = self.g(torch.cat([image * (1 - m), m], dim=1))
        return m * out + (1 - m) * image


def fp16_weights(src, dst):
    m = onnx.load(src)
    g = m.graph
    inits, casts = [], []
    for init in list(g.initializer):
        a = numpy_helper.to_array(init)
        if a.dtype == np.float32 and a.size >= 256 and np.abs(a).max() < 60000:
            h = numpy_helper.from_array(a.astype(np.float16), init.name + '_fp16')
            inits.append(h)
            casts.append(helper.make_node('Cast', [h.name], [init.name], to=TensorProto.FLOAT, name='cast_' + init.name))
        else:
            inits.append(init)
    del g.initializer[:]; g.initializer.extend(inits)
    nodes = casts + list(g.node)
    del g.node[:]; g.node.extend(nodes)
    onnx.save(m, dst)


def main():
    work, out = sys.argv[1], sys.argv[2]
    os.makedirs(work, exist_ok=True)
    ffc = lama_modules(work)
    carve = fetch(CARVE, os.path.join(work, 'lama_fp32.onnx'))
    model = Lama(load_weights(generator(ffc), carve))

    import onnxruntime as ort
    torch.manual_seed(1)
    img = torch.rand(1, 3, 512, 512); msk = torch.zeros(1, 1, 512, 512); msk[..., 150:330, 200:380] = 1
    with torch.no_grad():
        ref = model(img, msk).numpy()
    o = ort.SessionOptions(); o.log_severity_level = 3
    orig = ort.InferenceSession(carve, o).run(None, {'image': img.numpy(), 'mask': msk.numpy()})[0] / 255.0
    print('Nachbau vs. Original (512²): max', float(np.abs(orig - ref).max()))
    assert np.abs(orig - ref).max() < 1e-3

    ffc.FourierUnit.forward = fourier_unit_forward
    with torch.no_grad():
        dft_out = model(img, msk).numpy()
    print('DFT vs. FFT: max', float(np.abs(dft_out - ref).max()))
    assert np.abs(dft_out - ref).max() < 1e-3

    tmp = out + '.fp32.onnx'
    x = torch.rand(1, 3, 200, 264); mx = torch.zeros(1, 1, 200, 264); mx[..., 60:140, 80:180] = 1
    torch.onnx.export(model, (x, mx), tmp, input_names=['image', 'mask'], output_names=['output'],
                      dynamic_axes={k: {2: 'height', 3: 'width'} for k in ('image', 'mask', 'output')},
                      opset_version=17, dynamo=False)
    fp16_weights(tmp, out)
    os.remove(tmp)
    s = ort.InferenceSession(out, o)
    for hw in ((512, 512), (384, 640)):
        torch.manual_seed(2)
        x = torch.rand(1, 3, *hw); mx = torch.zeros(1, 1, *hw); mx[..., hw[0] // 4:hw[0] // 2, hw[1] // 3:hw[1] // 2] = 1
        with torch.no_grad():
            r = model(x, mx).numpy()
        d = np.abs(s.run(None, {'image': x.numpy(), 'mask': mx.numpy()})[0] - r)
        print(f'ONNX {hw}: mittel {d.mean() * 255:.3f}, max {d.max() * 255:.2f} (von 255)')
        assert d.mean() * 255 < 1.0
    print('fertig:', out, f'{os.path.getsize(out) / 1e6:.1f} MB')


if __name__ == '__main__':
    main()
