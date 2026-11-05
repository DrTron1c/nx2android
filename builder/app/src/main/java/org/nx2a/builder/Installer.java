package org.nx2a.builder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.provider.Settings;
import android.net.Uri;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Android's own installer, for this app's updates and for the games it ports: no Wireless debugging. Android shows its popup for each install, and takes an update only when it is
 * signed with the key of the app it replaces. An Activity can only be opened from the app while the app is on screen, so what cannot be opened yet waits in {@link #next} for
 * {@link MainActivity#onResume}, and a notification brings the person back after a port that finished while they were away.
 */
public final class Installer {
    /** The Activity to open next: Android's confirmation or the game. */
    private static volatile Intent next;
    /** MainActivity is on screen. */
    static volatile boolean front;
    /** What a failed update of this app says; shown by MainActivity. */
    interface Report { void failed(String message); }
    static volatile Report report;

    private Installer() { }

    static boolean allowed(Context c) { return c.getPackageManager().canRequestPackageInstalls(); }
    static void askToAllow(Context c) {
        c.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** Hands the APK to Android. flag: where a failure is written for porter to read; null for this app's own update. */
    static void install(Context c, File apk, String pkg, String flag) throws IOException {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sp.setAppPackageName(pkg); sp.setSize(apk.length());
        int id = pi.createSession(sp);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("app.apk", 0, apk.length())) {   // closed once, and before the commit: a second close of this stream fails
                byte[] b = new byte[1 << 16]; for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
                s.fsync(out);
            }
            Intent done = new Intent(c, Result.class).putExtra("pkg", pkg).putExtra("flag", flag);
            s.commit(PendingIntent.getBroadcast(c, id, done, PendingIntent.FLAG_UPDATE_CURRENT).getIntentSender());   // the system fills the answer in: not immutable
        }
    }

    /** Opens the game, or leaves it for when this app is next on screen. */
    static void openGame(Context c, String pkg, String label) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) i = new Intent().setClassName(pkg, "android.app.NativeActivity");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        open(c, i, label == null || label.isEmpty() ? "Open the game" : "Open " + label, PendingIntent.getActivity(c, 2, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
    }
    private static void open(Context c, Intent i, String title, PendingIntent tap) {
        if (front) {
            try { c.startActivity(i); return; } catch (RuntimeException ignored) { }
        }
        next = i;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("install", "Installs", NotificationManager.IMPORTANCE_HIGH));
        nm.notify(2, new Notification.Builder(c, "install").setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle(title).setContentText("Tap to carry on")
            .setContentIntent(tap).setAutoCancel(true).build());
    }
    /** The game has been seen, or the port is over: nothing is waiting any more. */
    static void clear(Context c) { next = null; c.getSystemService(NotificationManager.class).cancel(2); }
    /** MainActivity came to the front. */
    static void resumed(Context c) {
        front = true;
        Intent i = next; next = null;
        if (i != null) {
            c.getSystemService(NotificationManager.class).cancel(2);
            try { c.startActivity(i); } catch (RuntimeException ignored) { }
        }
    }

    /** Android's answer: it wants the person to confirm, or the install is over. */
    public static final class Result extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            String pkg = i.getStringExtra("pkg"), flag = i.getStringExtra("flag");
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm == null) return;
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                Intent back = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                open(c, confirm, "Tap to install", PendingIntent.getActivity(c, 3, back, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
                return;
            }
            if (status == PackageInstaller.STATUS_SUCCESS) {
                if (flag != null) openGame(c, pkg, null);
                return;
            }
            String why = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            String say = status == PackageInstaller.STATUS_FAILURE_ABORTED ? "The install was cancelled. Tap Port to ask again: the finished work is kept."
                : status == PackageInstaller.STATUS_FAILURE_CONFLICT ? "Android refused the install: an app of that name is signed with another key" + (why == null ? "." : " (" + why + ").")
                : "Android could not install it" + (why == null ? "." : ": " + why);
            if (flag == null) { Report r = report; if (r != null) r.failed(say); return; }
            try (FileOutputStream o = new FileOutputStream(flag)) { o.write(say.getBytes(StandardCharsets.UTF_8)); } catch (IOException ignored) { }
        }
    }
}
