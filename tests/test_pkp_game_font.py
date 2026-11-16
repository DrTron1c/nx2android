# The sleep mod's reader of Pokopia's own font (bananative/android/app/src/main/java/dev/nxnative/android/GameFont.java): a made-up G1N font (a map of codes, 12-byte
# glyphs, 4bpp bitmaps whose ink ends on the last row but one) in a zlib-chunked IDRK entry of a made-up RDB .fdata, at the place it has on 2.0.1 and moved after other
# entries, goes through the real class (javac + java, with small stand-ins for the Android classes it draws with): the glyphs are found by their codes, the space is
# glyph 0, letters sit on the baseline and descend below it by their place, the bitmaps' wide right margin is taken in, digits keep the widest digit's place, the
# second face is the talk's (drawn at 0.825 of its width, 7 units closer, words too), and only as much of the entry is read as the first two faces need.
import shutil, struct, subprocess, tempfile, unittest, zlib
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
APP = REPO / 'bananative/android/app/src/main/java/dev/nxnative/android'

STUBS = {
    'android/graphics/Bitmap.java': '''package android.graphics;
public class Bitmap {
    public enum Config { ALPHA_8, ARGB_8888 }
    final int w, h; final byte[] a; final Config cfg;
    Bitmap(int w, int h, Config c) { this.w = w; this.h = h; cfg = c; a = new byte[w * h]; }
    public static Bitmap createBitmap(int w, int h, Config c) { return new Bitmap(w, h, c); }
    public static Bitmap createScaledBitmap(Bitmap s, int w, int h, boolean f) { Bitmap b = new Bitmap(w, h, s.cfg); for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) b.a[y * w + x] = s.a[(y * s.h / h) * s.w + x * s.w / w]; return b; }
    public int getRowBytes() { return w; } public int getWidth() { return w; } public int getHeight() { return h; } public Config getConfig() { return cfg; }
    public void copyPixelsFromBuffer(java.nio.Buffer b) { ((java.nio.ByteBuffer) b).get(a, 0, a.length); }
    public void copyPixelsToBuffer(java.nio.Buffer b) { ((java.nio.ByteBuffer) b).put(a); }
    public int getPixel(int x, int y) { return (a[y * w + x] & 0xff) << 24; }
    public Bitmap extractAlpha() { return this; } public void recycle() {}
}''',
    'android/graphics/Canvas.java': '''package android.graphics;
public class Canvas { public final java.util.List<float[]> drawn = new java.util.ArrayList<>(); public void drawBitmap(Bitmap b, Rect s, RectF d, Paint p) { drawn.add(new float[] {d.left, d.top, d.right, d.bottom}); } }''',
    'android/graphics/Rect.java': 'package android.graphics; public class Rect {}',
    'android/graphics/RectF.java': 'package android.graphics; public class RectF { public float left, top, right, bottom; public void set(float a, float b, float c, float d) { left = a; top = b; right = c; bottom = d; } }',
    'android/graphics/Paint.java': 'package android.graphics; public class Paint {}',
    'android/util/SparseArray.java': 'package android.util; public class SparseArray<E> { private final java.util.HashMap<Integer, E> m = new java.util.HashMap<>(); public E get(int k) { return m.get(k); } public void put(int k, E v) { m.put(k, v); } }',
    'dev/nxnative/android/Native.java': 'package dev.nxnative.android; final class Native { static void note(String s) { System.err.println(s); } }',
    'dev/nxnative/android/Harness.java': '''package dev.nxnative.android;
public class Harness {
    public static void main(String[] a) throws Exception {
        GameFont[] fs = GameFont.load(new java.io.File(a[0]), new java.io.File(a[1]));
        System.out.println("loaded " + (fs != null));
        if (fs == null) return;
        GameFont f = fs[0], t = fs[1];
        System.out.println("talk " + t.measure("A", 60, false) + " " + t.measure(" ", 60, false));
        System.out.println("has " + f.has("A g") + " " + f.has("12:21") + " " + f.has("B"));
        System.out.println("width " + f.measure("A", 60, false) + " " + f.measure(" ", 60, false) + " " + f.measure("1", 60, false) + " " + f.measure("1", 60, true));
        android.graphics.Canvas c = new android.graphics.Canvas();
        f.draw(c, "Ag", 0, 100, 60, new android.graphics.Paint(), false);
        for (float[] r : c.drawn) System.out.println("rect " + r[0] + " " + r[1] + " " + r[2] + " " + r[3]);
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(a[2], "r")) { System.out.println("read " + GameFont.face(raf.getChannel(), Long.parseLong(a[3])).length); }
    }
}''',
}

W, H = 10, 16   # every glyph's bitmap; its slot in the glyph data is 0x100 bytes


def bitmap(cols, rows):   # 4bpp, rows of W (even) pixels, high nibble first; ink (15) in the columns and rows given
    px = bytearray(W * H // 2)
    for y in rows:
        for x in cols:
            i = y * W + x
            px[i // 2] |= 0xf0 if i % 2 == 0 else 0x0f
    return bytes(px)


def g1n(extra=0, faces=2):
    # code: (ordinal, width, place, advance, ink columns, ink rows); the ink always ends on row 14 (the last but one), as in the game's font. Each face the same glyphs
    glyphs = [(0x20, 4, 20, 12, (), ()), (0x21, W, 20, W, (1,), range(3, 15)), (0x31, W, 20, W, range(1, 6), range(3, 15)), (0x32, W, 20, W, range(1, 6), range(3, 15)),
              (0x3a, W, 20, W, (1,), (6, 14)), (0x41, W, 20, W, range(1, 6), range(3, 15)), (0x67, W, 16, W, range(1, 6), range(6, 15))]
    t0 = 0x20 + 4 * faces + 64 * faces   # the header, the face offsets, a palette a face
    m = bytearray(0x20000)
    for i, (code, *_ ) in enumerate(glyphs):
        struct.pack_into('<H', m, 2 * code, i)
    tables = b''; data = b''; offs = []
    for f in range(faces):
        offs.append(t0 + len(tables)); infos = b''
        for i, (code, w, place, adv, cols, rows) in enumerate(glyphs):
            infos += struct.pack('<BBbBBbBBI', w, H, 0, place, adv, -32, 0, 0, len(data))
            data += bitmap(cols, rows).ljust(0x100, b'\0')
        tables += bytes(m) + infos
    gofs = t0 + len(tables)
    body = tables + data + b'\x55' * extra   # extra: another face's bitmaps, which the reader does not need
    head = b'_N1G0000' + struct.pack('<IIIIII', gofs + len(data) + extra, t0, 10, gofs, faces, faces) + struct.pack('<%dI' % faces, *offs) + b'\0' * 64 * faces
    assert len(head) == t0
    return head + body


def entry(data, ktid=0x8207d16b, typ=0x786dcd84):   # flags 0x400000: u16 length + 8 bytes + a zlib stream per 64 KB
    chunks = b''
    for i in range(0, len(data), 0x10000):
        z = zlib.compress(data[i:i + 0x10000]); chunks += struct.pack('<H', len(z)) + b'\0' * 8 + z
    head = b'IDRK0000' + struct.pack('<QQQ', 0x30 + len(chunks), len(chunks), len(data)) + struct.pack('<IIII', 0, ktid, typ, 0x400000)
    e = head + chunks
    return e + b'\0' * (-len(e) % 16)


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'a JDK')
class GameFontRead(unittest.TestCase):
    def run_font(self, t, place_fdata):
        cls = t / 'cls'; cls.mkdir()
        for p, src in STUBS.items():
            f = t / 'src' / p; f.parent.mkdir(parents=True, exist_ok=True); f.write_text(src)
        srcs = [str(APP / 'GameFont.java'), str(APP / 'UiExport.java')] + [str(t / 'src' / p) for p in STUBS]
        subprocess.run(['javac', '-nowarn', '-d', str(cls)] + srcs, check=True, capture_output=True)
        font = g1n(extra=0x30000)
        fd, at = place_fdata(entry(font))
        upd = t / 'update/romfs'; base = t / 'base/romfs'
        dest = base / 'motor_asset_re/KIDS_PLATFORM_OUNCE/0x05d525ae.fdata'; dest.parent.mkdir(parents=True)   # only in the base game's RomFS: the update has none
        with open(dest, 'wb') as f:
            for off, b in fd: f.seek(off); f.write(b)
        r = subprocess.run(['java', '-cp', str(cls), 'dev.nxnative.android.Harness', str(upd), str(base), str(dest), str(at)], capture_output=True, text=True, check=True)
        return r.stdout.split('\n'), len(font)

    def check(self, out, size):
        self.assertIn('loaded true', out)
        self.assertIn('has true true false', out)   # no 'B' in the font
        # 'A' is 10 units wide, its margins 1 and 4: taken in by 3 (to 7); the space keeps its 12; a digit 7, and so as tabular; at size 60 a unit is a pixel
        self.assertIn('width 7.0 12.0 7.0 7.0', out)
        talk = next(l for l in out if l.startswith('talk ')).split()[1:]   # the talk face: 7 units closer, words too, at 0.825 of its width
        self.assertAlmostEqual(float(talk[0]), (10 - 7) * 0.825, places=4); self.assertAlmostEqual(float(talk[1]), (12 - 7) * 0.825, places=4)
        rects = [list(map(float, l.split()[1:])) for l in out if l.startswith('rect ')]
        self.assertEqual(len(rects), 2)
        a, g = rects
        k = 1.0
        self.assertAlmostEqual(a[1] + 15 * k, 100)   # 'A': the bottom of its last inked row (14) on the baseline
        self.assertAlmostEqual(g[1] + 15 * k, 104)   # 'g': place 16, 4 under 'A''s 20: 4 units below it
        self.assertAlmostEqual(g[0], 7)              # after 'A''s 7
        read = int(next(l for l in out if l.startswith('read ')).split()[1])
        self.assertLess(read, size)                  # the other face's bitmaps are not read
        self.assertGreaterEqual(read, size - 0x30000)

    def test_at_its_place(self):
        with tempfile.TemporaryDirectory(prefix='nx2a-font-') as tmp:
            at = 0x2f021150   # 2.0.1's: a sparse file
            out, size = self.run_font(Path(tmp), lambda e: ([(0, b'PDRK0000' + struct.pack('<Q', 0x10)), (at, e)], at))
            self.check(out, size)

    def test_moved(self):
        with tempfile.TemporaryDirectory(prefix='nx2a-font-') as tmp:
            other = entry(b'\0' * 0x40, ktid=0x1234, typ=0xafbec60c)
            fd = b'PDRK0000' + struct.pack('<Q', 0x10) + other + entry(b'G1N font of another file', typ=0x786dcd84, ktid=0x99) + other
            out, size = self.run_font(Path(tmp), lambda e: ([(0, fd + e)], len(fd)))
            self.check(out, size)


if __name__ == '__main__':
    unittest.main()
