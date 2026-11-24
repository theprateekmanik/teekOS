package space.teek.tvos;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Every app (Dock included) in one scrolling grid, same tiles, glass and motion as Home. */
public class AllAppsActivity extends Activity {

    private static final int COLS = HomeActivity.COLS;

    private AppStore store;
    private float dp;
    private int tileW, tileH;
    private Backdrop backdrop;
    private Smooth scroller;
    private GridLayout grid;
    private TextView count;
    private final List<TileView> tiles = new ArrayList<>();
    private TileView lastFocused;
    private String movingKey;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Type.init(this);
        dp = getResources().getDisplayMetrics().density;
        store = new AppStore(this);

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int side = (int) (64 * dp), pad = (int) (16 * dp), gap = (int) (24 * dp);
        tileW = (screenW - 2 * side - 2 * pad - (COLS - 1) * gap) / COLS;
        tileH = Math.round(tileW * 9f / 16f);

        FrameLayout root = new FrameLayout(this);
        backdrop = new Backdrop(this);
        root.addView(backdrop, Ui.match());

        scroller = new Smooth(this);
        grid = new GridLayout(this);
        grid.setColumnCount(COLS);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setUseDefaultMargins(false);
        grid.setPadding(side + pad, (int) (24 * dp), side + pad, (int) (120 * dp));
        scroller.addView(grid, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams slp = Ui.match();
        slp.topMargin = (int) (96 * dp);
        scroller.setVerticalFadingEdgeEnabled(true);
        scroller.setFadingEdgeLength((int) (36 * dp));
        root.addView(scroller, slp);
        scroller.setOnScrollChangeListener((v, x, y, ox, oy) ->
                backdrop.dim(Math.min(1f, y / (300 * dp))));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.BOTTOM);
        header.setPadding(side + pad, (int) (34 * dp), side, 0);
        TextView title = new TextView(this);
        title.setText("All Apps");
        title.setTextColor(Color.WHITE);
        title.setTextSize(30);
        title.setTypeface(Type.semibold);
        title.setLetterSpacing(-0.02f);
        header.addView(title);
        count = new TextView(this);
        count.setTextColor(0x8CFFFFFF);
        count.setTextSize(14);
        count.setTypeface(Type.regular);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = (int) (14 * dp);
        clp.bottomMargin = (int) (6 * dp);
        header.addView(count, clp);
        root.addView(header, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        setContentView(root);
        rebuild();

        grid.setAlpha(0f);
        grid.setTranslationY(24 * dp);
        grid.animate().alpha(1f).translationY(0).setDuration(Motion.FOCUS_MS)
                .setInterpolator(Motion.SETTLE).start();
    }

    @Override protected void onResume() { super.onResume(); Actions.settleHome(this); }
    @Override protected void onStop() { super.onStop(); Actions.endLaunch(this); }

    @Override public void onBackPressed() {
        super.onBackPressed();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    private void rebuild() {
        String keep = movingKey != null ? movingKey : lastFocused != null ? lastFocused.app.key : null;
        grid.removeAllViews();
        tiles.clear();
        lastFocused = null;

        List<AppStore.App> apps = store.load(false);
        List<String> dock = store.getDock();
        count.setText(apps.size() + " apps  ·  " + dock.size() + " in Dock");
        int rowGap = (int) (44 * dp), label = (int) (TileView.LABEL_SPACE_DP * dp);
        int gap = (int) (24 * dp);
        for (int i = 0; i < apps.size(); i++) {
            TileView t = new TileView(this, apps.get(i), tileW, tileH, true);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(i / COLS), GridLayout.spec(i % COLS));
            lp.width = tileW;
            lp.height = tileH + label;
            lp.rightMargin = (i % COLS == COLS - 1) ? 0 : gap;
            lp.bottomMargin = rowGap - label;
            t.setOnFocusChangeListener((v, f) -> {
                if (!f || v == lastFocused) return;
                lastFocused = (TileView) v;
                backdrop.show(lastFocused);
                scroller.glideTo(scroller.centerOf(v, 0.45f));
            });
            t.setOnClickListener(v -> Actions.open(this, (TileView) v));
            t.setOnLongClickListener(v -> { menu((TileView) v); return true; });
            t.setOnKeyListener((v, code, e) -> {
                if (code == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP) {
                    menu((TileView) v);
                    return true;
                }
                return false;
            });
            grid.addView(t, lp);
            tiles.add(t);
        }
        if (tiles.isEmpty()) return;
        TileView target = tiles.get(0);
        if (keep != null) for (TileView t : tiles) if (t.app.key.equals(keep)) target = t;
        final TileView f = target;
        grid.post(f::requestFocus);
        if (movingKey != null) grid.post(this::resumeMove);
    }

    private void menu(TileView t) {
        TileMenu.show(this, store, t, () -> { movingKey = t.app.key; resumeMove(); }, this::rebuild);
    }

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

    private List<String> keys() {
        List<String> k = new ArrayList<>();
        for (TileView x : tiles) k.add(x.app.key);
        return k;
    }

    private boolean[] moves(String key) {
        List<String> k = keys();
        int i = k.indexOf(key), n = k.size();
        return new boolean[]{i > 0, i < n - 1, i >= COLS, i / COLS < (n - 1) / COLS};
    }

    /** Persist, re-lay out in place, and spring every tile to its new spot. */
    private void moveTile(TileView t, int dir) {
        List<String> k = keys();
        int i = k.indexOf(t.app.key), n = k.size();
        int d = dir == View.FOCUS_LEFT ? -1 : dir == View.FOCUS_RIGHT ? 1 : dir == View.FOCUS_UP ? -COLS : COLS;
        int j = Math.max(0, Math.min(n - 1, i + d));
        if (i == j) return;
        store.moveWithin(t.app.key, d, k);
        java.util.Map<View, int[]> before = Flip.capture(grid);
        tiles.remove(i);
        tiles.add(j, t);
        for (int x = 0; x < tiles.size(); x++) tiles.get(x).setLayoutParams(params(x));
        Flip.play(grid, before, t);
        scroller.post(() -> scroller.glideTo(scroller.centerOf(t, 0.45f)));
        boolean[] can = moves(t.app.key);
        t.updateMoves(can[0], can[1], can[2], can[3]);
    }

    private GridLayout.LayoutParams params(int i) {
        int rowGap = (int) (44 * dp), label = (int) (TileView.LABEL_SPACE_DP * dp), gap = (int) (24 * dp);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                GridLayout.spec(i / COLS), GridLayout.spec(i % COLS));
        lp.width = tileW;
        lp.height = tileH + label;
        lp.rightMargin = (i % COLS == COLS - 1) ? 0 : gap;
        lp.bottomMargin = rowGap - label;
        return lp;
    }
}
