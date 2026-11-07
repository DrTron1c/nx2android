package org.nx2a.builder;

import android.system.ErrnoException;
import android.system.Os;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/** Downloads a release file (resuming a cut-off download) and unpacks a .tar.gz with its links and modes. */
final class Download {
    static final String BASE = "https://github.com/DrTron1c/nx2android/releases/download/builder-tools/";

    interface Progress { void at(String what, long done, long total); }

    static String text(String name) throws IOException {
        HttpURLConnection c = open(BASE + name, 0);
        try (InputStream in = c.getInputStream(); java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream()) {
            byte[] b = new byte[4096]; for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n);
            return new String(o.toByteArray(), StandardCharsets.UTF_8).trim();
        } finally { c.disconnect(); }
    }

    /** Any URL's body as text (a GitHub API answer). */
    static String url(String url) throws IOException {
        HttpURLConnection c = open(url, 0);
        try (InputStream in = c.getInputStream(); java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream()) {
            byte[] b = new byte[1 << 16]; for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n);
            return new String(o.toByteArray(), StandardCharsets.UTF_8);
        } finally { c.disconnect(); }
    }
    /** Any URL into a file (a driver's zip), whole or not at all. */
    static void urlToFile(String url, File to, Progress p) throws IOException {
        to.getParentFile().mkdirs(); File part = new File(to.getPath() + ".part");
        HttpURLConnection c = open(url, 0);
        try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(part)) {
            long total = c.getContentLengthLong(), done = 0, last = 0; byte[] b = new byte[1 << 16];
            for (int n; (n = in.read(b)) > 0; ) { o.write(b, 0, n); done += n; if (done - last > 1 << 20) { p.at("Downloading " + to.getName(), done, total); last = done; } }
            if (total > 0 && done < total) throw new IOException("the connection ended early");
        } finally { c.disconnect(); }
        if (!part.renameTo(to)) throw new IOException("could not finish " + to);
    }

    private static HttpURLConnection open(String url, long from) throws IOException {
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(20000); c.setReadTimeout(60000); c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "nx2android-builder");
            if (from > 0) c.setRequestProperty("Range", "bytes=" + from + "-");
            int s = c.getResponseCode();
            if (s >= 300 && s < 400) { url = c.getHeaderField("Location"); c.disconnect(); continue; }
            if (s >= 400) { c.disconnect(); throw new IOException("the download answered " + s + " for " + url); }
            return c;
        }
        throw new IOException("too many redirects: " + url);
    }

    static File download(String name, File dir, Progress p) throws IOException {
        dir.mkdirs();
        File part = new File(dir, name + ".part");
        for (int attempt = 0; ; attempt++) {
            long have = part.length();
            try {
                HttpURLConnection c = open(BASE + name, have);
                boolean resumed = c.getResponseCode() == 206;
                if (!resumed) have = 0;
                long total = have + c.getContentLengthLong();
                try (InputStream in = c.getInputStream(); RandomAccessFile f = new RandomAccessFile(part, "rw")) {
                    f.setLength(have); f.seek(have);
                    byte[] b = new byte[1 << 20]; long done = have, last = 0;
                    for (int n; (n = in.read(b)) > 0; ) {
                        f.write(b, 0, n); done += n;
                        if (done - last > 1 << 22) { p.at("Downloading " + name, done, total); last = done; }
                    }
                    if (total > 0 && done < total) throw new IOException("the connection ended early");
                } finally { c.disconnect(); }
                File whole = new File(dir, name);
                if (!part.renameTo(whole)) throw new IOException("could not finish " + whole);
                return whole;
            } catch (IOException e) {
                if (attempt >= 8) throw e;
                try { Thread.sleep(3000); } catch (InterruptedException x) { throw new IOException("interrupted"); }
            }
        }
    }

    private static String field(byte[] h, int off, int len) {
        int e = off; while (e < off + len && h[e] != 0) e++;
        return new String(h, off, e - off, StandardCharsets.UTF_8);
    }
    private static long octal(byte[] h, int off, int len) {
        if ((h[off] & 0x80) != 0) { long v = 0; for (int i = off + 1; i < off + len; i++) v = v << 8 | (h[i] & 0xff); return v; }   // GNU base-256, for files past 8 GB
        String s = field(h, off, len).trim(); return s.isEmpty() ? 0 : Long.parseLong(s, 8);
    }
    private static void readFully(InputStream in, byte[] b, int n) throws IOException {
        for (int got = 0; got < n; ) { int k = in.read(b, got, n - got); if (k < 0) throw new IOException("the archive ends early"); got += k; }
    }

    static void unpack(File gz, File into, Progress p) throws IOException, ErrnoException {
        long size = gz.length();
        CountingInput counted = new CountingInput(new FileInputStream(gz));
        try (InputStream in = new GZIPInputStream(new BufferedInputStream(counted, 1 << 20), 1 << 16)) {
            byte[] h = new byte[512], buf = new byte[1 << 16]; String longName = null, longLink = null; long files = 0;
            while (true) {
                readFully(in, h, 512);
                if (h[0] == 0) break;
                String name = longName != null ? longName : field(h, 0, 100);
                String prefix = field(h, 345, 155); if (longName == null && !prefix.isEmpty() && h[156] != 'L') name = prefix + "/" + name;
                String link = longLink != null ? longLink : field(h, 157, 100);
                char type = (char) h[156]; long len = octal(h, 124, 12); int mode = (int) octal(h, 100, 8);
                if (type == 'L' || type == 'K') {
                    byte[] t = new byte[(int) len]; readFully(in, t, t.length);
                    int pad = (int) (512 - len % 512) % 512; if (pad > 0) readFully(in, buf, pad);
                    String v = field(t, 0, t.length); if (type == 'L') longName = v; else longLink = v;
                    continue;
                }
                longName = null; longLink = null;
                if (name.startsWith("./")) name = name.substring(2);
                if (name.isEmpty()) continue;
                if (name.contains("..")) throw new IOException("an unsafe path in the archive: " + name);
                File f = new File(into, name);
                if (type == '5') { f.mkdirs(); continue; }
                f.getParentFile().mkdirs(); f.delete();
                if (type == '2') { Os.symlink(link, f.getPath()); continue; }
                if (type == '1') { Os.link(new File(into, link).getPath(), f.getPath()); continue; }
                if (type != '0' && type != 0) { skip(in, len + (512 - len % 512) % 512, buf); continue; }
                try (OutputStream o = new FileOutputStream(f)) {
                    for (long left = len; left > 0; ) { int n = in.read(buf, 0, (int) Math.min(left, buf.length)); if (n < 0) throw new IOException("the archive ends early"); o.write(buf, 0, n); left -= n; }
                }
                Os.chmod(f.getPath(), mode & 0777);
                int pad = (int) ((512 - len % 512) % 512); if (pad > 0) readFully(in, buf, pad);
                if (++files % 200 == 0) p.at("Unpacking " + gz.getName(), counted.count, size);
            }
        }
    }
    private static void skip(InputStream in, long n, byte[] buf) throws IOException {
        while (n > 0) { int k = in.read(buf, 0, (int) Math.min(n, buf.length)); if (k < 0) return; n -= k; }
    }

    private static final class CountingInput extends InputStream {
        final InputStream in; long count;
        CountingInput(InputStream in) { this.in = in; }
        @Override public int read() throws IOException { int b = in.read(); if (b >= 0) count++; return b; }
        @Override public int read(byte[] b, int o, int n) throws IOException { int k = in.read(b, o, n); if (k > 0) count += k; return k; }
        @Override public void close() throws IOException { in.close(); }
    }
}
