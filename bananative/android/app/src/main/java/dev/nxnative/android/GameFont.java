package dev.nxnative.android;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.SparseArray;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.Arrays;

/**
 * nx2android: Pokémon Pokopia's own text font, read from the player's game files (never shipped), for the sleep mod's menu and clocks (SleepMod). The game's fonts are
 * KTGL G1N entries (type KTID 0x786dcd84) in RDB data file 0x05d525ae.fdata; on 2.0.1 (the sleep mod's survey, UiExport) the 2.2 MB one, file KTID 0x8207d16b, is the
 * text font: its first face has ASCII, Latin-1 and a few superscripts.
 *
 * G1N (as lehieugch68/g1n-font-editor reads it): '_N1G0000', u32 size, u32 header size, u32, u32 glyph data offset, u32 palettes, u32 faces, u32 face offsets[faces],
 * palettes (16 RGBA each). A face: u16 map[0x10000] (code → glyph; 0 is glyph 0 only for the code just before the first one mapped, the space), then 12-byte glyphs
 * {u8 width, u8 height, s8 x offset, u8 place (the height of the bitmap's bottom: 'A' 74, 'g' 58, "'" 100), u8 advance, s8, u8 0, u8 0, u32 bitmap offset into the glyph
 * data}; a bitmap is 4bpp rows of the width rounded up to even, high nibble first, its ink ending on the last row but one. Only the first face is read, and only as much
 * of the entry as it needs.
 */
final class GameFont {
    static final String FDATA = "motor_asset_re/KIDS_PLATFORM_OUNCE/0x05d525ae.fdata";
    static final int TYPE_G1N = 0x786dcd84;
    static final long TEXT_AT = 0x2f021150L;   // where the text font's entry is on 2.0.1 (the survey); looked for by its file KTID when it is not there
    static final int TEXT_KTID = 0x8207d16b;
    private static final long IDRK = 0x303030304b524449L;   // 'IDRK0000'

    private static final class Glyph { Bitmap bmp, big; int w, h, x, place, adv, bigH; }
    private final SparseArray<Glyph> glyphs = new SparseArray<>();
    private final float lift, track, digit;   // a bitmap's top sits (lift + place) units above the baseline; track: the space between letters the bitmaps' padding leaves too
                                               // wide; digit: the widest digit's advance (a clock's digits keep their places)
    private final RectF r = new RectF();

    GameFont(byte[] d) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        if (d.length < 0x24 || b.getInt(0) != 0x47314e5f) throw new IOException("no G1N font");   // '_N1G'
        int gofs = b.getInt(0x14), faces = b.getInt(0x1c), t = b.getInt(0x20);
        int end = faces > 1 ? b.getInt(0x24) : gofs, gi = t + 0x20000, n = (end - gi) / 12;
        if (faces < 1 || t < 0x24 || gi + 12L * Math.max(n, 0) > d.length || n <= 0 || n > 0x10000) throw new IOException("a G1N face this reader does not know");
        Glyph[] byIndex = new Glyph[n];
        boolean first = true;
        for (int c = 0; c < 0x10000; c++) {
            int v = b.getShort(t + 2 * c) & 0xffff;
            if (v == 0 && !(first && c + 1 < 0x10000 && (b.getShort(t + 2 * c + 2) & 0xffff) != 0)) continue;   // 0: glyph 0 for the code before the first mapped one
            first = false;
            if (v >= n) continue;
            if (byIndex[v] == null) byIndex[v] = glyph(d, b, gofs, gi + 12 * v);
            glyphs.put(c, byIndex[v]);
        }
        Glyph a = glyphs.get('A');
        if (a == null || a.bmp == null) throw new IOException("no 'A' in the G1N font");
        lift = rows(a)[1] + 1 - a.place;   // 'A' sits on the baseline
        // the bitmaps keep a wide margin on the right (room for an outline): take it in to the left margin's size
        String s = "AHMNOabdehlmnopsuvx0123456789";
        int[] lm = new int[s.length()], rm = new int[s.length()]; int k = 0;
        for (int i = 0; i < s.length(); i++) { Glyph g = glyphs.get(s.charAt(i)); int[] cl = g != null && g.bmp != null ? cols(g) : null; if (cl != null) { lm[k] = cl[0]; rm[k++] = g.w - 1 - cl[1]; } }
        track = k > 0 ? median(lm, k) - median(rm, k) : 0;
        float dw = 0;
        for (char ch = '0'; ch <= '9'; ch++) { Glyph g = glyphs.get(ch); if (g != null) dw = Math.max(dw, adv(g, ch)); }
        digit = dw;
    }

    private static float median(int[] v, int k) { int[] s = Arrays.copyOf(v, k); Arrays.sort(s); return s[k / 2]; }

    private static int alpha(byte[] d, int gofs, int off, int w, int x, int y) {
        int s = (w + 1) & ~1, i = y * s + x; long p = (long) gofs + off + i / 2;
        if (p < 0 || p >= d.length) return 0;
        return (i & 1) == 0 ? (d[(int) p] >> 4) & 15 : d[(int) p] & 15;
    }

    private static Glyph glyph(byte[] d, ByteBuffer b, int gofs, int at) {
        Glyph g = new Glyph();
        g.w = d[at] & 0xff; g.h = d[at + 1] & 0xff; g.x = d[at + 2]; g.place = d[at + 3] & 0xff; g.adv = d[at + 4] & 0xff;
        int off = b.getInt(at + 8);
        if (g.w > 0 && g.h > 0) {
            g.bmp = Bitmap.createBitmap(g.w, g.h, Bitmap.Config.ALPHA_8);
            int rb = g.bmp.getRowBytes();
            byte[] px = new byte[rb * g.h];
            boolean ink = false;
            for (int y = 0; y < g.h; y++) for (int x = 0; x < g.w; x++) { int v = alpha(d, gofs, off, g.w, x, y); px[y * rb + x] = (byte) (v * 17); ink |= v != 0; }
            if (ink) g.bmp.copyPixelsFromBuffer(ByteBuffer.wrap(px)); else { g.bmp.recycle(); g.bmp = null; }
        }
        return g;
    }

    private static boolean ink(Bitmap b, int x, int y) { return (b.getPixel(x, y) >>> 24) != 0; }

    /** The first and last row with ink */
    private static int[] rows(Glyph g) {
        int top = -1, bot = -1;
        for (int y = 0; y < g.h; y++) for (int x = 0; x < g.w; x++) if (ink(g.bmp, x, y)) { if (top < 0) top = y; bot = y; break; }
        return new int[] {top, bot};
    }

    /** The first and last column with ink, null for none */
    private static int[] cols(Glyph g) {
        int l = -1, rr = -1;
        for (int x = 0; x < g.w; x++) for (int y = 0; y < g.h; y++) if (ink(g.bmp, x, y)) { if (l < 0) l = x; rr = x; break; }
        return l < 0 ? null : new int[] {l, rr};
    }

    /** g's bitmap at k times its size, its edges kept sharp: a bitmap scaled up smoothly has soft edges k pixels wide; they are steepened back to about one */
    private Bitmap big(Glyph g, float k) {
        int bh = Math.round(g.h * k);
        if (g.big != null && g.bigH == bh) return g.big;
        Bitmap s = Bitmap.createScaledBitmap(g.bmp, Math.max(1, Math.round(g.w * k)), Math.max(1, bh), true);
        if (s.getConfig() != Bitmap.Config.ALPHA_8) { Bitmap a = s.extractAlpha(); if (s != g.bmp) s.recycle(); s = a; }
        ByteBuffer px = ByteBuffer.allocate(s.getRowBytes() * s.getHeight());
        s.copyPixelsToBuffer(px);
        byte[] a = px.array(); float slope = k / 1.2f;
        for (int i = 0; i < a.length; i++) a[i] = (byte) Math.round(Math.max(0f, Math.min(1f, ((a[i] & 0xff) / 255f - 0.5f) * slope + 0.5f)) * 255);
        px.rewind(); s.copyPixelsFromBuffer(px);
        if (g.big != null) g.big.recycle();
        g.big = s; g.bigH = bh;
        return s;
    }

    /** Whether every character of s has a glyph */
    boolean has(String s) {
        for (int i = 0; i < s.length(); i++) if (glyphs.get(s.charAt(i)) == null) return false;
        return true;
    }

    private static float scale(float size) { return size / 60f; }   // size as Paint's text size: the cell is 64 units, the capitals 42 of them

    private float adv(Glyph g, char ch) { return ch == ' ' || g.bmp == null ? g.adv : g.adv + track; }

    /** s's width; digits all as wide as the widest when tabular */
    float measure(String s, float size, boolean tabular) {
        float w = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i); Glyph g = glyphs.get(ch);
            if (g != null) w += tabular && ch >= '0' && ch <= '9' ? digit : adv(g, ch);
        }
        return w * scale(size);
    }

    /** s from x (left), its baseline at y, in p's colour and alpha (p should filter bitmaps); its width. Digits keep the widest digit's place when tabular. */
    float draw(Canvas c, String s, float x, float y, float size, Paint p, boolean tabular) {
        float k = scale(size), x0 = x;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i); Glyph g = glyphs.get(ch);
            if (g == null) continue;
            boolean slot = tabular && ch >= '0' && ch <= '9';
            float a = slot ? digit : adv(g, ch);
            if (g.bmp != null) {
                float top = y - (lift + g.place) * k, left = x + (g.x + (slot ? (digit - adv(g, ch)) / 2 : 0)) * k;
                r.set(left, top, left + g.w * k, top + g.h * k);
                c.drawBitmap(k > 1.3f ? big(g, k) : g.bmp, null, r, p);
            }
            x += a * k;
        }
        return x - x0;
    }

    /** The text font from the game's RomFS folders (the first that has it), null when none has */
    static GameFont load(File... romfs) {
        for (File root : romfs) {
            File f = root != null ? new File(root, FDATA) : null;
            if (f == null || !f.isFile()) continue;
            try (RandomAccessFile raf = new RandomAccessFile(f, "r"); FileChannel ch = raf.getChannel()) {
                long e = entry(ch, TEXT_AT, TEXT_KTID);
                if (e < 0) e = find(ch, TEXT_KTID);
                if (e < 0) { Native.note("sleep mod: no font " + Integer.toHexString(TEXT_KTID) + " in " + f); continue; }
                return new GameFont(face(ch, e));
            } catch (IOException | RuntimeException ex) {
                Native.note("sleep mod: the game's font in " + f + " did not load: " + ex);
            }
        }
        return null;
    }

    /** The entry's data up to the end of its first face's bitmaps */
    static byte[] face(FileChannel ch, long e) throws IOException {
        ByteBuffer h = ByteBuffer.allocate(0x30).order(ByteOrder.LITTLE_ENDIAN); ch.read(h, e);
        long esz = h.getLong(8), csz = h.getLong(0x10), n = h.getLong(0x18);
        if (n <= 0x24 || n > (64L << 20) || csz <= 0 || csz > esz) throw new IOException("an IDRK entry this reader does not know");
        ByteArrayOutputStream o = new ByteArrayOutputStream(1 << 20);
        long[] need = {n};
        UiExport.idrkChunks(ch, e + esz - csz, e + esz, h.getInt(0x2c), (buf, len) -> {
            o.write(buf, 0, len);
            if (need[0] == n) need[0] = needed(o.toByteArray(), n);
            return o.size() < need[0];
        });
        return o.toByteArray();
    }

    /** How much of the G1N the first face needs: the header and the face tables, then its glyphs' bitmaps (n until that is known) */
    private static long needed(byte[] d, long n) {
        if (d.length < 0x24) return n;
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        long gofs = b.getInt(0x14) & 0xffffffffL;
        if (d.length < gofs) return n;
        int faces = b.getInt(0x1c), t = b.getInt(0x20), end = faces > 1 ? b.getInt(0x24) : (int) gofs, gi = t + 0x20000;
        long most = 0;
        for (int at = gi; at + 12 <= end && at + 12 <= d.length; at += 12) {
            int w = d[at] & 0xff, hh = d[at + 1] & 0xff;
            most = Math.max(most, (b.getInt(at + 8) & 0xffffffffL) + (long) ((w + 1) & ~1) * hh / 2);
        }
        return Math.min(n, gofs + most);
    }

    /** at, when an IDRK entry of the G1N type with that file KTID starts there; else -1 */
    private static long entry(FileChannel ch, long at, int ktid) throws IOException {
        if (at < 0 || at + 0x30 > ch.size()) return -1;
        ByteBuffer h = ByteBuffer.allocate(0x30).order(ByteOrder.LITTLE_ENDIAN); ch.read(h, at);
        return h.getLong(0) == IDRK && h.getInt(0x24) == ktid && h.getInt(0x28) == TYPE_G1N ? at : -1;
    }

    /** The entry with that file KTID, walking the file's IDRK entries (another game version moved it) */
    private static long find(FileChannel ch, int ktid) throws IOException {
        ByteBuffer h = ByteBuffer.allocate(0x30).order(ByteOrder.LITTLE_ENDIAN);
        long o = 0, size = ch.size();
        while (o + 0x30 <= size) {
            h.clear(); ch.read(h, o);
            if (h.getLong(0) != IDRK) { o = (o + 16) & ~15L; continue; }
            if (h.getInt(0x24) == ktid && h.getInt(0x28) == TYPE_G1N) return o;
            long esz = h.getLong(8);
            if (esz < 0x30) return -1;
            o += esz;
        }
        return -1;
    }
}
