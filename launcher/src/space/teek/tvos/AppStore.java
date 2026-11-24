package space.teek.tvos;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Lists leanback apps and persists the user's order and hidden set. */
final class AppStore {

    static final class App {
        final String key;          // flattened component name
        final String pkg;
        final String label;
        final Intent launch;
        final Drawable banner;     // may be null
        final Drawable icon;

        App(String key, String pkg, String label, Intent launch, Drawable banner, Drawable icon) {
            this.key = key; this.pkg = pkg; this.label = label;
            this.launch = launch; this.banner = banner; this.icon = icon;
        }
    }

    /** First-run order: the streaming apps people open most, then everything else A–Z. */
    private static final List<String> PREFERRED = Arrays.asList(
            "com.netflix.ninja",
            "com.google.android.youtube.tv",
            "com.amazon.amazonvideo.livingroom",
            "in.startv.hotstar",
            "com.apple.atve.androidtv.appletv",
            "com.spotify.tv.android",
            "com.sonyliv",
            "com.graymatrix.did",
            "com.jio.media.stb.ondemand",
            "tv.airtel.xstream.tvapp",
            "com.google.android.youtube.tvmusic");

    private static final String SELF = "space.teek.tvos";
    /** Virtual app: switches to the PS5's HDMI input. */
    static final String PS_KEY = "teek:ps5";
    static final int DOCK_MAX = 5;

    private final Context ctx;
    private final SharedPreferences prefs;

    AppStore(Context ctx) {
        this.ctx = ctx;
        this.prefs = ctx.getSharedPreferences("teek", Context.MODE_PRIVATE);
    }

    /** All launchable apps, ordered; hidden apps excluded unless includeHidden. */
    List<App> load(boolean includeHidden) {
        PackageManager pm = ctx.getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> infos = pm.queryIntentActivities(q, 0);

        Map<String, App> byKey = new HashMap<>();
        for (ResolveInfo ri : infos) {
            String pkg = ri.activityInfo.packageName;
            if (SELF.equals(pkg)) continue;
            String cls = ri.activityInfo.name;
            String key = pkg + "/" + cls;
            Intent launch = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setClassName(pkg, cls)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            Drawable banner = BrandBanner.forPackage(ctx, pkg);   // cleaner art for weak banners
            if (banner == null) banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            Drawable icon = ri.loadIcon(pm);
            String label = String.valueOf(ri.loadLabel(pm));
            byKey.put(key, new App(key, pkg, label, launch, banner, icon));
        }

        if (Actions.hasPlayStation(ctx))
            byKey.put(PS_KEY, new App(PS_KEY, "teek.ps5", "PlayStation 5", null, new PsBanner(ctx), null));

        List<String> order = getOrder();
        Set<String> hidden = getHidden();
        List<App> out = new ArrayList<>();
        for (String k : order) {
            App a = byKey.remove(k);
            if (a != null && (includeHidden || !hidden.contains(k))) out.add(a);
        }
        // Newly installed apps: preferred ones first, the rest alphabetically, appended.
        List<App> rest = new ArrayList<>(byKey.values());
        Collections.sort(rest, (a, b) -> {
            int pa = rank(a.pkg), pb = rank(b.pkg);
            if (pa != pb) return Integer.compare(pa, pb);
            return a.label.compareToIgnoreCase(b.label);
        });
        for (App a : rest) if (includeHidden || !hidden.contains(a.key)) out.add(a);

        if (!includeHidden) {
            List<String> keys = new ArrayList<>();
            for (App a : out) keys.add(a.key);
            // keep hidden ones in the stored order so un-hiding restores position
            for (String k : order) if (hidden.contains(k) && !keys.contains(k)) keys.add(k);
            setOrder(keys);
            if (!prefs.contains("dock")) seedDock(out);
        }
        return out;
    }

    /** The app's banner at the highest density it ships (for the full-screen launch zoom). */
    static Drawable hiResBanner(Context c, App app) {
        try {
            PackageManager pm = c.getPackageManager();
            android.content.pm.ActivityInfo ai = pm.getActivityInfo(app.launch.getComponent(), 0);
            int id = ai.banner != 0 ? ai.banner : ai.applicationInfo.banner;
            if (id == 0) return null;
            return pm.getResourcesForApplication(ai.applicationInfo)
                    .getDrawableForDensity(id, android.util.DisplayMetrics.DENSITY_XXXHIGH, null);
        } catch (Exception e) {
            return null;
        }
    }

    /** First run: the four top streaming apps plus the PS5. */
    private void seedDock(List<App> apps) {
        List<String> d = new ArrayList<>();
        for (App a : apps) if (!PS_KEY.equals(a.key) && d.size() < DOCK_MAX - 1) d.add(a.key);
        if (Actions.hasPlayStation(ctx)) d.add(PS_KEY);
        setDock(d);
    }

    List<String> getDock() {
        List<String> l = new ArrayList<>();
        for (String k : prefs.getString("dock", "").split(",")) if (!k.isEmpty()) l.add(k);
        return l;
    }

    void setDock(List<String> keys) {
        prefs.edit().putString("dock", String.join(",", keys)).putInt("rev", rev() + 1).apply();
    }

    /** @return false if the Dock is already full. */
    boolean addToDock(String key) {
        List<String> d = getDock();
        if (d.contains(key)) return true;
        if (d.size() >= DOCK_MAX) return false;
        d.add(key);
        setDock(d);
        return true;
    }

    void removeFromDock(String key) {
        List<String> d = getDock();
        d.remove(key);
        setDock(d);
    }

    void replaceInDock(String old, String key) {
        List<String> d = getDock();
        int i = d.indexOf(old);
        if (i < 0) return;
        d.remove(key);
        d.set(d.indexOf(old), key);
        setDock(d);
    }

    void moveInDock(String key, int delta) {
        List<String> d = getDock();
        int i = d.indexOf(key);
        if (i < 0) return;
        int j = Math.max(0, Math.min(d.size() - 1, i + delta));
        d.remove(i);
        d.add(j, key);
        setDock(d);
    }

    /** Bumped on every user change, so other screens know to refresh. */
    int rev() { return prefs.getInt("rev", 0); }

    private static int rank(String pkg) {
        int i = PREFERRED.indexOf(pkg);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    List<String> getOrder() {
        String s = prefs.getString("order", "");
        List<String> l = new ArrayList<>();
        for (String k : s.split(",")) if (!k.isEmpty()) l.add(k);
        return l;
    }

    void setOrder(List<String> keys) {
        String v = String.join(",", keys);
        if (v.equals(prefs.getString("order", ""))) return;   // unchanged: don't trigger refreshes
        prefs.edit().putString("order", v).putInt("rev", rev() + 1).apply();
    }

    Set<String> getHidden() {
        return new HashSet<>(prefs.getStringSet("hidden", Collections.emptySet()));
    }

    void setHidden(String key, boolean hide) {
        Set<String> h = getHidden();
        if (hide) h.add(key); else h.remove(key);
        prefs.edit().putStringSet("hidden", h).putInt("rev", rev() + 1).apply();
    }

    /**
     * Moves key by delta positions among the apps the user can actually see on this screen
     * (e.g. the Home grid excludes Dock apps), so every press visibly moves the tile.
     * Integer.MIN_VALUE moves it to the front.
     */
    void moveWithin(String key, int delta, List<String> visible) {
        int i = visible.indexOf(key);
        if (i < 0) return;
        int j = delta == Integer.MIN_VALUE ? 0 : Math.max(0, Math.min(visible.size() - 1, i + delta));
        if (i == j) return;
        String anchor = visible.get(j);
        List<String> order = getOrder();
        order.remove(key);
        int a = order.indexOf(anchor);
        if (a < 0) return;
        order.add(j > i ? a + 1 : a, key);
        setOrder(order);
    }

    /** Moves key by delta positions within the stored order. */
    void move(String key, int delta) {
        List<String> order = getOrder();
        int i = order.indexOf(key);
        if (i < 0) return;
        int j = Math.max(0, Math.min(order.size() - 1, delta == Integer.MIN_VALUE ? 0 : i + delta));
        order.remove(i);
        order.add(j, key);
        setOrder(order);
    }
}
