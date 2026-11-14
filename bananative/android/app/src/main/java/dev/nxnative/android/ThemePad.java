package dev.nxnative.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * nx2android's themed on-screen gamepad (bananative/UPSTREAM.md): the player's own designs from nx2android's engine (../nxnative/dm/android/themes; Pokopia's in its
 * HUD style, with L3 and R3), drawn as that engine drew them (gfx_vk_present.c ov_theme_draw, overlay_theme.c). build.gradle's nxThemeAssets puts a game's pack in
 * assets/overlay/<title ID>/: theme.tsv, one atlas per layout (tablet.png, phone.png), and layout.tsv, the gamepad's layout (overlay_default.tsv with the moves of the
 * game's android.tsv). TouchControls lays its controls out from it and draws the sprites; a game without a pack keeps Banana Native's own controls.
 */
final class ThemePad {
    static final int IDLE = 0, HELD = 1, KNOB = 2, DECOR = 3;
    static final String[] LAYOUTS = {"tablet", "phone"};
    /** The phone layout when the window is at least this wide for its height (overlay_layout.h OV_PHONE_ASPECT: a 20:9 phone is 2.2, a 16:10 tablet 1.6) */
    static final float PHONE_ASPECT = 1.9f;

    /** A control of a layout: its name, kind (stick_l, stick_r, dpad, button, menu), anchor and centre (x in window heights from the anchor, a fraction of the width;
     *  y a fraction of the height), size in window heights, and a button's DM_BTN_* name */
    static final class Ctl {
        final String name, kind, button, label;
        final float anchor, x, y, w, h;
        Ctl(String[] c) {
            name = c[1]; kind = c[2]; anchor = Float.parseFloat(c[3]); x = Float.parseFloat(c[4]); y = Float.parseFloat(c[5]);
            w = Float.parseFloat(c[6]); h = Float.parseFloat(c[7]); button = c[8]; label = c[9].equals("~") ? "" : c[9];
        }
    }
    /** A sprite of the atlas: its control (index in the layout), the D-pad direction it shows (0 right, 1 left, 2 down, 3 up, -1 none), its state, its place in the
     *  atlas (px) and its offset from the control's centre (design px) */
    static final class Sprite {
        final int ctl, dir, state; final float u, v, w, h, ox, oy;
        Sprite(int ctl, int dir, int state, float u, float v, float w, float h, float ox, float oy) { this.ctl = ctl; this.dir = dir; this.state = state; this.u = u; this.v = v; this.w = w; this.h = h; this.ox = ox; this.oy = oy; }
    }
    static final class Layout {
        final List<Ctl> ctls = new ArrayList<>();
        final List<Sprite> sprites = new ArrayList<>();
        float designH, scale = 1, travel; String atlasName;
    }
    /** A layout's atlas as nx2android's engine showed it (bake): premultiplied RGBA8 rows, w * 4 bytes each, in a direct buffer the host copies (Native.padAtlas) */
    static final class Baked {
        final java.nio.ByteBuffer px; final int w, h;
        Baked(java.nio.ByteBuffer px, int w, int h) { this.px = px; this.w = w; this.h = h; }
        /** The same as a bitmap, for the view's own drawing (when the present does not draw the gamepad) */
        Bitmap bitmap() {
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);   // premultiplied RGBA in memory, as px
            px.rewind(); b.copyPixelsFromBuffer(px); px.rewind();
            return b;
        }
    }

    final Layout[] layouts = {new Layout(), new Layout()};
    float alpha = 1;
    private final Context ctx; private final String dir;

    private ThemePad(Context c, String dir) { ctx = c; this.dir = dir; }

    /** The game's pack, or null when the APK has none for it (Banana Native's own controls are drawn then) */
    static ThemePad load(Context c, String titleId) {
        if (titleId == null) return null;
        String dir = "overlay/" + titleId.toUpperCase(Locale.ROOT) + "/";
        ThemePad t = new ThemePad(c, dir);
        try {
            for (String line : lines(c, dir + "layout.tsv")) {
                String[] f = line.split("\t");
                if (line.startsWith("#") || f.length != 10) continue;
                int l = f[0].equals("phone") ? 1 : f[0].equals("tablet") ? 0 : -1;
                if (l >= 0) t.layouts[l].ctls.add(new Ctl(f));
            }
            for (String line : lines(c, dir + "theme.tsv")) {
                String[] f = line.split("\t");
                if (line.startsWith("#")) continue;
                if (f[0].equals("alpha") && f.length > 1) t.alpha = Float.parseFloat(f[1]);
                else if (f[0].equals("layout") && f.length >= 7) {
                    int l = f[1].equals("phone") ? 1 : f[1].equals("tablet") ? 0 : -1; if (l < 0) continue;
                    Layout L = t.layouts[l]; L.designH = Float.parseFloat(f[3]); L.atlasName = f[4]; L.scale = Float.parseFloat(f[5]); L.travel = Float.parseFloat(f[6]);
                } else if (f[0].equals("sprite") && f.length >= 11) {
                    int l = f[1].equals("phone") ? 1 : f[1].equals("tablet") ? 0 : -1; if (l < 0) continue;
                    Layout L = t.layouts[l]; int ci = -1;
                    for (int i = 0; i < L.ctls.size(); i++) if (L.ctls.get(i).name.equals(f[2])) { ci = i; break; }
                    int st = f[4].equals("idle") ? IDLE : f[4].equals("held") ? HELD : f[4].equals("knob") ? KNOB : f[4].equals("decor") ? DECOR : -1;
                    int d = f[3].equals("right") ? 0 : f[3].equals("left") ? 1 : f[3].equals("down") ? 2 : f[3].equals("up") ? 3 : -1;
                    if (ci < 0 || st < 0) continue;
                    L.sprites.add(new Sprite(ci, d, st, Float.parseFloat(f[5]), Float.parseFloat(f[6]), Float.parseFloat(f[7]), Float.parseFloat(f[8]), Float.parseFloat(f[9]), Float.parseFloat(f[10])));
                }
            }
        } catch (IOException | RuntimeException e) {
            return null;   // no pack for this game (or a broken one): Banana Native's controls
        }
        for (Layout L : t.layouts) if (L.ctls.isEmpty() || L.sprites.isEmpty() || L.designH <= 0 || L.atlasName == null) return null;
        Native.note("on-screen controls: nx2android's gamepad theme (" + t.layouts[0].sprites.size() + " tablet sprites, " + t.layouts[1].sprites.size() + " phone)");
        return t;
    }

    /** 0 tablet, 1 phone, by the window's shape */
    static int layoutFor(int w, int h) { return h > 0 && (float) w / h >= PHONE_ASPECT ? 1 : 0; }

    /**
     * The layout's atlas baked as nx2android's own engine showed it, so the gamepad keeps the player's pastel colours. That engine drew the theme's 8-bit sprites (UNORM)
     * into the game's sRGB swapchain (Pokopia presents sRGB: its log's "swapchain ... format 43"), which encodes what is written to it: each colour came out through the sRGB
     * curve, lighter than the PNG's, blended with the picture at the player's opacity in linear light. Banana Native's present (and an Android view) write bytes as they are,
     * so the curve is baked in here: colour c becomes srgb(c), and the opacity op times the texel's alpha a becomes srgb(op * a), which keeps the sprites as light over a dark
     * picture as they were (over a light one within a few percent). The menu button at max(op, 0.5), as there (findable at any opacity). Null when it cannot be read.
     * Slow (about a quarter of a second): off the main thread.
     */
    Baked bake(int l, float op, float menuOp, int menuCtl) {
        Layout L = layouts[l];
        Bitmap a;
        try (InputStream in = ctx.getAssets().open(dir + L.atlasName)) {
            BitmapFactory.Options o = new BitmapFactory.Options(); o.inScaled = false; o.inPreferredConfig = Bitmap.Config.ARGB_8888; o.inPremultiplied = false;   // the PNG's own colours
            a = BitmapFactory.decodeStream(in, null, o);
        } catch (IOException e) {
            Native.note("[RED] on-screen controls: the gamepad theme's " + L.atlasName + " cannot be read: " + e.getMessage());
            return null;
        }
        if (a == null) { Native.note("[RED] on-screen controls: the gamepad theme's " + L.atlasName + " cannot be decoded"); return null; }
        int w = a.getWidth(), h = a.getHeight();
        java.nio.ByteBuffer px = java.nio.ByteBuffer.allocateDirect(w * h * 4);
        int[] enc = new int[256];
        for (int i = 0; i < 256; i++) enc[i] = Math.round(255 * srgb(i / 255f));
        int[] pad = alphas(op * alpha), menu = alphas(menuOp * alpha);
        int rows = Math.max(1, 65536 / w); int[] row = new int[w * rows]; byte[] out = new byte[w * rows * 4];
        for (int y = 0; y < h; y += rows) {   // a band of rows at a time (no second copy of the whole atlas)
            int n = Math.min(rows, h - y);
            a.getPixels(row, 0, w, 0, y, w, n);
            px.put(out, 0, put(row, w * n, out, enc, pad));
        }
        for (Sprite sp : L.sprites) {   // the menu button's sprites again, at its own opacity
            if (sp.ctl != menuCtl) continue;
            int x0 = Math.max(0, (int) Math.floor(sp.u)), y0 = Math.max(0, (int) Math.floor(sp.v)), x1 = Math.min(w, (int) Math.ceil(sp.u + sp.w)), y1 = Math.min(h, (int) Math.ceil(sp.v + sp.h));
            if (x1 <= x0 || y1 <= y0) continue;
            int[] r = new int[x1 - x0]; byte[] b = new byte[r.length * 4];
            for (int y = y0; y < y1; y++) {
                a.getPixels(r, 0, r.length, x0, y, r.length, 1);
                px.position((y * w + x0) * 4); px.put(b, 0, put(r, r.length, b, enc, menu));
            }
        }
        a.recycle();
        px.rewind();
        return new Baked(px, w, h);
    }
    /** sRGB's encoding curve (IEC 61966-2-1), 0..1 */
    static float srgb(float x) { return x <= 0.0031308f ? 12.92f * x : (float) (1.055 * Math.pow(x, 1 / 2.4) - 0.055); }
    private static int[] alphas(float op) {   // a texel's alpha 0..255 → the baked alpha, srgb(op * a)
        int[] t = new int[256]; op = Math.max(0, Math.min(1, op));
        for (int i = 0; i < 256; i++) t[i] = Math.round(255 * srgb(op * i / 255f));
        return t;
    }
    private static int put(int[] argb, int n, byte[] out, int[] enc, int[] alpha) {   // n straight ARGB texels → premultiplied RGBA bytes; returns the byte count
        for (int i = 0, o = 0; i < n; i++, o += 4) {
            int c = argb[i], A = alpha[c >>> 24];
            out[o] = (byte) ((enc[(c >> 16) & 255] * A + 127) / 255); out[o + 1] = (byte) ((enc[(c >> 8) & 255] * A + 127) / 255);
            out[o + 2] = (byte) ((enc[c & 255] * A + 127) / 255); out[o + 3] = (byte) A;
        }
        return n * 4;
    }

    /** A DM_BTN_* name's bit (dm/host/dm_input.h: nn::hid::NpadButton's order) */
    static long bit(String name) {
        switch (name) {
            case "DM_BTN_A": return 1;
            case "DM_BTN_B": return 1 << 1;
            case "DM_BTN_X": return 1 << 2;
            case "DM_BTN_Y": return 1 << 3;
            case "DM_BTN_LS": return 1 << 4;
            case "DM_BTN_RS": return 1 << 5;
            case "DM_BTN_L": return 1 << 6;
            case "DM_BTN_R": return 1 << 7;
            case "DM_BTN_ZL": return 1 << 8;
            case "DM_BTN_ZR": return 1 << 9;
            case "DM_BTN_PLUS": return 1 << 10;
            case "DM_BTN_MINUS": return 1 << 11;
            default: return 0;
        }
    }

    private static List<String> lines(Context c, String name) throws IOException {
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open(name), StandardCharsets.UTF_8))) {
            for (String s; (s = r.readLine()) != null; ) if (!s.isEmpty()) out.add(s);
        }
        return out;
    }
}
