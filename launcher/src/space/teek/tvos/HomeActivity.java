package space.teek.tvos;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class HomeActivity extends Activity {

    static final int COLS = 5;
    /** Rows of apps under the Dock; everything else lives in All Apps. */
    private static final int HOME_ROWS = 2;

    private AppStore store;
    private float dp;
    private int side, shelfPad, gap, tileW, tileH;

    private Backdrop backdrop;
    /** Cinematic Dashboard: full-screen artwork + recommendations above the Dock (prefs "cinematic"). */
    private boolean cinematic;
    /** The panel's artwork (the screen behind keeps the ambient Backdrop). */
    private Cinema panelArt;
    private FrameLayout panel;
    private View panelText;
    private TextView recSource, recTitle, recDetails, recDesc, recPage, cta;
    private long recsAt;
    private Map<String, List<Recs.Rec>> recs = new HashMap<>();
    /** App whose titles the panel shows, the title index, and that app's Dock tile. */
    private String panelPkg;
    private int recIdx;
    private TileView panelTile;
    private int overlap, panelW, panelH;
    private android.widget.ImageView recLogo;
    private TextView recLogoText, progLabel;
    private View progRow, progFill;

    /** White marks for the panel's top-left: Simple Icons (CC0) vectors; others use the app name. */
    private static final Map<String, Integer> MARKS = new HashMap<>();
    static {
        MARKS.put("com.netflix.ninja", R.drawable.logo_netflix);
        MARKS.put("com.amazon.amazonvideo.livingroom", R.drawable.logo_primevideo);
        MARKS.put("com.apple.atve.androidtv.appletv", R.drawable.logo_appletv);
        MARKS.put("com.google.android.youtube.tv", R.drawable.logo_youtube);
        MARKS.put("com.google.android.youtube.tvmusic", R.drawable.logo_youtubemusic);
        MARKS.put("com.spotify.tv.android", R.drawable.logo_spotify);
        MARKS.put("tv.airtel.xstream.tvapp", R.drawable.logo_airtel);
    }
    private Smooth scroller;
    private View hero, welcome, dock, allApps;
    private LinearLayout dockRow;
    private TextView clock, date, dockHint, greeting;
    private GridLayout grid;
    private final List<TileView> tiles = new ArrayList<>();
    private TileView lastFocused;
    private boolean dirty;
    private int seenRev = -1;
    /** Keys shown in the Home grid (not the Dock) and in the Dock, in order, for move mode. */
    private final List<String> gridKeys = new ArrayList<>(), dockKeys = new ArrayList<>();
    /** App currently in move mode, or null. */
    private String movingKey;

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            TileView.Art.clear();   // app art may have changed
            if (hasWindowFocus()) rebuild(); else dirty = true;
        }
    };
    private final BroadcastReceiver ticks = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Type.init(this);
        dp = getResources().getDisplayMetrics().density;
        store = new AppStore(this);
        cinematic = ControlCenter.prefs(this).getBoolean("cinematic", true);
        if (cinematic && checkSelfPermission("android.permission.READ_TV_LISTINGS")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.READ_TV_LISTINGS"}, 1);
        }

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        side = (int) (64 * dp);
        shelfPad = (int) (16 * dp);
        gap = (int) (24 * dp);
        tileW = (screenW - 2 * side - 2 * shelfPad - (COLS - 1) * gap) / COLS;
        tileH = Math.round(tileW * 9f / 16f);
        int scrollerTop = (int) (84 * dp);
        int shelfH = tileH + 2 * shelfPad;
        int afterShelf = (int) (30 * dp), peek = (int) (20 * dp);
        // Dock sits low like tvOS, with the next row just peeking in at the bottom edge.
        int heroH = screenH - peek - afterShelf - shelfH - scrollerTop;

        FrameLayout root = new FrameLayout(this);   // window background supplies the black
        backdrop = new Backdrop(this);
        root.addView(backdrop, Ui.match());

        scroller = new Smooth(this);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        column.setPadding(side, 0, side, (int) (90 * dp));

        // Cinematic: the panel runs down behind the Dock's top half; the Dock (added next) overlays it.
        overlap = 0;   // the panel stops short of the Dock
        panelW = screenW - 2 * side;
        panelH = heroH - (int) (22 * dp);
        hero = cinematic ? buildCinemaHero() : buildHero();
        column.addView(hero, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heroH + overlap));

        // The Dock: user-chosen apps on a Liquid Glass shelf.
        FrameLayout shelf = new FrameLayout(this);
        shelf.setClipChildren(false);
        shelf.setClipToPadding(false);
        shelf.setBackground(new Glass(28 * dp, 1.5f * dp, 1f, true));
        shelf.setPadding(shelfPad, shelfPad, shelfPad, shelfPad);
        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setClipChildren(false);
        dockRow.setClipToPadding(false);
        shelf.addView(dockRow);
        dockHint = new TextView(this);
        dockHint.setText("Hold OK on any app and choose Add to Dock");
        dockHint.setTextColor(0x99FFFFFF);
        dockHint.setTextSize(14);
        dockHint.setTypeface(Type.medium);
        shelf.addView(dockHint, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        dock = shelf;
        LinearLayout.LayoutParams shp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, shelfH);
        shp.topMargin = -overlap;
        column.addView(shelf, shp);

        grid = new GridLayout(this);
        grid.setColumnCount(COLS);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setUseDefaultMargins(false);
        grid.setPadding(shelfPad, 0, shelfPad, 0);   // align columns with the Dock
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.topMargin = afterShelf;
        column.addView(grid, glp);

        // All Apps: a glass capsule centred under the two rows.
        TextView all = Ui.pill(this, "All Apps", v -> {
            startActivity(new Intent(this, AllAppsActivity.class));
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        });
        all.setTextSize(15);
        all.setPadding((int) (28 * dp), (int) (10 * dp), (int) (28 * dp), (int) (10 * dp));
        View.OnFocusChangeListener pillFocus = all.getOnFocusChangeListener();
        all.setOnFocusChangeListener((v, f) -> {
            pillFocus.onFocusChange(v, f);
            if (f) {
                lastFocused = null;
                scroller.glideTo(scroller.centerOf(v, 0.62f));
            }
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.gravity = Gravity.CENTER_HORIZONTAL;
        alp.topMargin = (int) (6 * dp);
        alp.leftMargin = 0;
        column.addView(all, alp);
        allApps = all;

        scroller.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        // Content area starts under the header and fades out at its top edge.
        FrameLayout.LayoutParams slp = Ui.match();
        slp.topMargin = scrollerTop;
        scroller.setVerticalFadingEdgeEnabled(true);
        scroller.setFadingEdgeLength((int) (36 * dp));
        root.addView(scroller, slp);
        scroller.setOnScrollChangeListener((v, x, y, ox, oy) -> onScrolled(y));

        root.addView(buildHeader(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        setContentView(root);

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packages, pf);
        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(ticks, tf);
        registerReceiver(cinematicChanged, new IntentFilter(ControlCenter.ACTION_CINEMATIC));
        registerReceiver(ccState, new IntentFilter(ControlCenter.ACTION_STATE));

        rebuild();
        intro();
    }

    @Override protected void onDestroy() {
        unregisterReceiver(packages);
        unregisterReceiver(ticks);
        unregisterReceiver(cinematicChanged);
        unregisterReceiver(ccState);
        super.onDestroy();
    }

    private boolean firstResume = true;

    @Override protected void onResume() {
        super.onResume();
        Cleanup.healHook(this);   // Android turns the hook off if something kills it
        front = true;
        updateClock();
        Actions.settleHome(this);
        if (!firstResume) shuffleGreeting();
        firstResume = false;
        // Rebuild if apps changed, or the Dock/order was edited from All Apps.
        if (dirty || store.rev() != seenRev) { dirty = false; rebuild(); }
        if (cinematic && System.currentTimeMillis() - recsAt > 5 * 60_000) loadRecs();
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (cinematic) loadRecs();
    }

    @Override protected void onStop() {
        super.onStop();
        front = false;
        Actions.endLaunch(this);
    }

    /** A new greeting each time you come back: quick fade out, swap, fade in. */
    private void shuffleGreeting() {
        String next = Greetings.pick(String.valueOf(greeting.getText()));
        greeting.animate().cancel();
        greeting.animate().alpha(0f).setDuration(160).setInterpolator(Motion.EASE).withEndAction(() -> {
            greeting.setText(next);
            greeting.animate().alpha(1f).setDuration(Motion.FADE_MS).setInterpolator(Motion.EASE).start();
        }).start();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) { backdrop.trim(); Recs.trimMemory(); }
    }

    /** Home pressed (from an app or while home): back to the first app at the top. */
    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        // Home always lands at the top (greeting visible); Back from an app keeps your place.
        if (movingKey != null) {
            for (TileView t : tiles) if (t.isMoving()) t.stopMoving();
            movingKey = null;
        }
        if (!tiles.isEmpty()) tiles.get(0).requestFocus();
    }

    @Override public void onBackPressed() {
        if (!tiles.isEmpty() && !tiles.get(0).isFocused()) tiles.get(0).requestFocus();
    }

    // ---------------------------------------------------------------- building

    private View buildHeader() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(side, (int) (30 * dp), side, (int) (12 * dp));
        h.setClipChildren(false);    // focused buttons grow past the header's bounds
        h.setClipToPadding(false);

        TextView mark = new TextView(this);
        SpannableStringBuilder s = new SpannableStringBuilder("teekOS");
        s.setSpan(new TypefaceSpan(Type.bold), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new TypefaceSpan(Type.light), 4, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new ForegroundColorSpan(0xB3FFFFFF), 4, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        mark.setText(s);
        mark.setTextColor(Color.WHITE);
        mark.setTextSize(22);
        mark.setLetterSpacing(-0.02f);
        h.addView(mark);
        // Sleep (cosmos standby) and Power, beside the wordmark.
        View sleep = Ui.iconButton(this, R.drawable.ic_sleep, "Sleep", v -> Actions.sleep(this));
        ((LinearLayout.LayoutParams) sleep.getLayoutParams()).leftMargin = (int) (20 * dp);
        sleep.setId(View.generateViewId());
        sleep.setNextFocusLeftId(sleep.getId());   // left edge of the header: stay put
        h.addView(sleep);
        View power = Ui.iconButton(this, R.drawable.ic_power, "Turn off", v -> Actions.powerOff(this));
        h.addView(power);
        // Minimal mode: the screen previews the mood — standby's night sky for Sleep, near-black for Off.
        if (!cinematic) {
            mood(sleep, "sleep", 0xFF040A1C, 0xFF0A1734, 0xFF1B3570);
            mood(power, "power", 0xFF0C0A0B, 0xFF020202, 0xFF3A1216);
        }

        h.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        // Cleaner: close other apps and clear junk (gauge icon), beside the mode switch.
        h.addView(Ui.iconButton(this, R.drawable.ic_speed, "Clean up", v -> Cleanup.button(this)));
        // The Google TV mark in its glass disc switches the Cinematic Dashboard on/off.
        // (Opening Google TV itself lives in the Settings menu.)
        h.addView(Ui.logoButton(this, getDrawable(R.drawable.logo_google_tv), "Cinematic Dashboard",
                v -> toggleCinematic()));
        h.addView(Ui.iconButton(this, R.drawable.ic_settings, "Control Center", v -> controlCenter()));
        updateClock();
        return h;
    }

    private void mood(View b, String key, int top, int bottom, int glow) {
        View.OnFocusChangeListener base = b.getOnFocusChangeListener();
        b.setOnFocusChangeListener((v, f) -> {
            base.onFocusChange(v, f);
            if (f) { lastFocused = null; backdrop.showTint(key, top, bottom, glow); }
        });
    }

    /** "Welcome back, Teek", centred in the space above the Dock. */
    private View buildHero() {
        FrameLayout f = new FrameLayout(this);
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER_HORIZONTAL);

        date = new TextView(this);
        date.setTextColor(0x99FFFFFF);
        date.setTextSize(8.4f);   // 30% smaller than the original 12sp
        date.setTypeface(Type.regular);
        date.setLetterSpacing(0.2f);
        date.setGravity(Gravity.CENTER);

        TextView title = new TextView(this);
        greeting = title;
        title.setText(Greetings.pick(null));
        title.setSingleLine(true);
        title.setAutoSizeTextTypeUniformWithConfiguration(26, 42, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        title.setTextColor(Color.WHITE);
        title.setTextSize(42);
        title.setTypeface(Type.semibold);
        title.setLetterSpacing(-0.02f);
        title.setGravity(Gravity.CENTER);

        v.addView(date);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (60 * dp));   // fixed box: autosize fits any greeting
        tlp.topMargin = (int) (6 * dp);
        v.addView(title, tlp);
        welcome = v;
        f.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        return f;
    }

    private void rebuild() {
        String keep = movingKey != null ? movingKey : lastFocused != null ? lastFocused.app.key : null;
        dockRow.removeAllViews();
        grid.removeAllViews();
        tiles.clear();
        lastFocused = null;

        List<AppStore.App> apps = store.load(false);
        seenRev = store.rev();
        Map<String, AppStore.App> byKey = new HashMap<>();
        for (AppStore.App a : apps) byKey.put(a.key, a);

        List<AppStore.App> docked = new ArrayList<>();
        for (String k : store.getDock()) if (byKey.containsKey(k)) docked.add(byKey.get(k));
        List<AppStore.App> rest = new ArrayList<>();
        for (AppStore.App a : apps) if (!docked.contains(a)) rest.add(a);
        dockHint.setVisibility(docked.isEmpty() ? View.VISIBLE : View.GONE);
        gridKeys.clear();
        for (int g = 0; g < Math.min(rest.size(), HOME_ROWS * COLS); g++) gridKeys.add(rest.get(g).key);
        dockKeys.clear();
        for (AppStore.App a : docked) dockKeys.add(a.key);

        for (int i = 0; i < docked.size(); i++) {
            TileView t = tile(docked.get(i), false, true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW, tileH);
            lp.rightMargin = i == docked.size() - 1 ? 0 : gap;
            dockRow.addView(t, lp);
        }

        int shown = Math.min(rest.size(), HOME_ROWS * COLS);
        for (int g = 0; g < shown; g++) {
            TileView t = tile(rest.get(g), false, false);   // Home: no name labels
            grid.addView(t, gridParams(g));
        }
        ((TextView) allApps).setText("All Apps  ·  " + apps.size());

        if (tiles.isEmpty()) { allApps.post(allApps::requestFocus); return; }
        TileView target = tiles.get(0);
        if (keep != null) for (TileView t : tiles) if (t.app.key.equals(keep)) target = t;
        final TileView f = target;
        dockRow.post(f::requestFocus);
        if (movingKey != null) dockRow.post(this::resumeMove);

        // Bake every tile's backdrop while idle, so focus changes never wait on it.
        java.util.Iterator<TileView> pending = new ArrayList<>(tiles).iterator();
        android.os.Looper.myQueue().addIdleHandler(() -> {
            if (!pending.hasNext()) return false;
            backdrop.prepare(pending.next());
            return pending.hasNext();
        });
    }

    private GridLayout.LayoutParams gridParams(int g) {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                GridLayout.spec(g / COLS), GridLayout.spec(g % COLS));
        lp.width = tileW;
        lp.height = tileH;
        lp.rightMargin = (g % COLS == COLS - 1) ? 0 : gap;
        lp.bottomMargin = (int) (36 * dp);
        return lp;
    }

    private TileView tile(AppStore.App app, boolean label, boolean inDock) {
        TileView t = new TileView(this, app, tileW, tileH, label);
        t.setOnFocusChangeListener((v, f) -> { if (f) onTileFocused((TileView) v, inDock); });
        t.setOnClickListener(v -> Actions.open(this, (TileView) v));
        t.setOnLongClickListener(v -> { menu((TileView) v); return true; });
        t.setOnKeyListener((v, code, e) -> {
            if (code == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP) {
                menu((TileView) v);
                return true;
            }
            return false;
        });
        tiles.add(t);
        return t;
    }

    private void menu(TileView t) {
        TileMenu.show(this, store, t, () -> { movingKey = t.app.key; resumeMove(); }, this::rebuild);
    }

    /** Put the moving app's tile into move mode, with chevrons for the moves it can make. */
    private void resumeMove() {
        TileView t = null;
        for (TileView x : tiles) if (x.app.key.equals(movingKey)) t = x;
        if (t == null) { movingKey = null; return; }
        final TileView tile = t;
        boolean[] can = moves(tile.app.key);
        tile.requestFocus();
        tile.startMoving(new TileView.Mover() {
            @Override public void move(int dir) { moveTile(tile, dir); }
            @Override public void done() { movingKey = null; }
        }, can[0], can[1], can[2], can[3]);
    }

    /** left, right, up, down — which moves are possible for this app right now. */
    private boolean[] moves(String key) {
        boolean dock = dockKeys.contains(key);
        List<String> list = dock ? dockKeys : gridKeys;
        int i = list.indexOf(key), n = list.size();
        return new boolean[]{i > 0, i < n - 1, !dock && i >= COLS, !dock && i / COLS < (n - 1) / COLS};
    }

    /**
     * One step of move mode: persist the new order, then re-lay out in place (no rebuild) and
     * let Flip spring every tile to its new spot, so neighbours glide aside.
     */
    private void moveTile(TileView t, int dir) {
        String key = t.app.key;
        if (dockKeys.contains(key)) {
            int i = dockKeys.indexOf(key), j = i + (dir == View.FOCUS_LEFT ? -1 : 1);
            if (j < 0 || j >= dockKeys.size()) return;
            store.moveInDock(key, j - i);
            java.util.Map<View, int[]> before = Flip.capture(dockRow);
            dockKeys.remove(i);
            dockKeys.add(j, key);
            dockRow.removeView(t);
            dockRow.addView(t, j);
            for (int k = 0; k < dockRow.getChildCount(); k++) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) dockRow.getChildAt(k).getLayoutParams();
                lp.rightMargin = k == dockRow.getChildCount() - 1 ? 0 : gap;
            }
            dockRow.requestLayout();
            Flip.play(dockRow, before, t);
            t.requestFocus();
        } else {
            int i = gridKeys.indexOf(key), n = gridKeys.size();
            int d = dir == View.FOCUS_LEFT ? -1 : dir == View.FOCUS_RIGHT ? 1 : dir == View.FOCUS_UP ? -COLS : COLS;
            int j = Math.max(0, Math.min(n - 1, i + d));
            if (i == j) return;
            store.moveWithin(key, d, new ArrayList<>(gridKeys));
            java.util.Map<View, int[]> before = Flip.capture(grid);
            gridKeys.remove(i);
            gridKeys.add(j, key);
            for (int k = 0; k < grid.getChildCount(); k++) {
                TileView c = (TileView) grid.getChildAt(k);
                c.setLayoutParams(gridParams(gridKeys.indexOf(c.app.key)));
            }
            Flip.play(grid, before, t);
            scroller.post(() -> scroller.glideTo(scroller.centerOf(t, 0.5f)));
        }
        seenRev = store.rev();   // we've applied this change ourselves
        reorderTiles();
        boolean[] can = moves(key);
        t.updateMoves(can[0], can[1], can[2], can[3]);
    }

    /** Keep `tiles` in on-screen order (Dock, then grid) after in-place moves. */
    private void reorderTiles() {
        List<TileView> next = new ArrayList<>();
        for (int k = 0; k < dockRow.getChildCount(); k++) next.add((TileView) dockRow.getChildAt(k));
        for (String k : gridKeys) for (TileView x : tiles) if (x.app.key.equals(k)) next.add(x);
        tiles.clear();
        tiles.addAll(next);
    }

    // ---------------------------------------------------------------- behaviour

    private void onTileFocused(TileView t, boolean inDock) {
        if (t == lastFocused) return;
        lastFocused = t;
        // In cinematic mode a Dock app with titles takes its colours from the title's art instead.
        List<Recs.Rec> titles = recs.get(t.app.pkg);
        if (!(cinematic && inDock && titles != null && !titles.isEmpty())) backdrop.show(t);
        scroller.glideTo(inDock ? 0 : scroller.centerOf(t, 0.5f));
        if (cinematic && inDock) present(t);
    }

    /** As the grid scrolls up, the greeting fades and the backdrop dims (tvOS behaviour). */
    private void onScrolled(int y) {
        float p = Math.min(1f, y / (float) Math.max(1, hero.getHeight()));
        welcome.setAlpha(Math.max(0f, 1f - p * 2.2f));
        welcome.setTranslationY(-p * 40 * dp);
        backdrop.dim(p);
    }

    private void updateClock() {
        Date now = new Date();
        if (clock != null) {
            boolean h24 = android.text.format.DateFormat.is24HourFormat(this);
            clock.setText(new SimpleDateFormat(h24 ? "HH:mm" : "h:mm a", Locale.US).format(now));
        }
        if (date != null) date.setText(new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())
                .format(now).toUpperCase(Locale.getDefault()));
    }

    // ---------------------------------------------------------------- cinematic

    /** The hook draws the Control Center (so it also works over apps); in-app if the hook is off. */
    private void controlCenter() {
        String on = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (on != null && on.contains(getPackageName() + "/")) {
            sendBroadcast(new Intent(ControlCenter.ACTION_SHOW).setPackage(getPackageName()));
        } else {
            ControlCenter.show(this, android.view.WindowManager.LayoutParams.TYPE_APPLICATION,
                    getWindow().getDecorView().getWindowToken(), () -> sendBroadcast(
                            new Intent(HomeKeyService.ACTION_POWER_OFF).setPackage(getPackageName())));
        }
    }

    /** Real frosted blur, only behind the Control Center's glass (capsule + panel) when it opens over Home. */
    private final List<View> ccBlur = new ArrayList<>();
    private boolean front;
    private final BroadcastReceiver ccState = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            FrameLayout root = (FrameLayout) findViewById(android.R.id.content);
            for (View b : ccBlur) b.animate().alpha(0f).setDuration(Motion.SETTLE_MS).withEndAction(() -> root.removeView(b)).start();
            ccBlur.clear();
            int[] rects = i.getIntArrayExtra("rects");
            float[] radii = i.getFloatArrayExtra("radii");
            if (!i.getBooleanExtra("shown", false) || rects == null || !front) return;
            int sw = root.getWidth(), sh = root.getHeight();
            android.graphics.Bitmap snap = android.graphics.Bitmap.createBitmap(480, 270, android.graphics.Bitmap.Config.ARGB_8888);
            android.view.PixelCopy.request(getWindow(), snap, r -> {
                if (r != android.view.PixelCopy.SUCCESS) return;
                new Thread(() -> {
                    Actions.blur(snap, 4);
                    root.post(() -> {
                        float kx = 480f / sw, ky = 270f / sh;
                        for (int k = 0; k + 3 < rects.length; k += 4) {
                            int l = rects[k], t = rects[k + 1], w = rects[k + 2] - l, h = rects[k + 3] - t;
                            if (w <= 0 || h <= 0) continue;
                            android.graphics.Bitmap part = android.graphics.Bitmap.createBitmap(snap,
                                    Math.max(0, (int) (l * kx)), Math.max(0, (int) (t * ky)),
                                    Math.min(480 - (int) (l * kx), Math.max(1, (int) (w * kx))),
                                    Math.min(270 - (int) (t * ky), Math.max(1, (int) (h * ky))));
                            android.widget.ImageView iv = new android.widget.ImageView(HomeActivity.this);
                            iv.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
                            iv.setImageBitmap(part);
                            float rad = radii != null && k / 4 < radii.length ? radii[k / 4] : 0;
                            iv.setOutlineProvider(new android.view.ViewOutlineProvider() {
                                @Override public void getOutline(View v, android.graphics.Outline o) {
                                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), rad);
                                }
                            });
                            iv.setClipToOutline(true);
                            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
                            lp.leftMargin = l;
                            lp.topMargin = t;
                            iv.setAlpha(0f);
                            root.addView(iv, lp);
                            ccBlur.add(iv);
                            iv.animate().alpha(1f).setDuration(Motion.FADE_MS).start();
                        }
                    });
                }).start();
            }, new android.os.Handler(android.os.Looper.getMainLooper()));
        }
    };

    private final BroadcastReceiver cinematicChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (ControlCenter.prefs(c).getBoolean("cinematic", true) != cinematic) recreate();
        }
    };

    private void toggleCinematic() {
        ControlCenter.prefs(this).edit().putBoolean("cinematic", !cinematic).commit();
        recreate();
    }

    /** Small greeting top-left, then the glass recommendation panel reaching under the Dock. */
    private View buildCinemaHero() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setClipChildren(false);
        v.setClipToPadding(false);

        // No greeting in cinematic mode: the panel takes the space. (Kept detached so the
        // shuffle logic stays shared with Minimal mode.)
        greeting = new TextView(this);
        greeting.setText(Greetings.pick(null));

        panel = new FrameLayout(this);
        float radius = 28 * dp;   // same corner as the Dock
        panel.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View x, android.graphics.Outline o) {
                o.setRoundRect(0, 0, x.getWidth(), x.getHeight(), radius);
            }
        });
        panel.setClipToOutline(true);
        panelArt = new Cinema(this, panelW, panelH, false);
        panel.addView(panelArt, Ui.match());
        View rim = new View(this);
        rim.setBackground(new Glass(radius, 1.5f * dp, 1f, false));
        panel.addView(rim, Ui.match());

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setClipChildren(false);
        text.setClipToPadding(false);
        int pad = (int) (36 * dp);
        text.setPadding(pad, (int) (34 * dp), 0, (int) (30 * dp));
        recSource = label(10, Type.semibold, 0x99FFFFFF);
        recSource.setVisibility(View.GONE);
        recSource.setLetterSpacing(0.14f);
        text.addView(recSource);
        recLogo = new android.widget.ImageView(this);
        recLogo.setAdjustViewBounds(true);
        recLogo.setScaleType(android.widget.ImageView.ScaleType.FIT_START);
        recLogo.setImageTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        text.addView(recLogo, new LinearLayout.LayoutParams((int) (220 * dp), (int) (22 * dp)));
        recLogoText = label(15, Type.bold, Color.WHITE);
        recLogoText.setLetterSpacing(0.02f);
        recLogoText.setVisibility(View.GONE);
        text.addView(recLogoText);
        recTitle = label(34, Type.semibold, Color.WHITE);
        recTitle.setLetterSpacing(-0.02f);
        recTitle.setSingleLine(true);
        recTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams((int) (panelW * 0.55f), ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = (int) (10 * dp);
        text.addView(recTitle, tl);
        recDetails = label(12, Type.medium, 0xB3FFFFFF);
        recDetails.setVisibility(View.GONE);
        text.addView(recDetails);
        recDesc = label(12, Type.regular, 0x99FFFFFF);
        recDesc.setMaxLines(2);
        recDesc.setVisibility(View.GONE);
        recDesc.setEllipsize(android.text.TextUtils.TruncateAt.END);
        recDesc.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams((int) (panelW * 0.42f), ViewGroup.LayoutParams.WRAP_CONTENT);
        dl.topMargin = (int) (8 * dp);
        text.addView(recDesc, dl);
        // Watched so far, in the title's own colour; only when the app reports a position.
        LinearLayout pr = new LinearLayout(this);
        pr.setOrientation(LinearLayout.HORIZONTAL);
        pr.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout track = new FrameLayout(this);
        GradientDrawable tb = new GradientDrawable();
        tb.setCornerRadius(4 * dp);
        tb.setColor(0x40FFFFFF);
        track.setBackground(tb);
        track.setClipToOutline(true);
        track.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        progFill = new View(this);
        track.addView(progFill, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
        pr.addView(track, new LinearLayout.LayoutParams((int) (180 * dp), (int) (4 * dp)));
        progLabel = label(11, Type.medium, 0xB3FFFFFF);
        LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ql.leftMargin = (int) (12 * dp);
        pr.addView(progLabel, ql);
        LinearLayout.LayoutParams prl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        prl.topMargin = (int) (12 * dp);
        text.addView(pr, prl);
        progRow = pr;
        pr.setVisibility(View.GONE);
        text.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        cta = Ui.pill(this, "Play", x -> openRec());
        cta.setTextSize(14);
        cta.setPadding((int) (24 * dp), (int) (9 * dp), (int) (24 * dp), (int) (9 * dp));
        ((LinearLayout.LayoutParams) cta.getLayoutParams()).leftMargin = 0;
        cta.setOnKeyListener((x, code, e) -> ctaKey(code, e));
        row.addView(cta);
        recPage = label(11, Type.medium, 0x80FFFFFF);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pl.leftMargin = (int) (18 * dp);
        row.addView(recPage, pl);
        text.addView(row);
        panelText = text;
        panel.addView(text, Ui.match());

        v.addView(panel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, panelH));
        welcome = v;   // scroll fade + intro act on the whole hero, not the panel's own fades
        panel.setAlpha(0f);
        panel.setVisibility(View.INVISIBLE);
        return v;
    }

    private TextView label(float sp, android.graphics.Typeface tf, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTypeface(tf);
        t.setTextColor(color);
        return t;
    }

    private void loadRecs() {
        recsAt = System.currentTimeMillis();
        Recs.load(this, m -> {
            if (isDestroyed()) return;
            recs = m;
            String pkg = panelPkg;
            panelPkg = null;
            if (pkg != null || panelTile != null) present(panelTile);
        });
    }

    /** A Dock app took focus: show its titles, or step aside if it publishes none. */
    private void present(TileView t) {
        panelTile = t;
        String pkg = t == null ? null : t.app.pkg;
        List<Recs.Rec> l = pkg == null ? null : recs.get(pkg);
        if (l == null || l.isEmpty()) { showLogo(t); return; }
        if (pkg.equals(panelPkg)) return;
        panelPkg = pkg;
        recIdx = 0;
        showRec(true);
    }

    /** Progress colour from the title's art (set when the art arrives; white until then). */
    private int progColor = 0xFFFFFFFF;

    private Recs.Rec current() {
        List<Recs.Rec> l = panelPkg == null ? null : recs.get(panelPkg);
        return l == null || recIdx >= l.size() ? null : l.get(recIdx);
    }

    private void showRec(boolean newApp) {
        Recs.Rec r = current();
        if (r == null) return;
        int n = recs.get(panelPkg).size();
        Runnable fill = () -> {
            // App mark top-left, then the title.
            setMark(r.pkg);
            recTitle.setText(r.title);
            recTitle.setVisibility(View.VISIBLE);
            boolean prog = r.resume && r.duration > 0 && r.position > 0 && r.position < r.duration;
            progRow.setVisibility(prog ? View.VISIBLE : View.GONE);
            if (prog) {
                progFill.getLayoutParams().width = Math.round(180 * dp * r.position / (float) r.duration);
                progFill.requestLayout();
                progFill.setBackgroundColor(progColor);
                progLabel.setText(Math.max(1, (r.duration - r.position) / 60000) + " min left");
            }
            cta.setText(r.resume ? "\u25B6  Resume" : "\u25B6  Play");
            ctaIcon(0);
            recPage.setText(n > 1 ? "\u2039   " + (recIdx + 1) + " of " + n + "   \u203A" : "");
            panelText.animate().withLayer().alpha(1f).translationX(0).setDuration(Motion.FADE_MS).setInterpolator(Motion.EASE).start();
        };
        if (panel.getVisibility() != View.VISIBLE || panel.getAlpha() == 0f) {
            panelText.setAlpha(0f);
            fill.run();
        } else {
            panelText.animate().withLayer().alpha(0f).setDuration(110).setInterpolator(Motion.EASE).withEndAction(fill).start();
        }
        panel.setVisibility(View.VISIBLE);
        panel.animate().withLayer().alpha(1f).setDuration(Motion.FADE_MS).setInterpolator(Motion.EASE).start();
        Recs.art(this, r.art, Cinema.W, Cinema.H, b -> {
            if (b == null || isDestroyed() || r != current()) return;
            panelArt.show(r.art, b, "com.netflix.ninja".equals(r.pkg) ? 0.13f : 0f);
            backdrop.showArt(r.art, b);   // the title's own colours, not the app's
            progColor = Backdrop.accent(b);
            progFill.setBackgroundColor(progColor);
        });
    }

    /** Nothing published (or PS5): the app's own banner on its brand colours, CTA opens it. */
    private void showLogo(TileView t) {
        if (t == null) { panelPkg = null; hidePanel(); return; }
        String key = "logo:" + t.app.key;
        if (key.equals(panelPkg)) return;
        panelPkg = key;
        recIdx = 0;
        boolean ps = AppStore.PS_KEY.equals(t.app.key);
        Runnable fill = () -> {
            recLogo.setVisibility(View.INVISIBLE);
            recLogoText.setVisibility(View.GONE);
            progRow.setVisibility(View.GONE);
            recTitle.setText("");
            recTitle.setVisibility(View.INVISIBLE);   // keeps the CTA where it always sits
            cta.setText(ps ? "Kick In" : "Open");
            ctaIcon(ps ? R.drawable.ic_games : 0);
            recPage.setText("");
            panelText.animate().withLayer().alpha(1f).setDuration(Motion.FADE_MS).setInterpolator(Motion.EASE).start();
        };
        if (panel.getVisibility() != View.VISIBLE || panel.getAlpha() == 0f) { panelText.setAlpha(0f); fill.run(); }
        else panelText.animate().withLayer().alpha(0f).setDuration(110).setInterpolator(Motion.EASE).withEndAction(fill).start();
        panel.setVisibility(View.VISIBLE);
        panel.animate().withLayer().alpha(1f).setDuration(Motion.FADE_MS).setInterpolator(Motion.EASE).start();
        if (ps) {
            android.graphics.drawable.Drawable mark = getDrawable(R.drawable.ps5_lockup).mutate();
            mark.setTint(Color.WHITE);
            panelArt.showMark(t.app.key, mark, 0xFF0A6BDA, 0xFF02123F);
        } else panelArt.showLogo(t.app.key, t.art, t.ambientColors);
    }

    /** Optional glyph before the CTA text; follows the pill's colour (white, dark when focused). */
    private void ctaIcon(int res) {
        if (res == 0) { cta.setCompoundDrawablesRelative(null, null, null, null); return; }
        android.graphics.drawable.Drawable d = getDrawable(res).mutate();
        int sz = (int) (18 * dp);
        d.setBounds(0, 0, sz, sz);
        cta.setCompoundDrawablesRelative(d, null, null, null);
        cta.setCompoundDrawablePadding((int) (8 * dp));
        cta.setCompoundDrawableTintList(new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {}}, new int[]{0xFF111114, 0xE6FFFFFF}));
    }

    private void setMark(String pkg) {
        Integer m = MARKS.get(pkg);
        if (m != null) {
            recLogo.setImageResource(m);
            recLogo.setVisibility(View.VISIBLE);
            recLogoText.setVisibility(View.GONE);
        } else {
            String name = pkg;
            try { name = String.valueOf(getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg, 0))); } catch (Exception ignored) { }
            recLogo.setVisibility(View.GONE);
            recLogoText.setText(name);
            recLogoText.setVisibility(View.VISIBLE);
        }
    }

    private void hidePanel() {
        if (panel.getVisibility() != View.VISIBLE) return;
        if (cta.isFocused() && panelTile != null) panelTile.requestFocus();
        panel.animate().withLayer().alpha(0f).setDuration(Motion.FADE_MS).setInterpolator(Motion.EASE)
                .withEndAction(() -> { if (panelPkg == null) panel.setVisibility(View.INVISIBLE); }).start();
    }

    /** CTA: Left/Right page through this app's titles, Down returns to its Dock tile. */
    private boolean ctaKey(int code, KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return code == KeyEvent.KEYCODE_DPAD_LEFT
                || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_DOWN;
        List<Recs.Rec> l = panelPkg == null ? null : recs.get(panelPkg);
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            int d = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
            if (l != null && recIdx + d >= 0 && recIdx + d < l.size()) {
                recIdx += d;
                panelText.setTranslationX(0);
                showRec(false);
            }
            return true;   // never wander off sideways
        }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN && panelTile != null) { panelTile.requestFocus(); return true; }
        return false;
    }

    private void openRec() {
        if (panelPkg != null && panelPkg.startsWith("logo:")) {
            if (panelTile != null) { panelTile.requestFocus(); Actions.open(this, panelTile); }
            return;
        }
        launchRec(current());
    }

    private void launchRec(Recs.Rec r) {
        if (r == null) return;
        Intent i = Recs.intent(r);
        try {
            if (i == null) throw new Exception();
            startActivity(i, android.app.ActivityOptions.makeCustomAnimation(this, R.anim.app_in, R.anim.hold).toBundle());
        } catch (Exception e) {
            Intent l = getPackageManager().getLeanbackLaunchIntentForPackage(r.pkg);
            if (l == null) l = getPackageManager().getLaunchIntentForPackage(r.pkg);
            if (l != null) startActivity(l.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            else android.widget.Toast.makeText(this, "Couldn't open " + r.title, android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** Cold-start reveal: greeting rises in, Dock tiles cascade up on springs, grid follows. */
    private void intro() {
        welcome.setAlpha(0f);
        welcome.setTranslationY(18 * dp);
        welcome.animate().alpha(1f).translationY(0).setStartDelay(120).setDuration(Motion.FOCUS_MS)
                .setInterpolator(Motion.SETTLE).start();
        dock.setAlpha(0f);
        dock.setTranslationY(36 * dp);
        dock.animate().alpha(1f).translationY(0).setStartDelay(220).setDuration(Motion.FOCUS_MS)
                .setInterpolator(Motion.SETTLE).start();
        for (int i = 0; i < dockRow.getChildCount(); i++) {
            View t = dockRow.getChildAt(i);
            t.setAlpha(0f);
            t.setTranslationY(22 * dp);
            t.animate().alpha(1f).translationY(0).setStartDelay(300 + i * 45L)
                    .setDuration(Motion.FOCUS_MS).setInterpolator(Motion.FOCUS).start();
        }
        grid.setAlpha(0f);
        grid.animate().alpha(1f).setStartDelay(520).setDuration(Motion.FADE_MS)
                .setInterpolator(Motion.EASE).start();
    }
}
