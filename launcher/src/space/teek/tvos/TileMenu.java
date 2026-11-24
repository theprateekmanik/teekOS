package space.teek.tvos;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Hold-OK / Menu options for a tile, including Dock customisation, as a Glass menu. */
final class TileMenu {
    private TileMenu() { }

    static void show(Activity a, AppStore store, TileView t, Runnable move, Runnable changed) {
        String key = t.app.key;
        boolean inDock = store.getDock().contains(key);
        GlassMenu m = new GlassMenu(t.app.label, inDock ? "In your Dock" : null);

        m.add("Move", move);   // back on the screen with chevrons around the tile
        if (inDock) {
            m.destructive("Remove from Dock", then(() -> store.removeFromDock(key), changed));
            m.destructive("Hide App", then(() -> store.setHidden(key, true), changed));
        } else {
            m.add("Add to Dock", () -> {
                if (store.addToDock(key)) changed.run(); else replaceInDock(a, store, t, changed);
            });
            m.add("Show Hidden Apps", () -> showHidden(a, store, changed));
            if (!AppStore.PS_KEY.equals(key)) {
                m.add("App Info", () -> a.startActivity(
                        new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + t.app.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            }
            m.destructive("Hide App", then(() -> store.setHidden(key, true), changed));
        }
        m.show(a);
    }

    /** Dock is full: pick which Dock app to swap out. */
    private static void replaceInDock(Activity a, AppStore store, TileView t, Runnable changed) {
        List<String> dock = store.getDock();
        List<AppStore.App> all = store.load(true);
        GlassMenu m = new GlassMenu("Dock is full", "Replace an app with " + t.app.label);
        for (String k : dock) {
            String name = k;
            for (AppStore.App x : all) if (x.key.equals(k)) name = x.label;
            m.add(name, then(() -> store.replaceInDock(k, t.app.key), changed));
        }
        m.show(a);
    }

    private static void showHidden(Activity a, AppStore store, Runnable changed) {
        java.util.Set<String> hidden = store.getHidden();
        List<AppStore.App> h = new ArrayList<>();
        for (AppStore.App x : store.load(true)) if (hidden.contains(x.key)) h.add(x);
        if (h.isEmpty()) {
            Toast.makeText(a, "No hidden apps", Toast.LENGTH_SHORT).show();
            return;
        }
        GlassMenu m = new GlassMenu("Hidden Apps", "Choose one to show again");
        for (AppStore.App x : h) m.add(x.label, then(() -> store.setHidden(x.key, false), changed));
        m.show(a);
    }

    private static Runnable then(Runnable r, Runnable changed) {
        return () -> { r.run(); changed.run(); };
    }
}
