package space.teek.tvos;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * One app tile, tvOS-style: on focus the card lifts and scales on a spring, a specular gloss
 * and a glass rim light up, and the card tilts in the direction of travel before settling
 * (parallax). Pushing past the edge of the grid gives a small bump instead of nothing.
 */
final class TileView extends FrameLayout {

    static final float FOCUS_SCALE = 1.12f;
    /** Room under the card for the name label (dp). */
    static final int LABEL_SPACE_DP = 34;
    private static final float TILT_DEG = 7f;

    final AppStore.App app;
    final FrameLayout card;
    private final View gloss, rim;
    private final TextView label;
    private final boolean showLabel;
    private final float dp;
    private final int w, h;
    private ObjectAnimator tilt;

    /** Receives D-pad moves while the tile is in move mode. */
    interface Mover {
        void move(int direction);
        void done();
    }

    private Mover mover;
    private final View[] chevrons = new View[4];   // left, right, up, down
    private final ObjectAnimator[] nudges = new ObjectAnimator[4];
    private boolean[] allowed = new boolean[4];
    /** The app's ambient colour, which lights the whole backdrop when focused. */
    final int[] ambientColors;
    /** Tiny sample of the artwork for the blurred-logo backdrop. */
    final Bitmap logoSample;
    /** The banner's own background colour (corner pixel): the launch expansion fills with it. */
    final int edgeColor;
    final Bitmap art;

    /**
     * Rendered artwork + derived colours, cached per app and size so rebuilding the grid
     * (moves, menu actions) never re-renders or re-uploads tile bitmaps.
     */
    static final class Art {
        final Bitmap bitmap, sample;
        final int[] colors;
        final int edge;
        private static final java.util.Map<String, Art> CACHE = new java.util.HashMap<>();

        private Art(Bitmap b) {
            bitmap = b;
            colors = Backdrop.ambientColors(b);
            sample = Bitmap.createScaledBitmap(b, 20, 11, true);
            edge = b.getPixel(2, 2) | 0xFF000000;
        }

        static Art get(AppStore.App app, int w, int h, TileView v) {
            String k = app.key + "@" + w + "x" + h;
            Art a = CACHE.get(k);
            if (a == null) CACHE.put(k, a = new Art(v.render(app, w, h)));
            return a;
        }

        /** Package added/removed/changed, or memory pressure: drop everything. */
        static void clear() { CACHE.clear(); }
    }

    TileView(Context c, AppStore.App app, int w, int h, boolean showLabel) {
        super(c);
        this.app = app;
        this.w = w;
        this.h = h;
        this.showLabel = showLabel;
        dp = c.getResources().getDisplayMetrics().density;
        setClipChildren(false);
        setClipToPadding(false);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        setContentDescription(app.label);

        card = new FrameLayout(c);
        final float radius = 12 * dp;
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        card.setClipToOutline(true);
        card.setElevation(0);   // no idle shadows: cheaper on this GPU, and tvOS-flat
        card.setCameraDistance(9000 * dp);
        card.setPivotX(w / 2f);
        card.setPivotY(h / 2f);

        Art cached = Art.get(app, w, h, this);
        Bitmap art = cached.bitmap;
        ambientColors = cached.colors;
        logoSample = cached.sample;
        edgeColor = cached.edge;

        this.art = art;
        ImageView img = new ImageView(c);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setImageBitmap(art);
        card.addView(img, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // Specular gloss: a soft light source that rests near the top and drifts with tilt.
        gloss = new View(c);
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setColors(new int[]{0x47FFFFFF, 0x14FFFFFF, 0x00FFFFFF});
        g.setGradientRadius(w * 0.62f);
        gloss.setBackground(g);
        gloss.setAlpha(0f);
        card.addView(gloss, new LayoutParams(w * 2, h * 2, Gravity.CENTER));
        restGloss();

        // Glass rim: always faintly there, catches the light when focused.
        rim = new View(c);
        rim.setBackground(new Glass(radius, 1.5f * dp, 1f, false));
        rim.setAlpha(0.35f);
        card.addView(rim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        addView(card, new LayoutParams(w, h));

        label = new TextView(c);
        label.setText(app.label);
        label.setTextColor(Color.WHITE);
        label.setTextSize(13);
        label.setTypeface(Type.medium);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setAlpha(0f);
        label.setShadowLayer(6 * dp, 0, 1 * dp, 0xCC000000);
        LayoutParams lp = new LayoutParams(w, LayoutParams.WRAP_CONTENT);
        lp.topMargin = h + (int) (13 * dp);
        if (showLabel) addView(label, lp);
    }

    private void restGloss() {
        gloss.setTranslationX(-w * 0.18f);
        gloss.setTranslationY(-h * 0.62f);
    }

    @Override
    protected void onFocusChanged(boolean gain, int direction, Rect previous) {
        super.onFocusChanged(gain, direction, previous);
        if (mover != null) return;   // moving: stays lifted while re-parented / re-laid out
        card.animate().cancel();
        gloss.animate().cancel();
        rim.animate().cancel();
        label.animate().cancel();
        if (gain) {
            setTranslationZ(1);   // draw above neighbours (Z order, keeps grid order intact)
            card.animate().scaleX(FOCUS_SCALE).scaleY(FOCUS_SCALE).translationZ(20 * dp)
                    .setDuration(Motion.FOCUS_MS).setInterpolator(Motion.FOCUS).start();
            rim.animate().alpha(1f).setDuration(Motion.FADE_MS).setInterpolator(Motion.EASE).start();
            parallax(direction);
            if (showLabel) {
                label.setTranslationY(-6 * dp);
                label.animate().alpha(1f).translationY(0).setStartDelay(70)
                        .setDuration(Motion.SETTLE_MS).setInterpolator(Motion.SETTLE).start();
            }
        } else {
            setTranslationZ(0);
            if (tilt != null) tilt.cancel();
            card.animate().scaleX(1f).scaleY(1f).translationZ(0).rotationX(0).rotationY(0)
                    .translationX(0).translationY(0)
                    .setDuration(Motion.SETTLE_MS).setInterpolator(Motion.SETTLE).start();
            rim.animate().alpha(0.35f).setDuration(Motion.FADE_MS).setInterpolator(Motion.EASE).start();
            gloss.animate().alpha(0f).setDuration(220).setInterpolator(Motion.EASE).start();
            label.animate().alpha(0f).setStartDelay(0).setDuration(140).start();
        }
    }

    /**
     * Arrival tilt: the card leans with the direction of travel and springs upright, while the
     * gloss slides across the surface the opposite way — the D-pad version of tvOS parallax.
     */
    private void parallax(int direction) {
        float ry = 0, rx = 0, gx = 0, gy = 0;
        switch (direction) {
            case FOCUS_RIGHT: ry = TILT_DEG;  gx = -w * 0.35f; break;
            case FOCUS_LEFT:  ry = -TILT_DEG; gx = w * 0.35f;  break;
            case FOCUS_DOWN:  rx = -TILT_DEG; gy = -h * 0.30f; break;
            case FOCUS_UP:    rx = TILT_DEG;  gy = h * 0.30f;  break;
            default: break;
        }
        if (tilt != null) tilt.cancel();
        tilt = ObjectAnimator.ofPropertyValuesHolder(card,
                PropertyValuesHolder.ofFloat(View.ROTATION_Y, ry, 0f),
                PropertyValuesHolder.ofFloat(View.ROTATION_X, rx, 0f));
        tilt.setDuration(Motion.TILT_MS);
        tilt.setInterpolator(Motion.TILT);
        tilt.start();

        restGloss();
        float restX = gloss.getTranslationX(), restY = gloss.getTranslationY();
        gloss.setTranslationX(restX + gx);
        gloss.setTranslationY(restY + gy);
        gloss.setAlpha(0f);
        gloss.animate().alpha(1f).translationX(restX).translationY(restY)
                .setDuration(Motion.TILT_MS).setInterpolator(Motion.TILT).start();
    }

    /** Edge of the grid: a small push in that direction, then spring back. */
    private void bump(int direction) {
        float d = 7 * dp, tx = 0, ty = 0, ry = 0, rx = 0;
        switch (direction) {
            case FOCUS_RIGHT: tx = d;  ry = 4; break;
            case FOCUS_LEFT:  tx = -d; ry = -4; break;
            case FOCUS_DOWN:  ty = d;  rx = -4; break;
            case FOCUS_UP:    ty = -d; rx = 4; break;
        }
        if (tilt != null) tilt.cancel();
        tilt = ObjectAnimator.ofPropertyValuesHolder(card,
                PropertyValuesHolder.ofKeyframe(View.TRANSLATION_X, kf(tx)),
                PropertyValuesHolder.ofKeyframe(View.TRANSLATION_Y, kf(ty)),
                PropertyValuesHolder.ofKeyframe(View.ROTATION_Y, kf(ry)),
                PropertyValuesHolder.ofKeyframe(View.ROTATION_X, kf(rx)));
        tilt.setDuration(Motion.TILT_MS);
        tilt.start();
    }

    private static android.animation.Keyframe[] kf(float peak) {
        android.animation.Keyframe a = android.animation.Keyframe.ofFloat(0f, 0f);
        android.animation.Keyframe b = android.animation.Keyframe.ofFloat(0.12f, peak);
        b.setInterpolator(Motion.EASE);
        android.animation.Keyframe c = android.animation.Keyframe.ofFloat(1f, 0f);
        c.setInterpolator(Motion.TILT);
        return new android.animation.Keyframe[]{a, b, c};
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (mover != null) return moveKey(code, e);
        int dir = 0;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: dir = FOCUS_LEFT; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: dir = FOCUS_RIGHT; break;
            case KeyEvent.KEYCODE_DPAD_UP: dir = FOCUS_UP; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: dir = FOCUS_DOWN; break;
        }
        if (dir != 0 && focusSearch(dir) == null) {
            if (e.getRepeatCount() == 0) bump(dir);
            return true;
        }
        return super.onKeyDown(code, e);
    }

    // ------------------------------------------------------------- move mode

    boolean isMoving() { return mover != null; }

    /**
     * Move mode: glass chevrons appear around the tile for each direction it can go, each
     * nudging gently outward. D-pad moves the tile; OK or Back ends move mode.
     */
    /** After a move: refresh which chevrons show, without replaying their entrance. */
    void updateMoves(boolean left, boolean right, boolean up, boolean down) {
        allowed = new boolean[]{left, right, up, down};
        for (int i = 0; i < 4; i++) {
            if (chevrons[i] == null) continue;
            boolean was = chevrons[i].getVisibility() == VISIBLE;
            chevrons[i].setVisibility(allowed[i] ? VISIBLE : GONE);
            if (allowed[i] && !was) {
                chevrons[i].setAlpha(0f);
                chevrons[i].animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(0)
                        .setDuration(Motion.SETTLE_MS).setInterpolator(Motion.SETTLE).start();
            }
        }
    }

    void startMoving(Mover m, boolean left, boolean right, boolean up, boolean down) {
        mover = m;
        allowed = new boolean[]{left, right, up, down};
        label.animate().alpha(0f).setDuration(120).start();
        int[] icons = {R.drawable.ic_chev_left, R.drawable.ic_chev_right, R.drawable.ic_chev_up, R.drawable.ic_chev_down};
        // Chevrons straddle the lifted card's edge, like handles, so they stay clear of neighbours.
        int size = (int) (28 * dp);
        float off = w * (FOCUS_SCALE - 1f) / 2f - size * 0.35f, offV = h * (FOCUS_SCALE - 1f) / 2f - size * 0.35f;
        for (int i = 0; i < 4; i++) {
            if (chevrons[i] == null) {
                android.widget.ImageView c = new android.widget.ImageView(getContext());
                c.setImageResource(icons[i]);
                c.setImageTintList(android.content.res.ColorStateList.valueOf(0xF2FFFFFF));
                // Same material as the menu: dark frosted body + light-catching glass rim.
                GradientDrawable disc = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{0xF22A2A30, 0xF718181C});
                disc.setShape(GradientDrawable.OVAL);
                c.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                        disc, new Glass(100 * dp, 1.2f * dp, 1f, false)}));
                int pad = (int) (3 * dp);
                c.setPadding(pad, pad, pad, pad);
                c.setElevation(6 * dp);
                LayoutParams lp = new LayoutParams(size, size);
                switch (i) {
                    case 0: lp.leftMargin = (int) (-off - size); lp.topMargin = (h - size) / 2; break;
                    case 1: lp.leftMargin = (int) (w + off); lp.topMargin = (h - size) / 2; break;
                    case 2: lp.leftMargin = (w - size) / 2; lp.topMargin = (int) (-offV - size); break;
                    default: lp.leftMargin = (w - size) / 2; lp.topMargin = (int) (h + offV); break;
                }
                addView(c, lp);
                chevrons[i] = c;
            }
            View c = chevrons[i];
            c.setVisibility(allowed[i] ? VISIBLE : GONE);
            if (!allowed[i]) continue;
            c.setAlpha(0f);
            c.setScaleX(0.6f);
            c.setScaleY(0.6f);
            c.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(60L * i)
                    .setDuration(Motion.FOCUS_MS).setInterpolator(Motion.FOCUS).start();
            float d = 4 * dp;
            nudges[i] = ObjectAnimator.ofFloat(c, i < 2 ? View.TRANSLATION_X : View.TRANSLATION_Y,
                    0f, (i == 0 || i == 2) ? -d : d, 0f);
            nudges[i].setDuration(1400);
            nudges[i].setStartDelay(500);
            nudges[i].setRepeatCount(ObjectAnimator.INFINITE);
            nudges[i].setInterpolator(Motion.EASE);
            nudges[i].start();
        }
    }

    void stopMoving() {
        mover = null;
        for (int i = 0; i < 4; i++) {
            if (nudges[i] != null) nudges[i].cancel();
            if (chevrons[i] != null) {
                View c = chevrons[i];
                c.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setStartDelay(0).setDuration(160)
                        .withEndAction(() -> c.setVisibility(GONE)).start();
            }
        }
        if (showLabel && isFocused()) label.animate().alpha(1f).setDuration(Motion.FADE_MS).start();
    }

    private boolean moveKey(int code, KeyEvent e) {
        int dir = -1;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: dir = 0; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: dir = 1; break;
            case KeyEvent.KEYCODE_DPAD_UP: dir = 2; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: dir = 3; break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BACK:
                Mover m = mover;
                stopMoving();
                if (m != null) m.done();
                return true;
            default:
                return true;   // swallow everything else while moving
        }
        int[] focusDir = {FOCUS_LEFT, FOCUS_RIGHT, FOCUS_UP, FOCUS_DOWN};
        if (!allowed[dir]) { bump(focusDir[dir]); return true; }
        mover.move(focusDir[dir]);
        return true;
    }

    /** Click: the card dips, then springs back as the app opens. */
    void press(Runnable then) {
        card.animate().cancel();
        card.animate().scaleX(1.03f).scaleY(1.03f).setDuration(Motion.PRESS_MS)
                .setInterpolator(Motion.EASE)
                .withEndAction(() -> {
                    card.animate().scaleX(FOCUS_SCALE).scaleY(FOCUS_SCALE)
                            .setDuration(Motion.FOCUS_MS).setInterpolator(Motion.FOCUS).start();
                    then.run();
                }).start();
    }

    /** Banner if the app ships one; otherwise its icon on a tinted card with the name. */
    /** Sharp art for the launch zoom: vectors redraw at size; rasters use the highest-density banner. */
    Bitmap renderLarge(int w, int h) {
        if (!(app.banner instanceof BitmapDrawable) || app.launch == null) return render(app, w, h);
        Drawable hi = AppStore.hiResBanner(getContext(), app);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        drawCover(new Canvas(bmp), hi != null ? hi : app.banner, w, h);
        return bmp;
    }

    Bitmap render(AppStore.App app, int w, int h) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        if (app.banner != null) {
            drawCover(cv, app.banner, w, h);
            return bmp;
        }
        int base = averageColor(toBitmap(app.icon, 48, 48));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{shade(base, 0.55f), shade(base, 0.25f)});
        bg.setBounds(0, 0, w, h);
        bg.draw(cv);
        int s = (int) (h * 0.5f);
        app.icon.setBounds((w - s) / 2, (int) (h * 0.14f), (w + s) / 2, (int) (h * 0.14f) + s);
        app.icon.draw(cv);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setTextSize(h * 0.13f);
        p.setTypeface(Type.medium);
        p.setTextAlign(Paint.Align.CENTER);
        String t = TextUtils.ellipsize(app.label, new TextPaint(p), w * 0.9f,
                TextUtils.TruncateAt.END).toString();
        cv.drawText(t, w / 2f, h * 0.88f, p);
        return bmp;
    }

    /**
     * Never crop or stretch: fit the art inside (aspect preserved) and fill any margin with the
     * art's own edge colour, so non-16:9 banners keep their whole logo.
     */
    private static void drawCover(Canvas cv, Drawable d, int w, int h) {
        int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        if (iw <= 0 || ih <= 0) { d.setBounds(0, 0, w, h); d.draw(cv); return; }
        float sc = Math.min(w / (float) iw, h / (float) ih);
        int dw = Math.round(iw * sc), dh = Math.round(ih * sc);
        if (dw < w - 1 || dh < h - 1) {
            int edge = toBitmap(d, 16, 9).getPixel(0, 0) | 0xFF000000;
            cv.drawColor(edge);
        }
        int l = (w - dw) / 2, t = (h - dh) / 2;
        d.setBounds(l, t, l + dw, t + dh);
        if (d instanceof BitmapDrawable) ((BitmapDrawable) d).setFilterBitmap(true);
        d.draw(cv);
    }

    private static Bitmap toBitmap(Drawable d, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, w, h);
        d.draw(new Canvas(b));
        return b;
    }

    static int averageColor(Bitmap b) {
        Bitmap one = Bitmap.createScaledBitmap(b, 1, 1, true);
        int c = one.getPixel(0, 0);
        return Color.rgb(Color.red(c), Color.green(c), Color.blue(c));
    }

    static int shade(int c, float f) {
        return Color.rgb((int) (Color.red(c) * f), (int) (Color.green(c) * f), (int) (Color.blue(c) * f));
    }
}
