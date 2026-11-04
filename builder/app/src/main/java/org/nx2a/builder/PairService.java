package org.nx2a.builder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.Service;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Pairs Wireless debugging from a notification, so the pairing popup in Settings can stay open while the code is typed (as Shizuku does): it listens for the ports
 * Android announces on the network, asks for the code in the notification, then runs adb.
 */
public final class PairService extends Service {
    private static final String KEY = "code", ACTION_CODE = "org.nx2a.builder.PAIR_CODE", ACTION_STOP = "org.nx2a.builder.PAIR_STOP";
    private static final int ID = 2;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private NsdManager nsd;
    private NotificationManager nm;
    private Env env;
    private NsdManager.DiscoveryListener pairing, connecting;
    private volatile int pairPort, connectPort;
    private volatile boolean paired, working;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        env = new Env(this); nsd = getSystemService(NsdManager.class); nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("pair", "Wireless debugging pairing", NotificationManager.IMPORTANCE_HIGH));
    }

    private Notification note(String text, boolean askCode) {
        Notification.Builder b = new Notification.Builder(this, "pair").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("nx2android pairing").setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text)).setOngoing(true).setOnlyAlertOnce(!askCode);
        if (askCode) {
            RemoteInput in = new RemoteInput.Builder(KEY).setLabel("Six-digit code").build();
            PendingIntent code = PendingIntent.getService(this, 1, new Intent(this, PairService.class).setAction(ACTION_CODE), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_edit), "Enter code", code).addRemoteInput(in).build());
        }
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, PairService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        b.addAction(new Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Stop", stop).build());
        return b.build();
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) { stopSelf(); return START_NOT_STICKY; }
        if (ACTION_CODE.equals(action)) {
            Bundle r = RemoteInput.getResultsFromIntent(intent);
            CharSequence c = r != null ? r.getCharSequence(KEY) : null;
            if (c != null) { String code = c.toString().trim(); new Thread(() -> pair(code)).start(); }
            return START_NOT_STICKY;
        }
        startForeground(ID, note("In Settings, open Wireless debugging and tap Pair device with pairing code. Leave that popup open and come back to this notification.", false));
        if (pairing == null) {
            pairing = discover("_adb-tls-pairing._tcp", true); connecting = discover("_adb-tls-connect._tcp", false);
            ui.postDelayed(this::stopSelf, 15 * 60 * 1000L);
        }
        return START_NOT_STICKY;
    }

    private NsdManager.DiscoveryListener discover(String type, boolean isPair) {
        NsdManager.DiscoveryListener l = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String s) { }
            @Override public void onDiscoveryStopped(String s) { }
            @Override public void onStartDiscoveryFailed(String s, int e) { }
            @Override public void onStopDiscoveryFailed(String s, int e) { }
            @Override public void onServiceFound(NsdServiceInfo i) { resolve(i, isPair, 0); }
            @Override public void onServiceLost(NsdServiceInfo i) { }
        };
        nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l);
        return l;
    }

    private void resolve(NsdServiceInfo info, boolean isPair, int attempt) {
        nsd.resolveService(info, new NsdManager.ResolveListener() {
            @Override public void onResolveFailed(NsdServiceInfo i, int e) { if (attempt < 8) ui.postDelayed(() -> resolve(info, isPair, attempt + 1), 400); }
            @Override public void onServiceResolved(NsdServiceInfo i) { int p = i.getPort(); new Thread(() -> found(p, isPair)).start(); }
        });
    }

    private void found(int port, boolean isPair) {
        try (Socket s = new Socket()) { s.connect(new InetSocketAddress("127.0.0.1", port), 800); } catch (Exception e) { return; }   // another device on the Wi-Fi
        if (isPair) {
            if (paired || port == pairPort) return;
            pairPort = port;
            nm.notify(ID, note("Port " + port + " found. Tap Enter code here and type the six-digit code from Settings.", true));
        } else connectPort = port;
    }

    private void pair(String code) {
        if (working) return;
        working = true;
        if (pairPort == 0) { nm.notify(ID, note("Waiting for the pairing popup in Settings.", false)); working = false; return; }
        nm.notify(ID, note("Pairing…", false));
        String out = env.run(env.adb("pair", "127.0.0.1:" + pairPort, code), 30).trim();
        if (!out.toLowerCase().contains("success")) {
            nm.notify(ID, note("That did not pair: " + out + "\nCheck the code and try again, with the popup still open.", true));
            working = false; return;
        }
        paired = true;
        int port = connectPort;
        for (int i = 0; i < 20 && port == 0; i++) { try { Thread.sleep(500); } catch (InterruptedException e) { break; } port = connectPort; }
        String saved = getSharedPreferences("builder", MODE_PRIVATE).getString("connect", "");
        if (port == 0 && !saved.isEmpty()) port = Integer.parseInt(saved);
        String target = "127.0.0.1:" + port;
        String c = port == 0 ? "no connect port found" : env.run(env.adb("connect", target), 15).trim();
        boolean ok = c.contains("connected to");
        if (ok) getSharedPreferences("builder", MODE_PRIVATE).edit().putString("device", target).putString("connect", String.valueOf(port)).apply();
        working = false;
        String text = ok ? "Paired and connected. Go back to the Builder." : "Paired, but not connected: " + c + "\nGo back to the Builder and tap Connect.";
        stopForeground(true);
        getSystemService(NotificationManager.class).notify(3, new Notification.Builder(this, "pair").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("nx2android pairing").setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text)).setAutoCancel(true).build());
        stopSelf();
    }

    private void stopDiscovery(NsdManager.DiscoveryListener l) { if (l != null) try { nsd.stopServiceDiscovery(l); } catch (IllegalArgumentException ignored) { } }

    @Override public void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        stopDiscovery(pairing); stopDiscovery(connecting);
        super.onDestroy();
    }
}
