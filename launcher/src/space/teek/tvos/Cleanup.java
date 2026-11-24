package space.teek.tvos;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Closes apps sitting in the background to free memory (no root: ActivityManager's
 * killBackgroundProcesses, which never touches the app on screen). Anything playing media is
 * left alone. Used by the Control Center's Clean Up button and when the TV goes idle.
 */
final class Cleanup {
    private Cleanup() { }

    private static volatile boolean busy;

    /**
     * The teekOS cleaner (header gauge / Control Center): closes every other app except teekOS and
     * whatever is playing, and clears apps' cache (junk) files. Uses the TV's own debugging service
     * (LocalAdb) where it's on: the TV asks once to allow it. Elsewhere it closes background apps.
     */
    static void button(Context c) {
        if (busy) return;
        busy = true;
        Handler main = new Handler(Looper.getMainLooper());
        SharedPreferences p = ControlCenter.prefs(c);
        GlassToast.show(c, R.drawable.ic_speed, "Cleaning up…", 1800);
        new Thread(() -> {   // everything here touches the network: never on the main thread
            String msg;
            try {
                boolean adb = LocalAdb.available();
                android.util.Log.i("TEEKCLEAN", "clean pressed; debugging service " + (adb ? "on" : "off"));
                if (!adb) throw new java.io.IOException("debugging service off");
                if (!p.getBoolean("adb_ok", false))
                    GlassToast.show(c, R.drawable.ic_speed, "Allow teekOS on the TV prompt · tick Always allow", 8000);
                msg = deep(c);
                p.edit().putBoolean("adb_ok", true).apply();
            } catch (Exception e) {
                android.util.Log.i("TEEKCLEAN", "deep clean unavailable: " + e);
                ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
                long before = free(am);
                run(c, false);
                try { Thread.sleep(1200); } catch (InterruptedException ignored) { }
                long mb = Math.max(0, free(am) - before) >> 20;
                msg = mb > 0 ? "Cleaned · freed " + mb + " MB" : "Already clean";
            }
            final String m = msg;
            main.post(() -> { busy = false; GlassToast.show(c, R.drawable.ic_speed, m, 4500); });
        }, "teek-clean").start();
    }

    /** Force-stop running apps (not teekOS, not what's playing) and trim every app's cache. */
    private static String deep(Context c) throws java.io.IOException {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        long memBefore = free(am), diskBefore = disk();
        Set<String> keep = playing(c);
        keep.add(c.getPackageName());
        // Keep the app on screen (Control Center opened over it): clean everything else.
        String top = LocalAdb.shell(c, "dumpsys activity activities | grep -m1 mResumedActivity", 30000);
        java.util.regex.Matcher fm = java.util.regex.Pattern.compile(" ([\\w.]+)/").matcher(top);
        if (fm.find()) keep.add(fm.group(1));
        String ps = LocalAdb.shell(c, "ps -A -o NAME", 30000);
        Set<String> running = new HashSet<>();
        for (String line : ps.split("\n")) running.add(line.trim().split(":")[0]);
        StringBuilder cmd = new StringBuilder();
        int closed = 0;
        for (String pkg : candidates(c)) {
            if (keep.contains(pkg) || !running.contains(pkg)) continue;
            cmd.append("am force-stop ").append(pkg).append("; ");
            closed++;
        }
        cmd.append("pm trim-caches 1000G");
        LocalAdb.shell(c, cmd.toString(), 30000);
        try { Thread.sleep(1200); } catch (InterruptedException ignored) { }
        long mem = Math.max(0, free(am) - memBefore) >> 20, junk = Math.max(0, disk() - diskBefore) >> 20;
        return "Cleaned · " + closed + (closed == 1 ? " app" : " apps") + " closed · " + mem + " MB memory · " + junk + " MB junk";
    }

    private static long disk() {
        android.os.StatFs f = new android.os.StatFs(android.os.Environment.getDataDirectory().getPath());
        return f.getAvailableBytes();
    }

    /** Apps a person opens (launcher entries) plus the stock launcher. */
    private static Set<String> candidates(Context c) {
        PackageManager pm = c.getPackageManager();
        Set<String> apps = new HashSet<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0))
                apps.add(r.activityInfo.packageName);
        }
        ComponentName stock = StockHome.get(c);
        if (stock != null) apps.add(stock.getPackageName());
        // Never: core system apps (system uid: tuner, Live TV), the Play Store and Play services
        // (updates), Settings, and the keyboard.
        apps.removeIf(p -> {
            if (NEVER.contains(p)) return true;
            try { return pm.getApplicationInfo(p, 0).uid < android.os.Process.FIRST_APPLICATION_UID; }
            catch (Exception e) { return true; }
        });
        return apps;
    }

    private static final Set<String> NEVER = new HashSet<>(java.util.Arrays.asList(
            "com.android.vending", "com.google.android.gms", "com.google.android.gsf",
            "com.android.tv.settings", "com.android.settings", "com.google.android.inputmethod.latin",
            "com.google.android.tvrecommendations", "com.google.android.katniss"));

    /** Turn the Home hook back on if something killed it (Android disables a killed a11y service). */
    static void healHook(Context c) {
        String me = c.getPackageName() + "/.HomeKeyService";
        String on = android.provider.Settings.Secure.getString(c.getContentResolver(), android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (on != null && on.contains(c.getPackageName() + "/")) return;
        String next = on == null || on.isEmpty() ? me : on + ":" + me;
        if (c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED) {
            android.provider.Settings.Secure.putString(c.getContentResolver(), android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next);
            android.provider.Settings.Secure.putInt(c.getContentResolver(), android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            android.util.Log.i("TEEKCLEAN", "Home hook re-enabled");
        } else if (ControlCenter.prefs(c).getBoolean("adb_ok", false)) {
            new Thread(() -> {
                try {
                    LocalAdb.shell(c, "settings put secure enabled_accessibility_services " + next
                            + "; settings put secure accessibility_enabled 1", 5000);
                } catch (Exception ignored) { }
            }).start();
        }
    }

    /** Close background apps; {@code report} shows "Freed N MB" afterwards. */
    static void run(Context c, boolean report) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        long before = free(am);
        Set<String> keep = playing(c);
        keep.add(c.getPackageName());
        PackageManager pm = c.getPackageManager();
        Set<String> apps = new HashSet<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            List<ResolveInfo> l = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0);
            for (ResolveInfo r : l) apps.add(r.activityInfo.packageName);
        }
        ComponentName stock = StockHome.get(c);   // the stock launcher is the biggest idle app
        if (stock != null) apps.add(stock.getPackageName());
        int n = 0;
        for (String p : apps) {
            if (keep.contains(p)) continue;
            try { am.killBackgroundProcesses(p); n++; }
            catch (Exception e) { android.util.Log.w("TEEKCLEAN", p + ": " + e); }
        }
        android.util.Log.i("TEEKCLEAN", "asked to close " + n + " of " + apps.size() + " apps; kept " + keep);
        if (!report) return;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            long mb = Math.max(0, free(am) - before) / (1024 * 1024);
            GlassToast.show(c, R.drawable.ic_speed, mb > 0 ? "Cleaned up · freed " + mb + " MB" : "Already clean", 3500);
        }, 1500);
    }

    private static long free(ActivityManager am) {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem;
    }

    /** Packages playing media right now (needs Now Playing access; otherwise nothing is known). */
    private static Set<String> playing(Context c) {
        Set<String> s = new HashSet<>();
        try {
            MediaSessionManager msm = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
            for (MediaController m : msm.getActiveSessions(new ComponentName(c, MediaListener.class))) {
                PlaybackState st = m.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) s.add(m.getPackageName());
            }
        } catch (Exception ignored) { }
        return s;
    }
}
