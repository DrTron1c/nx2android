package org.nx2a.builder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import org.json.JSONException;
import org.json.JSONObject;

/** Runs a port (or the toolchain setup) in the foreground, so Android keeps it alive while the screen is off. */
public final class PortService extends Service {
    interface Listener { void onLine(String line); void onStep(String step, long done, long total); void onDone(boolean ok, String summary); }

    static Listener listener;
    static volatile boolean running;
    private static volatile Process process;
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static final StringBuilder recent = new StringBuilder();
    private static String lastStep = "";

    static String recentLog() { synchronized (recent) { return recent.toString(); } }
    static String lastStep() { return lastStep; }
    static void cancel() { Process p = process; if (p != null) p.destroyForcibly(); }

    private PowerManager.WakeLock wake;
    private NotificationManager nm;
    private FileWriter log;

    static void start(Context c, ArrayList<String> args) {
        c.startForegroundService(new Intent(c, PortService.class).putStringArrayListExtra("args", args));
    }

    @Override public IBinder onBind(Intent i) { return null; }

    private Notification note(String text, int percent) {
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Notification.Builder b = new Notification.Builder(this, "port").setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("nx2android Builder").setContentText(text).setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE));
        if (percent >= 0) b.setProgress(100, percent, false);
        return b.build();
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent == null || running) return START_NOT_STICKY;
        nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("port", "Port progress", NotificationManager.IMPORTANCE_LOW));
        startForeground(1, note("Starting", -1));
        wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nx2a:port");
        wake.acquire(12 * 3600 * 1000L);
        running = true;
        ArrayList<String> args = intent.getStringArrayListExtra("args");
        synchronized (recent) { recent.setLength(0); }
        new Thread(() -> work(args), "port").start();
        return START_NOT_STICKY;
    }

    private void line(String s) {
        synchronized (recent) { recent.append(s).append('\n'); if (recent.length() > 40000) recent.delete(0, recent.length() - 30000); }
        try { if (log != null) { log.write(s + "\n"); log.flush(); } } catch (IOException ignored) { }
        ui.post(() -> { if (listener != null) listener.onLine(s); });
    }

    private void step(String s, long done, long total) {
        lastStep = s;
        ui.post(() -> { if (listener != null) listener.onStep(s, done, total); });
        nm.notify(1, note(s, total > 0 ? (int) (100 * done / total) : -1));
    }

    private void work(ArrayList<String> args) {
        Env env = new Env(this);
        boolean ok = false; String summary = "";
        try {
            File logs = new File("/sdcard/nx2android"); logs.mkdirs();
            try { log = new FileWriter(new File(logs, "last-port.log")); } catch (IOException e) { log = null; }
            ProcessBuilder b = env.porter(args.toArray(new String[0]));
            line("$ porter " + String.join(" ", args));
            Process p = process = b.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                for (String l; (l = r.readLine()) != null; ) {
                    if (l.startsWith("{")) summary = event(l, summary); else line(l);
                }
            }
            ok = p.waitFor() == 0;
        } catch (IOException | InterruptedException e) { line("[RED] " + e.getMessage()); }
        process = null; running = false;
        if (wake.isHeld()) wake.release();
        try { if (log != null) log.close(); } catch (IOException ignored) { }
        boolean done = ok; String sum = summary;
        ui.post(() -> { if (listener != null) listener.onDone(done, sum); });
        stopForeground(true); stopSelf();
    }

    private String event(String l, String summary) {
        try {
            JSONObject o = new JSONObject(l); String e = o.optString("ev"); String step = o.optString("step"); String name = o.optString("name");
            switch (e) {
                case "stage": step("[" + o.optInt("k") + "/" + o.optInt("n") + "] " + name + (step.isEmpty() ? "" : " · " + step), 0, 0); line("· " + name); break;
                case "step": step(name + (step.isEmpty() ? "" : " · " + step), 0, 0); break;
                case "progress": step(name + (step.isEmpty() ? "" : " · " + step), o.optLong("done"), o.optLong("total")); break;
                case "stage_end": line((o.optBoolean("ok", true) ? "✓ " : "✗ ") + name + (o.optString("summary").isEmpty() ? "" : ": " + o.optString("summary"))); break;
                case "say": line(o.optString("msg")); break;
                case "result": return o.optString("name") + " " + o.optString("display");
                default: break;
            }
        } catch (JSONException x) { line(l); }
        return summary;
    }
}
