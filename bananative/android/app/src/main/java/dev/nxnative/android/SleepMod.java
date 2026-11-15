package dev.nxnative.android;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * nx2android: Pokémon Pokopia's sleep mod, the launcher's side; the game's side is dm/host/android/pkp_sleep.c, which counts the player getting into a bed, makes the
 * game's own save and gets the player out of bed. Here: when the player gets into a bed the menu asks how long to sleep (6, 12, 18 or 24 hours, or Not now), drawn
 * over the game like its own talk choices, read from the touch gamepad, a controller (the game sees nothing of them while the menu is up: Native.pkpHold) or a tap.
 * A choice of hours: the picture fades to black, the game saves, the game's clock moves on by that much (Native.clockSkip: only the game's clock, the tablet's is
 * never set), the time counts up in the middle of the screen one hour a second with a loading icon turning in the bottom right corner, and the picture fades back in.
 * The total skip is kept (pkp_clock_skip) and given to every start (DM_TIME_SKIP), so the game stays ahead of real time until Back to real time (the in-game panel).
 * Which of the game's actions is a bed is learned: the player gets into one and taps "This is the bed" in the in-game panel (pkp_beds). Not in Link Play.
 * The Pokédex: while the game draws its avatar shaders (the profile's shader_seen.tsv, GitHub issue #35; Native.watchFs) the game's clock shows in the picture's top left corner.
 */
final class SleepMod {
    static final String TITLE = "0400A3A01EE4C000";
    static final int[] HOURS = {6, 12, 18, 24};
    static final String[] CHOICES = {"6 hours", "12 hours", "18 hours", "24 hours", "Not now"};
    private static final int IDLE = 0, ASK = 1, FADE_OUT = 2, SAVE = 3, TICK = 4, FADE_IN = 5;
    private static final long FADE_MS = 650, HOLD_MS = 900, SAVE_MAX_MS = 20000;
    // NpadButton bits as the host reports what was pressed (Native.pkpSeen): A, B, Up, Down, the left stick up and down
    private static final long A = 1, B = 1 << 1, UP = 1 << 13, DOWN = 1 << 15, L_UP = 1 << 17, L_DOWN = 1 << 19;
    // The Pokédex's avatar pixel shaders (dm/games/0400A3A01EE4C000/shader_seen.tsv): drawn within this long, the Pokédex is up
    private static final long[] DEX_FS = {0xa09599043ae63349L, 0xa3305c931b85d38aL};
    private static final long DEX_MS = 400;

    static boolean game(String titleId) { return TITLE.equalsIgnoreCase(titleId); }

    // ---- Kept for the game ----
    static int[] beds(Context c) {
        List<Integer> l = new ArrayList<>();
        for (String s : Prefs.of(c).getString("pkp_beds", "").split(",")) try { if (!s.isEmpty()) l.add(Integer.parseInt(s.trim())); } catch (NumberFormatException ignored) {}
        int[] a = new int[l.size()]; for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }
    static void beds(Context c, int[] ids) {
        StringBuilder b = new StringBuilder();
        for (int id : ids) { if (b.length() > 0) b.append(','); b.append(id); }
        Prefs.of(c).edit().putString("pkp_beds", b.toString()).commit();
    }
    /** How far the game's clock runs ahead of real time, in seconds */
    static long skip(Context c) { return Prefs.of(c).getLong("pkp_clock_skip", 0); }
    static void skip(Context c, long s) { Prefs.of(c).edit().putLong("pkp_clock_skip", s).commit(); }
    /** The host's DM_TIME_SKIP for a start of Pokopia */
    static void env(Context c, String titleId, List<String> env) { if (game(titleId) && skip(c) != 0) env.add("DM_TIME_SKIP=" + skip(c)); }
    /** "1 day 6 hours" */
    static String ahead(long s) {
        long h = Math.round(s / 3600.0), d = h / 24; h %= 24;
        String t = d > 0 ? d + (d == 1 ? " day" : " days") : "";
        if (h > 0 || d == 0) t += (t.isEmpty() ? "" : " ") + h + (h == 1 ? " hour" : " hours");
        return t;
    }

    private final Activity act;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final long[] st = new long[6];
    private final Panel view;
    private final java.util.function.Supplier<float[]> picture;   // the game's picture in the view, normalized (x, y, w, h: TouchControls.picture)
    private Typeface face, faceBold;
    private boolean present, stopped, dex;
    private long bedsSeen = -1, phaseAt, tickFrom, seenPrev = -1;
    private int phase = IDLE, sel, hours, seenFrames, pressedRow = -1, saveState;

    SleepMod(Activity a, java.util.function.Supplier<float[]> picture) {
        act = a; this.picture = picture;
        view = new Panel(a);
        view.setVisibility(View.GONE);
        try { face = Typeface.createFromAsset(a.getAssets(), "fonts/MPLUSRounded1c-Bold-subset.ttf"); faceBold = Typeface.createFromAsset(a.getAssets(), "fonts/MPLUSRounded1c-ExtraBold-subset.ttf"); }
        catch (RuntimeException e) { face = Typeface.create("sans-serif-medium", Typeface.NORMAL); faceBold = Typeface.DEFAULT_BOLD; }
        Native.pkpHold(0);   // a screen created again while the game runs: no hold left from the last one
        Native.pkpBeds(beds(a));
        Native.watchFs(DEX_FS);
        ui.post(poll);
    }

    View view() { return view; }

    /** The screen goes: the player's input back to the game */
    void stop() { stopped = true; ui.removeCallbacksAndMessages(null); Choreographer.getInstance().removeFrameCallback(frame); if (phase != IDLE) Native.pkpHold(0); }

    /** The game's state now (Native.pkpState), null when the mod's gate is not in the game's code */
    long[] state() { long[] o = new long[6]; return Native.pkpState(o) ? o : null; }

    /** "This is the bed": the action the player is in now joins the beds; the sentence for the panel */
    String learnBed() {
        long[] s = state();
        if (s == null) return "The sleep mod is not in this game's code yet: prepare the game again (its card › Prepare), then start it.";
        int cur = (int) s[0];
        if (cur <= 0) return "No bed now: get into a bed in the game first, then tap this again.";
        int[] b = beds(act);
        for (int id : b) if (id == cur) return "This bed is already known.";
        int[] n = new int[Math.min(b.length + 1, 8)];
        n[0] = cur; System.arraycopy(b, 0, n, 1, n.length - 1);
        beds(act, n); Native.pkpBeds(n);
        bedsSeen = s[2];
        Native.note("sleep mod: action " + cur + " is a bed");
        return "Got it: the game asks how long to sleep the next time you get into this bed.";
    }
    void forgetBeds() { beds(act, new int[0]); Native.pkpBeds(new int[0]); }

    /** Back to real time: the game's clock follows the tablet's again */
    void realTime() { skip(act, 0); Native.clockSkip(0); Native.note("sleep mod: the game's clock is back on real time"); }

    // ---- The player gets into a bed ----
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (stopped) return;
            present = Native.pkpState(st);
            if (present) {
                if (bedsSeen < 0) bedsSeen = st[2];   // the count when this screen started
                else if (st[2] > bedsSeen) { bedsSeen = st[2]; if (phase == IDLE && st[4] == 0) ask(); }
            }
            long age = Native.watchFsAge();
            boolean d = age >= 0 && age <= DEX_MS;
            if (phase == IDLE && d != dex) view.setVisibility(d ? View.VISIBLE : View.GONE);
            dex = d;
            if (phase == IDLE && dex) view.invalidate();   // the minutes move on
            ui.postDelayed(this, 150);
        }
    };

    /** The menu: how long to sleep */
    void ask() {
        if (phase != IDLE) return;
        phase = ASK; sel = 0; seenPrev = -1; seenFrames = 0; pressedRow = -1; phaseAt = SystemClock.uptimeMillis();
        Native.pkpHold(1);
        view.setVisibility(View.VISIBLE);
        Choreographer.getInstance().postFrameCallback(frame);
    }

    private void choose(int i) {
        if (phase != ASK) return;
        if (i >= HOURS.length) {   // Not now: the player gets out of bed, then the input is theirs again (pkp_sleep.c getup_step)
            phase = IDLE; view.setVisibility(dex ? View.VISIBLE : View.GONE);
            Native.pkpHold(2);
            return;
        }
        hours = HOURS[i]; phase = FADE_OUT; phaseAt = SystemClock.uptimeMillis();
        Native.note("sleep mod: sleep " + hours + " hours");
    }

    private void startTick() {
        long now = Native.clockNow();
        if (now <= 0) now = System.currentTimeMillis() / 1000 + skip(act);
        long total = skip(act) + hours * 3600L;
        skip(act, total); Native.clockSkip(total);
        tickFrom = now; phase = TICK; phaseAt = SystemClock.uptimeMillis();
        Native.note("sleep mod: " + (saveState == 3 ? "saved" : "not saved (" + saveState + ")") + ", the game's clock +" + hours + " h (" + ahead(total) + " ahead of real time)");
    }

    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override public void doFrame(long t) {
            if (stopped || phase == IDLE) return;
            long el = SystemClock.uptimeMillis() - phaseAt;
            switch (phase) {
                case ASK: {
                    long s = Native.pkpSeen();
                    if (seenFrames++ < 3 || seenPrev < 0) seenPrev = s;   // what was held when the menu came up (the A that got into bed) is no press
                    else {
                        long down = s & ~seenPrev; seenPrev = s;
                        if ((down & (UP | L_UP)) != 0) sel = (sel + CHOICES.length - 1) % CHOICES.length;
                        if ((down & (DOWN | L_DOWN)) != 0) sel = (sel + 1) % CHOICES.length;
                        if ((down & A) != 0) choose(sel);
                        else if ((down & B) != 0) choose(CHOICES.length - 1);
                    }
                    break;
                }
                case FADE_OUT: if (el >= FADE_MS) { phase = SAVE; phaseAt = SystemClock.uptimeMillis(); saveState = 1; Native.pkpSave(); } break;
                case SAVE: {
                    long[] s = state(); saveState = s == null ? 4 : (int) s[3];
                    if (saveState == 3 || saveState == 4 || el > SAVE_MAX_MS) startTick();
                    break;
                }
                case TICK: if (el >= hours * 1000L + HOLD_MS) { phase = FADE_IN; phaseAt = SystemClock.uptimeMillis(); } break;
                case FADE_IN: if (el >= FADE_MS) { phase = IDLE; Native.pkpHold(0); view.setVisibility(dex ? View.VISIBLE : View.GONE); return; } break;
                default: break;
            }
            view.invalidate();
            if (phase != IDLE) Choreographer.getInstance().postFrameCallback(this);
        }
    };

    // ---- Drawing ----
    @SuppressLint("ViewConstructor")
    private final class Panel extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF(), msg = new RectF(), r = new RectF();
        private final Path tri = new Path();
        private final float dp;
        private float rowH, rowTop;

        Panel(Context c) {
            super(c);
            dp = c.getResources().getDisplayMetrics().density;
        }

        /** The game's picture in the view, in pixels */
        private RectF pic(int w, int h) {
            float[] p = picture != null ? picture.get() : null;
            if (p == null || p[2] <= 0 || p[3] <= 0) p = new float[] {0, 0, 1, 1};
            r.set(p[0] * w, p[1] * h, (p[0] + p[2]) * w, (p[1] + p[3]) * h);
            return new RectF(r);
        }

        private void layoutMenu(int w, int h) {
            RectF g = pic(w, h);
            rowH = 50 * dp;
            float bw = Math.max(210 * dp, Math.min(260 * dp, g.width() * 0.22f)), bh = rowH * CHOICES.length + 24 * dp;
            float mw = Math.min(g.width() * 0.62f, 760 * dp), mh = 92 * dp, bottom = g.bottom - 26 * dp;
            msg.set(g.centerX() - mw / 2, bottom - mh, g.centerX() + mw / 2, bottom);
            box.set(msg.right - bw, msg.top - 18 * dp - bh, msg.right, msg.top - 18 * dp);
            rowTop = box.top + 12 * dp;
        }

        private int rowAt(float x, float y) {
            if (!box.contains(x, y)) return -1;
            int i = (int) ((y - rowTop) / rowH);
            return i >= 0 && i < CHOICES.length ? i : -1;
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (phase == IDLE) return false;
            if (phase != ASK) return true;   // the screen is black: no touch reaches the game or the controls
            layoutMenu(getWidth(), getHeight());
            float x = e.getX(e.getActionIndex()), y = e.getY(e.getActionIndex());
            int row = rowAt(x, y);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: case MotionEvent.ACTION_POINTER_DOWN:
                    if (!box.contains(x, y) && !msg.contains(x, y)) return false;   // the gamepad's own touches go on to it
                    pressedRow = row; if (row >= 0) sel = row; invalidate(); return true;
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_POINTER_UP:
                    if (row >= 0 && row == pressedRow) choose(row);
                    pressedRow = -1; return true;
                case MotionEvent.ACTION_CANCEL: pressedRow = -1; return true;
                default: return true;
            }
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            long el = SystemClock.uptimeMillis() - phaseAt;
            if (phase == IDLE) { if (dex) drawDexClock(c, w, h); return; }
            if (phase == ASK) { drawMenu(c, w, h, Math.min(1f, el / 160f)); return; }
            float black = phase == FADE_OUT ? Math.min(1f, el / (float) FADE_MS) : phase == FADE_IN ? 1f - Math.min(1f, el / (float) FADE_MS) : 1f;
            c.drawColor(Color.argb(Math.round(255 * black), 0, 0, 0));
            if (phase == SAVE || phase == TICK || phase == FADE_IN) {
                long shown = phase == SAVE ? Native.clockNow() : tickFrom + Math.min(hours * 3600L, Math.round((phase == TICK ? el : hours * 1000L) * 3.6));
                drawClock(c, w, h, shown, phase == FADE_IN ? black : 1f);
                if (phase != FADE_IN) drawSpinner(c, w, h, black);
            }
        }

        private void drawMenu(Canvas c, int w, int h, float in) {
            layoutMenu(w, h);
            int a = Math.round(255 * in);
            // the question, in a speech box along the bottom
            fill.setColor(Color.argb(a, 255, 252, 244));
            fill.setShadowLayer(10 * dp, 0, 3 * dp, Color.argb(Math.round(70 * in), 60, 40, 20));
            c.drawRoundRect(msg, 26 * dp, 26 * dp, fill);
            c.drawRoundRect(box, 22 * dp, 22 * dp, fill);
            fill.clearShadowLayer();
            text.setTypeface(face); text.setTextSize(22 * dp); text.setColor(Color.argb(a, 74, 64, 54)); text.setTextAlign(Paint.Align.LEFT);
            c.drawText("How long would you like to sleep?", msg.left + 30 * dp, msg.centerY() + 8 * dp, text);
            // the choices, with the game's kind of cursor beside the selected one
            for (int i = 0; i < CHOICES.length; i++) {
                float top = rowTop + i * rowH;
                if (i == sel) {
                    r.set(box.left + 10 * dp, top + 4 * dp, box.right - 10 * dp, top + rowH - 4 * dp);
                    fill.setColor(Color.argb(a, 255, 233, 168));
                    c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill);
                    float cx = box.left + 26 * dp, cy = top + rowH / 2, s = 7 * dp;
                    tri.reset(); tri.moveTo(cx - s * 0.7f, cy - s); tri.lineTo(cx + s, cy); tri.lineTo(cx - s * 0.7f, cy + s); tri.close();
                    fill.setColor(Color.argb(a, 242, 165, 65));
                    c.drawPath(tri, fill);
                }
                text.setTypeface(i == sel ? faceBold : face); text.setTextSize(20 * dp);
                text.setColor(i == sel ? Color.argb(a, 60, 48, 38) : Color.argb(a, 110, 98, 86));
                c.drawText(CHOICES[i], box.left + 44 * dp, top + rowH / 2 + 7 * dp, text);
            }
        }

        private void drawClock(Canvas c, int w, int h, long t, float in) {
            ZonedDateTime z = Instant.ofEpochSecond(t).atZone(ZoneId.systemDefault());
            int hr = z.getHour() % 12 == 0 ? 12 : z.getHour() % 12;
            String time = hr + ":" + String.format(Locale.US, "%02d", z.getMinute()), ampm = z.getHour() < 12 ? "AM" : "PM";
            int a = Math.round(255 * in);
            text.setTextAlign(Paint.Align.LEFT);
            text.setTypeface(faceBold); text.setTextSize(76 * dp);
            float tw = text.measureText(time);
            Paint small = new Paint(text); small.setTextSize(30 * dp);
            float aw = small.measureText(ampm), gap = 12 * dp, x = (w - tw - gap - aw) / 2, y = h / 2f + 22 * dp;
            text.setColor(Color.argb(a, 255, 255, 255)); c.drawText(time, x, y, text);
            small.setColor(Color.argb(Math.round(a * 0.85f), 255, 233, 168)); c.drawText(ampm, x + tw + gap, y, small);
            small.setTypeface(face); small.setTextSize(22 * dp); small.setTextAlign(Paint.Align.CENTER); small.setColor(Color.argb(Math.round(a * 0.7f), 255, 255, 255));
            c.drawText(z.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH), w / 2f, y + 46 * dp, small);
            if (phase == SAVE) c.drawText("Saving…", w / 2f, y + 86 * dp, small);
            else if (saveState != 3) c.drawText("The game was busy and did not save", w / 2f, y + 86 * dp, small);
        }

        /** The game's clock in the picture's top left corner while the Pokédex is up */
        private void drawDexClock(Canvas c, int w, int h) {
            RectF g = pic(w, h);
            ZonedDateTime z = Instant.ofEpochSecond(Native.clockNow() > 0 ? Native.clockNow() : System.currentTimeMillis() / 1000 + skip(act)).atZone(ZoneId.systemDefault());
            int hr = z.getHour() % 12 == 0 ? 12 : z.getHour() % 12;
            String t = z.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + "  " + hr + ":" + String.format(Locale.US, "%02d", z.getMinute()) + (z.getHour() < 12 ? " AM" : " PM");
            float size = Math.max(16 * dp, g.height() * 0.034f), x = g.left + g.width() * 0.028f, y = g.top + g.height() * 0.035f + size;
            text.setTypeface(faceBold); text.setTextSize(size); text.setTextAlign(Paint.Align.LEFT);
            text.setStyle(Paint.Style.STROKE); text.setStrokeWidth(size * 0.16f); text.setStrokeJoin(Paint.Join.ROUND); text.setColor(Color.rgb(74, 64, 54));
            c.drawText(t, x, y, text);
            text.setStyle(Paint.Style.FILL); text.setColor(Color.WHITE);
            c.drawText(t, x, y, text);
        }

        private void drawSpinner(Canvas c, int w, int h, float in) {
            float cx = w - 56 * dp, cy = h - 56 * dp, rad = 20 * dp;
            double turn = (SystemClock.uptimeMillis() % 1200) / 1200.0 * 2 * Math.PI;
            for (int i = 0; i < 10; i++) {
                double ang = turn + i * 2 * Math.PI / 10;
                float k = (i + 1) / 10f;
                dot.setColor(Color.argb(Math.round(255 * in * (0.15f + 0.85f * k)), 255, 233, 168));
                c.drawCircle(cx + (float) Math.cos(ang) * rad, cy + (float) Math.sin(ang) * rad, (2.2f + 2.6f * k) * dp, dot);
            }
        }
    }
}
