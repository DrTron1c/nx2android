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
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/** One screen: tools, game, keys, device connection, port. */
public final class MainActivity extends Activity implements PortService.Listener {
    private static final int BG = 0xFF0E0D12, CARD = 0xFF26242C, TEXT = 0xFFFFFFFF, DIM = 0xFFC9CDD0, ACCENT = 0xFFF49BA0, GOOD = 0xFF94D2A6, LILAC = 0xFFB79BD6, PLUM = 0xFF401E36, PILL = 0xFF5F676B;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Env env;
    private SharedPreferences prefs;
    private TextView toolsState, gameState, keysState, adbState, overallText, stepText, logText;
    private Button toolsButton, appButton, portButton, pauseButton, cancelButton;
    private ProgressBar overallBar, bar;
    private LinearLayout checklist;
    private ScrollView logScroll;
    private EditText pairPort, pairCode, connectPort;
    private CheckBox touch, fps60, boost, allShaders, installAfter, overAdb, keepOn;
    private TextView driverState, lsfgState, storageState;
    private String pickInto = "keys";
    private boolean isSwitch2;
    private String gameId, gameName;   // the chosen folder's title ID and name (porter inspect)
    private LinearLayout engineCard;
    private TextView engineState;
    private Button engineButton;

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
        Installer.report = m -> post(() -> { stepText.setText(m); stepText.setTextColor(TEXT); appButton.setEnabled(true); });
        Installer.resumed(this);
        if (System.currentTimeMillis() - appChecked > 1800000) checkApp();
        String dev = prefs.getString("device", null);
        if (dev != null) { adbState.setText("Last connected: " + dev); connectPort.setText(prefs.getString("connect", "")); }
        if (PortService.running) { logText.setText(PortService.recentLog()); setBusy(true); }
        onState();
        refreshEngine(false);
    }
    @Override protected void onPause() { super.onPause(); PortService.listener = null; Installer.front = false; Installer.report = null; }

    // ---- layout ----
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private TextView text(String s, int size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color); if (bold) t.setTypeface(Typeface.DEFAULT_BOLD); return t;
    }
    // the game's gamepad: pastel pills in a white ring on near black, plum letters on the pastel
    private GradientDrawable ring(int fill, int radius, int stroke) { GradientDrawable g = new GradientDrawable(); g.setCornerRadius(dp(radius)); g.setColor(fill); g.setStroke(dp(stroke), Color.WHITE); return g; }
    private Button button(String s, boolean primary, View.OnClickListener l) {
        Button x = new Button(this); x.setText(s); x.setAllCaps(false); x.setTextColor(primary ? PLUM : TEXT); x.setTypeface(Typeface.DEFAULT_BOLD); x.setOnClickListener(l);
        x.setBackground(ring(primary ? LILAC : PILL, 26, 2));
        x.setMinHeight(dp(48)); x.setPadding(dp(20), 0, dp(20), 0);
        return x;
    }
    private LinearLayout card(String title) {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(dp(18), dp(16), dp(18), dp(18));
        c.setBackground(ring(CARD, 24, 2));
        c.addView(text(title, 17, TEXT, true));
        return c;
    }
    private void add(LinearLayout parent, View v, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); p.topMargin = dp(top); parent.addView(v, p);
    }
    private EditText field(String hint, int width) {
        EditText e = new EditText(this); e.setHint(hint); e.setInputType(InputType.TYPE_CLASS_NUMBER); e.setTextColor(TEXT); e.setHintTextColor(DIM); e.setSingleLine(true); e.setWidth(dp(width)); e.setBackground(ring(PILL, 20, 2)); e.setPadding(dp(14), dp(8), dp(14), dp(8)); return e;
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
        appButton = button("Update this app", true, v -> updateApp()); appButton.setVisibility(View.GONE); add(c1, appButton, 8);
        add(root, c1, 16);

        LinearLayout c2 = card("2. Game");
        gameState = text("", 14, DIM, false); add(c2, gameState, 6);
        add(c2, button("Choose game folder", false, v -> pick(new File(prefs.getString("folder", Environment.getExternalStorageDirectory().getPath())), true)), 10);
        add(root, c2, 12);

        // A Switch 2 game plays on Banana Native's engine (Engine.java): installed and updated from here, started from here or from a home-screen icon
        engineCard = card("Play on the Switch 2 engine"); engineCard.setVisibility(View.GONE);
        add(engineCard, text("Banana Native's engine, built by this project: Switch 2 games run on it at its speed, with all its settings. The first time, it prepares the game on this tablet (its code and the shaders it uses); after that, Play starts the game at once.", 13, DIM, false), 6);
        engineState = text("", 14, DIM, false); add(engineCard, engineState, 8);
        engineButton = button("Install the engine", true, v -> installEngine()); add(engineCard, engineButton, 10);
        LinearLayout playRow = new LinearLayout(this);
        grow(playRow, button("Play", true, v -> playGame())); grow(playRow, button("Add to home screen", false, v -> addToHome()));
        add(engineCard, playRow, 8);
        add(root, engineCard, 12);

        LinearLayout c3 = card("3. prod.keys");
        keysState = text("", 14, DIM, false); add(c3, keysState, 6);
        add(c3, button("Choose prod.keys", false, v -> { pickInto = "keys"; pick(Environment.getExternalStorageDirectory(), false); }), 10);
        add(root, c3, 12);

        LinearLayout c4 = card("4. Wireless debugging (optional)");
        adbState = text("", 14, DIM, false); add(c4, adbState, 6);
        add(c4, text("Not needed to install a game any more. It is for the Game log button and the old way of installing. Tap the button, then in Settings open Developer options → Wireless debugging → Pair device with pairing code and leave that popup open. Pull down the notification from this app, tap Enter code and type the six-digit code.", 13, DIM, false), 6);
        add(c4, button("Pair using a notification", true, v -> pairByNotification()), 10);
        add(c4, text("Or type them here, with this app and Settings side by side:", 13, DIM, false), 12);
        LinearLayout row = new LinearLayout(this); pairPort = field("Pairing port", 110); pairCode = field("Pairing code", 110); grow(row, pairPort); grow(row, pairCode);
        row.addView(button("Pair", false, v -> pair())); add(c4, row, 8);
        LinearLayout row2 = new LinearLayout(this); connectPort = field("Connect port", 110); connectPort.setText(prefs.getString("connect", "")); grow(row2, connectPort);
        row2.addView(button("Connect", false, v -> connect())); add(c4, row2, 8);
        add(root, c4, 12);

        LinearLayout c5 = card("5. Port");
        touch = new CheckBox(this); touch.setText("Touch game (hide the on-screen gamepad)"); touch.setTextColor(TEXT);  add(c5, touch, 6);
        fps60 = new CheckBox(this); fps60.setText("60 fps when the game's build allows it"); fps60.setTextColor(TEXT);  add(c5, fps60, 0);
        boost = new CheckBox(this); boost.setText("Hold the GPU at full speed (steadier, but hotter)"); boost.setTextColor(TEXT); boost.setChecked(prefs.getBoolean("boost", true)); add(c5, boost, 0);
        boost.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean("boost", on).apply());
        // Every shader of the game (porter --all-shaders): off, a game whose profile lists the shaders it uses (Pokopia: 8,157 of 131,426) translates only those, minutes instead of hours,
        // and the ones it asks for later are saved while it runs and added at the next port
        allShaders = new CheckBox(this); allShaders.setText("Translate every shader (hours; off: only the ones the game is known to use, the rest are added at the next port)"); allShaders.setTextColor(TEXT);
        allShaders.setChecked(prefs.getBoolean("allShaders", false)); add(c5, allShaders, 0);
        allShaders.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean("allShaders", on).apply());
        installAfter = new CheckBox(this); installAfter.setText("Install when done"); installAfter.setTextColor(TEXT);  installAfter.setChecked(true); add(c5, installAfter, 0);
        overAdb = new CheckBox(this); overAdb.setText("Install over Wireless debugging instead"); overAdb.setTextColor(TEXT); overAdb.setChecked(prefs.getBoolean("overAdb", false)); add(c5, overAdb, 0);
        overAdb.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean("overAdb", on).apply());
        keepOn = new CheckBox(this); keepOn.setText("Keep the screen on while porting"); keepOn.setTextColor(TEXT);  keepOn.setChecked(prefs.getBoolean("keepOn", true)); add(c5, keepOn, 0);
        keepOn.setOnCheckedChangeListener((v, on) -> { prefs.edit().putBoolean("keepOn", on).apply(); screen(); });
        for (CheckBox b : new CheckBox[]{touch, fps60, boost, allShaders, installAfter, overAdb, keepOn}) b.setButtonTintList(android.content.res.ColorStateList.valueOf(LILAC));
        add(c5, text("GPU driver", 14, TEXT, true), 10);
        driverState = text("", 14, DIM, false); add(c5, driverState, 4);
        LinearLayout driverRow = new LinearLayout(this);
        driverRow.addView(button("Choose driver", false, v -> { pickInto = "driver"; String was = prefs.getString("driver", null); pick(was != null ? new File(was) : new File(Environment.getExternalStorageDirectory(), "Download"), false); }));
        driverRow.addView(button("Use the system's", false, v -> { prefs.edit().remove("driver").apply(); refresh(); }));
        driverRow.addView(button("Get Turnip", false, v -> getTurnip()));
        add(c5, driverRow, 6);
        add(c5, text("Lossless Scaling frame generation", 14, TEXT, true), 10);
        lsfgState = text("", 14, DIM, false); add(c5, lsfgState, 4);
        LinearLayout lsfgRow = new LinearLayout(this);
        lsfgRow.addView(button("Choose Lossless.dll", false, v -> { pickInto = "lsfg"; String was = prefs.getString("lsfg", null); pick(was != null ? new File(was) : new File(Environment.getExternalStorageDirectory(), "Download"), false); }));
        lsfgRow.addView(button("Remove", false, v -> { prefs.edit().remove("lsfg").apply(); refresh(); }));
        add(c5, lsfgRow, 6);
        overallText = text("", 16, TEXT, true); overallText.setVisibility(View.GONE); add(c5, overallText, 10);
        overallBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); overallBar.setMax(1000); overallBar.setVisibility(View.GONE); add(c5, overallBar, 4);
        stepText = text("", 14, TEXT, false); add(c5, stepText, 8);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); bar.setVisibility(View.GONE); add(c5, bar, 4);
        for (ProgressBar b : new ProgressBar[]{overallBar, bar}) { b.setProgressTintList(android.content.res.ColorStateList.valueOf(LILAC)); b.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(LILAC)); b.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(PILL)); }
        checklist = new LinearLayout(this); checklist.setOrientation(LinearLayout.VERTICAL); add(c5, checklist, 8);
        portButton = button("Port this game", true, v -> port()); add(c5, portButton, 10);
        pauseButton = button("Pause", false, v -> PortService.command(this, PortService.state(this).paused ? PortService.RESUME : PortService.PAUSE)); pauseButton.setVisibility(View.GONE); add(c5, pauseButton, 8);
        cancelButton = button("Cancel", false, v -> PortService.command(this, PortService.CANCEL)); cancelButton.setVisibility(View.GONE); add(c5, cancelButton, 8);
        add(root, c5, 12);

        LinearLayout c7 = card("Storage");
        storageState = text("", 14, DIM, false); add(c7, storageState, 6);
        add(c7, button("Delete temporary files", false, v -> storageJob("Delete temporary files?", "Leftovers of finished and stopped ports. Nothing you need goes.", this::deleteTemp)), 10);
        add(c7, button("Free space, keep the shaders", false, v -> storageJob("Free space?", "Removes the builder's copy of the game's files. The game keeps its own, and the next port makes them again in under a minute.", this::deleteCopies)), 8);
        add(c7, button("Delete all port files", false, v -> storageJob("Delete all port files?", "This includes the translated shaders: the next port takes about an hour. Your installed game and its saves stay.", () -> { for (File f : list(env.work)) delete(f); })), 8);
        add(c7, button("Game's Android storage", false, v -> gameSettings()), 8);
        add(c7, button("Uninstall the game", false, v -> uninstallGame()), 8);
        add(c7, text("To start a new game: pause menu, Saves, Delete save. It keeps a backup you can bring back with Import. Clear data in the game's Android storage deletes its saves for good.", 13, DIM, false), 10);
        add(root, c7, 12);

        LinearLayout c6 = card("Log");
        logScroll = new ScrollView(this); logText = text("", 12, DIM, false); logText.setTypeface(Typeface.MONOSPACE); logText.setTextIsSelectable(true); logScroll.addView(logText);
        c6.addView(logScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240)));
        add(c6, button("Copy log", false, v -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("nx2android log", logText.getText()));
            stepText.setText("Log copied. It is also saved in nx2android/last-port.log in your storage.");
        }), 8);
        add(c6, button("Get game log", false, v -> gameLog()), 8);
        add(c6, button("Watch game memory for 7 minutes", false, v -> watchMemory()), 8);
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
        String dr = prefs.getString("driver", null);
        driverState.setText(dr != null ? new File(dr).getName() + "\nUsed at the next port. If the game then fails to open, tap Use the system's and port again." : "The tablet's own driver. A custom one (a .zip or .so, such as Turnip) goes in the game at the next port.");
        refreshStorage();
        String ls = prefs.getString("lsfg", null);
        lsfgState.setText(ls != null ? new File(ls).getName() + "\nAdded at the next port. In the game: pause menu, Display, Frame generation." : "Your own Lossless.dll from Lossless Scaling for Windows. It stays on this tablet and is never uploaded.");
        String c = prefs.getString("device", null);
        adbState.setText(c != null ? "Last connected: " + c : "Not connected yet.");
    }

    /** Mesa's Turnip, the open driver for Adreno GPUs, from the public releases of the projects that build it for Android (as Banana Native's DriverFetch lists them). Third-party
     *  drivers, each under its own licence (Turnip is MIT): the person picks one, it is saved in this app's files and used from the next port. */
    private static final String[] TURNIP_REPOS = {"whitebelyash/freedreno_turnip-CI", "K11MCH1/AdrenoToolsDrivers", "MrPurple666/purple-turnip"};
    private void getTurnip() {
        stepText.setText("Looking for Turnip drivers..."); stepText.setTextColor(TEXT);
        bg(() -> {
            java.util.List<String[]> got = new java.util.ArrayList<>(); StringBuilder failed = new StringBuilder();
            for (String repo : TURNIP_REPOS) {
                try {
                    org.json.JSONArray rel = new org.json.JSONArray(Download.url("https://api.github.com/repos/" + repo + "/releases?per_page=4"));
                    for (int i = 0; i < rel.length(); i++) {
                        org.json.JSONObject r = rel.getJSONObject(i); org.json.JSONArray as = r.getJSONArray("assets");
                        for (int k = 0; k < as.length(); k++) {
                            org.json.JSONObject a = as.getJSONObject(k); String n = a.getString("name"), low = n.toLowerCase(java.util.Locale.ROOT);
                            if (!low.endsWith(".zip") || !(low.contains("turnip") || repo.contains("turnip"))) continue;
                            got.add(new String[]{repo.split("/")[0] + ": " + n + " (" + (a.getLong("size") >> 20) + " MB, " + r.optString("published_at", "").replaceAll("T.*", "") + ")", a.getString("browser_download_url"), n});
                        }
                    }
                } catch (Exception e) { failed.append(repo).append(' '); }
            }
            post(() -> {
                if (got.isEmpty()) { stepText.setText("No Turnip drivers found" + (failed.length() > 0 ? " (could not reach " + failed.toString().trim() + ")" : "") + "."); return; }
                String[] names = new String[got.size()]; for (int i = 0; i < names.length; i++) names[i] = got.get(i)[0];
                stepText.setText("");
                // Pick for me: the newest build that is for every Adreno or for the 7xx (the tablet's 740), not one made for the 8xx / 8 Elite only; mainline Mesa's CI builds first,
                // then K11MCH1's, then the purple fork, as the list was gathered (each repo's newest releases first)
                int best = -1;
                for (int i = 0; i < got.size() && best < 0; i++) {
                    String n = got.get(i)[2].toLowerCase(java.util.Locale.ROOT);
                    if (!n.matches(".*(a8[0-9]{2}|8.?elite|a8xx|gen ?4|8gen4|sm8750).*")) best = i;
                }
                final int pick = best;
                java.util.function.IntConsumer take = w -> {
                    String[] g = got.get(w); File to = new File(getFilesDir(), "drivers/" + g[2]);
                    stepText.setText("Downloading " + g[0] + "...");
                    bg(() -> {
                        try {
                            Download.urlToFile(g[1], to, (what, done, total) -> post(() -> stepText.setText(what + (total > 0 ? " " + done * 100 / total + "%" : ""))));
                            post(() -> { prefs.edit().putString("driver", to.getPath()).apply(); refresh(); stepText.setText(g[2] + " is ready: tap Port to use it. If the game then fails to open or draws wrongly, tap Use the system's and port again."); });
                        } catch (IOException e) { post(() -> stepText.setText("The download failed: " + e.getMessage())); }
                    });
                };
                AlertDialog.Builder d = new AlertDialog.Builder(this).setTitle("Turnip for the Adreno 740: pick one (newest first)").setItems(names, (x, w) -> take.accept(w)).setNegativeButton("Cancel", null);
                if (pick >= 0) d.setPositiveButton("Pick for me", (x, w) -> take.accept(pick));
                d.show();
            });
        });
    }
    private void bg(Runnable r) { new Thread(r).start(); }
    private void post(Runnable r) { ui.post(r); }

    // ---- this app's own updates: the build number in versions.txt against this one's. Android's installer replaces the app and keeps everything in it ----
    private long appChecked;
    private void checkApp() {
        appChecked = System.currentTimeMillis();
        bg(() -> {
            try {
                String[] w = Download.text("versions.txt").split("\\s+");
                long have = getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode(), want = w.length > 2 ? Long.parseLong(w[2]) : 0;
                post(() -> { appButton.setVisibility(want > have ? View.VISIBLE : View.GONE); if (want > have) appButton.setText("Update this app to build " + want); });
            } catch (Exception ignored) { }   // no connection: the next start asks again
        });
    }
    private void updateApp() {
        if (PortService.running) { stepText.setText("The update waits until the port has finished: it would stop the port."); return; }
        if (!Installer.allowed(this)) { stepText.setText("Turn on Allow from this source for this app, then come back and tap the button again."); Installer.askToAllow(this); return; }
        appButton.setEnabled(false); stepText.setText("Downloading the update"); stepText.setTextColor(TEXT);
        bg(() -> {
            try {
                File dir = new File(getCacheDir(), "update"); delete(dir);
                File apk = Download.download("nx2android-builder.apk", dir, (what, done, total) -> post(() -> onStep(what, done, total)));
                post(() -> stepText.setText("Tap Install on the popup. Then open the app again."));
                Installer.install(this, apk, getPackageName(), null);
                post(() -> appButton.setEnabled(true));
            } catch (Exception e) {
                post(() -> { stepText.setText("The update stopped: " + e.getMessage() + ". Tap the button again to try again."); appButton.setEnabled(true); });
            }
        });
    }

    // ---- the Switch 2 engine (Engine.java) ----
    /** What is installed, and with network, whether a newer build is out */
    private void refreshEngine(boolean network) {
        if (engineCard == null || engineCard.getVisibility() != View.VISIBLE) return;
        long have = Engine.installed(this);
        engineState.setText(have > 0 ? "Installed: build " + have + "." : "Not installed yet: about 200 MB to download.");
        engineState.setTextColor(have > 0 ? GOOD : DIM);
        engineButton.setText(have > 0 ? "Check for an engine update" : "Install the engine");
        if (network) bg(() -> {
            try {
                long want = Engine.latest();
                post(() -> { if (have > 0 && want > have) { engineButton.setText("Update the engine to build " + want); engineState.setText("Installed: build " + have + ". Build " + want + " is out."); } });
            } catch (Exception ignored) { }   // no connection, or no build published yet: the button asks again
        });
    }
    private void installEngine() {
        if (!Installer.allowed(this)) { engineState.setText("Turn on Allow from this source for this app, then come back and tap the button again."); Installer.askToAllow(this); return; }
        engineButton.setEnabled(false); engineState.setText("Checking for the engine"); engineState.setTextColor(TEXT);
        bg(() -> {
            try {
                long want = Engine.latest(), have = Engine.installed(this);
                if (have > 0 && want <= have) { post(() -> { engineState.setText("Installed: build " + have + ", the newest."); engineState.setTextColor(GOOD); engineButton.setEnabled(true); }); return; }
                File dir = new File(getCacheDir(), "engine"); delete(dir);
                File apk = Engine.download(dir, (what, done, total) -> post(() -> engineState.setText(what + (total > 0 ? ": " + done * 100 / total + " %" : ""))));
                post(() -> engineState.setText("Tap Install on the popup."));
                Installer.install(this, apk, Engine.PKG, null);
                post(() -> engineButton.setEnabled(true));
            } catch (Exception e) {
                post(() -> { engineState.setText("The engine's download stopped: " + e.getMessage() + ". Tap the button again to try again."); engineButton.setEnabled(true); });
            }
        });
    }
    private void playGame() {
        String f = prefs.getString("folder", null);
        if (f == null) { engineState.setText("Choose the game's folder first."); return; }
        if (Engine.installed(this) == 0) { engineState.setText("Install the engine first."); return; }
        try { startActivity(Engine.play(f)); } catch (RuntimeException e) { engineState.setText("The engine did not start: " + e.getMessage()); }
    }
    private void addToHome() {
        String f = prefs.getString("folder", null);
        if (f == null) { engineState.setText("Choose the game's folder first."); return; }
        if (!Engine.canPin(this)) { engineState.setText("This home screen cannot add icons."); return; }
        String fallback = gameName != null && !gameName.isEmpty() ? gameName : new File(f).getName();
        EditText name = new EditText(this); name.setText(fallback); name.setSingleLine(true); name.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this).setTitle("Add to home screen").setMessage("An icon that starts the game straight away, with the game's own picture. Its name:").setView(name)
            .setPositiveButton("Add", (d, w) -> {
                String label = name.getText().toString().trim();
                try { Engine.pin(this, f, gameId, label.isEmpty() ? fallback : label, Engine.icon(env, gameId)); engineState.setText("Confirm on the popup, and the icon goes on your home screen."); }
                catch (RuntimeException e) { engineState.setText("The icon was not added: " + e.getMessage()); }
            })
            .setNegativeButton("Cancel", null).show();
    }

    // ---- 1. tools ----
    private void downloadTools() {
        setBusy(true); stepText.setText("Checking the tools");
        bg(() -> {
            try {
                Download.Progress p = (what, done, total) -> post(() -> onStep(what, done, total));
                String want = Download.text("versions.txt");
                post(this::checkApp);
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

    // ---- storage: what the builder keeps, and the game's own place in Android's settings ----
    private static File[] list(File d) { File[] k = d.listFiles(); return k == null ? new File[0] : k; }
    private void storageJob(String title, String say, Runnable job) {
        if (PortService.running) { stepText.setText("Wait for the port to finish first."); return; }
        new AlertDialog.Builder(this).setTitle(title).setMessage(say).setNegativeButton("Keep", null).setPositiveButton("Delete", (x, i) -> {
            storageState.setText("Deleting…");
            bg(() -> { try { job.run(); } catch (RuntimeException e) { post(() -> stepText.setText(e.getMessage())); } refreshStorage(); });
        }).show();
    }
    private void deleteTemp() {
        delete(env.tmp); env.tmp.mkdirs(); delete(new File(getCacheDir(), "update"));
        for (File f : list(env.work)) {
            if (f.getName().endsWith(".partial")) delete(f);
            delete(new File(f, "tmp"));
            for (File k : list(new File(f, "shader"))) if (k.getName().endsWith(".partial")) delete(k);
        }
    }
    private void deleteCopies() {   // the game has its own copy: the data packs, and the RomFS images they link to, come back from the game folder at the next port
        deleteTemp();
        for (File f : list(env.work)) {
            if (f.isDirectory() && f.getName().endsWith("-data")) delete(f);
            for (File v : list(new File(f, "extract"))) { File i = new File(v, "RomFS.img"); if (i.isFile()) i.delete(); }
        }
    }
    private void refreshStorage() {
        bg(() -> {
            long tools = 0, work = 0;
            try {
                Process p = new ProcessBuilder("/system/bin/du", "-sk", env.work.getPath(), env.root.getPath()).redirectErrorStream(true).start();
                try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                    for (String l; (l = r.readLine()) != null; ) {
                        String[] c = l.trim().split("\\s+", 2); if (c.length < 2) continue;
                        long kb; try { kb = Long.parseLong(c[0]); } catch (NumberFormatException e) { continue; }
                        if (c[1].equals(env.work.getPath())) work = kb; else if (c[1].equals(env.root.getPath())) tools = kb;
                    }
                }
            } catch (java.io.IOException ignored) { }
            android.os.StatFs fs = new android.os.StatFs(getFilesDir().getPath());
            double free = fs.getAvailableBytes() / 1073741824.0, ported = work / 1048576.0, all = Math.max(0, tools - work) / 1048576.0;
            String game = gamePackage();
            String say = String.format(java.util.Locale.ROOT, "Free on this tablet: %.1f GB\nPort files: %.1f GB\nTools: %.1f GB\nGame: %s", free, ported, all, game == null ? "not installed" : "installed, with its own copy of about 15 GB");
            post(() -> storageState.setText(say));
        });
    }
    private String gamePackage() {
        for (android.content.pm.ApplicationInfo a : getPackageManager().getInstalledApplications(0)) if (a.packageName.startsWith("org.nx2a.t")) return a.packageName;
        return null;
    }
    private void gameSettings() {
        String g = gamePackage(); if (g == null) { stepText.setText("No ported game is installed."); return; }
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + g)));
    }
    private void uninstallGame() {
        String g = gamePackage(); if (g == null) { stepText.setText("No ported game is installed."); return; }
        startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + g)));
    }

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
            else { prefs.edit().putString(pickInto, f.getPath()).apply(); refresh(); }
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
            String id = null, nm = null;
            try { JSONObject o = new JSONObject(out.substring(out.indexOf('{'))); id = o.optString("title_id", null); nm = o.optString("name", null); } catch (Exception ignored) { }
            String gid = id, gnm = nm;
            post(() -> { gameState.setText(t); isSwitch2 = sw; gameId = gid; gameName = gnm; engineCard.setVisibility(sw ? View.VISIBLE : View.GONE); if (sw) refreshEngine(true); });
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
            String out = env.run(env.adb("-s", target, "shell", "for f in /sdcard/Android/data/org.nx2a.t*/files/log*.txt; do echo \"== $f\"; tail -n 3000 \"$f\"; done; cd /sdcard/Android/data/org.nx2a.t*/files && ls -la | grep -iE 'pass|frame|\\.ppm' | head -80; tar czf /sdcard/nx2android/frame-dump.tgz $(ls | grep -iE 'pass|\\.ppm|\\.pam|\\.tga') 2>&1 | tail -2; ls -la /sdcard/nx2android/frame-dump.tgz; echo == exits; dumpsys activity exit-info org.nx2a.t0400a3a01ee4c000 | head -n 120; echo == crash records; dumpsys dropbox --print data_app_native_crash 2>&1 | awk '/^========================================/{n++; o=\"\"} {o=o $0 \"\\n\"} END{printf \"%s\", o}' | head -n 200; echo == crash buffer; logcat -d -b crash -t 200 | tail -n 200; echo == kills; logcat -d -b main -b system -s lowmemorykiller:I lmkd:I | tail -n 40; echo == memory; cat /proc/meminfo | head -n 20; echo == logcat; logcat -d -b main -b system -b crash -t 6000 | grep -iE 'nx2a|dmhost|DEBUG|tombstone|F libc|lowmemory|lmkd|killing|has died|kgsl|adreno|am_kill|AndroidRuntime|oom' | tail -n 500"), 240);
            String saved = "";
            try (java.io.FileWriter w = new java.io.FileWriter(new File("/sdcard/nx2android/game-log.txt"))) { w.write(out); saved = "Saved as nx2android/game-log.txt in your storage."; } catch (java.io.IOException e) { saved = "Copy it with Copy log."; }
            String s = saved;
            post(() -> { logText.setText(out); stepText.setText(s); stepText.setTextColor(GOOD); });
        });
    }

    /** Android's own account of the game's memory while it runs, which the game cannot see from inside: nx2android/mem-watch.txt */
    private void watchMemory() {
        String d = prefs.getString("device", null);
        if (d == null) { stepText.setText("Connect Wireless debugging first."); return; }
        stepText.setText("Watching memory for 7 minutes. Open the game now."); stepText.setTextColor(TEXT);
        String typed = connectPort.getText().toString().trim();
        bg(() -> {
            String port = typed;
            Matcher f = Pattern.compile("_adb-tls-connect\\S*\\s+\\S*?(\\d+\\.\\d+\\.\\d+\\.\\d+):(\\d+)").matcher(env.run(env.adb("mdns", "services"), 10));
            if (f.find()) port = f.group(2);
            String target = validPort(port) ? "127.0.0.1:" + port : d;
            env.run(env.adb("connect", target), 15);
            try (java.io.FileWriter w = new java.io.FileWriter(new File("/sdcard/nx2android/mem-watch.txt"))) {
                for (int i = 0; i < 28; i++) {
                    w.write(env.run(env.adb("-s", target, "shell", "date; dumpsys meminfo org.nx2a.t0400a3a01ee4c000 | grep -E 'Native Heap|Graphics|EGL mtrack|GL mtrack|Other mtrack|Unknown|TOTAL|Gfx dev|\\.so mmap|Other mmap'; dumpsys meminfo | grep -E 'Total RAM|Free RAM|Used RAM|ZRAM|Lost RAM|GPU'; grep -E 'MemAvailable|SwapFree|^Cached|Shmem:|AnonPages|KernelStack|Slab:|Unevictable' /proc/meminfo"), 25));
                    w.write("\n"); w.flush();
                    Thread.sleep(15000);
                }
            } catch (Exception e) { post(() -> stepText.setText("Watching stopped: " + e.getMessage())); return; }
            post(() -> stepText.setText("Saved as nx2android/mem-watch.txt in your storage."));
        });
    }

    // ---- 5. the port ----
    private void port() {
        String f = prefs.getString("folder", null), k = prefs.getString("keys", null), d = prefs.getString("device", null);
        if (!env.ready()) { stepText.setText("Download the tools first."); return; }
        if (f == null) { stepText.setText("Choose the game folder first."); return; }
        if (k == null && !isSwitch2) { stepText.setText("Choose your prod.keys first."); return; }
        boolean install = installAfter.isChecked(), wireless = install && overAdb.isChecked();
        if (wireless && d == null) { stepText.setText("Connect Wireless debugging first, or untick Install over Wireless debugging."); return; }
        if (install && !wireless && !Installer.allowed(this)) {
            stepText.setText("Turn on Allow from this source for this app, then come back and tap Port again."); stepText.setTextColor(TEXT);
            Installer.askToAllow(this); return;
        }
        askToRunInBackground();
        ArrayList<String> a = new ArrayList<>(Arrays.asList("port", f, "--work", env.work.getPath(), "--jobs", "4", "--progress", "json"));
        if (k != null) { a.add("-k"); a.add(k); }
        if (install) { a.add("--install"); if (wireless) { a.add("--device"); a.add(d); } else a.add("--direct"); }
        if (touch.isChecked()) a.add("--touch");
        if (fps60.isChecked()) a.add("--fps60");
        if (boost.isChecked()) a.add("--gpu-boost");
        if (allShaders.isChecked()) a.add("--all-shaders");
        String dr = prefs.getString("driver", null);
        if (dr != null) { a.add("--vulkan-driver"); a.add(dr); }
        String ls = prefs.getString("lsfg", null);
        if (ls != null) { a.add("--lsfg-dll"); a.add(ls); }
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
