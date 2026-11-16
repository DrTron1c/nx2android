package dev.nxnative.android;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * nx2android: finds where Pokémon Pokopia keeps its own font and its talk bubble, in the player's own game files, so the sleep mod can draw its menu and clocks
 * with them (SleepMod). The first step: a survey of the game's data with the candidates in a zip that the player sends to the developer, who writes the
 * readers from it. Never published: the files are the player's.
 *
 * The data (dm/gfx/tools/shader_split.py, which reads the same files for shaders): Koei Tecmo's newer RDB, .fdata files of IDRK entries ('IDRK0000', u64 entry
 * length, u64 stored length, u64 inflated length, u32 0 or 1, u32 file KTID, u32 type KTID, u32 flags, the stored data at the entry's end; flags 0x400000: chunks of
 * u16 length + 8 bytes, 0x100000 with it: LZ4 blocks of at most 16 KB, without: zlib; neither: stored), and a LINKDATA pair (LI_*.IDX of 32-byte records {offset,
 * inflated length, stored length, compressed} into LD_*.BIN, compressed records in KT's chunked zlib). Every entry but the shader containers ('_S2G', '_DOK') is
 * inflated and searched for a font ('_N1G', G1N: KTGL's font, the game's main names UseG1NFontIndex) and for the talk window's names ("talk"); those go in the zip
 * whole (up to 96 MB in all), with a survey of every entry type.
 */
final class UiExport {
    interface Progress { void on(String line); }

    private static final byte[] IDRK = "IDRK0000".getBytes(StandardCharsets.US_ASCII);
    private static final String[] MARKS = {"_N1G", "G1N_", "talk", "Talk", "TALK", "fukidashi", "balloon", "Balloon"};
    private static final long EXPORT_MAX = 96L << 20, ENTRY_MAX = 24L << 20, SEARCH_MAX = 512L << 20;

    private final Progress progress;
    private final Map<String, long[]> types = new TreeMap<>();   // "file type KTID / magic" → count, inflated bytes
    private final List<Hit> hits = new ArrayList<>();
    private long bytesIn, lastNote;

    private static final class Hit {
        final String where, magic; final File file; final long at; final int kind; final long size; final String marks, context;
        Hit(String where, File file, long at, int kind, long size, String magic, String marks, String context) {
            this.where = where; this.file = file; this.at = at; this.kind = kind; this.size = size; this.magic = magic; this.marks = marks; this.context = context;
        }
        boolean font() { return magic.startsWith("_N1G") || magic.startsWith("G1N_"); }
    }

    private UiExport(Progress p) { progress = p; }

    /** The survey of the RomFS folder into zip; its sentence for the panel */
    static String run(File romfs, File zip, Progress p) throws IOException {
        UiExport u = new UiExport(p);
        List<File> files = new ArrayList<>();
        u.walk(romfs, files, 0);
        StringBuilder rep = new StringBuilder("Pokopia UI survey (nx2android sleep mod)\nRomFS: " + romfs + "\n\n");
        List<File> small = new ArrayList<>();
        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            rep.append(String.format(Locale.US, "%12d  %s\n", f.length(), rel(romfs, f)));
            if (n.endsWith(".fdata") || isIdrk(f)) u.idrkFile(romfs, f);
            else if (n.startsWith("ld_") && n.endsWith(".bin")) u.linkdata(romfs, f);
            if ((n.endsWith(".rdb") || n.endsWith(".rdx") || n.endsWith(".idx") || n.contains("font") || n.endsWith(".g1n")) && f.length() < (16 << 20)) small.add(f);
        }
        rep.append("\nEntry types (file, type KTID, first bytes): count, inflated bytes\n");
        for (Map.Entry<String, long[]> e : u.types.entrySet()) rep.append(String.format(Locale.US, "%8d %14d  %s\n", e.getValue()[0], e.getValue()[1], e.getKey()));
        rep.append("\nCandidates\n");
        for (Hit h : u.hits) rep.append(h.where).append("  kind ").append(h.kind).append("  ").append(h.size).append(" bytes  ").append(h.magic).append("  ").append(h.marks).append("\n    ").append(h.context).append('\n');
        long put = 0; int n = 0;
        zip.getParentFile().mkdirs();
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(zip))) {
            List<Hit> order = new ArrayList<>(u.hits);
            order.sort((a, b) -> Boolean.compare(b.font(), a.font()));   // fonts first
            for (Hit h : order) {
                if (h.size > ENTRY_MAX || put + h.size > EXPORT_MAX) continue;
                byte[] d = u.entry(h);
                if (d == null) continue;
                z.putNextEntry(new ZipEntry(String.format(Locale.US, "entries/%03d_%s_%x.bin", n++, h.font() ? "font" : "talk", h.at)));
                z.write(d); z.closeEntry(); put += d.length;
                p.on("Saving " + n + " entries (" + put / 1048576 + " MB)…");
            }
            for (File f : small) { z.putNextEntry(new ZipEntry("files/" + rel(romfs, f).replace('/', '_'))); z.write(java.nio.file.Files.readAllBytes(f.toPath())); z.closeEntry(); }
            z.putNextEntry(new ZipEntry("survey.txt")); z.write(rep.toString().getBytes(StandardCharsets.UTF_8)); z.closeEntry();
        }
        int fonts = 0; for (Hit h : u.hits) if (h.font()) fonts++;
        return "Done: " + fonts + " font" + (fonts == 1 ? "" : "s") + " and " + (u.hits.size() - fonts) + " talk window candidates, " + (zip.length() >> 20) + " MB. Send this file: " + zip;
    }

    /** A file that starts with an RDB data header (PDRK, or an IDRK entry) */
    private static boolean isIdrk(File f) {
        if (f.length() < 0x30) return false;
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] h = new byte[8]; r.readFully(h);
            String m = new String(h, StandardCharsets.US_ASCII);
            return m.equals("PDRK0000") || m.equals("IDRK0000");
        } catch (IOException e) { return false; }
    }

    private void walk(File d, List<File> out, int depth) {
        File[] l = d.listFiles();
        if (l == null || depth > 8) return;
        Arrays.sort(l);
        for (File f : l) { if (f.isDirectory()) walk(f, out, depth + 1); else out.add(f); }
    }

    private static String rel(File root, File f) { String r = root.getPath(), p = f.getPath(); return p.startsWith(r) ? p.substring(r.length()).replaceFirst("^/", "") : p; }

    private void note(String what, long at, long size) {
        long now = System.currentTimeMillis();
        if (now - lastNote < 500) return;
        lastNote = now;
        progress.on("Reading " + what + " " + (size > 0 ? at * 100 / size : 0) + "% (" + (bytesIn >> 20) + " MB looked at, " + hits.size() + " found)…");
    }

    // ---- IDRK entries ----
    private void idrkFile(File root, File f) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r"); FileChannel ch = raf.getChannel()) {
            long size = ch.size(), o = 0;
            ByteBuffer h = ByteBuffer.allocate(0x30).order(ByteOrder.LITTLE_ENDIAN);
            while (o + 0x30 <= size) {
                h.clear(); ch.read(h, o); h.flip();
                if (!startsWith(h, IDRK)) { o = find(ch, IDRK, o + 1, size); if (o < 0) break; continue; }
                long esz = h.getLong(8), csz = h.getLong(0x10), n = h.getLong(0x18);
                int ktid = h.getInt(0x24), type = h.getInt(0x28), flags = h.getInt(0x2c);
                if (esz < 0x30 || csz < 0 || csz > esz || o + esz > size) { o += 16; continue; }
                note(f.getName(), o, size);
                scanIdrk(rel(root, f), f, ch, o, o + esz - csz, o + esz, flags, n, ktid, type);
                o += esz;
            }
        }
    }

    private static boolean startsWith(ByteBuffer b, byte[] m) { if (b.remaining() < m.length) return false; for (int i = 0; i < m.length; i++) if (b.get(i) != m[i]) return false; return true; }

    private static long find(FileChannel ch, byte[] m, long from, long size) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(1 << 16);
        for (long o = from; o < size; o += b.capacity() - m.length) {
            b.clear(); int r = ch.read(b, o); if (r < m.length) return -1;
            byte[] a = b.array();
            outer:
            for (int i = 0; i + m.length <= r; i++) { for (int k = 0; k < m.length; k++) if (a[i + k] != m[k]) continue outer; return o + i; }
        }
        return -1;
    }

    /** Inflates an IDRK entry's chunks into sink (stops when sink says so; GameFont reads the game's font with it too) */
    interface Sink { boolean take(byte[] b, int n); }

    static void idrkChunks(FileChannel ch, long a, long b, int flags, Sink sink) throws IOException {
        boolean neu = (flags & 0x400000) != 0, lz4 = (flags & 0x500000) == 0x500000;
        if ((flags & 0x500000) == 0) {   // stored
            ByteBuffer buf = ByteBuffer.allocate(1 << 16);
            for (long o = a; o < b; ) { buf.clear(); buf.limit((int) Math.min(buf.capacity(), b - o)); int r = ch.read(buf, o); if (r <= 0 || !sink.take(buf.array(), r)) return; o += r; }
            return;
        }
        ByteBuffer hd = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN);
        byte[] out = new byte[1 << 16];
        Inflater inf = new Inflater();
        try {
            while (a + 4 <= b) {
                hd.clear(); hd.limit(neu ? 10 : 4); ch.read(hd, a); hd.flip();
                int cs = neu ? hd.getShort(0) & 0xffff : hd.getInt(0); a += neu ? 10 : 4;
                if (cs <= 0 || a + cs > b) return;
                ByteBuffer c = ByteBuffer.allocate(cs); ch.read(c, a); a += cs;
                int n;
                if (lz4) n = lz4(c.array(), cs, out, 0x4000);
                else { inf.reset(); inf.setInput(c.array(), 0, cs); try { n = inf.inflate(out); } catch (DataFormatException e) { return; } }
                if (n <= 0 || !sink.take(out, n)) return;
            }
        } finally { inf.end(); }
    }

    /** One LZ4 block into out; its length, -1 when it is no block */
    static int lz4(byte[] s, int n, byte[] o, int cap) {
        int i = 0, j = 0;
        while (i < n) {
            int t = s[i++] & 0xff, l = t >>> 4;
            if (l == 15) { int x; do { if (i >= n) return -1; x = s[i++] & 0xff; l += x; } while (x == 255); }
            if (i + l > n || j + l > cap || j + l > o.length) return -1;
            System.arraycopy(s, i, o, j, l); i += l; j += l;
            if (i >= n) break;
            if (i + 2 > n) return -1;
            int off = (s[i] & 0xff) | (s[i + 1] & 0xff) << 8; i += 2;
            int m = t & 15;
            if (m == 15) { int x; do { if (i >= n) return -1; x = s[i++] & 0xff; m += x; } while (x == 255); }
            m += 4;
            if (off == 0 || off > j || j + m > cap || j + m > o.length) return -1;
            for (int k = 0; k < m; k++) o[j + k] = o[j - off + k];
            j += m;
        }
        return j;
    }

    /** Searches one entry as it inflates (the shader containers are only counted) */
    private final class Search implements Sink {
        final StringBuilder marks = new StringBuilder(); String magic = null, context = ""; long total; boolean skip;
        private byte[] tail = new byte[0];
        @Override public boolean take(byte[] b, int n) {
            if (magic == null) { magic = magicOf(b, n); skip = magic.startsWith("_S2G") || magic.startsWith("_DOK"); }
            total += n; bytesIn += n;
            if (skip) return false;
            byte[] w = new byte[tail.length + n]; System.arraycopy(tail, 0, w, 0, tail.length); System.arraycopy(b, 0, w, tail.length, n);
            String s = new String(w, StandardCharsets.ISO_8859_1);
            for (String m : MARKS) {
                int k = s.indexOf(m);
                if (k >= 0 && marks.indexOf(m) < 0) { marks.append(marks.length() > 0 ? "," : "").append(m); if (context.isEmpty() || !m.startsWith("_N1G")) context = printable(s, Math.max(0, k - 40), Math.min(s.length(), k + 80)); }
            }
            tail = Arrays.copyOfRange(w, Math.max(0, w.length - 16), w.length);
            return total < SEARCH_MAX;
        }
    }

    private void scanIdrk(String file, File f, FileChannel ch, long entry, long a, long b, int flags, long n, int ktid, int type) throws IOException {
        Search s = new Search();
        idrkChunks(ch, a, b, flags, s);
        String key = String.format(Locale.US, "%s type %08x %s", file, type, s.magic == null ? "?" : s.magic);
        long[] t = types.get(key); if (t == null) types.put(key, t = new long[2]); t[0]++; t[1] += n;
        if (s.marks.length() > 0) hits.add(new Hit(String.format(Locale.US, "%s#IDRK@%#x file %08x type %08x", file, entry, ktid, type), f, entry, 0, n, s.magic, s.marks.toString(), s.context));
    }

    // ---- LINKDATA ----
    private void linkdata(File root, File bin) throws IOException {
        File idx = new File(bin.getParentFile(), "LI_" + bin.getName().substring(3, bin.getName().length() - 4) + ".IDX");
        if (!idx.isFile()) { for (File g : bin.getParentFile().listFiles()) if (g.getName().equalsIgnoreCase(idx.getName())) idx = g; }
        if (!idx.isFile()) return;
        byte[] ix = java.nio.file.Files.readAllBytes(idx.toPath());
        ByteBuffer ib = ByteBuffer.wrap(ix).order(ByteOrder.LITTLE_ENDIAN);
        try (RandomAccessFile raf = new RandomAccessFile(bin, "r"); FileChannel ch = raf.getChannel()) {
            for (int i = 0; i + 32 <= ix.length; i += 32) {
                long off = ib.getLong(i), len = ib.getLong(i + 8), st = ib.getLong(i + 16), comp = ib.getLong(i + 24);
                if (st <= 0 || st > (512L << 20) || off < 0 || off + st > ch.size()) continue;
                note(bin.getName(), off, ch.size());
                byte[] d = linkEntry(ch, off, st, comp != 0);
                if (d == null) continue;
                bytesIn += d.length;
                String magic = magicOf(d, d.length);
                String key = String.format(Locale.US, "%s %s", rel(root, bin), magic);
                long[] t = types.get(key); if (t == null) types.put(key, t = new long[2]); t[0]++; t[1] += d.length;
                String s = new String(d, StandardCharsets.ISO_8859_1); StringBuilder m = new StringBuilder(); String ctx = "";
                for (String k : MARKS) { int at = s.indexOf(k); if (at >= 0) { m.append(m.length() > 0 ? "," : "").append(k); ctx = printable(s, Math.max(0, at - 40), Math.min(s.length(), at + 80)); } }
                if (m.length() > 0) hits.add(new Hit(String.format(Locale.US, "%s record %d @%#x", rel(root, bin), i / 32, off), bin, i / 32, 1, d.length, magic, m.toString(), ctx));
            }
        }
    }

    private static byte[] linkEntry(FileChannel ch, long off, long st, boolean comp) throws IOException {
        ByteBuffer b = ByteBuffer.allocate((int) st); ch.read(b, off);
        byte[] d = b.array();
        if (!comp) return d;
        byte[] z = ktGz(d);
        return z != null ? z : d;
    }

    /** KT's chunked zlib (shader_split.py kt_gz): u32 chunk size, u32 count, u32 inflated length, u32 stored length of each chunk, the chunks at 0x80, each a u32 length +
     *  one zlib stream or the plain bytes */
    static byte[] ktGz(byte[] d) {
        if (d.length < 16) return null;
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        long cs = b.getInt(0) & 0xffffffffL, n = b.getInt(4) & 0xffffffffL, tot = b.getInt(8) & 0xffffffffL;
        if (cs < 0x10000 || (cs & (cs - 1)) != 0 || n == 0 || n != (tot + cs - 1) / cs || 12 + 4 * n > d.length || tot > (1L << 30)) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) tot);
        long o = (12 + 4 * n + 0x7f) & ~0x7fL;
        Inflater inf = new Inflater();
        try {
            for (int i = 0; i < n; i++) {
                long s = b.getInt(12 + 4 * i) & 0xffffffffL;
                if (o + s > d.length) return null;
                if (s == Math.min(cs, tot - i * cs)) out.write(d, (int) o, (int) s);
                else {
                    int ln = b.getInt((int) o);
                    inf.reset(); inf.setInput(d, (int) o + 4, ln);
                    byte[] c = new byte[(int) Math.min(cs, tot - i * cs)];
                    int k = inf.inflate(c); out.write(c, 0, k);
                }
                o = (o + s + 0x7f) & ~0x7fL;
            }
        } catch (DataFormatException e) { return null; } finally { inf.end(); }
        return out.toByteArray();
    }

    /** A candidate's whole data, again */
    private byte[] entry(Hit h) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(h.file, "r"); FileChannel ch = raf.getChannel()) {
            if (h.kind == 1) {
                byte[] ix = java.nio.file.Files.readAllBytes(new File(h.file.getParentFile(), "LI_" + h.file.getName().substring(3, h.file.getName().length() - 4) + ".IDX").toPath());
                ByteBuffer ib = ByteBuffer.wrap(ix).order(ByteOrder.LITTLE_ENDIAN); int i = (int) h.at * 32;
                return linkEntry(ch, ib.getLong(i), ib.getLong(i + 16), ib.getLong(i + 24) != 0);
            }
            ByteBuffer hd = ByteBuffer.allocate(0x30).order(ByteOrder.LITTLE_ENDIAN); ch.read(hd, h.at);
            long esz = hd.getLong(8), csz = hd.getLong(0x10); int flags = hd.getInt(0x2c);
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(h.size, ENTRY_MAX));
            hd.flip(); out.write(hd.array(), 0, 0x30);   // the IDRK header first, for its KTIDs and parameters
            idrkChunks(ch, h.at + esz - csz, h.at + esz, flags, (b, n) -> { out.write(b, 0, n); return out.size() < ENTRY_MAX + 0x30; });
            return out.toByteArray();
        }
    }

    private static String magicOf(byte[] b, int n) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < Math.min(8, n); i++) { int c = b[i] & 0xff; s.append(c >= 0x20 && c < 0x7f ? (char) c : '.'); }
        return s.toString();
    }

    private static String printable(String s, int a, int b) {
        StringBuilder o = new StringBuilder();
        for (int i = a; i < b; i++) { char c = s.charAt(i); o.append(c >= 0x20 && c < 0x7f ? c : '.'); }
        return o.toString();
    }
}
