package space.teek.tvos;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.List;

/**
 * The TV's own launcher (Google TV, stock Android TV, or a TV maker's), found at runtime so teekOS
 * works on any Android TV: the Home hook watches for it, and "Google TV" in teekOS opens it.
 */
final class StockHome {
    private StockHome() { }

    /** Not real home screens: setup wizards and the Settings fallback. */
    private static final String[] SKIP = {"setupwraith", "tungsten", "provision", "com.android.tv.settings", "com.android.settings"};
    private static ComponentName cached;

    static ComponentName get(Context c) {
        if (cached != null) return cached;
        PackageManager pm = c.getPackageManager();
        List<ResolveInfo> l = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_ALL);
        ResolveInfo best = null;
        for (ResolveInfo r : l) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(c.getPackageName())) continue;
            boolean skip = false;
            for (String s : SKIP) if (pkg.contains(s)) skip = true;
            if (skip) continue;
            if (best == null || r.priority > best.priority) best = r;
        }
        if (best != null) cached = new ComponentName(best.activityInfo.packageName, best.activityInfo.name);
        return cached;
    }

    /** Launch the stock home screen; false if none was found. */
    static boolean open(Context c) {
        ComponentName h = get(c);
        if (h == null) return false;
        try {
            c.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setComponent(h)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Exception e) { return false; }
    }
}
