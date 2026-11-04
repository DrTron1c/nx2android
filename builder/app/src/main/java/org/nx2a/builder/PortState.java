package org.nx2a.builder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** What a port has done and has left: the checklist, the percentages and the time left. It is saved as it changes, so it outlasts the app. */
final class PortState {
    static final int WAITING = 0, RUNNING = 1, DONE = 2, FAILED = 3;

    static final class Stage {
        final String name; int status; String note = ""; long seconds = -1; boolean reused;
        double partial;   // how far a step that goes on from its saved place had come when it stopped
        Stage(String n) { name = n; }
    }

    private static boolean keepsItsPlace(String step) { return step.equals("Vulkan shaders") || step.equals("install"); }

    /** Seconds each step takes on a tablet until this device has timed it. Pokémon Pokopia's on a Snapdragon 8 Gen 2, the largest game so far. */
    private static final Map<String, Double> TYPICAL = new HashMap<>();
    static {
        TYPICAL.put("unpack the game", 30.0); TYPICAL.put("build nx_extract", 5.0); TYPICAL.put("extract DLC", 300.0); TYPICAL.put("decompress NSZ / XCZ", 600.0); TYPICAL.put("60 fps", 30.0);
        TYPICAL.put("rewrite", 300.0); TYPICAL.put("shader split", 300.0); TYPICAL.put("Vulkan shaders", 4200.0); TYPICAL.put("host", 900.0);
        TYPICAL.put("APK", 20.0); TYPICAL.put("data pack", 15.0); TYPICAL.put("install", 900.0);
    }

    final List<Stage> stages = new ArrayList<>();
    final ArrayList<String> args = new ArrayList<>();
    private JSONObject timed = new JSONObject();   // this device's own seconds, by step
    String step = "", count = "", summary = "";
    double frac = -1, eta = -1;       // the step's own figures, -1 when it gave none
    boolean finished, failed, paused, cancelled, wasPaused;   // wasPaused: the last run was left paused, so only the player takes it up
    private double shown;             // the step's bar never goes back
    private int reached = -1;         // the last step this run came to
    private long startedAt, pausedFor, pausedAt;   // this step's clock, in ms, without the time spent paused
    private final File file;

    private PortState(File f) { file = f; }

    static PortState load(File f) {
        PortState s = new PortState(f);
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            s.timed = o.optJSONObject("timed") != null ? o.getJSONObject("timed") : new JSONObject();
            s.finished = o.optBoolean("finished"); s.failed = o.optBoolean("failed"); s.cancelled = o.optBoolean("cancelled"); s.summary = o.optString("summary");
            s.step = o.optString("step"); s.shown = o.optDouble("shown", 0); s.wasPaused = o.optBoolean("paused");
            JSONArray a = o.optJSONArray("args");
            for (int i = 0; a != null && i < a.length(); i++) s.args.add(a.getString(i));
            JSONArray st = o.optJSONArray("stages");
            for (int i = 0; st != null && i < st.length(); i++) {
                JSONObject j = st.getJSONObject(i); Stage x = new Stage(j.getString("name"));
                x.status = j.optInt("status"); x.note = j.optString("note"); x.seconds = j.optLong("seconds", -1); x.reused = j.optBoolean("reused"); x.partial = j.optDouble("partial", 0);
                if (x.status == RUNNING) { x.status = FAILED; if (keepsItsPlace(x.name)) x.partial = s.shown; }   // the port ended without saying so: Android, or the app being closed, ended it
                s.stages.add(x);
            }
        } catch (IOException | JSONException ignored) { }   // no port yet
        return s;
    }

    /** Whole or not at all: a port killed while this writes keeps the last one. */
    synchronized void save() {
        try {
            JSONObject o = new JSONObject();
            o.put("timed", timed).put("paused", paused).put("finished", finished).put("failed", failed).put("cancelled", cancelled).put("summary", summary).put("step", step).put("shown", shown);
            o.put("args", new JSONArray(args));
            JSONArray st = new JSONArray();
            for (Stage x : stages) st.put(new JSONObject().put("name", x.name).put("status", x.status).put("note", x.note).put("seconds", x.seconds).put("reused", x.reused).put("partial", x.partial));
            o.put("stages", st);
            File tmp = new File(file.getPath() + ".new");
            try (FileOutputStream w = new FileOutputStream(tmp)) { w.write(o.toString().getBytes(StandardCharsets.UTF_8)); w.getFD().sync(); }
            if (!tmp.renameTo(file)) tmp.delete();
        } catch (IOException | JSONException ignored) { }
    }

    /** A new run: the same game keeps its ticks until the run comes to them; another game starts a fresh list. */
    synchronized void begin(List<String> a) {
        String was = args.size() > 1 ? args.get(1) : "", now = a.size() > 1 ? a.get(1) : "";
        if (!was.equals(now) || finished) stages.clear();
        args.clear(); args.addAll(a);
        finished = failed = paused = cancelled = wasPaused = false; reached = -1; summary = ""; step = ""; count = ""; frac = -1; eta = -1; shown = 0; pausedFor = 0;
        startedAt = System.currentTimeMillis();
        for (Stage s : stages) if (s.status != DONE) s.status = WAITING;
        save();
    }

    synchronized void plan(JSONArray names) {
        List<Stage> old = new ArrayList<>(stages); stages.clear();
        for (int i = 0; i < names.length(); i++) {
            String n = names.optString(i); Stage s = new Stage(n);
            for (Stage o : old) if (o.name.equals(n)) s = o;
            stages.add(s);
        }
        save();
    }

    synchronized void stage(String name) {
        int at = -1;
        for (int i = 0; i < stages.size(); i++) if (stages.get(i).name.equals(name)) at = i;
        if (at < 0) { at = Math.min(reached + 1, stages.size()); stages.add(at, new Stage(name)); }   // a step the plan did not name
        reached = at;
        for (int i = 0; i < at; i++) {   // a step of the plan this game turns out not to need
            Stage s = stages.get(i);
            if (s.status != DONE) { s.status = DONE; if (s.note.isEmpty()) s.note = "not needed"; s.reused = true; }
        }
        Stage s = stages.get(at); s.status = RUNNING; s.note = ""; s.seconds = -1; s.reused = false; s.partial = 0;
        step = ""; count = ""; frac = -1; eta = -1; shown = 0; pausedFor = 0; pausedAt = 0; startedAt = System.currentTimeMillis();
        save();
    }

    synchronized void step(String text) { step = text; }

    synchronized void progress(String text, JSONObject o) {
        step = text;
        long done = o.optLong("done"), total = o.optLong("total");
        String unit = o.optString("unit"), note = o.optString("note");
        count = o.isNull("total") || total <= 0 || unit.isEmpty() ? "" : "B".equals(unit) ? gb(done) + " of " + gb(total) + " GB" : String.format("%,d of %,d %s", done, total, unit);
        if (!note.isEmpty() && !"null".equals(note)) count = count.isEmpty() ? note : count + " · " + note;
        frac = o.isNull("frac") ? -1 : o.optDouble("frac", -1);
        eta = o.isNull("eta") ? -1 : o.optDouble("eta", -1);
    }
    private static String gb(long b) { return String.format("%.1f", b / 1073741824.0); }

    synchronized void stageEnd(String name, boolean ok, String text, double elapsed) {
        for (Stage s : stages) if (s.name.equals(name)) {
            s.status = ok ? DONE : FAILED; s.note = text; s.seconds = (long) elapsed; s.reused = text.contains("reused");
            if (ok && !s.reused && elapsed >= 1) try { timed.put(name, elapsed); } catch (JSONException ignored) { }
        }
        step = ""; count = ""; frac = -1; eta = -1; shown = 0;
        save();
    }

    synchronized void end(boolean ok, String text) {
        finished = ok; failed = !ok && !cancelled; summary = text; paused = false;
        for (Stage s : stages) if (s.status == RUNNING) { s.status = ok ? DONE : FAILED; if (!ok && keepsItsPlace(s.name)) s.partial = shown; }
        save();
    }

    synchronized void pause() { paused = true; pausedAt = System.currentTimeMillis(); save(); }
    synchronized void resume() { if (pausedAt > 0) pausedFor += System.currentTimeMillis() - pausedAt; pausedAt = 0; paused = false; save(); }

    /** A port that stopped before its end, which another run takes up. */
    synchronized boolean canContinue() {
        if (finished || stages.isEmpty()) return false;
        for (Stage s : stages) if (s.status != WAITING) return true;
        return false;
    }

    private double expected(Stage s) {
        double t = timed.optDouble(s.name, -1);
        if (t > 0) return t;
        Double d = TYPICAL.get(s.name);
        return d != null ? d : s.name.startsWith("extract") ? 600 : 60;
    }

    private double elapsed() {
        if (startedAt == 0) return 0;
        long end = paused && pausedAt > 0 ? pausedAt : System.currentTimeMillis();
        return Math.max(0, end - startedAt - pausedFor) / 1000.0;
    }

    private Stage current() {
        for (Stage s : stages) if (s.status == RUNNING) return s;
        return null;
    }

    /** The running step, 0 to 1: its own count when it has one, else the time it has taken against what it usually takes. */
    synchronized double stageFraction() {
        Stage s = current();
        if (s == null) return 0;
        double f = frac >= 0 ? frac : Math.min(0.95, elapsed() / expected(s));
        shown = Math.max(shown, f);
        return shown;
    }

    /** The whole port, 0 to 1: its steps weighed by how long each takes. */
    synchronized double overall() {
        if (finished) return 1;
        double all = 0, have = 0, f = current() != null ? stageFraction() : 0;
        for (Stage s : stages) {
            double e = expected(s); all += e;
            if (s.status == DONE) have += e; else if (s.status == RUNNING) have += e * f; else have += e * s.partial;
        }
        return all > 0 ? Math.min(have / all, 0.99) : 0;
    }

    private double stageLeft() {
        Stage s = current();
        if (s == null) return 0;
        return eta >= 0 ? eta : Math.max(0, 1 - stageFraction()) * expected(s);
    }

    synchronized double secondsLeft() {
        double left = stageLeft();
        for (Stage s : stages) if (s.status == WAITING || s.status == FAILED) left += expected(s) * (1 - s.partial);
        return left;
    }

    private static String about(double seconds) { return seconds < 30 ? "under a minute" : "about " + time(seconds); }

    static String time(double seconds) {
        long m = Math.round(seconds / 60);
        return m < 60 ? m + " min" : (m / 60) + " h" + (m % 60 == 0 ? "" : " " + (m % 60) + " min");
    }

    synchronized String overallLine() {
        int p = (int) (overall() * 100);
        if (finished) return "100% done";
        if (paused) return p + "% done · paused";
        if (current() == null) return p + "% done";
        return p + "% done · " + about(secondsLeft()) + " left";
    }

    synchronized String stepLine() {
        Stage s = current();
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.name);
        if (!step.isEmpty()) b.append(" · ").append(step);
        b.append("\n").append((int) (stageFraction() * 100)).append("%");
        if (!count.isEmpty()) b.append(" · ").append(count);
        if (!paused) b.append(" · ").append(about(stageLeft())).append(" left in this step");
        return b.toString();
    }

    /** The checklist's row for one step. */
    synchronized String row(Stage s) {
        switch (s.status) {
            case DONE: return "✓  " + s.name + (s.note.isEmpty() ? "" : "  ·  " + s.note) + (s.seconds >= 60 && !s.reused ? "  ·  " + time(s.seconds) : "");
            case RUNNING: return (paused ? "⏸  " : "▶  ") + s.name + "  ·  " + (int) (stageFraction() * 100) + "%";
            case FAILED: return "✗  " + s.name + "  ·  stopped here" + (s.partial > 0 ? " at " + (int) (s.partial * 100) + "%, goes on from there" : "");
            default: return "○  " + s.name + "  ·  " + about(expected(s));
        }
    }

    /** The first step left to do. */
    synchronized String nextStep() {
        for (Stage s : stages) if (s.status != DONE) return s.name;
        return "";
    }
}
