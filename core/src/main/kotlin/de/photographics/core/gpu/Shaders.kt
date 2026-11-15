package de.photographics.core.gpu

import de.photographics.core.render.BlendMode

/**
 * GLSL-ES-3.00-Quellen des [GpuCompositor]. Sie bilden die CPU-Fassung (Compositor, BlendMode)
 * Formel für Formel nach; Tests vergleichen beide pixelweise.
 *
 * Koordinaten: Zielpixel (x, y) eines Puffers = `gl_FragCoord.xy` ohne Spiegelung; [uOut0] ist das
 * Ausgabepixel, das im Puffer bei (0, 0) liegt.
 */
internal object Shaders {
    /** Rechteck [uRect] (Pixel des Ziels x0, y0, x1, y1) über ein Ziel der Größe [uTarget].xy, ohne Vertexpuffer. */
    val vertex = """
        #version 300 es
        uniform vec4 uRect;
        uniform vec4 uTarget;
        void main() {
            vec2 c = vec2((gl_VertexID & 1) == 0 ? uRect.x : uRect.z, (gl_VertexID & 2) == 0 ? uRect.y : uRect.w);
            gl_Position = vec4(c / uTarget.xy * 2.0 - 1.0, 0.0, 1.0);
        }
    """.trimIndent()

    private val common = header(fetch = false)

    /**
     * Kopf aller Fragment-Shader. [fetch]: das Ziel ist zugleich Eingabe (`EXT_shader_framebuffer_fetch`,
     * Mobil-GPUs mischen dann im Chip, ohne Zwischenpuffer).
     */
    private fun header(fetch: Boolean) = """
        #version 300 es
        ${if (fetch) "#extension GL_EXT_shader_framebuffer_fetch : require" else ""}
        precision highp float;
        precision highp int;
        precision highp sampler2D;
        uniform ivec2 uOut0;
        ${if (fetch) "inout highp vec4 o;" else "out vec4 o;"}
        int fdiv(int a, int b) { return a >= 0 ? a / b : -((b - 1 - a) / b); }
        ivec2 outPx() { return ivec2(gl_FragCoord.xy) + uOut0; }
    """.trimIndent()

    /**
     * Kachel eines Rasters lesen (wie `Compositor.Area.read`): [uMap] = Schritt, 2^Stufe, Versatz x/y
     * der Ebene; [uTile0] = Ursprung der Kachel und [uExt] = gespeicherter Bereich, beides in
     * Pixeln der Stufe. ARGB-Kacheln liegen als Bytes B, G, R, A vor und werden vormultipliziert,
     * Masken (R8) als Grauwert in alle Kanäle geschrieben.
     */
    val sample = common + "\n" + tileFn + "\n" + """
        void main() {
            vec4 c = sampleTile();
            o = uScalar == 1 ? vec4(c.r) : vec4(c.b * c.a, c.g * c.a, c.r * c.a, c.a);
        }
    """.trimIndent()

    /** Kachel lesen (verwirft Pixel außerhalb): roher Texel; der Aufrufer vormultipliziert. */
    private val tileFn get() = """
        uniform sampler2D uTile;
        uniform ivec4 uMap;
        uniform ivec2 uTile0;
        uniform ivec4 uExt;
        uniform int uScalar;
        vec4 sampleTile() {
            ivec2 p = outPx();
            int st = uMap.x; int f = uMap.y; ivec2 d = uMap.zw;
            ivec2 s;
            if (st == 1) s = p - d;
            else if (st == f) s = p - ivec2(fdiv(d.x, f), fdiv(d.y, f));
            else { int h = st / 2; s = ivec2(fdiv(p.x * st + h - d.x, f), fdiv(p.y * st + h - d.y, f)); }
            if (s.x < uExt.x || s.y < uExt.y || s.x >= uExt.z || s.y >= uExt.w) discard;
            ivec2 t = s - uTile0;
            if (t.x < 0 || t.y < 0 || t.x >= 256 || t.y >= 256) discard;
            return texelFetch(uTile, t, 0);
        }
    """.trimIndent()

    private val modes = BlendMode.entries.joinToString("\n") { "const int M_${it.name} = ${it.ordinal};" }

    /**
     * Mischen von [uSrc] über [uDst] (beide vormultipliziert), Ergebnis in einen dritten Puffer.
     * 4/5 = Einstellungsebene normal/atop: Quelle ist das über [uLut] (Kanaltabellen) bzw. [uLut3] korrigierte Ziel.
     * [uKind]: 0 = normal (`blendBuffer`), 1 = atop/Schnittmaske (`blendAtop`), 2 = Überblenden
     * (hindurchwirkende Gruppe mit Deckkraft/Maske), 3 = Kopie von [uSrc].
     */
    val blend get() = blendSource(fetch = false)

    /**
     * [fetch]: an Ort und Stelle mischen – das Ziel ist der aktuelle Framebuffer; die Quelle kommt bei
     * [uSrcTile] = 1 direkt aus einer Kacheltextur (je Kachel ein Rechteck, Pixel außerhalb bleiben).
     */
    fun blendSource(fetch: Boolean) = header(fetch) + "\n" +
        // Ohne discard (bremst Kachel-GPUs): Pixel außerhalb der Kachel behalten ihren Wert
        (if (fetch) "bool tileOut = false;\n" + tileFn.replace("discard;", "{ tileOut = true; return vec4(0.0); }") + "\nuniform int uSrcTile;\n" else "") + modes + "\n" + blendBody
            .replace("TILE_GUARD", if (fetch) "if (tileOut) { o = d; return; }" else "")
            .replace("DST_COLOR", if (fetch) "o" else "texelFetch(uDst, q, 0)")
            .replace("SRC_COLOR", if (fetch) "(uSrcTile == 1 ? premul(sampleTile()) : texelFetch(uSrc, q, 0))" else "texelFetch(uSrc, q, 0)")

    private val blendBody = """
        vec4 premul(vec4 c) { return vec4(c.b * c.a, c.g * c.a, c.r * c.a, c.a); }
        uniform sampler2D uSrc;
        uniform sampler2D uDst;
        uniform sampler2D uMask;
        uniform sampler2D uLut;
        uniform highp sampler3D uLut3;
        uniform sampler2D uVib;
        uniform int uLutMode;
        uniform int uAdjType;
        uniform float uP[12];
        uniform int uMode;
        uniform int uKind;
        uniform int uHasMask;
        uniform float uAlpha;

        float colorBurn(float cb, float cs) { return cb >= 1.0 ? 1.0 : (cs <= 0.0 ? 0.0 : 1.0 - min(1.0, (1.0 - cb) / cs)); }
        float colorDodge(float cb, float cs) { return cb <= 0.0 ? 0.0 : (cs >= 1.0 ? 1.0 : min(1.0, cb / (1.0 - cs))); }
        float hardLight(float cb, float cs) { if (cs <= 0.5) return cb * 2.0 * cs; float s = 2.0 * cs - 1.0; return cb + s - cb * s; }

        float channel(int m, float cb, float cs) {
            if (m == M_DARKEN) return min(cb, cs);
            if (m == M_MULTIPLY) return cb * cs;
            if (m == M_COLOR_BURN) return colorBurn(cb, cs);
            if (m == M_LINEAR_BURN) return max(0.0, cb + cs - 1.0);
            if (m == M_LIGHTEN) return max(cb, cs);
            if (m == M_SCREEN) return cb + cs - cb * cs;
            if (m == M_COLOR_DODGE) return colorDodge(cb, cs);
            if (m == M_LINEAR_DODGE) return min(1.0, cb + cs);
            if (m == M_OVERLAY) return hardLight(cs, cb);
            if (m == M_SOFT_LIGHT) return cs <= 0.5 ? 2.0 * cb * cs + cb * cb * (1.0 - 2.0 * cs) : 2.0 * cb * (1.0 - cs) + sqrt(cb) * (2.0 * cs - 1.0);
            if (m == M_HARD_LIGHT) return hardLight(cb, cs);
            if (m == M_VIVID_LIGHT) return cs <= 0.5 ? colorBurn(cb, 2.0 * cs) : colorDodge(cb, 2.0 * (cs - 0.5));
            if (m == M_LINEAR_LIGHT) return clamp(cb + 2.0 * cs - 1.0, 0.0, 1.0);
            if (m == M_PIN_LIGHT) return cs <= 0.5 ? min(cb, 2.0 * cs) : max(cb, 2.0 * cs - 1.0);
            if (m == M_HARD_MIX) return cb + cs >= 1.0 - 1e-4 ? 1.0 : 0.0;
            if (m == M_DIFFERENCE) return abs(cb - cs);
            if (m == M_EXCLUSION) return cb + cs - 2.0 * cb * cs;
            if (m == M_SUBTRACT) return max(0.0, cb - cs);
            if (m == M_DIVIDE) return cs <= 0.0 ? (cb > 0.0 ? 1.0 : 0.0) : min(1.0, cb / cs);
            return cs;
        }

        float lum(vec3 c) { return 0.3 * c.r + 0.59 * c.g + 0.11 * c.b; }
        float sat(vec3 c) { return max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b)); }
        vec3 clipColor(vec3 c) {
            float l = lum(c); float n = min(c.r, min(c.g, c.b)); float x = max(c.r, max(c.g, c.b));
            if (n < 0.0) { float k = l - n; c = l + (c - l) * l / k; }
            if (x > 1.0) { float k = x - l; c = l + (c - l) * (1.0 - l) / k; }
            return clamp(c, 0.0, 1.0);
        }
        vec3 setLum(vec3 c, float l) { return clipColor(c + (l - lum(c))); }
        vec3 setSat(vec3 c, float s) {
            float mx = max(c.r, max(c.g, c.b)); float mn = min(c.r, min(c.g, c.b));
            if (mx - mn <= 1e-6) return vec3(0.0);
            return (c - mn) * s / (mx - mn);
        }

        vec3 mixMode(int m, vec3 b, vec3 s) {
            if (m == M_HUE) return setLum(setSat(s, sat(b)), lum(b));
            if (m == M_SATURATION) return setLum(setSat(b, sat(s)), lum(b));
            if (m == M_COLOR) return setLum(s, lum(b));
            if (m == M_LUMINOSITY) return setLum(b, lum(s));
            if (m == M_DARKER_COLOR) return lum(s) < lum(b) ? s : b;
            if (m == M_LIGHTER_COLOR) return lum(s) > lum(b) ? s : b;
            return vec3(channel(m, b.r, s.r), channel(m, b.g, s.g), channel(m, b.b, s.b));
        }

        float noise(ivec2 p) {
            uint h = uint(p.x) * 374761393u + uint(p.y) * 668265263u;
            h = (h ^ (h >> 13u)) * 1274126177u;
            h = h ^ (h >> 16u);
            return float(h & 0xFFFFFFu) / 16777216.0;
        }

        // ---- Kanalübergreifende Einstellungen (wie adjust/Adjustment.kt) ----
        vec3 pack(vec3 c) { return clamp(floor(c * 255.0 + 0.5), 0.0, 255.0) / 255.0; }
        float fmodT(float a, float b) { return a - b * trunc(a / b); }
        vec3 rgbToHsl(vec3 c) {
            float mx = max(c.r, max(c.g, c.b)); float mn = min(c.r, min(c.g, c.b));
            float l = (mx + mn) / 2.0; float h = 0.0; float s = 0.0; float d = mx - mn;
            if (d > 1e-6) {
                s = l > 0.5 ? d / (2.0 - mx - mn) : d / (mx + mn);
                if (mx == c.r) h = (c.g - c.b) / d + (c.g < c.b ? 6.0 : 0.0);
                else if (mx == c.g) h = (c.b - c.r) / d + 2.0;
                else h = (c.r - c.g) / d + 4.0;
                h *= 60.0;
            }
            return vec3(h, s, l);
        }
        float hue2rgb(float p, float q, float t) {
            if (t < 0.0) t += 1.0;
            if (t > 1.0) t -= 1.0;
            if (t < 1.0 / 6.0) return p + (q - p) * 6.0 * t;
            if (t < 0.5) return q;
            if (t < 2.0 / 3.0) return p + (q - p) * (2.0 / 3.0 - t) * 6.0;
            return p;
        }
        vec3 hslToRgb(float h, float s, float l) {
            if (s <= 0.0) return vec3(l);
            float q = l < 0.5 ? l * (1.0 + s) : l + s - l * s;
            float p = 2.0 * l - q;
            float hk = fmodT(fmodT(h, 360.0) + 360.0, 360.0) / 360.0;
            return vec3(hue2rgb(p, q, hk + 1.0 / 3.0), hue2rgb(p, q, hk), hue2rgb(p, q, hk - 1.0 / 3.0));
        }

        /** Dynamik-Tabelle (33×33, R normal, G Hautton) bilinear über (Max, Min) in 8-Bit-Werten – wie PsVibrance.lerp. */
        vec2 vibGain(int mxg, int mng) {
            float x = float(mxg) / 8.0; float y = float(mng) / 8.0;
            int x0 = min(int(x), 31); int y0 = min(int(y), 31);
            float fx = x - float(x0); float fy = y - float(y0);
            vec2 a = texelFetch(uVib, ivec2(x0, y0), 0).rg; vec2 b = texelFetch(uVib, ivec2(x0 + 1, y0), 0).rg;
            vec2 c = texelFetch(uVib, ivec2(x0, y0 + 1), 0).rg; vec2 d = texelFetch(uVib, ivec2(x0 + 1, y0 + 1), 0).rg;
            return (a * (1.0 - fx) + b * fx) * (1.0 - fy) + (c * (1.0 - fx) + d * fx) * fy;
        }

        /** Dynamik wie PsVibrance (Photoshop): uP = (Dynamik t, Sättigung, t^1,6). */
        vec3 vibrance(ivec3 ci) {
            vec3 c = vec3(ci) / 255.0;
            vec3 l = mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c));
            float t = uP[0];
            float mx = max(l.r, max(l.g, l.b)); float mn = min(l.r, min(l.g, l.b));
            if (t != 0.0 && mx > 0.0 && mx > mn) {
                int mxg = max(ci.r, max(ci.g, ci.b)); int mng = min(ci.r, min(ci.g, ci.b));
                float ratio = mn / mx;
                float dark = min(1.0, float(mxg) / 62.0);
                float skin = 0.0;
                if (t > 0.0) {
                    float d = mx - mn;
                    float h = mx == l.r ? (l.g - l.b) / d * 60.0 : (mx == l.g ? (l.b - l.r) / d * 60.0 + 120.0 : (l.r - l.g) / d * 60.0 + 240.0);
                    if (h < 0.0) h += 360.0;
                    skin = h <= 30.0 ? 1.0 : (h < 45.0 ? (45.0 - h) / 15.0 : (h < 300.0 ? 0.0 : (h - 300.0) / 60.0));
                }
                vec2 g = vibGain(mxg, mng);
                float k = clamp(1.0 + g.x * (1.0 - skin) + g.y * skin, 0.0, 1.0 / (1.0 - ratio));
                float tb = t > 0.0 ? (t * (1.0 - skin) + uP[2] * skin) * (4.0 / 9.0) : t * (16.0 / 9.0);
                float sc = 1.0 + tb * (1.0 - mx) * ratio * (1.0 - ratio) * dark;
                l = (mx - (mx - l) * k) * sc;
            }
            if (uP[1] != 0.0) { float gray = 0.288 * l.r + 0.712 * l.g; l = gray + (l - gray) * (1.0 + uP[1]); }
            l = clamp(l, 0.0, 1.0);
            return pack(mix(l * 12.92, 1.055 * pow(l, vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), l)));
        }

        /** [uAdjType]: 1 Dynamik, 2 Farbton/Sättigung, 4 Schwarzweiß, 5 Fotofilter, 6 Schwellenwert. */
        vec3 adjustFn(ivec3 ci) {
            vec3 c = vec3(ci) / 255.0;
            if (uAdjType == 1) return vibrance(ci);
            if (uAdjType == 2) {
                // wie HueSaturationAdj: Helligkeit kanalweise, dann Farbton (HSL), dann Photoshop-Sättigung
                float s = uP[1]; float l = uP[2];
                c = l >= 0.0 ? c + (1.0 - c) * l : c * (1.0 + l);
                float mx = max(c.r, max(c.g, c.b)); float mn = min(c.r, min(c.g, c.b));
                if (uP[3] > 0.5) return pack(hslToRgb(uP[0], clamp(s, 0.0, 1.0), (mx + mn) / 2.0));
                if (uP[0] != 0.0) { vec3 hsl = rgbToHsl(c); c = hslToRgb(hsl.x + uP[0], hsl.y, hsl.z); mx = max(c.r, max(c.g, c.b)); mn = min(c.r, min(c.g, c.b)); }
                float delta = mx - mn; float value = mx + mn;
                if (s != 0.0 && delta > 0.0) {
                    float light = value / 2.0;
                    float sat = light < 0.5 ? delta / value : delta / (2.0 - value);
                    float k = s >= 0.0 ? 1.0 / (s + sat >= 1.0 ? sat : 1.0 - s) - 1.0 : s;
                    c += (c - light) * k;
                }
                return pack(c);
            }
            if (uAdjType == 4) {
                float mn = min(c.r, min(c.g, c.b));
                float rr = c.r - mn; float gg = c.g - mn; float bb = c.b - mn;
                float gray;
                if (rr == 0.0) { float m = min(gg, bb); gray = mn + uP[3] * m + (gg > bb ? uP[2] * (gg - m) : uP[4] * (bb - m)); }
                else if (gg == 0.0) { float m = min(rr, bb); gray = mn + uP[5] * m + (rr > bb ? uP[0] * (rr - m) : uP[4] * (bb - m)); }
                else { float m = min(rr, gg); gray = mn + uP[1] * m + (rr > gg ? uP[0] * (rr - m) : uP[2] * (gg - m)); }
                float v = clamp(gray, 0.0, 1.0);
                if (uP[6] < 0.5) return pack(vec3(v));
                vec3 t = vec3(uP[7], uP[8], uP[9]);
                vec3 ov = v < 0.5 ? 2.0 * v * t : 1.0 - 2.0 * (1.0 - v) * (1.0 - t);
                return pack(ov);
            }
            if (uAdjType == 5) {
                // wie PhotoFilterAdj: 3×3-Matrix im linearen sRGB, dann ggf. Luminanz (SetLum 0,3/0,59/0,11)
                vec3 lc = mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c));
                vec3 n = max(vec3(dot(vec3(uP[0], uP[1], uP[2]), lc), dot(vec3(uP[3], uP[4], uP[5]), lc), dot(vec3(uP[6], uP[7], uP[8]), lc)), vec3(0.0));
                vec3 e = mix(n * 12.92, 1.055 * pow(n, vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), n));
                if (uP[9] > 0.5) {
                    vec3 w = vec3(0.3, 0.59, 0.11);
                    e += dot(c, w) - dot(e, w);
                    float lum = dot(e, w); float mn = min(e.r, min(e.g, e.b)); float mx = max(e.r, max(e.g, e.b));
                    if (mn < 0.0) e = lum + (e - lum) * (lum / (lum - mn));
                    if (mx > 1.0) e = lum + (e - lum) * ((1.0 - lum) / (mx - lum));
                }
                return pack(e);
            }
            // Schwellenwert auf den 8-Bit-Werten (Photoshops 15-Bit-Luminanz, wie die CPU)
            int l = (9829 * ci.r + 19335 * ci.g + 3604 * ci.b + 16384) >> 15;
            return float(l) >= uP[0] ? vec3(1.0) : vec3(0.0);
        }

        /** Einstellungsebene: Ziel (vormultipliziert, Deckung > 0) auf 8 Bit runden und nachschlagen, wie die CPU. */
        vec3 adjusted(vec4 d) {
            ivec3 c = clamp(ivec3(floor(d.rgb * (255.0 / d.a) + 0.5)), ivec3(0), ivec3(255));
            if (uLutMode == 3) return adjustFn(c);
            if (uLutMode == 1) return vec3(texelFetch(uLut, ivec2(c.r, 0), 0).r, texelFetch(uLut, ivec2(c.g, 0), 0).g, texelFetch(uLut, ivec2(c.b, 0), 0).b);
            return texelFetch(uLut3, ivec3(c.b, c.g, c.r), 0).rgb;
        }

        void main() {
            ivec2 q = ivec2(gl_FragCoord.xy);
            vec4 d = DST_COLOR;
            float m = uHasMask == 1 ? texelFetch(uMask, q, 0).r : 1.0;
            if (uKind >= 4) {
                float da = d.a;
                if (da <= 0.0) { o = d; return; }
                vec3 cs = adjusted(d);
                vec3 cb = d.rgb / da;
                if (uKind == 5) {
                    // Geschnittene Einstellungsebene (blendAtop mit Quelle = korrigiertes Ziel)
                    float sa = da * uAlpha * m;
                    if (sa <= 0.0) { o = d; return; }
                    o = vec4(da * (cb + (mixMode(uMode, cb, cs) - cb) * sa), da);
                    return;
                }
                float t = uAlpha * m;
                if (t <= 0.0) { o = d; return; }
                if (uMode == M_NORMAL || uMode == M_PASS_THROUGH) o = vec4(d.rgb + (cs * da - d.rgb) * t, da);
                else o = vec4(da * (cb + (mixMode(uMode, cb, cs) - cb) * t), da);
                return;
            }
            vec4 s = SRC_COLOR;
            TILE_GUARD
            if (uKind == 3) { o = s; return; }
            if (uKind == 2) { o = d + (s - d) * (uAlpha * m); return; }
            float srcA = s.a;
            float sa = srcA * uAlpha * m;
            float da = d.a;
            if (uKind == 1) {
                if (da <= 0.0 || sa <= 0.0) { o = d; return; }
                vec3 cb = d.rgb / da;
                vec3 bl = mixMode(uMode, cb, s.rgb / srcA);
                o = vec4(da * (cb + (bl - cb) * sa), da);
                return;
            }
            if (sa <= 0.0) { o = d; return; }
            if (uMode == M_DISSOLVE) { if (noise(outPx()) < sa) sa = 1.0; else { o = d; return; } }
            float inv = 1.0 - sa;
            if (uMode == M_NORMAL || uMode == M_DISSOLVE || da <= 0.0) {
                o = vec4(s.rgb * (sa / srcA) + d.rgb * inv, sa + da * inv);
                return;
            }
            vec3 cs = s.rgb / srcA;
            vec3 bl = mixMode(uMode, d.rgb / da, cs);
            o = vec4(sa * ((1.0 - da) * cs + da * bl) + d.rgb * inv, sa + da * inv);
        }
    """.trimIndent()

    /**
     * Ausgabe: vormultipliziert → ARGB-Bytes B, G, R, A (Little Endian = ARGB-Ganzzahl), gerundet wie
     * `Compositor.toArgb`.
     */
    val output = common + "\n" + """
        uniform sampler2D uSrc;
        float to8(float v) { return clamp(floor(v + 0.5), 0.0, 255.0); }
        void main() {
            vec4 s = texelFetch(uSrc, ivec2(gl_FragCoord.xy), 0);
            float a = s.a;
            if (a <= 1.0 / 512.0) { o = vec4(0.0); return; }
            float inv = 255.0 / a;
            o = vec4(to8(s.b * inv), to8(s.g * inv), to8(s.r * inv), to8(a * 255.0)) / 255.0;
        }
    """.trimIndent()
}
