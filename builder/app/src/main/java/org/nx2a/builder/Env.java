package org.nx2a.builder;

import android.content.Context;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Where the toolchain lives in the app's storage, and how to run its programs. */
final class Env {
    final File root, usr, ndk, prebuilt, engine, home, tmp, work;

    Env(Context c) {
        root = c.getFilesDir();
        usr = new File(root, "usr"); ndk = new File(root, "ndk"); prebuilt = new File(root, "prebuilt"); engine = new File(root, "nx2android");
        home = new File(root, "home"); tmp = new File(root, "tmp"); work = new File(root, "work");
    }

    boolean ready() { return new File(usr, "bin/python3").exists() && new File(engine, "porter/cli.py").exists() && new File(ndk, "sysroot").isDirectory(); }

    ProcessBuilder process(String... cmd) {
        home.mkdirs(); tmp.mkdirs();
        ProcessBuilder b = new ProcessBuilder(cmd);
        Map<String, String> e = b.environment();
        String p = usr.getPath();
        e.put("PREFIX", p);
        e.put("HOME", home.getPath());
        e.put("TMPDIR", tmp.getPath());
        e.put("PATH", p + "/bin:/system/bin:/system/xbin");
        e.put("LD_LIBRARY_PATH", p + "/lib");
        e.put("PYTHONHOME", p);
        e.put("PYTHONPATH", engine.getPath());
        e.put("PYTHONUNBUFFERED", "1");
        e.put("PYTHONDONTWRITEBYTECODE", "1");
        e.put("NX2A_HOME", new File(home, "nx2android").getPath());
        e.put("NX2A_PREBUILT", prebuilt.getPath());
        e.put("NX2A_NDK_HOST", "linux-aarch64");
        e.put("ANDROID_NDK_HOME", ndk.getPath());
        e.put("DOTNET_ROOT", p + "/lib/dotnet");
        e.put("DOTNET_CLI_TELEMETRY_OPTOUT", "1");
        e.put("DOTNET_SYSTEM_GLOBALIZATION_INVARIANT", "1");
        e.put("DOTNET_EnableWriteXorExecute", "0");
        e.put("SSL_CERT_FILE", p + "/etc/tls/cert.pem");
        b.directory(engine.isDirectory() ? engine : root);
        b.redirectErrorStream(true);
        return b;
    }

    /** adb by its full path: Java looks a bare name up in the app's own PATH, not in the one given to the child. */
    ProcessBuilder adb(String... args) {
        List<String> c = new ArrayList<>(Arrays.asList(new File(usr, "bin/adb").getPath()));
        c.addAll(Arrays.asList(args));
        return process(c.toArray(new String[0]));
    }

    /** python -m porter ... */
    ProcessBuilder porter(String... args) {
        List<String> c = new ArrayList<>(Arrays.asList(new File(usr, "bin/python3").getPath(), "-m", "porter"));
        c.addAll(Arrays.asList(args));
        return process(c.toArray(new String[0]));
    }

    /** Runs a command to its end: its output, or the failure as text. */
    String run(ProcessBuilder b, int timeoutSeconds) {
        StringBuilder out = new StringBuilder();
        try {
            Process p = b.start();
            Thread killer = new Thread(() -> {
                try { Thread.sleep(timeoutSeconds * 1000L); p.destroyForcibly(); } catch (InterruptedException ignored) { }
            });
            killer.setDaemon(true); killer.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                for (String l; (l = r.readLine()) != null; ) out.append(l).append('\n');
            }
            p.waitFor(); killer.interrupt();
        } catch (IOException | InterruptedException e) { out.append("could not run ").append(b.command().get(0)).append(": ").append(e.getMessage()); }
        return out.toString();
    }
}
