package dev.nxnative.android;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/**
 * nx2android: Pokémon Pokopia's sleep mod, the launcher's side; the game's side is dm/host/android/pkp_sleep.c, which counts the player getting into a bed, makes the
 * game's own save and gets the player out of bed. Here: when the player gets into a bed the menu asks how long to sleep (6, 12, 18 or 24 hours, until morning: the
 * next 5 AM, when the game's day starts, or Not now), drawn
 * over the game as its own talk does (its talk box along the bottom, the question typing out, then its choice window above the box's right end with the yellow
 * selection and the pointer: measured on the user's recording of a talk, 2026-11-16) and in its own font, read
 * from the player's game files (GameFont; M PLUS Rounded stands in until it is read, or when it is not there), read from the touch gamepad, a controller (the game sees nothing of them while the menu is up: Native.pkpHold) or a tap.
 * A choice of hours: the picture fades to black, the game saves, the game's clock moves on by that much (Native.clockSkip: only the game's clock, the tablet's is
 * never set) and the game is told it is back from the HOME menu (Native.pkpFocus), which is when Pokopia takes a new clock; the time counts up in the middle of the
 * screen one hour a second, the day and date under it, with a loading icon turning in the bottom right corner. As the count starts the player gets out of bed
 * (Native.pkpHold(3)), and only then does the clock move, so the game runs its own new day (its fade and load at 5 AM, which waits for the player to be up)
 * under the black; after the count the picture fades back in once the game has been quiet for a moment. The on-screen gamepad is hidden and no input reaches the game from the fade out to the end of the fade in.
 * The total skip is kept (pkp_clock_skip) and given to every start (DM_TIME_SKIP), so the game stays ahead of real time until Back to real time (the in-game panel).
 * Save and Quit (the game back at its title after a game: pkp_sleep.c guide_gate) moves the game's clock on 6 hours, so the next time in is later in the day.
 * The bed is learned once, in three taps in the in-game panel (standing next to it, lying in it, standing again): the host keeps the values of the player's state that
 * change in the bed and come back out of it (pkp_sleep.c "The bed, learned"), kept here as pkp_bed_signs. Not in Link Play.
 * The Pokédex: while the game draws its avatar shaders (the profile's shader_seen.tsv, GitHub issue #35; Native.watchFs) the game's clock shows in the picture's top left corner.
 */
final class SleepMod {
    static final String TITLE = "0400A3A01EE4C000";
    static final int[] HOURS = {6, 12, 18, 24};
    static final String[] CHOICES = {"6 hours", "12 hours", "18 hours", "24 hours", "Until morning", "Not now"};
    static final String QUESTION = "How long would you like to sleep?";
    // The question types out as the game's talk does (CHAR_MS a letter; A or B shows it whole), then the choices pop up (POP_MS) and take input
    private static final long CHAR_MS = 25, POP_MS = 140;
    static final int MORNING = HOURS.length, NOT_NOW = HOURS.length + 1;   // the choices after the hours
    static final int MORNING_HOUR = 5;   // Pokopia's day starts at 5 AM: Until morning wakes the player just after it
    private static final int IDLE = 0, ASK = 1, FADE_OUT = 2, SAVE = 3, TICK = 4, SETTLE = 5, FADE_IN = 6;
    private static final long FADE_MS = 650, HOLD_MS = 400, SAVE_MAX_MS = 20000, FOCUS_MS = 400;
    // The loading screen: the player is got out of bed as the count starts, before the clock moves (builds 24-25 got up 1.5 s after the clock moved and the focus came
    // back, and the game took none of it: the user's recording, 2026-11-16); the clock moves when that is over (GETUP_MAX_MS at most), and the game's own new day
    // (its fade and load at 5 AM, which waits for the player to be up: the user's recording, build 21) then runs under the black. The picture comes back once the
    // game has been quiet (no event, no autosave, no new load, a player) for QUIET_MS, starting AFTER_MOVE_MS after the clock moved (the game takes the new time and
    // starts its new day), SETTLE_MAX_MS after the move at most
    private static final long GETUP_MAX_MS = 9000, AFTER_MOVE_MS = 2500, QUIET_MS = 1200, SETTLE_MAX_MS = 15000;
    private static final long TOD_NEAR = 15 * 60;   // the game's time of day this close to the new clock (seconds) is the new time
    // NpadButton bits as the host reports what was pressed (Native.pkpSeen): A, B, Up, Down, the left stick up and down
    private static final long A = 1, B = 1 << 1, UP = 1 << 13, DOWN = 1 << 15, L_UP = 1 << 17, L_DOWN = 1 << 19;
    // The Pokédex's avatar pixel shaders (dm/games/0400A3A01EE4C000/shader_seen.tsv): drawn within this long, the Pokédex is up
    private static final long[] DEX_FS = {0xa09599043ae63349L, 0xa3305c931b85d38aL};
    private static final long DEX_MS = 400;

    static boolean game(String titleId) { return TITLE.equalsIgnoreCase(titleId); }

    // ---- Kept for the game ----
    /** The bed's signs (pkp_sleep.c), as the host wrote them; "" before the bed is learned */
    static String signs(Context c) { return Prefs.of(c).getString("pkp_bed_signs", ""); }
    static int signCount(Context c) { String v = signs(c); return v.isEmpty() ? 0 : v.split(";").length; }
    /** How far the game's clock runs ahead of real time, in seconds */
    static long skip(Context c) { return Prefs.of(c).getLong("pkp_clock_skip", 0); }
    static void skip(Context c, long s) { Prefs.of(c).edit().putLong("pkp_clock_skip", s).commit(); }
    /** Each Save and Quit moves the game's clock on QUIT_HOURS for the next time in (on unless the player turns it off in the panel) */
    static final int QUIT_HOURS = 6;
    static boolean quitSkip(Context c) { return Prefs.of(c).getBoolean("pkp_quit_skip", true); }
    static void quitSkip(Context c, boolean on) { Prefs.of(c).edit().putBoolean("pkp_quit_skip", on).apply(); }

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
    private final long[] st = new long[10];
    private final Panel view;
    private final java.util.function.Supplier<float[]> picture;   // the game's picture in the view, normalized (x, y, w, h: TouchControls.picture)
    private Typeface face, faceBold;   // the stand-in font
    private volatile GameFont font, talkFont;   // the game's own (GameFont), once read: its HUD face (the clocks), its talk face (the menu)
    private boolean fontAsked;
    private boolean present, stopped, dex;
    private long bedsSeen = -1, quitsSeen = -1, phaseAt, tickFrom, seenPrev = -1, todTarget = -1, date0, getupAt, movedAt, quietAt, loadsSeen = -1;
    private final java.util.function.Consumer<Boolean> controls;   // hides and turns off the on-screen gamepad while the loading screen shows (TouchControls.suppress)
    private long sleepSec;   // how far this sleep moves the game's clock
    private boolean untilMorning;
    private int phase = IDLE, sel, seenFrames, pressedRow = -1, saveState, teach;

    SleepMod(Activity a, java.util.function.Supplier<float[]> picture, java.util.function.Consumer<Boolean> controls) {
        act = a; this.picture = picture; this.controls = controls;
        view = new Panel(a);
        view.setVisibility(View.GONE);
        try { face = Typeface.createFromAsset(a.getAssets(), "fonts/MPLUSRounded1c-Bold-subset.ttf"); faceBold = Typeface.createFromAsset(a.getAssets(), "fonts/MPLUSRounded1c-ExtraBold-subset.ttf"); }
        catch (RuntimeException e) { face = Typeface.create("sans-serif-medium", Typeface.NORMAL); faceBold = Typeface.DEFAULT_BOLD; }
        Native.pkpHold(0);   // a screen created again while the game runs: no hold left from the last one
        if (Prefs.of(a).contains("pkp_beds")) Prefs.of(a).edit().remove("pkp_beds").commit();   // build 16 learned a move, not the bed: dropped
        Native.pkpSignsSet(signs(a));
        Native.watchFs(DEX_FS);
        ui.post(poll);
    }

    View view() { return view; }

    /** The screen goes: the player's input back to the game */
    void stop() { stopped = true; ui.removeCallbacksAndMessages(null); Choreographer.getInstance().removeFrameCallback(frame); if (phase != IDLE) { Native.pkpHold(0); controls.accept(false); } }

    /** The game's state now (Native.pkpState), null when the mod's gate is not in the game's code */
    long[] state() { long[] o = new long[10]; return Native.pkpState(o) ? o : null; }

    /** Teaching the bed: 0 not started (or done), 1 standing sampled (next: in the bed), 2 in the bed sampled (next: standing again) */
    int teachStep() { return teach; }

    /** One tap of teaching the bed (step 1 standing next to it, 2 lying in it, 3 standing again): the host samples about half a second of game frames, then said
     *  gets the sentence for the panel; after step 3 the signs are kept */
    void teach(int step, java.util.function.Consumer<String> said) {
        if (Native.pkpLearn(step) == -2) { said.accept("This engine has no bed learning: update it."); return; }
        long t0 = SystemClock.uptimeMillis();
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (stopped) return;
                int st = Native.pkpLearn(0);
                if ((st & 1 << (step - 1)) == 0 || (st & 0x100) != 0) {
                    if (SystemClock.uptimeMillis() - t0 < 6000) { ui.postDelayed(this, 100); return; }
                    said.accept("The game did not move on while the panel was open (paused?). Continue the game, then tap again.");
                    return;
                }
                if (step == 1) { teach = 1; said.accept("Got it. Now close this panel, get into the bed and lie down, then open the panel again and tap 2."); return; }
                if (step == 2) { teach = 2; said.accept("Got it. Now close this panel, get out of the bed and stand, then open the panel again and tap 3."); return; }
                teach = 0;
                int n = Native.pkpLearn(4);
                if (n <= 0) { said.accept("Nothing changed between standing and lying in the bed. Try again from step 1, keeping still for each tap."); return; }
                String v = Native.pkpSigns();
                Prefs.of(act).edit().putString("pkp_bed_signs", v).commit();
                Native.note("sleep mod: the bed learned, " + n + " signs");
                long[] s = state(); if (s != null) bedsSeen = s[2];
                said.accept("Learned (" + n + " signs). From now on the game asks how long to sleep each time you lie down in a bed.");
            }
        }, 150);
    }

    /** One line on what the mod sees, for a screenshot: frames counted, the action now, the last four actions started */
    static String seen(long[] s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder("The mod sees: " + s[5] + " game frames, " + ((s[7] & 1) != 0 ? "in bed" : "not in bed") + " (" + (s[7] >> 8 & 0xff) + " signs), action now " + s[0] + ", recent actions");
        int n = 0;
        for (int i = 0; i < 4; i++) { long a = s[6] >>> (16 * i) & 0xffff; if (a == 0) break; b.append(n++ == 0 ? " " : ", ").append(a); }
        if (n == 0) b.append(" none");
        return b.append('.').toString();
    }

    void forgetBed() { Prefs.of(act).edit().remove("pkp_bed_signs").commit(); Native.pkpSignsSet(""); teach = 0; }

    /** The game's own font, read once the game's RomFS folder is known (GameFont, off the UI thread); the stand-in until then, or when it is not there */
    private void loadFont() {
        if (fontAsked || !(act instanceof GameActivity)) return;
        GameActivity ga = (GameActivity) act;
        String rp = ga.romfsPath();
        if (rp == null) return;
        fontAsked = true;
        if (rp.contains(".nup:")) { Native.note("sleep mod: the game is a .nup package: the stand-in font"); return; }
        File g = ga.gameDir();
        Thread t = new Thread(() -> {
            GameFont[] f = GameFont.load(new File(rp), g != null ? new File(g, "base/romfs") : null);
            if (f == null) return;
            talkFont = f[1] != null ? f[1] : f[0]; font = f[0];
            Native.note("sleep mod: the game's own font is read");
            view.postInvalidate();
        }, "pkp-font");
        t.setDaemon(true);
        t.start();
    }

    /** Back to real time: the game's clock follows the tablet's again */
    void realTime() { skip(act, 0); Native.clockSkip(0); Native.note("sleep mod: the game's clock is back on real time"); }

    // ---- The player gets into a bed ----
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (stopped) return;
            loadFont();
            present = Native.pkpState(st);
            if (present) {
                if (bedsSeen < 0) bedsSeen = st[2];   // the count when this screen started
                else if (st[2] > bedsSeen) { bedsSeen = st[2]; if (phase == IDLE && st[4] == 0) ask(); }
                if (quitsSeen < 0) quitsSeen = st[8];
                else if (st[8] > quitsSeen) {   // Save and Quit: the next time in is 6 hours later (pkp_quit_skip)
                    long n = st[8] - quitsSeen; quitsSeen = st[8];
                    if (quitSkip(act)) {
                        long total = skip(act) + n * QUIT_HOURS * 3600L;
                        skip(act, total); Native.clockSkip(total);
                        Native.note("sleep mod: Save and Quit: the game's clock +" + n * QUIT_HOURS + " h for the next time in (" + ahead(total) + " ahead of real time)");
                    }
                }
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
        if (i >= NOT_NOW) {   // Not now: the player gets out of bed, then the input is theirs again (pkp_sleep.c getup_step)
            phase = IDLE; view.setVisibility(dex ? View.VISIBLE : View.GONE);
            Native.pkpHold(2);
            return;
        }
        untilMorning = i == MORNING; sleepSec = untilMorning ? 0 : HOURS[i] * 3600L;   // until morning: measured when the clock moves (startTick)
        phase = FADE_OUT; phaseAt = SystemClock.uptimeMillis();
        controls.accept(true);   // the loading screen: no gamepad on it, and no input reaches the game until the picture is back (the hold stays on)
        Native.note("sleep mod: sleep " + (untilMorning ? "until morning" : HOURS[i] + " hours"));
    }

    /** After the save: the count starts and the player gets out of bed (the input still held: Native.pkpHold(3)); the clock moves once they are up (moveClock) */
    private void startTick() {
        long now = Native.clockNow();
        if (now <= 0) now = System.currentTimeMillis() / 1000 + skip(act);
        if (untilMorning) sleepSec = untilMorning(now);
        Native.pkpHold(3); getupAt = SystemClock.uptimeMillis(); movedAt = 0; loadsSeen = -1; quietAt = 0;
        tickFrom = now; phase = TICK; phaseAt = SystemClock.uptimeMillis();
        Native.note("sleep mod: " + (saveState == 3 ? "saved" : "not saved (" + saveState + ")") + "; getting up before the clock moves");
    }

    /** The game's clock on by sleepSec, and the game told it is back in focus, which is when Pokopia takes a new clock (as when the player comes back from the HOME menu
     *  after setting the console's clock) */
    private void moveClock(long[] s) {
        long[] tod = new long[2];
        date0 = Native.pkpTod(tod) ? tod[1] : 0;
        long total = skip(act) + sleepSec;
        skip(act, total); Native.clockSkip(total);
        long now = Native.clockNow() > 0 ? Native.clockNow() : tickFrom + sleepSec;
        todTarget = tod[0] >= 0 ? Instant.ofEpochSecond(now).atZone(ZoneId.systemDefault()).toLocalTime().toSecondOfDay() : -1;
        Native.pkpFocus(2);
        ui.postDelayed(() -> Native.pkpFocus(1), FOCUS_MS);
        movedAt = SystemClock.uptimeMillis();
        boolean inBed = s != null && (s[7] & 0xff) != 0 && (s[7] >> 8 & 0xff) != 0;
        Native.note("sleep mod: " + (movedAt - getupAt > GETUP_MAX_MS ? "getting up took too long; " : "") + (inBed ? "the bed's signs still say in bed; " : "up; ")
            + "the game's clock +" + sleepSec + " s (" + ahead(total) + " ahead of real time)");
    }

    /** Seconds from the game's time now to just after the next MORNING_HOUR (10 s past it, so the game's new day has begun) */
    static long untilMorning(long now) {
        ZonedDateTime z = Instant.ofEpochSecond(now).atZone(ZoneId.systemDefault()), m = z.toLocalDate().atTime(MORNING_HOUR, 0, 10).atZone(ZoneId.systemDefault());
        if (!m.isAfter(z)) m = z.toLocalDate().plusDays(1).atTime(MORNING_HOUR, 0, 10).atZone(ZoneId.systemDefault());
        return m.toEpochSecond() - now;
    }

    /** How long the question takes to type out */
    static long revealMs() { return QUESTION.length() * CHAR_MS; }

    /** The counting clock's run: an hour a second */
    private long tickMs() { return sleepSec * 1000 / 3600; }

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
                        if (el < revealMs()) { if ((down & (A | B)) != 0) phaseAt -= revealMs() - el; break; }   // still typing: A or B shows the question whole
                        if ((down & (UP | L_UP)) != 0) sel = (sel + CHOICES.length - 1) % CHOICES.length;
                        if ((down & (DOWN | L_DOWN)) != 0) sel = (sel + 1) % CHOICES.length;
                        if ((down & A) != 0) choose(sel);
                        else if ((down & B) != 0) choose(NOT_NOW);
                    }
                    break;
                }
                case FADE_OUT: if (el >= FADE_MS) { phase = SAVE; phaseAt = SystemClock.uptimeMillis(); saveState = 1; Native.pkpSave(); } break;
                case SAVE: {
                    long[] s = state(); saveState = s == null ? 4 : (int) s[3];
                    if (saveState == 3 || saveState == 4 || el > SAVE_MAX_MS) startTick();
                    break;
                }
                case TICK: case SETTLE: {   // the count (an hour a second), then the loading screen until the game is quiet after its new day
                    long now = SystemClock.uptimeMillis();
                    long[] s = state();
                    boolean gettingUp = s != null && (s[7] >> 24 & 1) != 0;
                    if (movedAt == 0 && (!gettingUp || now - getupAt > GETUP_MAX_MS)) moveClock(s);   // up: now the clock moves
                    if (phase == TICK) { if (el >= tickMs() + HOLD_MS) { phase = SETTLE; phaseAt = now; } break; }
                    boolean busy = s != null && ((s[9] & 3) != 0 || (s[9] & 4) == 0 || (s[9] >> 8) != loadsSeen);   // an event, the autosave, no player, a new load
                    if (s != null) loadsSeen = s[9] >> 8;
                    if (busy || gettingUp || movedAt == 0 || now - movedAt < AFTER_MOVE_MS || quietAt == 0) quietAt = now;
                    boolean late = movedAt != 0 && now - movedAt > SETTLE_MAX_MS;
                    if (now - quietAt >= QUIET_MS || late) {
                        if (late) Native.note("sleep mod: the game was still busy " + SETTLE_MAX_MS / 1000 + " s after the clock moved (" + (s == null ? "no state" : "flags " + (s[9] & 0xff) + (gettingUp ? ", getting up" : "")) + ")");
                        if (!newTime()) { long[] g = new long[2]; Native.pkpTod(g); Native.note("sleep mod: the save data's time of day reads " + g[0] + " s (date " + g[1] + "), the new clock's " + todTarget + " s"); }
                        phase = FADE_IN; phaseAt = now;
                    }
                    break;
                }
                case FADE_IN: if (el >= FADE_MS) { phase = IDLE; quietAt = 0; Native.pkpHold(0); controls.accept(false); view.setVisibility(dex ? View.VISIBLE : View.GONE); return; } break;
                default: break;
            }
            view.invalidate();
            if (phase != IDLE) Choreographer.getInstance().postFrameCallback(this);
        }
    };

    /** Whether the save data's time of day is the new clock's (and for a whole day, its date moved on): only noted in the log (on the user's tablet it never read
     *  as the new time, though the game showed it); true when it cannot be read */
    private boolean newTime() {
        if (todTarget < 0) return true;
        long[] t = new long[2];
        if (!Native.pkpTod(t) || t[0] < 0) return true;
        long d = Math.abs(t[0] % 86400 - todTarget); d = Math.min(d, 86400 - d);
        return d <= TOD_NEAR && (sleepSec < 23 * 3600L + 1800 || date0 == 0 || t[1] != date0);
    }

    // ---- Drawing ----
    @SuppressLint("ViewConstructor")
    private final class Panel extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF(), msg = new RectF(), r = new RectF();
        private final Path tri = new Path(), talk = new Path(), talkIn = new Path();
        private final float dp;
        private float u, rowH, rowTop, talkX, talkY;   // u: the game's picture height / 677 (the user's recording, a 1016×677 frame of it, is where the sizes were measured)

        Panel(Context c) {
            super(c);
            dp = c.getResources().getDisplayMetrics().density;
            edge.setStyle(Paint.Style.STROKE); edge.setStrokeJoin(Paint.Join.ROUND);
        }

        /** s in the game's font f when it is read and has every character of it, else in the stand-in tf; x as align says; its width. Digits keep their places when
         *  tabular. */
        private float say(Canvas c, GameFont f, String s, float x, float y, float size, int color, Typeface tf, Paint.Align align, boolean tabular) {
            if (f != null && f.has(s)) {
                float w = f.measure(s, size, tabular);
                glyph.setColor(color);
                return f.draw(c, s, align == Paint.Align.CENTER ? x - w / 2 : align == Paint.Align.RIGHT ? x - w : x, y, size, glyph, tabular);
            }
            text.setTypeface(tf); text.setTextSize(size); text.setColor(color); text.setTextAlign(align); text.setStyle(Paint.Style.FILL);
            c.drawText(s, x, y, text);
            return text.measureText(s);
        }

        private float width(GameFont f, String s, float size, Typeface tf, boolean tabular) {
            if (f != null && f.has(s)) return f.measure(s, size, tabular);
            text.setTypeface(tf); text.setTextSize(size);
            return text.measureText(s);
        }

        /** The game's pointer at a selection (a round-cornered triangle, salmon edge, yellow inside), pointing right, its middle at (cx, cy); h its half height */
        private void pointer(Canvas c, float cx, float cy, float h, int a) {
            tri.reset(); tri.moveTo(cx - h * 0.7f, cy - h); tri.lineTo(cx + h * 0.85f, cy); tri.lineTo(cx - h * 0.7f, cy + h); tri.close();
            edge.setColor(Color.argb(a, 255, 131, 115)); edge.setStrokeWidth(h * 0.42f); c.drawPath(tri, edge);
            fill.setShader(new LinearGradient(cx - h * 0.7f, cy, cx + h * 0.85f, cy, Color.argb(a, 253, 238, 130), Color.argb(a, 252, 215, 34), Shader.TileMode.CLAMP));
            c.drawPath(tri, fill);
            fill.setShader(null);
        }

        // The game's talk box: its outline through these points, in u from the middle of the picture's bottom edge (the last two below it), a smooth closed curve
        private final float[] TALK = {-301, 20, -328, -67, -301, -127, -258, -137, -58, -152, 42, -140, 175, -132, 275, -130, 315, -120, 335, -74, 309, 20};

        /** The talk box's outline into p; sx scales it across and top moves its top edge down (its inside: the edge's width) */
        private void blob(Path p, float sx, float top) {
            int n = TALK.length / 2;
            float[] x = new float[n], y = new float[n];
            for (int i = 0; i < n; i++) {
                float yy = TALK[2 * i + 1];
                x[i] = talkX + TALK[2 * i] * u * sx; y[i] = talkY + (yy + (yy < 0 ? top * Math.min(1f, -yy / 120f) : 0)) * u;
            }
            p.reset(); p.moveTo(x[0], y[0]);
            for (int i = 0; i < n; i++) {   // Catmull-Rom through the points, as cubic Béziers
                int i0 = (i + n - 1) % n, i2 = (i + 1) % n, i3 = (i + 2) % n;
                p.cubicTo(x[i] + (x[i2] - x[i0]) / 6, y[i] + (y[i2] - y[i0]) / 6, x[i2] - (x[i3] - x[i]) / 6, y[i2] - (y[i3] - y[i]) / 6, x[i2], y[i2]);
            }
            p.close();
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
            u = Math.min(g.height() / 677f, g.width() / 700f);
            talkX = g.centerX(); talkY = g.bottom;
            msg.set(talkX - 330 * u, talkY - 152 * u, talkX + 336 * u, g.bottom);
            // the choice window: as wide as its longest choice, its right edge 12 u in from the picture's, its bottom 157 u above the picture's (just above the talk box)
            float tw = 0;
            GameFont tf = talkFont;
            for (String ch : CHOICES) tw = Math.max(tw, width(tf, ch, 26 * u, face, false));
            rowH = 57 * u;
            float bw = Math.max(220 * u, 36 * u + tw + 46 * u), bh = 30 * u + rowH * CHOICES.length - 12 * u, right = g.right - 12 * u, bottom = talkY - 157 * u;
            box.set(right - bw, bottom - bh, right, bottom);
            rowTop = box.top + 15 * u;
        }

        private int rowAt(float x, float y) {
            if (!box.contains(x, y) || SystemClock.uptimeMillis() - phaseAt < revealMs()) return -1;
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
                case MotionEvent.ACTION_DOWN: case MotionEvent.ACTION_POINTER_DOWN: {
                    long el = SystemClock.uptimeMillis() - phaseAt;
                    if (!box.contains(x, y) && !msg.contains(x, y)) return false;   // the gamepad's own touches go on to it
                    if (el < revealMs()) { phaseAt -= revealMs() - el; pressedRow = -1; invalidate(); return true; }   // a tap shows the question whole
                    pressedRow = row; if (row >= 0) sel = row; invalidate(); return true;
                }
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
            if (phase == ASK) { drawMenu(c, w, h, el); return; }
            float black = phase == FADE_OUT ? Math.min(1f, el / (float) FADE_MS) : phase == FADE_IN ? 1f - Math.min(1f, el / (float) FADE_MS) : 1f;
            c.drawColor(Color.argb(Math.round(255 * black), 0, 0, 0));
            if (phase == SAVE || phase == TICK || phase == SETTLE || phase == FADE_IN) {
                long shown = phase == SAVE ? Native.clockNow() : phase == TICK ? tickFrom + Math.min(sleepSec, Math.round(el * 3.6)) : tickFrom + sleepSec;
                drawClock(c, w, h, shown, phase == FADE_IN ? black : 1f);
                if (phase != FADE_IN) drawSpinner(c, w, h, black);
            }
        }

        /** The menu el ms after it came up: the game's talk box with the question typing out, then its choice window */
        private void drawMenu(Canvas c, int w, int h, long el) {
            layoutMenu(w, h);
            float in = Math.min(1f, el / 160f);
            int a = Math.round(255 * in);
            // the talk box: a lavender edge, deeper toward the bottom, round a light grey inside with a soft rim
            blob(talk, 1f, 0f); blob(talkIn, 1f - 13f / 320f, 9f);
            fill.setShader(new LinearGradient(0, talkY - 150 * u, 0, talkY - 40 * u, Color.argb(a, 220, 198, 253), Color.argb(a, 187, 185, 252), Shader.TileMode.CLAMP));
            c.drawPath(talk, fill);
            fill.setShader(null);
            fill.setColor(Color.argb(a, 244, 244, 244)); c.drawPath(talkIn, fill);
            edge.setColor(Color.argb(Math.round(150 * in), 214, 208, 252)); edge.setStrokeWidth(4 * u); c.drawPath(talkIn, edge);
            int shown = (int) Math.min(QUESTION.length(), el / CHAR_MS);
            say(c, talkFont, QUESTION.substring(0, shown), talkX - 278 * u, talkY - 84 * u, 30 * u, Color.argb(a, 52, 50, 56), face, Paint.Align.LEFT, false);
            if (el < revealMs()) return;
            // the choice window pops up once the question is out: a light panel, a thin lavender edge, a grey pill a choice, the selected one yellow with the pointer
            float pop = Math.min(1f, (el - revealMs()) / (float) POP_MS), k = 0.9f + 0.1f * pop;
            int b = Math.round(255 * pop);
            c.save(); c.scale(k, k, box.right, box.bottom);
            fill.setShader(new LinearGradient(0, box.top, 0, box.bottom, Color.argb(b, 247, 247, 247), Color.argb(b, 233, 233, 235), Shader.TileMode.CLAMP));
            c.drawRoundRect(box, 22 * u, 22 * u, fill);
            fill.setShader(null);
            edge.setColor(Color.argb(b, 227, 186, 253)); edge.setStrokeWidth(3.3f * u); c.drawRoundRect(box, 22 * u, 22 * u, edge);
            for (int i = 0; i < CHOICES.length; i++) {
                float top = rowTop + i * rowH;
                r.set(box.left + 13 * u, top, box.right - 14 * u, top + 45 * u);
                float rad = r.height() / 2;
                if (i == sel) {
                    r.offset(0, 3 * u); fill.setColor(Color.argb(Math.round(45 * pop), 120, 100, 20)); c.drawRoundRect(r, rad, rad, fill); r.offset(0, -3 * u);
                    fill.setColor(Color.argb(b, 253, 226, 51));
                } else fill.setColor(Color.argb(b, 233, 232, 237));
                c.drawRoundRect(r, rad, rad, fill);
                say(c, talkFont, CHOICES[i], box.left + 36 * u, r.centerY() + 9 * u, 26 * u, i == sel ? Color.argb(b, 104, 78, 0) : Color.argb(b, 52, 50, 56), face, Paint.Align.LEFT, false);
                if (i == sel) pointer(c, r.left + 2 * u, r.centerY(), 13 * u, b);
            }
            c.restore();
        }

        private void drawClock(Canvas c, int w, int h, long t, float in) {
            ZonedDateTime z = Instant.ofEpochSecond(t).atZone(ZoneId.systemDefault());
            int hr = z.getHour() % 12 == 0 ? 12 : z.getHour() % 12;
            String time = hr + ":" + String.format(Locale.US, "%02d", z.getMinute()), ampm = z.getHour() < 12 ? "AM" : "PM";
            int a = Math.round(255 * in);
            float ts = 76 * dp, as = 30 * dp, gap = 12 * dp;
            GameFont f = font;
            float tw = width(f, time, ts, faceBold, true), aw = width(f, ampm, as, faceBold, false), x = (w - tw - gap - aw) / 2, y = h / 2f + 22 * dp;
            say(c, f, time, x, y, ts, Color.argb(a, 255, 255, 255), faceBold, Paint.Align.LEFT, true);   // the digits keep their places as the hours count
            say(c, f, ampm, x + tw + gap, y, as, Color.argb(Math.round(a * 0.85f), 255, 233, 168), faceBold, Paint.Align.LEFT, false);
            int soft = Color.argb(Math.round(a * 0.7f), 255, 255, 255);
            String date = z.format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH));   // the day and date: a long sleep shows the day change
            say(c, f, date, w / 2f, y + 46 * dp, 22 * dp, soft, face, Paint.Align.CENTER, false);
            if (phase == SAVE) say(c, f, "Saving...", w / 2f, y + 86 * dp, 22 * dp, soft, face, Paint.Align.CENTER, false);
            else if (saveState != 3) say(c, f, "The game was busy and did not save", w / 2f, y + 86 * dp, 22 * dp, soft, face, Paint.Align.CENTER, false);
        }

        /** The game's clock in the picture's top left corner while the Pokédex is up */
        private void drawDexClock(Canvas c, int w, int h) {
            RectF g = pic(w, h);
            ZonedDateTime z = Instant.ofEpochSecond(Native.clockNow() > 0 ? Native.clockNow() : System.currentTimeMillis() / 1000 + skip(act)).atZone(ZoneId.systemDefault());
            int hr = z.getHour() % 12 == 0 ? 12 : z.getHour() % 12;
            String t = z.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + "  " + hr + ":" + String.format(Locale.US, "%02d", z.getMinute()) + (z.getHour() < 12 ? " AM" : " PM");
            float size = Math.max(16 * dp, g.height() * 0.034f), x = g.left + g.width() * 0.028f, y = g.top + g.height() * 0.035f + size;
            GameFont f = font;
            if (f != null && f.has(t)) {   // the game's font, its outline drawn as copies around it
                float o = size * 0.075f;
                glyph.setColor(Color.rgb(74, 64, 54));
                for (int i = 0; i < 8; i++) f.draw(c, t, x + o * (float) Math.cos(i * Math.PI / 4), y + o * (float) Math.sin(i * Math.PI / 4), size, glyph, false);
                glyph.setColor(Color.WHITE);
                f.draw(c, t, x, y, size, glyph, false);
                return;
            }
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
