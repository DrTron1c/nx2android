package org.nx2a.builder;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/** One screen: tools, game, keys, device connection, port. */
public final class MainActivity extends Activity implements PortService.Listener {
    private static final int BG = 0xFF14161A, CARD = 0xFF1E2127, TEXT = 0xFFE8EAED, DIM = 0xFF9AA0A6, ACCENT = 0xFFFF5C5C, GOOD = 0xFF5CC689;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Env env;
    private SharedPreferences prefs;
    private TextView toolsState, gameState, keysState, adbState, overallText, stepText, logText;
    private Button toolsButton, portButton, pauseButton, cancelButton;
    private ProgressBar overallBar, bar;
    private LinearLayout checklist;
    private ScrollView logScroll;
    private EditText pairPort, pairCode, connectPort;
    private CheckBox touch, fps60, installAfter, keepOn;
    private boolean isSwitch2;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        env = new Env(this); prefs = getSharedPreferences("builder", MODE_PRIVATE);
        setContentView(build());
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        refresh();
    }
    @Override protected void onResume() {
        super.onResume(); PortService.listener = this;
        String dev = prefs.getString("device", null);
        if (dev != null) { adbState.setText("Last connected: " + dev); connectPort.setText(prefs.getString("connect", "")); }
        if (PortService.running) { logText.setText(PortService.recentLog()); setBusy(true); }
        onState();
    }
    @Override protected void onPause() { super.onPause(); PortService.listener = null; }

    // ---- layout ----
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private TextView text(String s, int size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color); if (bold) t.setTypeface(Typeface.DEFAULT_BOLD); return t;
    }
    private Button button(String s, boolean primary, View.OnClickListener l) {
        Button x = new Button(this); x.setText(s); x.setAllCaps(false); x.setTextColor(primary ? Color.WHITE : TEXT); x.setOnClickListener(l);
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(dp(10)); g.setColor(primary ? ACCENT : 0xFF2C3038); x.setBackground(g);
        x.setMinHeight(dp(44)); x.setPadding(dp(16), 0, dp(16), 0);
        return x;
    }
    private LinearLayout card(String title) {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(dp(16), dp(14), dp(16), dp(16));
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(dp(14)); g.setColor(CARD); c.setBackground(g);
        c.addView(text(title, 16, TEXT, true));
        return c;
    }
    private void add(LinearLayout parent, View v, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); p.topMargin = dp(top); parent.addView(v, p);
    }
    private EditText field(String hint, int width) {
        EditText e = new EditText(this); e.setHint(hint); e.setInputType(InputType.TYPE_CLASS_NUMBER); e.setTextColor(TEXT); e.setHintTextColor(DIM); e.setSingleLine(true); e.setWidth(dp(width)); return e;
    }

    private void grow(LinearLayout row, View v) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); p.rightMargin = dp(10); row.addView(v, p);
    }
    private static boolean validPort(String s) {
        if (s.isEmpty() || s.length() > 5) return false;
        int p = Integer.parseInt(s); return p > 0 && p <= 65535;
    }

    private View build() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(16), dp(20), dp(16), dp(24));
        root.addView(text("nx2android Builder", 24, TEXT, true));
        add(root, text("Port your own Switch and Switch 2 games on this device.", 14, DIM, false), 2);

        LinearLayout c1 = card("1. Tools");
        toolsState = text("", 14, DIM, false); add(c1, toolsState, 6);
        toolsButton = button("Download tools", true, v -> downloadTools()); add(c1, toolsButton, 10);
        add(root, c1, 16);

        LinearLayout c2 = card("2. Game");
        gameState = text("", 14, DIM, false); add(c2, gameState, 6);
        add(c2, button("Choose game folder", false, v -> pick(new File(prefs.getString("folder", Environment.getExternalStorageDirectory().getPath())), true)), 10);
        add(root, c2, 12);

        LinearLayout c3 = card("3. prod.keys");
        keysState = text("", 14, DIM, false); add(c3, keysState, 6);
        add(c3, button("Choose prod.keys", false, v -> pick(Environment.getExternalStorageDirectory(), false)), 10);
        add(root, c3, 12);

        LinearLayout c4 = card("4. Wireless debugging");
        adbState = text("", 14, DIM, false); add(c4, adbState, 6);
        add(c4, text("Needed to install the game and copy its data. Tap the button, then in Settings open Developer options → Wireless debugging → Pair device with pairing code and leave that popup open. Pull down the notification from this app, tap Enter code and type the six-digit code.", 13, DIM, false), 6);
        add(c4, button("Pair using a notification", true, v -> pairByNotification()), 10);
        add(c4, text("Or type them here, with this app and Settings side by side:", 13, DIM, false), 12);
        LinearLayout row = new LinearLayout(this); pairPort = field("Pairing port", 110); pairCode = field("Pairing code", 110); grow(row, pairPort); grow(row, pairCode);
        row.addView(button("Pair", false, v -> pair())); add(c4, row, 8);
        LinearLayout row2 = new LinearLayout(this); connectPort = field("Connect port", 110); connectPort.setText(prefs.getString("connect", "")); grow(row2, connectPort);
        row2.addView(button("Connect", false, v -> connect())); add(c4, row2, 8);
        add(root, c4, 12);

        LinearLayout c5 = card("5. Port");
        touch = new CheckBox(this); touch.setText("Touch game (hide the on-screen gamepad)"); touch.setTextColor(TEXT); add(c5, touch, 6);
        fps60 = new CheckBox(this); fps60.setText("60 fps when the game's build allows it"); fps60.setTextColor(TEXT); add(c5, fps60, 0);
        installAfter = new CheckBox(this); installAfter.setText("Install when done"); installAfter.setTextColor(TEXT); installAfter.setChecked(true); add(c5, installAfter, 0);
        keepOn = new CheckBox(this); keepOn.setText("Keep the screen on while porting"); keepOn.setTextColor(TEXT); keepOn.setChecked(prefs.getBoolean("keepOn", true)); add(c5, keepOn, 0);
        keepOn.setOnCheckedChangeListener((v, on) -> { prefs.edit().putBoolean("keepOn", on).apply(); screen(); });
        overallText = text("", 16, TEXT, true); overallText.setVisibility(View.GONE); add(c5, overallText, 10);
        overallBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); overallBar.setMax(1000); overallBar.setVisibility(View.GONE); add(c5, overallBar, 4);
        stepText = text("", 14, TEXT, false); add(c5, stepText, 8);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); bar.setVisibility(View.GONE); add(c5, bar, 4);
        checklist = new LinearLayout(this); checklist.setOrientation(LinearLayout.VERTICAL); add(c5, checklist, 8);
        portButton = button("Port this game", true, v -> port()); add(c5, portButton, 10);
        pauseButton = button("Pause", false, v -> PortService.command(this, PortService.state(this).paused ? PortService.RESUME : PortService.PAUSE)); pauseButton.setVisibility(View.GONE); add(c5, pauseButton, 8);
        cancelButton = button("Cancel", false, v -> PortService.command(this, PortService.CANCEL)); cancelButton.setVisibility(View.GONE); add(c5, cancelButton, 8);
        add(root, c5, 12);

        LinearLayout c6 = card("Log");
        logScroll = new ScrollView(this); logText = text("", 12, DIM, false); logText.setTypeface(Typeface.MONOSPACE); logText.setTextIsSelectable(true); logScroll.addView(logText);
        c6.addView(logScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240)));
        add(c6, button("Copy log", false, v -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("nx2android log", logText.getText()));
            stepText.setText("Log copied. It is also saved in nx2android/last-port.log in your storage.");
        }), 8);
        add(c6, button("Get game log", false, v -> gameLog()), 8);
        add(c6, button("Battery settings for this app", false, v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())))), 8);
        add(root, c6, 12);

        ScrollView s = new ScrollView(this); s.setBackgroundColor(BG); s.addView(root);
        return s;
    }

    private void refresh() {
        boolean ready = env.ready();
        toolsState.setText(ready ? "Installed." : "About 300 MB to download and about 1 GB unpacked, from this project's GitHub releases.");
        toolsState.setTextColor(ready ? GOOD : DIM);
        toolsButton.setText(ready ? "Check for updates" : "Download tools");
        String f = prefs.getString("folder", null), k = prefs.getString("keys", null);
        keysState.setText(k != null ? k : "Needed for Switch games. Switch 2 dumps need none.");
        if (f == null) gameState.setText("A folder with your game: NSP, XCI, NSZ, XCZ, a .nup file or a Switch 2 dump folder.");
        else if (ready) inspect(f); else gameState.setText(f);
        String c = prefs.getString("device", null);
        adbState.setText(c != null ? "Last connected: " + c : "Not connected yet.");
    }

    private void bg(Runnable r) { new Thread(r).start(); }
    private void post(Runnable r) { ui.post(r); }

    // ---- 1. tools ----
    private void downloadTools() {
        setBusy(true); stepText.setText("Checking the tools");
        bg(() -> {
            try {
                Download.Progress p = (what, done, total) -> post(() -> onStep(what, done, total));
                String want = Download.text("versions.txt");
                File dl = new File(env.tmp, "dl"); dl.mkdirs();
                String have = prefs.getString("versions", "");
                String[] w = want.split("\\s+"), h = have.isEmpty() ? new String[]{"", ""} : have.split("\\s+");
                if (!env.ready() || !w[0].equals(h[0])) {
                    File t = Download.download("toolchain.tar.gz", dl, p);
                    for (File old : new File[]{env.usr, env.ndk, env.prebuilt}) delete(old);
                    Download.unpack(t, env.root, p); t.delete();
                }
                if (!new File(env.engine, "porter/cli.py").exists() || w.length < 2 || !w[1].equals(h.length > 1 ? h[1] : "")) {
                    File e = Download.download("engine.tar.gz", dl, p);
                    delete(env.engine);
                    Download.unpack(e, env.root, p); e.delete();
                }
                prefs.edit().putString("versions", want).apply();
                String doctor = env.run(env.porter("doctor"), 120);
                post(() -> { logText.setText(doctor); stepText.setText("Tools are ready."); setBusy(false); refresh(); });
            } catch (Exception e) {
                post(() -> { stepText.setText("The download stopped: " + e.getMessage() + ". Tap the button again to continue it."); setBusy(false); });
            }
        });
    }
    private static void delete(File f) {
        File[] k = f.listFiles();
        if (k != null && !isLink(f)) for (File x : k) delete(x);
        f.delete();
    }
    private static boolean isLink(File f) { return java.nio.file.Files.isSymbolicLink(f.toPath()); }

    // ---- 2 and 3. game and keys ----
    private void pick(File start, boolean folder) {
        while (start != null && !start.isDirectory()) start = start.getParentFile();
        if (start == null) start = Environment.getExternalStorageDirectory();
        File here = start; File[] all = here.listFiles(); List<String> rows = new ArrayList<>();
        if (here.getParentFile() != null && !here.getPath().equals("/storage")) rows.add("⬆  Up");
        List<File> entries = new ArrayList<>();
        if (all != null) {
            List<File> sorted = new ArrayList<>(Arrays.asList(all));
            Collections.sort(sorted, (a, b) -> a.isDirectory() != b.isDirectory() ? (a.isDirectory() ? -1 : 1) : a.getName().compareToIgnoreCase(b.getName()));
            for (File f : sorted) if (f.isDirectory() || !folder) { entries.add(f); rows.add((f.isDirectory() ? "📁  " : "📄  ") + f.getName()); }
        }
        int off = rows.size() - entries.size();
        AlertDialog.Builder d = new AlertDialog.Builder(this).setTitle(here.getPath()).setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, rows), (x, i) -> {
            if (i < off) { pick(here.getParentFile(), folder); return; }
            File f = entries.get(i - off);
            if (f.isDirectory()) pick(f, folder);
            else { prefs.edit().putString("keys", f.getPath()).apply(); refresh(); }
        }).setNegativeButton("Close", null);
        if (folder) d.setPositiveButton("Use this folder", (x, i) -> { prefs.edit().putString("folder", here.getPath()).apply(); refresh(); });
        d.show();
    }
    private void inspect(String folder) {
        gameState.setText(folder + "\nReading…");
        bg(() -> {
            String out = env.run(env.porter("inspect", folder, "--json"), 300), shown;
            boolean s2 = false;
            try {
                JSONObject o = new JSONObject(out.substring(out.indexOf('{')));
                s2 = o.optBoolean("switch2");
                shown = o.has("error") ? o.getString("error") : (o.optString("name", o.optString("title_id")) + (s2 ? "  (Switch 2)" : "") + "\nVersion: " + o.optString("pack", "?")
                    + (o.optString("reason").isEmpty() ? "" : "\n" + o.optString("reason")));
            } catch (Exception e) { shown = "Could not read this folder:\n" + out.substring(0, Math.min(out.length(), 300)); }
            String t = folder + "\n" + shown; boolean sw = s2;
            post(() -> { gameState.setText(t); isSwitch2 = sw; });
        });
    }

    // ---- 4. wireless debugging: adb against this device ----
    private void pairByNotification() {
        if (!env.ready()) { adbState.setText("Download the tools first."); return; }
        if (!getSystemService(android.app.NotificationManager.class).areNotificationsEnabled()) {
            adbState.setText("Turn on notifications for this app, then tap the button again.");
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
            return;
        }
        startForegroundService(new Intent(this, PairService.class));
        adbState.setText("Waiting for the pairing popup in Settings.");
        try { startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); } catch (RuntimeException ignored) { }
    }
    private void pair() {
        String p = pairPort.getText().toString().trim(), c = pairCode.getText().toString().trim();
        if (p.isEmpty() || c.isEmpty()) { adbState.setText("Type the pairing port and code first."); return; }
        if (!validPort(p)) { adbState.setText("That pairing port is not valid. A port has at most 5 digits: use the number after the colon on the pairing popup."); return; }
        adbState.setText("Pairing…");
        bg(() -> {
            String out = env.run(env.adb("pair", "127.0.0.1:" + p, c), 30).trim();
            post(() -> { adbState.setText(out); if (out.toLowerCase().contains("success")) connect(); });
        });
    }
    private void connect() {
        adbState.setText("Connecting…");
        String typed = connectPort.getText().toString().trim();
        bg(() -> {
            String port = typed;
            String m = env.run(env.adb("mdns", "services"), 10);
            Matcher f = Pattern.compile("_adb-tls-connect\\S*\\s+\\S*?(\\d+\\.\\d+\\.\\d+\\.\\d+):(\\d+)").matcher(m);
            if (f.find()) port = f.group(2);
            if (!validPort(port)) { post(() -> adbState.setText("Type the port from the Wireless debugging screen: the number after the colon under IP address & port, at most 5 digits.")); return; }
            String target = "127.0.0.1:" + port;
            String out = env.run(env.adb("connect", target), 15).trim();
            boolean ok = out.contains("connected to");
            String p2 = port;
            post(() -> {
                adbState.setText(ok ? "Connected to " + target : out);
                if (ok) { prefs.edit().putString("device", target).putString("connect", p2).apply(); connectPort.setText(p2); }
            });
        });
    }

    /** What the last ported game wrote, and why Android ended it. Only the shell can read another app's files, so this needs Wireless debugging. */
    private void gameLog() {
        String d = prefs.getString("device", null);
        if (d == null) { stepText.setText("Connect Wireless debugging first."); return; }
        stepText.setText("Getting the game log"); stepText.setTextColor(TEXT);
        String typed = connectPort.getText().toString().trim();
        bg(() -> {
            String port = typed;
            Matcher f = Pattern.compile("_adb-tls-connect\\S*\\s+\\S*?(\\d+\\.\\d+\\.\\d+\\.\\d+):(\\d+)").matcher(env.run(env.adb("mdns", "services"), 10));
            if (f.find()) port = f.group(2);
            String target = validPort(port) ? "127.0.0.1:" + port : d;
            env.run(env.adb("connect", target), 15);
            String out = env.run(env.adb("-s", target, "shell", "for f in /sdcard/Android/data/org.nx2a.t*/files/log*.txt; do echo \"== $f\"; tail -n 400 \"$f\"; done; cd /sdcard/Android/data/org.nx2a.t*/files && ls -la | grep -iE 'pass|frame|\\.ppm' | head -80; tar czf /sdcard/nx2android/frame-dump.tgz $(ls | grep -iE 'pass|\\.ppm|\\.pam|\\.tga') 2>&1 | tail -2; ls -la /sdcard/nx2android/frame-dump.tgz; echo == exits; dumpsys activity exit-info org.nx2a.t0400a3a01ee4c000 | head -n 120; echo == memory; cat /proc/meminfo | head -n 6; echo == logcat; logcat -d -b main -b system -b crash -t 6000 | grep -iE 'nx2a|dmhost|DEBUG|tombstone|F libc|lowmemory|lmkd|killing|has died|kgsl|adreno|am_kill|AndroidRuntime|oom' | tail -n 500"), 240);
            String saved = "";
            try (java.io.FileWriter w = new java.io.FileWriter(new File("/sdcard/nx2android/game-log.txt"))) { w.write(out); saved = "Saved as nx2android/game-log.txt in your storage."; } catch (java.io.IOException e) { saved = "Copy it with Copy log."; }
            String s = saved;
            post(() -> { logText.setText(out); stepText.setText(s); stepText.setTextColor(GOOD); });
        });
    }

    // ---- 5. the port ----
    private void port() {
        String f = prefs.getString("folder", null), k = prefs.getString("keys", null), d = prefs.getString("device", null);
        if (!env.ready()) { stepText.setText("Download the tools first."); return; }
        if (f == null) { stepText.setText("Choose the game folder first."); return; }
        if (k == null && !isSwitch2) { stepText.setText("Choose your prod.keys first."); return; }
        boolean install = installAfter.isChecked();
        if (install && d == null) { stepText.setText("Connect Wireless debugging first, or untick Install when done."); return; }
        askToRunInBackground();
        ArrayList<String> a = new ArrayList<>(Arrays.asList("port", f, "--work", env.work.getPath(), "--jobs", "4", "--progress", "json"));
        if (k != null) { a.add("-k"); a.add(k); }
        if (install) { a.add("--install"); a.add("--device"); a.add(d); }
        if (touch.isChecked()) a.add("--touch");
        if (fps60.isChecked()) a.add("--fps60");
        logText.setText(""); setBusy(true); stepText.setText("Starting"); stepText.setTextColor(TEXT);
        PortService.listener = this;
        PortService.start(this, a);
    }
    /** The first time: Android's own question that lets a port go on with the screen off. HyperOS's own battery setting is under the button in the Log card. */
    private void askToRunInBackground() {
        if (getSystemService(android.os.PowerManager.class).isIgnoringBatteryOptimizations(getPackageName()) || prefs.getBoolean("askedBattery", false)) return;
        prefs.edit().putBoolean("askedBattery", true).apply();
        try { startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))); } catch (RuntimeException ignored) { }
    }
    private void screen() {
        boolean on = PortService.running && !PortService.state(this).paused && keepOn.isChecked();
        if (on) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private void setBusy(boolean busy) {
        portButton.setEnabled(!busy); toolsButton.setEnabled(!busy);
        cancelButton.setVisibility(PortService.running ? View.VISIBLE : View.GONE);
        pauseButton.setVisibility(PortService.running ? View.VISIBLE : View.GONE);
        bar.setVisibility(busy ? View.VISIBLE : View.GONE); bar.setIndeterminate(true);
        screen();
    }

    /** The port's figures, as saved: also after the app was closed, when the port has stopped or finished without it. */
    @Override public void onState() {
        PortState s = PortService.state(this);
        boolean live = PortService.running, known = !s.stages.isEmpty();
        overallText.setVisibility(known ? View.VISIBLE : View.GONE); overallBar.setVisibility(known ? View.VISIBLE : View.GONE);
        if (!known) return;
        overallText.setText(s.overallLine()); overallBar.setProgress((int) (s.overall() * 1000));
        while (checklist.getChildCount() > s.stages.size()) checklist.removeViewAt(checklist.getChildCount() - 1);
        while (checklist.getChildCount() < s.stages.size()) add(checklist, text("", 13, DIM, false), 3);
        for (int i = 0; i < s.stages.size(); i++) {
            PortState.Stage x = s.stages.get(i); TextView row = (TextView) checklist.getChildAt(i);
            row.setText(s.row(x));
            row.setTextColor(x.status == PortState.DONE ? GOOD : x.status == PortState.RUNNING ? TEXT : x.status == PortState.FAILED ? ACCENT : DIM);
        }
        if (live) {
            String line = s.stepLine();
            if (!line.isEmpty()) {
                stepText.setText(line); stepText.setTextColor(TEXT); bar.setVisibility(View.VISIBLE);
                bar.setIndeterminate(false); bar.setMax(1000); bar.setProgress((int) (s.stageFraction() * 1000));
            }
            pauseButton.setText(s.paused ? "Resume" : "Pause");
            pauseButton.setVisibility(View.VISIBLE); cancelButton.setVisibility(View.VISIBLE);
            portButton.setText("Port this game"); portButton.setEnabled(false);
        } else {
            bar.setVisibility(View.GONE);
            pauseButton.setVisibility(View.GONE); cancelButton.setVisibility(View.GONE);
            portButton.setEnabled(true);
            if (s.canContinue()) { portButton.setText("Continue from " + s.nextStep()); stepText.setText("Everything finished so far is saved."); stepText.setTextColor(DIM); }
            else portButton.setText("Port this game");
        }
        screen();
    }

    @Override public void onLine(String line) {
        logText.append(line + "\n");
        if (logText.length() > 60000) logText.setText(logText.getText().subSequence(logText.length() - 40000, logText.length()));
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }
    @Override public void onStep(String step, long done, long total) {
        stepText.setText(step + (total > 0 ? "  " + (100 * done / total) + "%" : ""));
        bar.setVisibility(View.VISIBLE);
        if (total > 0) { bar.setIndeterminate(false); bar.setMax(1000); bar.setProgress((int) (1000 * done / total)); } else bar.setIndeterminate(true);
    }
    @Override public void onDone(boolean ok, String summary) {
        setBusy(false); bar.setVisibility(View.GONE);
        onState();
        PortState s = PortService.state(this);
        stepText.setText(ok ? "Done: " + summary : s.cancelled ? "Cancelled. Everything finished so far is saved." : "The port stopped. The last lines of the log say why. Everything finished so far is saved.");
        stepText.setTextColor(ok ? GOOD : s.cancelled ? DIM : ACCENT);
    }
}
