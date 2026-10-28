"""Erzeugt die Vergleichsseite für Logo-Entwürfe (design/logo-entwuerfe.html)."""
import math, pathlib

C = 54.0  # Mittelpunkt im 108er-Raster der adaptiven Icons

def f(v): return f"{v:.2f}".rstrip('0').rstrip('.')

def aperture(R=25.0, r=9.5, rot=15.0):
    """Blende: Scheibe mit sechs Lamellenfugen und sechseckiger Öffnung."""
    verts = []
    for i in range(6):
        a = math.radians(60 * i + rot)
        verts.append((r * math.cos(a), r * math.sin(a)))
    seams = []
    for i in range(6):
        vx, vy = verts[i]; wx, wy = verts[(i + 1) % 6]
        dx, dy = wx - vx, wy - vy; L = math.hypot(dx, dy); dx /= L; dy /= L
        b = vx * dx + vy * dy
        t = -b + math.sqrt(b * b - (vx * vx + vy * vy - R * R))
        seams.append((C + vx, C + vy, C + vx + t * dx, C + vy + t * dy))
    hexpath = "M" + " L".join(f"{f(C+x)},{f(C+y)}" for x, y in verts) + " Z"
    lines = "".join(f'<line x1="{f(a)}" y1="{f(b)}" x2="{f(c)}" y2="{f(d)}"/>' for a, b, c, d in seams)
    return R, hexpath, lines

R, HEX, SEAMS = aperture()

OPTIONS = [
    dict(
        key="blende", name="Blende",
        idea="Sechs Lamellen um eine sechseckige Öffnung – das Werkzeug jeder Kamera, in Silber auf Grafit. Ruhig, technisch, sofort als Fotografie lesbar.",
        bg=("#33363c", "#121316"),
        fg=f'''
          <circle cx="54" cy="54" r="{R}" fill="url(#silver-{{v}})"/>
          <g stroke="#15161a" stroke-width="2.2" stroke-linecap="round">{SEAMS}</g>
          <path d="{HEX}" fill="#0d0e11" stroke="#15161a" stroke-width="1.2" stroke-linejoin="round"/>
          <circle cx="54" cy="54" r="2.6" fill="#e9a43a"/>''',
        mono=f'''
          <circle cx="54" cy="54" r="{R}" fill="currentColor"/>
          <g stroke="{{hole}}" stroke-width="2.2" stroke-linecap="round">{SEAMS}</g>
          <path d="{HEX}" fill="{{hole}}"/>''',
    ),
    dict(
        key="monogramm", name="Linsen-P",
        idea="Ein Ring als Objektiv, ein Strich als Stamm – zusammen das P von Photographics. Der warme Punkt markiert den Fokus. Wirkt als Monogramm auch ganz klein.",
        bg=("#2c2f35", "#0f1013"),
        fg='''
          <circle cx="57" cy="47" r="14.5" fill="none" stroke="url(#silver-{v})" stroke-width="7"/>
          <rect x="39" y="32.5" width="7" height="46" rx="3.5" fill="url(#silver-{v})"/>
          <circle cx="57" cy="47" r="4" fill="#e9a43a"/>''',
        mono='''
          <circle cx="57" cy="47" r="14.5" fill="none" stroke="currentColor" stroke-width="7"/>
          <rect x="39" y="32.5" width="7" height="46" rx="3.5" fill="currentColor"/>
          <circle cx="57" cy="47" r="4" fill="currentColor"/>''',
    ),
    dict(
        key="fokus", name="Fokusrahmen",
        idea="Vier Ecken wie das Messfeld im Sucher oder der Rahmen beim Freistellen, darin eine leuchtende Kugel. Steht für Bildausschnitt und präzise Retusche.",
        bg=("#30333a", "#131418"),
        fg='''
          <g fill="none" stroke="url(#silver-{v})" stroke-width="4" stroke-linecap="round" stroke-linejoin="round">
            <path d="M31,43 V31 H43"/><path d="M65,31 H77 V43"/><path d="M77,65 V77 H65"/><path d="M43,77 H31 V65"/></g>
          <circle cx="54" cy="54" r="12" fill="url(#glow-{v})"/>''',
        mono='''
          <g fill="none" stroke="currentColor" stroke-width="4" stroke-linecap="round" stroke-linejoin="round">
            <path d="M31,43 V31 H43"/><path d="M65,31 H77 V43"/><path d="M77,65 V77 H65"/><path d="M43,77 H31 V65"/></g>
          <circle cx="54" cy="54" r="12" fill="currentColor"/>''',
    ),
    dict(
        key="ebenen", name="Ebenen",
        idea="Drei überlagerte Kreise in Stahltönen; wo sie sich decken, entstehen neue Töne – wie Ebenen und Mischmodi. Das Herz des Editors als Bild.",
        bg=("#2f3238", "#111215"),
        fg='''
          <g style="mix-blend-mode:screen">
            <circle cx="46" cy="61" r="15" fill="#5d6673"/>
            <circle cx="62" cy="61" r="15" fill="#8a929c"/>
            <circle cx="54" cy="46" r="15" fill="#e9a43a" fill-opacity=".85"/></g>''',
        mono='''
          <circle cx="46" cy="61" r="15" fill="currentColor" fill-opacity=".45"/>
          <circle cx="62" cy="61" r="15" fill="currentColor" fill-opacity=".45"/>
          <circle cx="54" cy="46" r="15" fill="currentColor" fill-opacity=".75"/>''',
    ),
]

_uid = [0]
def icon_svg(o, size, shape, mono=False, tint_bg=None, tint_fg=None):
    _uid[0] += 1; v = f"{o['key']}{_uid[0]}"
    defs = f'''<defs>
      <linearGradient id="bg-{v}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{o['bg'][0]}"/><stop offset="1" stop-color="{o['bg'][1]}"/></linearGradient>
      <linearGradient id="silver-{v}" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#f4f5f7"/><stop offset=".55" stop-color="#b9bec6"/><stop offset="1" stop-color="#7d838d"/></linearGradient>
      <radialGradient id="glow-{v}" cx=".38" cy=".35" r=".75"><stop offset="0" stop-color="#ffd38a"/><stop offset=".6" stop-color="#e9a43a"/><stop offset="1" stop-color="#9c5f12"/></radialGradient>
    </defs>'''
    if mono:
        bg = f'<rect width="108" height="108" fill="{tint_bg}"/>'
        fg = f'<g style="color:{tint_fg}">' + o['mono'].replace('{v}', v).replace('{hole}', tint_bg) + '</g>'
    else:
        bg = f'<rect width="108" height="108" fill="url(#bg-{v})"/>'
        fg = o['fg'].replace('{v}', v)
    return (f'<svg class="icon {shape}" width="{size}" height="{size}" viewBox="18 18 72 72" role="img" '
            f'aria-label="{o["name"]}">{defs}{bg}{fg}</svg>')

cards = []
for i, o in enumerate(OPTIONS, 1):
    cards.append(f'''
    <article class="card" id="{o['key']}">
      <header><span class="tag">Entwurf {chr(64+i)}</span><h2>{o['name']}</h2></header>
      <div class="hero">{icon_svg(o, 168, 'squircle')}</div>
      <p class="idea">{o['idea']}</p>
      <div class="variants">
        <figure class="wall light">{icon_svg(o, 64, 'circle')}<figcaption>Rund · hell</figcaption></figure>
        <figure class="wall dark">{icon_svg(o, 64, 'squircle')}<figcaption>Squircle · dunkel</figcaption></figure>
        <figure class="wall light">{icon_svg(o, 40, 'circle')}<figcaption>Klein</figcaption></figure>
        <figure class="wall themed">{icon_svg(o, 64, 'circle', mono=True, tint_bg='#d9e2ff', tint_fg='#2c4278')}<figcaption>Themed Icon</figcaption></figure>
      </div>
      <div class="wordmark">{icon_svg(o, 36, 'squircle')}<span>Photographics</span></div>
    </article>''')

html = f'''<title>Photographics Logo-Entwürfe</title>
<meta name="claude:viewport-fit-sides" content="cover">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Sora:wght@500;600&family=IBM+Plex+Sans:wght@400;500&display=swap">
<style>
/* Layout: ruhige Galerie wie ein Kontaktbogen – vier gleich gebaute Karten, Icons stehen im Mittelpunkt */
:root {{
  --bg: #eef0f3; --surface: #ffffff; --fg: #1b1d21; --muted: #5d636d; --line: #d9dde3; --accent: #b8771f;
  --wall-light: #c9d3df; --wall-dark: #1d2026;
  --display: "Sora", "Segoe UI", system-ui, sans-serif;
  --body: "IBM Plex Sans", "Segoe UI", system-ui, sans-serif;
}}
@media (prefers-color-scheme: dark) {{ :root:not([data-theme="light"]) {{
  --bg: #121316; --surface: #1b1d22; --fg: #e8eaee; --muted: #9aa1ab; --line: #2c2f36; --accent: #e9a43a;
  --wall-light: #8e9bab; --wall-dark: #0b0c0e; color-scheme: dark; }} }}
:root[data-theme="dark"] {{
  --bg: #121316; --surface: #1b1d22; --fg: #e8eaee; --muted: #9aa1ab; --line: #2c2f36; --accent: #e9a43a;
  --wall-light: #8e9bab; --wall-dark: #0b0c0e; color-scheme: dark; }}
body {{ background: var(--bg); color: var(--fg); font-family: var(--body); font-size: 15px; line-height: 1.55;
  padding-left: max(16px, env(safe-area-inset-left, 0px)); padding-right: max(16px, env(safe-area-inset-right, 0px)); }}
main {{ max-width: 1180px; margin: 0 auto; padding-block: 40px 56px; display: grid; gap: 32px; }}
.intro h1 {{ font-family: var(--display); font-weight: 600; font-size: clamp(26px, 3.2vw, 36px); margin: 0 0 8px; text-wrap: balance; letter-spacing: -0.01em; }}
.intro p {{ margin: 0; color: var(--muted); max-width: 65ch; }}
.grid {{ display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: 20px; }}
.card {{ background: var(--surface); border: 1px solid var(--line); border-radius: 20px; padding: 22px; display: grid; gap: 16px; align-content: start; min-width: 0; }}
.card header {{ display: grid; gap: 2px; }}
.tag {{ font-size: 12px; letter-spacing: .08em; text-transform: uppercase; color: var(--accent); font-weight: 500; }}
.card h2 {{ font-family: var(--display); font-weight: 600; font-size: 21px; margin: 0; }}
.hero {{ display: grid; place-items: center; padding: 20px 0; background: radial-gradient(circle at 50% 40%, var(--line), transparent 70%); border-radius: 14px; }}
.idea {{ margin: 0; color: var(--muted); font-size: 14px; min-height: 6.2em; }}
.variants {{ display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; }}
figure {{ margin: 0; display: grid; justify-items: center; align-content: center; gap: 6px; padding: 12px 4px 8px; border-radius: 12px; min-height: 104px; }}
figcaption {{ font-size: 11px; letter-spacing: .02em; color: #fff; opacity: .85; text-align: center; }}
.wall.light {{ background: linear-gradient(160deg, var(--wall-light), #7f8fa3); }}
.wall.dark {{ background: linear-gradient(160deg, #2a2e36, var(--wall-dark)); }}
.wall.themed {{ background: linear-gradient(160deg, #3b4a6b, #1d2436); }}
.icon {{ display: block; max-width: 100%; height: auto; box-shadow: 0 6px 16px rgba(0,0,0,.28); }}
.icon.circle {{ border-radius: 50%; }}
.icon.squircle {{ border-radius: 28%; }}
.wordmark {{ display: flex; align-items: center; gap: 10px; padding-top: 12px; border-top: 1px solid var(--line); }}
.wordmark .icon {{ box-shadow: none; }}
.wordmark span {{ font-family: var(--display); font-weight: 500; font-size: 17px; letter-spacing: -0.005em; }}
.note {{ color: var(--muted); font-size: 13px; margin: 0; max-width: 70ch; }}
@media (max-width: 420px) {{ .variants {{ grid-template-columns: repeat(2, 1fr); }} }}
</style>
<main>
  <section class="intro">
    <h1>Photographics – Logo-Entwürfe</h1>
    <p>Vier eigenständige Richtungen im Geist reduzierter Profi-Fotosoftware: Grafit, gebürstetes Silber und ein einziger warmer Akzent. Jede Karte zeigt das Icon so, wie Android es darstellt – rund oder als Squircle, auf hellem und dunklem Hintergrund, in Launcher-Größe und als einfarbiges Themed Icon (Android 13+).</p>
  </section>
  <section class="grid">{"".join(cards)}</section>
  <p class="note">Die Entwürfe sind eigene Gestaltungen und bilden keine fremden Marken nach. Farben, Strichstärken und der Akzent lassen sich bei jeder Variante anpassen.</p>
</main>
'''
out = pathlib.Path(__file__).resolve().parent.parent / "design" / "logo-entwuerfe.html"
out.write_text(html, encoding="utf-8")
print(out)
