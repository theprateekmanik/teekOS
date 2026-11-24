package space.teek.tvos;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Recommendations that apps publish to the system TV provider (the same rows Google TV shows
 * as "Continue watching" and app channels). Read with READ_TV_LISTINGS only: no private data,
 * nothing from Google TV itself. Continue-watching first, then each app's channel, round-robin.
 */
final class Recs {
    private Recs() { }

    static final class Rec {
        final String pkg, title, art, intent;
        final boolean resume;
        String desc, year, genre, episode;
        long duration, position;
        Rec(String pkg, String title, String art, String intent, boolean resume) {
            this.pkg = pkg; this.title = title; this.art = art; this.intent = intent; this.resume = resume;
        }

        /** "2024 · Drama · 42 min left" from whatever the app filled in. */
        String details() {
            List<String> d = new ArrayList<>();
            if (year != null && !year.isEmpty() && !year.equals("0")) d.add(year);
            if (episode != null && !episode.isEmpty()) d.add(episode);
            if (genre != null && !genre.isEmpty()) d.add(genre.split(",")[0].trim());
            if (resume && duration > 0 && position > 0 && position < duration) {
                d.add(Math.max(1, (duration - position) / 60000) + " min left");
            } else if (duration > 0) d.add(duration / 60000 + " min");
            return String.join("  ·  ", d);
        }
    }

    /** Promo-only or non-video sources: OnePlus's OxygenPlay ads and music. */
    private static final Set<String> SKIP = new HashSet<>(java.util.Arrays.asList(
            "com.oneplus.tv.launcher", "com.spotify.tv.android"));
    /** Titles kept per app. */
    static final int PER_APP = 8;

    private static final ExecutorService IO = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    interface Got<T> { void got(T t); }

    /** Each app's titles, Continue Watching first. Apps that publish nothing are absent. */
    static void load(Context c, Got<Map<String, List<Rec>>> done) {
        Context app = c.getApplicationContext();
        IO.execute(() -> {
            Map<String, List<Rec>> l;
            try { l = query(app); } catch (Exception e) { l = new HashMap<>(); }
            final Map<String, List<Rec>> out = l;
            MAIN.post(() -> done.got(out));
        });
    }

    private static Map<String, List<Rec>> query(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, Boolean> usable = new HashMap<>();
        Map<String, List<Rec>> byApp = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();

        try (Cursor k = c.getContentResolver().query(Uri.parse("content://android.media.tv/watch_next_program"),
                null, null, null, "last_engagement_time_utc_millis DESC")) {
            while (k != null && k.moveToNext()) {
                Rec r = row(k, true);
                if (ok(r, pm, usable, seen)) add(byApp, r);
            }
        }
        // Preview channels the user hasn't hidden (fall back to all if none are marked browsable).
        Set<Long> browsable = new HashSet<>();
        try (Cursor ch = c.getContentResolver().query(Uri.parse("content://android.media.tv/channel"),
                new String[]{"_id", "browsable"}, null, null, null)) {
            while (ch != null && ch.moveToNext()) if (ch.getInt(1) == 1) browsable.add(ch.getLong(0));
        }
        try (Cursor p = c.getContentResolver().query(Uri.parse("content://android.media.tv/preview_program"),
                null, null, null, "weight DESC")) {
            int chCol = p == null ? -1 : p.getColumnIndex("channel_id");
            while (p != null && p.moveToNext()) {
                if (!browsable.isEmpty() && chCol >= 0 && !browsable.contains(p.getLong(chCol))) continue;
                Rec r = row(p, false);
                if (ok(r, pm, usable, seen)) add(byApp, r);
            }
        }
        return byApp;
    }

    private static void add(Map<String, List<Rec>> m, Rec r) {
        List<Rec> l = m.computeIfAbsent(r.pkg, x -> new ArrayList<>());
        if (l.size() < PER_APP) l.add(r);
    }

    private static Rec row(Cursor c, boolean resume) {
        String art = str(c, "poster_art_uri");
        if (art == null || !art.startsWith("http")) art = str(c, "thumbnail_uri");
        // Cloudinary-style CDNs (e.g. JioHotstar) serve small renditions: ask the same CDN for full width.
        if (art != null && art.contains("/upload/")) art = art.replaceFirst("([/,])w_\\d+", "$1w_1920");
        Rec r = new Rec(str(c, "package_name"), str(c, "title"), art, str(c, "intent_uri"), resume);
        r.desc = str(c, "short_description");
        r.year = str(c, "release_date");
        if (r.year != null && r.year.length() > 4) r.year = r.year.substring(0, 4);
        r.genre = str(c, "genre");
        String s = str(c, "season_display_number"), e = str(c, "episode_display_number");
        if (s != null && e != null) r.episode = "S" + s + " E" + e;
        r.duration = num(c, "duration_millis");
        r.position = num(c, "last_playback_position_millis");
        return r;
    }

    private static boolean ok(Rec r, PackageManager pm, Map<String, Boolean> usable, Set<String> seen) {
        if (r.pkg == null || r.title == null || r.title.isEmpty() || r.intent == null) return false;
        if (r.art == null || !r.art.startsWith("http") || SKIP.contains(r.pkg)) return false;
        // Netflix's "Watch Free Trailers" promo isn't a title.
        if ("com.netflix.ninja".equals(r.pkg) && "Watch Free Trailers".equalsIgnoreCase(r.title.trim())) return false;
        Boolean u = usable.get(r.pkg);
        if (u == null) {
            u = pm.getLeanbackLaunchIntentForPackage(r.pkg) != null || pm.getLaunchIntentForPackage(r.pkg) != null;
            usable.put(r.pkg, u);
        }
        return u && seen.add(r.pkg + "\n" + r.title);
    }

    private static long num(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        try { return i < 0 || c.isNull(i) ? 0 : c.getLong(i); } catch (Exception e) { return 0; }
    }

    private static String str(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 ? null : c.getString(i);
    }

    /** The deep link as an Intent, kept inside the app that published it. */
    static Intent intent(Rec r) {
        Intent i;
        try {
            i = r.intent.startsWith("intent:") ? Intent.parseUri(r.intent, Intent.URI_INTENT_SCHEME)
                    : new Intent(Intent.ACTION_VIEW, Uri.parse(r.intent));
        } catch (Exception e) { return null; }
        if (i.getComponent() == null && i.getPackage() == null) i.setPackage(r.pkg);
        i.setSelector(null);
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    // ---------------------------------------------------------------- artwork

    /** About 10 MB: every card thumbnail plus the last few backgrounds. */
    private static final android.util.LruCache<String, Bitmap> MEM = new android.util.LruCache<String, Bitmap>(10 << 20) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    static void trimMemory() { MEM.evictAll(); }

    /** Artwork decoded to about w×h (RGB_565), cached on disk and in a small memory LRU. */
    static void art(Context c, String url, int w, int h, Got<Bitmap> done) {
        String key = url + "@" + w;
        Bitmap hit;
        hit = MEM.get(key);
        if (hit != null) { done.got(hit); return; }
        File dir = new File(c.getCacheDir(), "recs");
        IO.execute(() -> {
            Bitmap b = null;
            try {
                dir.mkdirs();
                File f = new File(dir, Integer.toHexString(url.hashCode()));
                if (!f.exists()) {
                    HttpURLConnection k = (HttpURLConnection) new URL(url).openConnection();
                    k.setConnectTimeout(6000);
                    k.setReadTimeout(10000);
                    File tmp = new File(dir, f.getName() + ".part");
                    try (InputStream in = k.getInputStream(); FileOutputStream o = new FileOutputStream(tmp)) {
                        byte[] buf = new byte[16384];
                        for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
                    } finally { k.disconnect(); }
                    tmp.renameTo(f);
                }
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getPath(), o);
                int s = 1;
                while (o.outWidth / (s * 2) >= w && o.outHeight / (s * 2) >= h) s *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = s;
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                b = BitmapFactory.decodeFile(f.getPath(), o);
                if (b != null && b.getWidth() > w * 1.3f) {
                    Bitmap sc = Bitmap.createScaledBitmap(b, w, Math.round(w * b.getHeight() / (float) b.getWidth()), true);
                    if (sc != b) b.recycle();
                    b = sc;
                }
            } catch (Exception ignored) { }
            final Bitmap out = b;
            if (out != null) MEM.put(key, out);
            MAIN.post(() -> done.got(out));
        });
        trimDisk(dir);
    }

    private static long lastTrim;
    private static void trimDisk(File dir) {
        long now = System.currentTimeMillis();
        if (now - lastTrim < 3600_000) return;
        lastTrim = now;
        IO.execute(() -> {
            File[] fs = dir.listFiles();
            if (fs != null) for (File f : fs) if (now - f.lastModified() > 3 * 86400_000L) f.delete();
        });
    }
}
