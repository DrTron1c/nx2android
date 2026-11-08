#!/usr/bin/env python3
# Pokémon Pokopia's on-screen gamepad, drawn from the player's picture (nxnative/dm/android/themes/pokopia/design.png): writes the pack's atlases and theme.tsv, the profile's
# overlay rows, and the menus' rounded letters (nxnative/dm/android/font_ui.h). Shapes are drawn here so they stay sharp on any screen; the white icons are lifted from the picture.
#   python devtools/pokopia_hud.py
import re, sys
from pathlib import Path
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent.parent
AND = ROOT / 'nxnative/dm/android'; PACK = AND / 'themes/pokopia'; PROFILE = ROOT / 'nxnative/dm/games/0400A3A01EE4C000/android.tsv'
SRC = Image.open(PACK / 'design.png').convert('RGB')
BOLD = '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'
IH = 1024; IW = 1536   # the picture
PLUM = (92, 44, 76); WHITE = (255, 255, 255)
GREY = (150, 160, 163); PILL = (136, 148, 154); PAD = (146, 158, 166)
COL = dict(zl=(171, 128, 232), l=(255, 200, 92), zr=(255, 128, 142), r=(92, 196, 244), x=(238, 194, 128), y=(200, 160, 244), a=(104, 220, 142), b=(255, 164, 66))
CLOUD = [(-34, -18, 40), (6, -30, 44), (42, -8, 38), (-44, 16, 36), (-8, 26, 44), (34, 26, 38), (0, 0, 52)]   # the cloud: circles, in the picture's pixels from its centre
# name: centre in the picture, width and height for the touch, icon's centre, badge
CTL = {'zl': (120, 153, 170, 135, (122, 154), 'ZL'), 'l': (120, 301, 170, 135, (122, 298), 'L'), 'zr': (1402, 153, 170, 135, (1402, 158), 'ZR'), 'r': (1402, 301, 170, 135, (1403, 300), 'R'),
       'x': (1312, 463, 140, 140, (1312, 463), 'X'), 'y': (1196, 577, 140, 140, (1195, 578), 'Y'), 'a': (1401, 577, 140, 140, (1400, 577), 'A'), 'b': (1288, 688, 140, 140, (1288, 690), 'B'),
       'minus': (505, 110, 176, 118, (505, 110), '-'), 'plus': (1014, 110, 176, 118, (1014, 110), '+')}
BADGE = {'zl': (168, 97, 36), 'l': (173, 248, 31), 'zr': (1452, 97, 36), 'r': (1457, 248, 31), 'x': (1362, 412, 27), 'y': (1247, 530, 27), 'a': (1453, 530, 27), 'b': (1337, 641, 27), 'minus': (555, 175, 28), 'plus': (965, 175, 28)}
STICKS = {'stick_l': (145, 866), 'stick_r': (1387, 870)}; STICK_R = 125; KNOB_R = 65
DPAD = (173, 531); MENU = (768, 56, 92, 50)
# L3 and R3 (not in the picture): small discs in the d-pad's grey with their names in white and a stick's ring, beside each stick, clear of the d-pad and B
CLICK = {'ls': (335, 800, 'L3'), 'rs': (1185, 800, 'R3')}; CLICK_R = 50

def rounded_text(s, size):   # letters with soft corners: blurred and cut again
    S = 8; f = ImageFont.truetype(BOLD, size * S)
    b = f.getbbox(s); m = Image.new('L', (b[2] - b[0] + 4 * S * 3, b[3] - b[1] + 4 * S * 3), 0)
    ImageDraw.Draw(m).text((6 * S - b[0], 6 * S - b[1]), s, 255, font=f)
    m = m.filter(ImageFilter.GaussianBlur(S * size * 0.035)).point(lambda v: min(255, max(0, (v - 110) * 12)))
    return m.resize((m.width // S, m.height // S), Image.LANCZOS)

class Art:
    def __init__(s, k): s.k = k; s.S = 4   # k: texels per picture pixel
    def base(s, w, h): return Image.new('L', (int(w * s.k * s.S), int(h * s.k * s.S)), 0)
    def down(s, im): return im.resize((max(1, im.width // s.S), max(1, im.height // s.S)), Image.LANCZOS)
    def shape(s, w, h, fn):   # a mask in w x h picture pixels, centred
        m = s.base(w, h); fn(ImageDraw.Draw(m), w / 2, h / 2, s.k * s.S); return s.down(m)
    def layered(s, w, h, outer, fill, inner):   # a white ring round a filled shape: outer and inner are the same shape drawn at two sizes. No glow
        c = Image.merge('RGBA', (*Image.new('RGB', outer.size, WHITE).split(), outer))
        return Image.alpha_composite(c, Image.merge('RGBA', (*Image.new('RGB', outer.size, fill).split(), inner)))
    def icon(s, cx, cy, r, exclude=None, keep=None):
        K = s.k; box = (cx - r, cy - r, cx + r, cy + r); crop = SRC.crop(box).resize((int(2 * r * K), int(2 * r * K)), Image.BICUBIC)
        lo = ImageChops.darker(ImageChops.darker(crop.getchannel(0), crop.getchannel(1)), crop.getchannel(2))
        return lo.point(lambda v: min(255, max(0, (v - 214) * 7)))

def darker(c, f): return tuple(int(v * f) for v in c)

def badge(a, im, text, r):   # a white disc with a plum ring and a letter, top right of the sprite's place
    K = a.k; S = a.S; D = int((2 * r + 4) * K * S); m = Image.new('RGBA', (D, D), (0, 0, 0, 0)); d = ImageDraw.Draw(m); c = D / 2; R = r * K * S
    d.ellipse((c - R, c - R, c + R, c + R), fill=WHITE + (255,)); t = 2.2 * K * S; d.ellipse((c - R + t * 1, c - R + t * 1, c + R - t * 1, c + R - t * 1), fill=PLUM + (255,))
    t2 = 4.2 * K * S; d.ellipse((c - R + t2, c - R + t2, c + R - t2, c + R - t2), fill=WHITE + (255,))
    m = m.resize((D // S, D // S), Image.LANCZOS)
    if text in '-+':
        L = int(r * 0.52 * K); T = max(2, int(r * 0.20 * K)); e = m.width // 2; b = ImageDraw.Draw(m)
        b.rounded_rectangle((e - L, e - T // 2, e + L, e + T // 2), T // 2, fill=PLUM + (255,))
        if text == '+': b.rounded_rectangle((e - T // 2, e - L, e + T // 2, e + L), T // 2, fill=PLUM + (255,))
        return m
    g = rounded_text(text, int(r * (1.05 if len(text) == 1 else 0.86) * K)); x = (m.width - g.width) // 2; y = (m.height - g.height) // 2
    m.paste(Image.new('RGBA', g.size, PLUM + (255,)), (x, y), g)
    return m

def button(a, name, held):
    cx, cy, w, h, ic, label = CTL[name]; M = 40; W, H = (250, 210) if name in ('zl', 'l', 'zr', 'r') else (250, 200) if name in ('minus', 'plus') else (230, 230)
    K = a.k; fill = COL.get(name, PILL)
    def cloud(g): return lambda d, x, y, s: [d.ellipse(((x + ox * 0.95 - r + g) * s, (y + oy * 0.95 - r + g) * s, (x + ox * 0.95 + r - g) * s, (y + oy * 0.95 + r - g) * s), fill=255) for ox, oy, r in CLOUD]
    def disc(g): return lambda d, x, y, s: d.ellipse(((x - 68 + g) * s, (y - 68 + g) * s, (x + 68 - g) * s, (y + 68 - g) * s), fill=255)
    def pill(g): return lambda d, x, y, s: d.rounded_rectangle(((x - 88 + g) * s, (y - 59 + g) * s, (x + 88 - g) * s, (y + 59 - g) * s), (59 - g) * s, fill=255)
    form = cloud if name in ('zl', 'l', 'zr', 'r') else pill if name in ('minus', 'plus') else disc
    if held: fill = darker(fill, 0.82)
    im = a.layered(W, H, a.shape(W, H, form(0)), fill, a.shape(W, H, form(6.5)))
    r = 50 if name not in ('minus', 'plus') else 52
    ix, iy = ic; m = a.icon(ix, iy, r)
    bx, by, br = BADGE[name]; yy, xx = (Image.new('L', m.size, 255),) * 2
    ex = Image.new('L', (m.width * 2, m.height * 2), 255); ImageDraw.Draw(ex).ellipse(((bx - ix + r - br - 3) * K * 2, (by - iy + r - br - 3) * K * 2, (bx - ix + r + br + 3) * K * 2, (by - iy + r + br + 3) * K * 2), fill=0)
    m = ImageChops.multiply(m, ex.resize(m.size, Image.LANCZOS))
    p = (int((W / 2 + ix - cx - r) * K), int((H / 2 + iy - cy - r) * K)); im.paste((255, 255, 255, 255), p, m)
    g = badge(a, im, label, br); q = (int((W / 2 + bx - cx) * K - g.width / 2), int((H / 2 + by - cy) * K - g.height / 2)); im.alpha_composite(g, (max(0, q[0]), max(0, q[1])))
    return im, W, H

def stick(a):
    D = 2 * (STICK_R + 8)
    def rr(r): return lambda d, x, y, s: d.ellipse(((x - r) * s, (y - r) * s, (x + r) * s, (y + r) * s), fill=255)
    base = a.layered(D, D, a.shape(D, D, rr(STICK_R)), (170, 182, 190), a.shape(D, D, rr(STICK_R - 6.5)))
    kd = 2 * (KNOB_R + 8)
    knob = a.layered(kd, kd, a.shape(kd, kd, rr(KNOB_R)), (206, 214, 220), a.shape(kd, kd, rr(KNOB_R - 6.5)))
    t = a.shape(kd, kd, rr(KNOB_R - 13)); t2 = a.shape(kd, kd, rr(KNOB_R - 16)); knob.paste((240, 244, 246, 255), (0, 0), ImageChops.subtract(t, t2))
    return base, D, knob, kd

def dpad_arm(a, held, rot):
    W = 130; K = a.k
    def sq(g): return lambda d, x, y, s: d.rounded_rectangle(((x - 45 + g) * s, (y - 46 + g) * s, (x + 45 - g) * s, (y + 46 - g) * s), (22 - g) * s, fill=255)
    im = a.layered(W, W, a.shape(W, W, sq(0)), darker(PAD, 0.82) if held else PAD, a.shape(W, W, sq(6.5)))
    S = a.S; chev = Image.new('L', (int(W * K * S),) * 2, 0); c = W * K * S / 2; d = ImageDraw.Draw(chev); w = int(10 * K * S)
    pts = [(c - 20 * K * S, c + 10 * K * S), (c, c - 10 * K * S), (c + 20 * K * S, c + 10 * K * S)]
    d.line(pts, fill=255, width=w, joint='curve')
    for p in (pts[0], pts[1], pts[2]): d.ellipse((p[0] - w / 2, p[1] - w / 2, p[0] + w / 2, p[1] + w / 2), fill=255)
    chev = chev.resize((chev.width // S, chev.height // S), Image.LANCZOS)
    im.paste((255, 255, 255, 255), (0, 0), chev)
    return im.rotate(rot, Image.BICUBIC), W

def click(a, name, held):   # L3 / R3: the disc, the knob's thin white ring inside it, and the name
    R = CLICK_R; W = 2 * (R + 8); K = a.k
    def rr(r): return lambda d, x, y, s: d.ellipse(((x - r) * s, (y - r) * s, (x + r) * s, (y + r) * s), fill=255)
    im = a.layered(W, W, a.shape(W, W, rr(R)), darker(PAD, 0.82) if held else PAD, a.shape(W, W, rr(R - 6.5)))
    t = a.shape(W, W, rr(R - 12)); t2 = a.shape(W, W, rr(R - 15)); im.paste((240, 244, 246, 255), (0, 0), ImageChops.subtract(t, t2))
    g = rounded_text(CLICK[name][2], int(R * 0.62 * K)); im.paste((255, 255, 255, 255), ((im.width - g.width) // 2, (im.height - g.height) // 2), g)
    return im, W

def hub(a):
    W = 130
    def sq(d, x, y, s): d.rounded_rectangle(((x - 34) * s, (y - 34) * s, (x + 34) * s, (y + 34) * s), 6 * s, fill=255)
    m = a.shape(W, W, sq); im = Image.new('RGBA', m.size, PAD + (0,)); im.putalpha(m); return im, W

def app_menu(a, held):
    W, H = 150, 110; mw, mh = MENU[2], MENU[3]
    def pill(g): return lambda d, x, y, s: d.rounded_rectangle(((x - mw / 2 + g) * s, (y - mh / 2 + g) * s, (x + mw / 2 - g) * s, (y + mh / 2 - g) * s), (mh / 2 - g) * s, fill=255)
    im = a.layered(W, H, a.shape(W, H, pill(0)), darker(PILL, 0.82) if held else PILL, a.shape(W, H, pill(4)))
    d = ImageDraw.Draw(im); K = a.k
    for ox in (-16, 0, 16): r = 4.2 * K; x = W * K / 2 + ox * K; y = H * K / 2; d.ellipse((x - r, y - r, x + r, y + r), fill=(255, 255, 255, 255))
    return im, W, H

def pack(layout, dw, dh, ktex):
    k = dh / IH; K = k * ktex; a = Art(K)   # k: CSS px per picture pixel; K: texels per picture pixel
    tiles = []   # (row name, part, state, image, ox, oy in CSS px)
    def add(ctl, part, st, im, W, H, dy=0): tiles.append((ctl, part, st, im, -W * k / 2, -H * k / 2 + dy))
    for name in CTL:
        for st, h in (('idle', 0), ('held', 1)): im, W, H = button(a, name, h); add(name, '-', st, im, W, H, 3 if h else 0)
    base, D, knob, kd = stick(a)
    for s in STICKS: add(s, '-', 'idle', base, D, D); add(s, '-', 'held', base, D, D); add(s, '-', 'knob', knob, kd, kd)
    c = (DPAD[0], DPAD[1]); off = {'up': (0, -72, 0), 'down': (0, 72, 180), 'left': (-72, 0, 90), 'right': (72, 0, 270)}
    hi, hw = hub(a); tiles.append(('dpad', '-', 'decor', hi, -hw * k / 2, -hw * k / 2))
    for dir_, (ox, oy, rot) in off.items():
        for st, h in (('idle', 0), ('held', 1)):
            im, W = dpad_arm(a, h, rot); tiles.append(('dpad', dir_, st, im, (ox - W / 2) * k, (oy - W / 2) * k + (3 if h else 0)))
    for st, h in (('idle', 0), ('held', 1)): im, W, H = app_menu(a, h); add('menu', '-', st, im, W, H)
    for n in CLICK:
        for st, h in (('idle', 0), ('held', 1)): im, W = click(a, n, h); add(n, '-', st, im, W, W, 3 if h else 0)
    # the atlas: shelves, tallest first; a tile drawn twice is stored once
    seen = {}; order = sorted(range(len(tiles)), key=lambda i: -tiles[i][3].height); W = 2048; x = y = sh = 1; place = {}
    for i in order:
        im = tiles[i][3]
        if id(im) in seen: place[i] = seen[id(im)]; continue
        if x + im.width + 1 > W: x = 1; y += sh + 1; sh = 0
        place[i] = seen[id(im)] = (x, y); x += im.width + 1; sh = max(sh, im.height)
    H = y + sh + 1; atlas = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    rows = [f'layout\t{layout}\t{dw}\t{dh}\t{layout}.png\t{ktex}\t{round((STICK_R - KNOB_R + 6) * k)}']
    for i, (ctl, part, st, im, ox, oy) in enumerate(tiles):
        px, py = place[i]; atlas.paste(im, (px, py))
        rows.append(f'sprite\t{layout}\t{ctl}\t{part}\t{st}\t{px}\t{py}\t{im.width}\t{im.height}\t{ox:.2f}\t{oy:.2f}')
    atlas.save(PACK / f'{layout}.png', optimize=True); print(layout, atlas.size, (PACK / f'{layout}.png').stat().st_size >> 10, 'KB')
    return rows

def overlay_rows():
    out = []
    def frac(layout, x):   # the picture is 1.5 wide; a phone's window 2.22: each side keeps its controls' distance from its edge, in window heights
        if layout == 'tablet': return x / IW
        a = 915 / 412
        if x < IW * 0.25: return x / IH / a
        if x > IW * 0.75: return 1 - (IW - x) / IH / a
        return 0.5 + (x - IW / 2) / IH / a
    for lay in ('tablet', 'phone'):
        for n, (cx, cy, w, h, _, _) in CTL.items(): out.append(f'overlay\t{lay} {n} {frac(lay, cx):.4f} {cy / IH:.4f} {w / IH:.4f} {h / IH:.4f}')
        for n, (cx, cy) in STICKS.items(): out.append(f'overlay\t{lay} {n} {frac(lay, cx):.4f} {cy / IH:.4f} {2 * STICK_R / IH:.4f} {2 * STICK_R / IH:.4f}')
        out.append(f'overlay\t{lay} dpad {frac(lay, DPAD[0]):.4f} {DPAD[1] / IH:.4f} {230 / IH:.4f} {230 / IH:.4f}')
        out.append(f'overlay\t{lay} menu 0.5000 {MENU[1] / IH:.4f} {MENU[2] / IH:.4f} {MENU[3] / IH:.4f}')
        for n, (cx, cy, _) in CLICK.items(): out.append(f'overlay\t{lay} {n} {frac(lay, cx):.4f} {cy / IH:.4f} {2 * CLICK_R / IH:.4f} {2 * CLICK_R / IH:.4f}')
    return out

def font():   # the menus' letters: rounded bold, cells 40 texels high at an em of 30
    EM = 30; CELL = 40; S = 8; f = ImageFont.truetype(BOLD, EM * S); asc = f.getmetrics()[0]; out = ['// Generated by devtools/pokopia_hud.py: DejaVu Sans Bold with its corners rounded, the menus\' letters. UI_G[c - 32]: advance and width in texels, then UI_A + its offset', f'#define UI_EM {EM}', f'#define UI_CELL {CELL}']
    data = []; idx = []
    for ch in range(32, 127):
        c = chr(ch); adv = f.getlength(c) / S; b = f.getbbox(c); w = max(1, (b[2] - b[0]) // S + 3)
        m = Image.new('L', (w * S, CELL * S), 0); ImageDraw.Draw(m).text((S - b[0], (CELL * S - f.getmetrics()[0] - f.getmetrics()[1]) // 2), c, 255, font=f)
        m = m.filter(ImageFilter.GaussianBlur(S * 0.9)).point(lambda v: min(255, max(0, (v - 105) * 10))).resize((w, CELL), Image.LANCZOS)
        off = len(data); data += list(m.tobytes()); idx.append((round(adv * 16), w, (b[0] // S) - 1, off))
    out.append('static const struct { uint16_t adv16; uint8_t w; int8_t left; uint32_t off; } UI_G[95] = {' + ','.join('{%d,%d,%d,%d}' % t for t in idx) + '};')
    out.append('static const uint8_t UI_A[] = {' + ','.join(map(str, data)) + '};')
    (AND / 'font_ui.h').write_text('\n'.join(out) + '\n', encoding='utf-8'); print('font', len(data) >> 10, 'KB')

def main():
    rows = ['# Generated by devtools/pokopia_hud.py from design.png, the player\'s picture; do not edit', 'alpha\t1'] + pack('tablet', 1216, 760, 3) + pack('phone', 915, 412, 3)
    (PACK / 'theme.tsv').write_text('\n'.join(rows) + '\n', encoding='utf-8')
    t = PROFILE.read_text(encoding='utf-8'); t = re.sub(r'# the gamepad as in the player\'s picture.*?\n(overlay\t.*\n)*', '', t)
    PROFILE.write_text(t.rstrip('\n') + "\n# the gamepad as in the player's picture (themes/pokopia, devtools/pokopia_hud.py); L3 and R3 added beside the sticks in its style\n" + '\n'.join(overlay_rows()) + '\n', encoding='utf-8')
    font()

main()
