package org.nx2a.builder;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.os.Environment;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Locale;

/**
 * The Switch 2 engine: Banana Native's, built by this fork as its own app, org.nx2a.launcher (bananative/, .github/workflows/launcher.yml, the release "launcher").
 * The builder installs and updates it, starts a game on it through its frontend entry (<package>.Launch with the extra "game", the dump's folder: a game that is
 * ready starts at once, one that still needs Prepare opens the engine with the game; bananative/android/FRONTENDS.md), and pins home-screen icons that do the same
 * through PlayActivity, with the profile's own icon (Pokopia's player icon, dm/games/<id>/icon.png) as the per-game apps had it.
 */
final class Engine {
    static final String PKG = "org.nx2a.launcher", LAUNCH = PKG + ".Launch";
    static final String BASE = "https://github.com/DrTron1c/nx2android/releases/download/launcher/", APK = "nx2android-launcher.apk";

    private Engine() { }

    /** The installed engine's build (the launcher workflow's run number), 0 when it is not installed */
    static long installed(Context c) {
        try { return c.getPackageManager().getPackageInfo(PKG, 0).getLongVersionCode(); } catch (PackageManager.NameNotFoundException e) { return 0; }
    }

    /** The newest published build (launcher.txt: commit, run number, version name) */
    static long latest() throws IOException {
        String[] w = Download.textFrom(BASE, "launcher.txt").split("\\s+");
        return w.length > 1 ? Long.parseLong(w[1]) : 0;
    }

    static File download(File dir, Download.Progress p) throws IOException { return Download.downloadFrom(BASE, APK, dir, p); }

    /** Starts the game in the folder on the engine */
    static Intent play(String folder) {
        return new Intent(Intent.ACTION_MAIN).setClassName(PKG, LAUNCH).putExtra("game", folder).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** The engine's own pages (its Home: Prepare, every game's settings, the GPU driver, Lossless.dll), which have no icon in the app list (bananative/android/app/build.gradle) */
    static Intent home() {
        return new Intent(Intent.ACTION_MAIN).setClassName(PKG, "dev.nxnative.android.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** The profile's icon of the game, as its own app had (null when the profile has none: the shortcut then gets this app's icon) */
    static Bitmap icon(Env env, String titleId) {
        if (titleId == null) return null;
        File f = new File(env.engine, "nxnative/dm/games/" + titleId.toUpperCase(Locale.ROOT) + "/icon.png");
        return f.isFile() ? BitmapFactory.decodeFile(f.getPath()) : null;
    }

    // ---- the save from the game's old per-game app (nx2android's own engine, org.nx2a.t<title ID>) to the engine ----
    /** The old per-game app's package (porter's: org.nx2a.t<title ID>) */
    static String oldPkg(String titleId) { return "org.nx2a.t" + titleId.toLowerCase(Locale.ROOT); }

    static boolean oldInstalled(Context c, String titleId) {
        if (titleId == null) return false;
        try { c.getPackageManager().getPackageInfo(oldPkg(titleId), 0); return true; } catch (PackageManager.NameNotFoundException e) { return false; }
    }

    /** The marker the old app writes when it hands its save over (dm/host/save_sync.c save_sync_export): <storage>/nx2android/save-sync/<title ID>/take.txt */
    static File takeFile(String titleId) {
        return new File(Environment.getExternalStorageDirectory(), "nx2android/save-sync/" + titleId.toUpperCase(Locale.ROOT) + "/take.txt");
    }

    /** What the marker says now ("" for none): a new hand-over changes it */
    static String take(String titleId) {
        try { return new String(java.nio.file.Files.readAllBytes(takeFile(titleId).toPath()), java.nio.charset.StandardCharsets.UTF_8); } catch (IOException | RuntimeException e) { return ""; }
    }

    /**
     * Starts the old app in its export mode (front_android.c save_export: its committed save into the sync folder with the marker, a toast, and the app ends without
     * starting the game). Both apps' background processes end first: the old app's, so it starts fresh and no game of it is running; the engine's, so its next start
     * mounts the save again and takes the one handed over (a running game would keep its own)
     */
    static void export(Context c, String titleId) {
        ActivityManager am = c.getSystemService(ActivityManager.class);
        if (am != null) { am.killBackgroundProcesses(oldPkg(titleId)); am.killBackgroundProcesses(PKG); }
        Intent i = c.getPackageManager().getLaunchIntentForPackage(oldPkg(titleId));
        if (i == null) throw new IllegalStateException("the old app has no start screen");
        i.putExtra("org.nx2a.EXPORT_SAVE", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        c.startActivity(i);
    }

    static boolean canPin(Context c) {
        ShortcutManager sm = c.getSystemService(ShortcutManager.class);
        return sm != null && sm.isRequestPinShortcutSupported();
    }

    /** Asks the home screen to pin an icon that starts the game (the home screen shows its own confirmation); one already pinned is updated in place */
    static void pin(Context c, String folder, String titleId, String label, Bitmap icon) {
        ShortcutManager sm = c.getSystemService(ShortcutManager.class);
        Intent i = new Intent(Intent.ACTION_MAIN).setClassName(c, PlayActivity.class.getName()).putExtra("game", folder);
        String id = "game-" + (titleId != null ? titleId.toUpperCase(Locale.ROOT) : Integer.toHexString(folder.hashCode()));
        ShortcutInfo info = new ShortcutInfo.Builder(c, id)
                .setShortLabel(label.length() > 25 ? label.substring(0, 24) + "…" : label)
                .setLongLabel(label)
                .setIcon(icon != null ? Icon.createWithBitmap(icon) : Icon.createWithResource(c, R.drawable.ic_app))
                .setIntent(i)
                .build();
        if (!sm.requestPinShortcut(info, null)) throw new IllegalStateException("the home screen did not take the icon");
        sm.updateShortcuts(Collections.singletonList(info));   // already pinned: a new name or picture follows
    }
}
