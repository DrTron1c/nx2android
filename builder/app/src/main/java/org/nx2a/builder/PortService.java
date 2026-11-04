package org.nx2a.builder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Runs a port in the foreground, so Android keeps it alive with the screen off and after the app is closed. If Android kills it anyway, it starts again and the port goes on from its saved place. */
public final class PortService extends Service {
    interface Listener { void onLine(String line); void onStep(String step, long done, long total); void onState(); void onDone(boolean ok, String summary); }

    static final String PAUSE = "pause", RESUME = "resume", CANCEL = "cancel";
    private static final int SIGCONT = 18, SIGSTOP = 19, RETRIES = 5;
    private static final String[] OURS = {"-m\0porter", "shader_vk.py", "shader_split.py", "patch_nso.py", "dmsh_xlate", "android/build.py", "mkapk.py", "clang", "ld.lld"};

    static Listener listener;
    static volatile boolean running;
    private static volatile int root;
    private static PortState state;
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static final StringBuilder recent = new StringBuilder();

    static String recentLog() { synchronized (recent) { return recent.toString(); } }

    static synchronized PortState state(Context c) {
        if (state == null) state = PortState.load(new File(c.getFilesDir(), "port-state.json"));
        return state;
    }

    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private NotificationManager nm;
    private FileWriter log;
    private volatile boolean cancelled;
    private long saved;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            show();
            if (System.currentTimeMillis() - saved > 15000) { saved = System.currentTimeMillis(); state(PortService.this).save(); }
            ui.postDelayed(this, 2000);
        }
    };

    static void start(Context c, ArrayList<String> args) {
        c.startForegroundService(new Intent(c, PortService.class).putStringArrayListExtra("args", args));
    }
    static void command(Context c, String action) { c.startService(new Intent(c, PortService.class).setAction(action)); }

    @Override public IBinder onBind(Intent i) { return null; }

    private PendingIntent action(int code, String what) {
        return PendingIntent.getService(this, code, new Intent(this, PortService.class).setAction(what), PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification note(String title, String text, int percent) {
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        boolean paused = state(this).paused;
        Notification.Builder b = new Notification.Builder(this, "port").setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(title).setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text)).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE));
        if (percent >= 0) b.setProgress(100, percent, false);
        if (running) {
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(this, paused ? android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause), paused ? "Resume" : "Pause", action(1, paused ? RESUME : PAUSE)).build());
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Cancel", action(2, CANCEL)).build());
        }
        return b.build();
    }

    private void lock() {
        if (wake == null) {
            wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nx2a:port"); wake.setReferenceCounted(false);
            wifi = getApplicationContext().getSystemService(WifiManager.class).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "nx2a:port"); wifi.setReferenceCounted(false);   // Wireless debugging needs Wi-Fi at the end of the port
        }
        wake.acquire(24 * 3600 * 1000L); wifi.acquire();
    }
    private void unlock() {
        if (wake != null && wake.isHeld()) wake.release();
        if (wifi != null && wifi.isHeld()) wifi.release();
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("port", "Port progress", NotificationManager.IMPORTANCE_LOW));
        String what = intent == null ? null : intent.getAction();
        if (what != null) {
            if (PAUSE.equals(what)) pause(); else if (RESUME.equals(what)) resume(); else if (CANCEL.equals(what)) cancel();
            if (!running) stopSelf();
            return START_STICKY;
        }
        startForeground(1, note("nx2android Builder", "Starting", -1));
        if (running) return START_STICKY;
        ArrayList<String> args = intent == null ? null : intent.getStringArrayListExtra("args");
        PortState s = state(this);
        if (args == null) {   // Android killed the app and has started this again
            if (s.args.isEmpty() || !s.canContinue() || s.cancelled || s.failed || s.wasPaused) { stopForeground(true); stopSelf(); return START_NOT_STICKY; }
            args = new ArrayList<>(s.args);
        }
        lock();
        running = true; cancelled = false;
        s.begin(args);
        synchronized (recent) { recent.setLength(0); }
        ArrayList<String> a = args;
        new Thread(() -> work(a), "port").start();
        ui.postDelayed(tick, 2000);
        return START_STICKY;
    }

    // ---- processes ----
    private static Map<Integer, Integer> parents() {   // process → its parent, of this app's own processes (the only ones Android lets it see)
        Map<Integer, Integer> m = new HashMap<>();
        String[] all = new File("/proc").list();
        if (all == null) return m;
        for (String n : all) {
            if (!n.matches("\\d+")) continue;
            try {
                String s = new String(Files.readAllBytes(new File("/proc/" + n + "/stat").toPath()), StandardCharsets.UTF_8);
                String[] f = s.substring(s.lastIndexOf(')') + 2).split(" ");   // the name, in brackets, can hold spaces
                m.put(Integer.parseInt(n), Integer.parseInt(f[1]));
            } catch (IOException | RuntimeException ignored) { }   // it ended meanwhile
        }
        return m;
    }

    private static List<Integer> tree(int top) {
        Map<Integer, Integer> p = parents();
        List<Integer> out = new ArrayList<>();
        if (p.containsKey(top)) out.add(top);
        for (int i = 0; i < out.size(); i++) for (Map.Entry<Integer, Integer> e : p.entrySet()) if (e.getValue() == out.get(i).intValue()) out.add(e.getKey());
        return out;
    }

    private static void signal(int top, int sig) {
        for (int pass = 0; pass < 2; pass++) for (int pid : tree(top)) android.os.Process.sendSignal(pid, sig);   // twice: one may have started another meanwhile
    }

    /** What an earlier run left behind, which would write over this one's files. */
    private static void clearLeftovers() {
        int me = android.os.Process.myPid();
        for (int pid : parents().keySet()) {
            if (pid == me) continue;
            try {
                String c = new String(Files.readAllBytes(new File("/proc/" + pid + "/cmdline").toPath()), StandardCharsets.ISO_8859_1);
                for (String o : OURS) if (c.contains(o)) { android.os.Process.sendSignal(pid, SIGCONT); android.os.Process.killProcess(pid); break; }
            } catch (IOException | RuntimeException ignored) { }
        }
    }

    private void pause() {
        PortState s = state(this);
        if (!running || s.paused || root == 0) return;
        signal(root, SIGSTOP); s.pause(); unlock(); line("Paused.");
        show();
    }
    private void resume() {
        PortState s = state(this);
        if (!running || !s.paused) return;
        lock(); signal(root, SIGCONT); s.resume(); line("Resumed.");
        show();
    }
    private void cancel() {
        cancelled = true;
        int top = root;
        if (top != 0) {
            signal(top, SIGCONT);
            for (int pid : tree(top)) android.os.Process.killProcess(pid);
        }
    }

    // ---- what the port says ----
    private void line(String s) {
        synchronized (recent) { recent.append(s).append('\n'); if (recent.length() > 40000) recent.delete(0, recent.length() - 30000); }
        try { if (log != null) { log.write(s + "\n"); log.flush(); } } catch (IOException ignored) { }
        ui.post(() -> { if (listener != null) listener.onLine(s); });
    }

    private void show() {
        PortState s = state(this);
        ui.post(() -> { if (listener != null) listener.onState(); });
        if (nm != null && running) nm.notify(1, note(s.overallLine(), s.stepLine(), (int) (s.overall() * 100)));
    }

    private void work(ArrayList<String> args) {
        Env env = new Env(this);
        PortState s = state(this);
        boolean ok = false; String summary = "";
        File logs = new File("/sdcard/nx2android"); logs.mkdirs();
        try { log = new FileWriter(new File(logs, "last-port.log")); } catch (IOException e) { log = null; }
        line("$ porter " + String.join(" ", args));
        for (int attempt = 0; attempt <= RETRIES && !cancelled; attempt++) {
            clearLeftovers();
            int code = -1;
            try {
                Process p = env.porterWithPid(args).start();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    for (String l; (l = r.readLine()) != null; ) {
                        if (l.startsWith("@pid ")) root = Integer.parseInt(l.substring(5).trim());
                        else if (l.startsWith("{")) summary = event(l, summary);
                        else line(l);
                    }
                }
                code = p.waitFor();
            } catch (IOException | InterruptedException | NumberFormatException e) { line("[RED] " + e.getMessage()); }
            root = 0;
            ok = code == 0;
            if (cancelled || code < 129) break;   // above 128: a signal ended it, which is the system and not the port's own failure
            line("Android stopped the port. Going on from its saved place.");
            try { Thread.sleep(3000); } catch (InterruptedException ignored) { }
        }
        if (cancelled) { s.cancelled = true; line("Cancelled. What is finished stays saved."); }
        s.end(ok, summary);
        running = false; ui.removeCallbacks(tick);
        unlock();
        try { if (log != null) log.close(); } catch (IOException ignored) { }
        boolean done = ok; String sum = summary;
        ui.post(() -> { if (listener != null) listener.onDone(done, sum); });
        stopForeground(true); stopSelf();
    }

    private String event(String l, String summary) {
        PortState s = state(this);
        try {
            JSONObject o = new JSONObject(l); String e = o.optString("ev"); String step = o.optString("step"); String name = o.optString("name");
            switch (e) {
                case "plan": JSONArray a = o.optJSONArray("stages"); if (a != null) s.plan(a); show(); break;
                case "stage": s.stage(name); s.step(step); line("· " + name); show(); break;
                case "step": s.step(step); show(); break;
                case "progress": s.progress(step, o); show(); break;
                case "stage_end":
                    s.stageEnd(name, o.optBoolean("ok", true), o.optString("summary"), o.optDouble("elapsed", 0));
                    line((o.optBoolean("ok", true) ? "✓ " : "✗ ") + name + (o.optString("summary").isEmpty() ? "" : ": " + o.optString("summary"))); show(); break;
                case "say": line(o.optString("msg")); break;
                case "result": return o.optString("name") + " " + o.optString("display");
                default: break;
            }
        } catch (JSONException x) { line(l); }
        return summary;
    }
}
