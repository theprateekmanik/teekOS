package space.teek.tvos;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.util.HashMap;
import java.util.Map;

/**
 * Full-screen ambient light for the focused app, in its brand colours only:
 *  1. a two-tone gradient from the art's two strongest hues (or two shades of one hue),
 *  2. the app's logo blurred into soft colour shapes, slowly breathing,
 *  3. two glows of those colours drifting on 40-70 s cycles,
 *  4. a fixed scrim so the Dock and text stay legible.
 * Everything is soft, so the whole composition is rendered in software into a tiny 192x108
 * frame and the GPU draws just that one bitmap scaled to full screen: one full-screen layer,
 * whatever the effects. Redraws at ~15 fps when idle, every vsync only while crossfading.
 */
final class Backdrop extends View {

    private static final long IDLE_FRAME_MS = 66, FADE_MS = 700;

    private static final class Scene {
        Bitmap base, logo, glowA, glowB;
    }

    private final Map<String, Scene> cache = new HashMap<>();
    private Scene current, previous;
    private long fadeStart;
    private final long t0 = SystemClock.uptimeMillis();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Bitmap scrim;
    private final RectF dst = new RectF();
    private static final int FW = 192, FH = 108;
    private final Bitmap frame = Bitmap.createBitmap(FW, FH, Bitmap.Config.ARGB_8888);
    private final Canvas frameCanvas = new Canvas(frame);
    private final Paint blit = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private PorterDuffColorFilter dim;
    private int lastDim = 255;

    Backdrop(Context c) {
        super(c);
        scrim = Bitmap.createBitmap(1, 108, Bitmap.Config.ARGB_8888);
        Paint p = new Paint();
        p.setShader(new LinearGradient(0, 0, 0, 108,
                new int[]{0x33000000, 0x00000000, 0x59000000, 0xF0000000},
                new float[]{0f, 0.35f, 0.72f, 1f}, Shader.TileMode.CLAMP));
        new Canvas(scrim).drawRect(0, 0, 1, 108, p);
    }

    @Override protected void onWindowVisibilityChanged(int v) {
        super.onWindowVisibilityChanged(v);
        if (v == VISIBLE) invalidate();   // resume the slow drift when we come back on screen
    }

    /** Memory pressure: drop every baked scene except the one on screen. */
    void trim() {
        cache.values().retainAll(java.util.Arrays.asList(current, previous));
    }

    /** Bakes a tile's scene ahead of time (idle precompute). */
    void prepare(TileView t) { scene(t); }

    void show(TileView t) {
        Scene s = scene(t);
        if (s == current) return;
        previous = current;
        current = s;
        fadeStart = SystemClock.uptimeMillis();
        invalidate();
    }

    /** The featured title's own colours (Cinematic panel): same scene recipe, from its artwork. */
    void showArt(String key, Bitmap art) {
        Scene s = cache.get("art:" + key);
        if (s == null) {
            int[] col = ambientColors(art);
            s = new Scene();
            s.base = base(col[0], col[1]);
            s.logo = blurredLogo(Bitmap.createScaledBitmap(art, 20, 11, true));
            s.glowA = glow(withValue(col[0], 0.7f));
            s.glowB = glow(withValue(col[1], 0.6f));
            cache.put("art:" + key, s);
        }
        if (s == current) return;
        previous = current;
        current = s;
        fadeStart = SystemClock.uptimeMillis();
        invalidate();
    }

    /** A fixed mood (header buttons): exact base colours, soft glow, no logo. */
    void showTint(String key, int top, int bottom, int glow) {
        Scene s = cache.get("tint:" + key);
        if (s == null) {
            s = new Scene();
            s.base = Bitmap.createBitmap(96, 54, Bitmap.Config.ARGB_8888);
            Paint p = new Paint(Paint.DITHER_FLAG);
            p.setShader(new LinearGradient(0, 0, 0, 54, top, bottom, Shader.TileMode.CLAMP));
            new Canvas(s.base).drawRect(0, 0, 96, 54, p);
            s.logo = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);   // transparent
            s.glowA = glow(glow);
            s.glowB = glow(glow & 0x80FFFFFF);
            cache.put("tint:" + key, s);
        }
        if (s == current) return;
        previous = current;
        current = s;
        fadeStart = SystemClock.uptimeMillis();
        invalidate();
    }

    /** A bright version of the art's main colour, for accents like the progress bar. */
    static int accent(Bitmap art) { return withValue(ambientColors(art)[0], 0.95f); }

    /** 0 = full brightness, 1 = dimmed to half (scrolling into the grid). */
    void dim(float p) {
        int v = (int) (255 * (1f - 0.5f * p)) & ~3;
        if (v == lastDim) return;
        lastDim = v;
        dim = v >= 252 ? null : new PorterDuffColorFilter(Color.rgb(v, v, v), PorterDuff.Mode.MULTIPLY);
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        if (current == null) return;
        long now = SystemClock.uptimeMillis();
        float t = (now - t0) / 1000f;
        float k = previous == null ? 1f : Math.min(1f, (now - fadeStart) / (float) FADE_MS);
        float ease = Motion.EASE.getInterpolation(k);

        // Compose at 192x108 in software...
        frame.eraseColor(0xFF000000);
        if (previous != null && k < 1f) drawScene(frameCanvas, previous, 1f, t);
        drawScene(frameCanvas, current, previous == null ? 1f : ease, t);
        if (k >= 1f) previous = null;
        paint.setColorFilter(null);
        paint.setAlpha(255);
        dst.set(0, 0, FW, FH);
        frameCanvas.drawBitmap(scrim, null, dst, paint);

        // ...then one filtered full-screen draw on the GPU.
        blit.setColorFilter(dim);
        dst.set(0, 0, getWidth(), getHeight());
        c.drawBitmap(frame, null, dst, blit);

        if (previous != null) postInvalidateOnAnimation();
        else postInvalidateDelayed(IDLE_FRAME_MS);
    }

    private void drawScene(Canvas c, Scene s, float alpha, float t) {
        float w = FW, h = FH;

        paint.setAlpha((int) (255 * alpha));
        dst.set(0, 0, w, h);
        c.drawBitmap(s.base, null, dst, paint);

        // Blurred logo: slow breathing scale + drift, never sharp enough to read.
        float sc = 1.08f + 0.05f * (float) Math.sin(t / 19f);
        float ox = w * 0.03f * (float) Math.sin(t / 27f), oy = h * 0.03f * (float) Math.cos(t / 31f) - h * 0.06f;
        float lw = w * sc, lh = h * sc;
        dst.set((w - lw) / 2 + ox, (h - lh) / 2 + oy, (w + lw) / 2 + ox, (h + lh) / 2 + oy);
        paint.setAlpha((int) (120 * alpha));
        c.drawBitmap(s.logo, null, dst, paint);

        // Brand-colour glows drifting slowly.
        float r = w * 0.55f;
        float ax = w * (0.22f + 0.1f * (float) Math.sin(t / 43f)), ay = h * (0.18f + 0.1f * (float) Math.cos(t / 57f));
        dst.set(ax - r, ay - r, ax + r, ay + r);
        paint.setAlpha((int) (150 * alpha));
        c.drawBitmap(s.glowA, null, dst, paint);
        r = w * 0.48f;
        float bx = w * (0.8f + 0.08f * (float) Math.cos(t / 51f)), by = h * (0.42f + 0.1f * (float) Math.sin(t / 67f));
        dst.set(bx - r, by - r, bx + r, by + r);
        paint.setAlpha((int) (130 * alpha));
        c.drawBitmap(s.glowB, null, dst, paint);
    }

    // ------------------------------------------------------------------ baking

    private Scene scene(TileView t) {
        Scene s = cache.get(t.app.key);
        if (s != null) return s;
        int[] col = t.ambientColors;
        s = new Scene();
        s.base = base(col[0], col[1]);
        s.logo = blurredLogo(t.logoSample);
        s.glowA = glow(withValue(col[0], 0.7f));
        s.glowB = glow(withValue(col[1], 0.6f));
        cache.put(t.app.key, s);
        return s;
    }

    private static Bitmap base(int primary, int secondary) {
        int w = 96, h = 54;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, w, h, withValue(primary, 0.26f), withValue(secondary, 0.16f),
                Shader.TileMode.CLAMP));
        new Canvas(b).drawRect(0, 0, w, h, p);
        return b;
    }

    private static Bitmap glow(int color) {
        Bitmap b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new RadialGradient(32, 32, 32,
                new int[]{color, (color & 0x00FFFFFF) | 0x66000000, color & 0x00FFFFFF},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        new Canvas(b).drawRect(0, 0, 64, 64, p);
        return b;
    }

    /** 20x11 sample → 80x45 → two box-blur passes: colour shapes, no legible letters. */
    private static Bitmap blurredLogo(Bitmap sample) {
        Bitmap up = Bitmap.createScaledBitmap(sample, 80, 45, true);
        int w = up.getWidth(), h = up.getHeight();
        int[] px = new int[w * h];
        up.getPixels(px, 0, w, 0, 0, w, h);
        // Keep only the coloured parts of the art: whites and greys fade out, so a white banner
        // contributes its red logo, not a grey wash.
        float[] hsv = new float[3];
        for (int i = 0; i < px.length; i++) {
            Color.colorToHSV(px[i], hsv);
            int a = (int) (255 * Math.min(1f, Math.max(0f, (hsv[1] - 0.15f) * 1.8f)) * Math.min(1f, hsv[2] * 1.4f));
            int c = px[i];   // premultiply so transparent white can't bleed into the blur
            px[i] = (a << 24) | ((((c >> 16) & 255) * a / 255) << 16)
                    | ((((c >> 8) & 255) * a / 255) << 8) | ((c & 255) * a / 255);
        }
        for (int pass = 0; pass < 2; pass++) px = boxBlur(px, w, h, 5);
        for (int i = 0; i < px.length; i++) {   // back to straight alpha for Bitmap
            int c = px[i], a = c >>> 24;
            if (a == 0) { px[i] = 0; continue; }
            px[i] = (a << 24) | (Math.min(255, ((c >> 16) & 255) * 255 / a) << 16)
                    | (Math.min(255, ((c >> 8) & 255) * 255 / a) << 8) | Math.min(255, (c & 255) * 255 / a);
        }
        Bitmap soft = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Paint p = new Paint();
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(1.35f);
        ColorMatrix dark = new ColorMatrix();
        dark.setScale(0.8f, 0.8f, 0.8f, 1f);
        cm.postConcat(dark);
        p.setColorFilter(new ColorMatrixColorFilter(cm));
        new Canvas(out).drawBitmap(soft, 0, 0, p);
        return out;
    }

    private static int[] boxBlur(int[] in, int w, int h, int r) {
        int[] tmp = new int[in.length], out = new int[in.length];
        for (int y = 0; y < h; y++) blurLine(in, tmp, y * w, 1, w, r);
        for (int x = 0; x < w; x++) blurLine(tmp, out, x, w, h, r);
        return out;
    }

    private static void blurLine(int[] in, int[] out, int start, int step, int n, int r) {
        for (int i = 0; i < n; i++) {
            int a = 0, rr = 0, g = 0, b = 0, cnt = 0;
            for (int j = Math.max(0, i - r); j <= Math.min(n - 1, i + r); j++) {
                int c = in[start + j * step];
                a += c >>> 24; rr += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255; cnt++;
            }
            out[start + i * step] = (a / cnt) << 24 | (rr / cnt) << 16 | (g / cnt) << 8 | (b / cnt);
        }
    }

    /** Same hue and saturation, with brightness set to {@code value}. */
    private static int withValue(int color, float value) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = value;
        return Color.HSVToColor(hsv);
    }

    /**
     * The two brand colours of an app's art. Pixels vote into 15-degree hue bins, weighted by
     * saturation^2 x value, so a white banner with a red logo reads red. The second colour is
     * the strongest *other* hue present (at least 45 degrees away); if the art only has one hue,
     * it's a deeper shade of the same hue, never an invented colour. Black/white art gets a
     * neutral graphite.
     */
    static int[] ambientColors(Bitmap art) {
        Bitmap s = Bitmap.createScaledBitmap(art, 48, 27, true);
        int bins = 24;
        double[] wt = new double[bins], r = new double[bins], g = new double[bins], b = new double[bins];
        float[] hsv = new float[3];
        for (int y = 0; y < s.getHeight(); y++) {
            for (int x = 0; x < s.getWidth(); x++) {
                int px = s.getPixel(x, y);
                Color.colorToHSV(px, hsv);
                if (hsv[2] < 0.15f || hsv[1] < 0.2f) continue;
                int i = Math.min(bins - 1, (int) (hsv[0] / 15f));
                double w = hsv[1] * hsv[1] * hsv[2];
                wt[i] += w;
                r[i] += Color.red(px) * w;
                g[i] += Color.green(px) * w;
                b[i] += Color.blue(px) * w;
            }
        }
        int p = 0;
        for (int i = 1; i < bins; i++) if (wt[i] > wt[p]) p = i;
        if (wt[p] < 0.6) return new int[]{0xFF4A4E5C, 0xFF2A2D38};   // black/white artwork
        int q = -1;
        for (int i = 0; i < bins; i++) {
            int dist = Math.min(Math.abs(i - p), bins - Math.abs(i - p));
            if (dist >= 3 && wt[i] >= 0.12 * wt[p] && (q < 0 || wt[i] > wt[q])) q = i;
        }
        int primary = vivid(Color.rgb((int) (r[p] / wt[p]), (int) (g[p] / wt[p]), (int) (b[p] / wt[p])));
        if (q < 0) {
            Color.colorToHSV(primary, hsv);
            hsv[2] *= 0.6f;   // same hue, deeper
            return new int[]{primary, Color.HSVToColor(hsv)};
        }
        int secondary = vivid(Color.rgb((int) (r[q] / wt[q]), (int) (g[q] / wt[q]), (int) (b[q] / wt[q])));
        return new int[]{primary, secondary};
    }

    private static int vivid(int c) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        hsv[1] = Math.min(1f, Math.max(hsv[1] * 1.1f, 0.45f));
        hsv[2] = Math.max(0.45f, Math.min(hsv[2], 0.9f));
        return Color.HSVToColor(hsv);
    }
}
