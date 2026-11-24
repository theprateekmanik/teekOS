package space.teek.tvos;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Artwork layer, baked off the main thread so each frame is one opaque blit; changes crossfade.
 * Two looks: the full-screen ambience (downsampled until soft, then darkened: a blur without
 * live blur) and the recommendation panel (sharp, left-to-right gradient for the text).
 */
final class Cinema extends View {
    /** Decode size for downloaded artwork. */
    static final int W = 1280, H = 720;
    private final int bw, bh;
    private final boolean soft;

    private static final ExecutorService BAKE = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Bitmap cur, prev;
    private String curUrl, wanted;
    private float mix = 1f, dim = 0f;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG), shade = new Paint();
    private final Rect dst = new Rect();
    private ValueAnimator fade;

    /** {@code soft}: full-screen ambience; otherwise a sharp panel baked at w×h pixels. */
    Cinema(Context c, int w, int h, boolean soft) {
        super(c);
        bw = w; bh = h; this.soft = soft;
    }

    boolean hasArt() { return cur != null; }

    /** Show this artwork (already decoded at about W×H); crossfades from the current one. */
    void show(String url, Bitmap art) {
        if (art == null) return;
        show(url, art, 0f);
    }

    /** {@code crop}: fraction of the art's bottom to leave out (a label baked into the image). */
    void show(String url, Bitmap art, float crop) {
        if (art == null) return;
        swap(url, () -> { cropBottom = crop; return soft ? bakeSoft(art) : bakePanel(art, bw, bh); });
    }

    /** A white vector mark centred on a full-width gradient (PS5). */
    void showMark(String key, android.graphics.drawable.Drawable mark, int top, int bottom) {
        swap("mark:" + key, () -> {
            Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.RGB_565);
            Canvas c = new Canvas(b);
            Paint p = new Paint(Paint.DITHER_FLAG);
            p.setShader(new LinearGradient(0, 0, bw, bh, top, bottom, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, bw, bh, p);
            int mh = Math.round(bh * 0.30f);
            int mw = Math.round(mh * mark.getIntrinsicWidth() / (float) mark.getIntrinsicHeight());
            mark.setBounds((bw - mw) / 2, (bh - mh) / 2, (bw + mw) / 2, (bh + mh) / 2);
            mark.draw(c);
            return b;
        });
    }

    /** An app with no titles to show: its own banner, unscaled, on its brand colours. */
    void showLogo(String key, Bitmap logo, int[] colors) {
        if (logo == null) return;
        swap("logo:" + key, () -> bakeLogo(logo, colors, bw, bh));
    }

    private interface Bake { Bitmap make(); }

    private void swap(String url, Bake bake) {
        if (url.equals(curUrl)) return;
        wanted = url;
        BAKE.execute(() -> {
            Bitmap b = bake.make();
            MAIN.post(() -> {
                if (!url.equals(wanted)) { b.recycle(); return; }
                if (prev != null) prev.recycle();
                prev = cur;
                cur = b;
                curUrl = url;
                if (fade != null) fade.cancel();
                fade = ValueAnimator.ofFloat(0f, 1f);
                fade.setDuration(prev == null ? 700 : 520);
                fade.setInterpolator(Motion.EASE);
                fade.addUpdateListener(a -> { mix = (float) a.getAnimatedValue(); invalidate(); });
                fade.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator a) {
                        if (prev != null) { prev.recycle(); prev = null; }
                        invalidate();
                    }
                });
                fade.start();
            });
        });
    }

    /** 0 = full artwork, 1 = mostly dark (when the app grid scrolls up). */
    void dim(float p) { dim = p; invalidate(); }

    void clear() {
        if (fade != null) fade.cancel();
        if (cur != null) cur.recycle();
        if (prev != null) prev.recycle();
        cur = prev = null;
        curUrl = wanted = null;
        invalidate();
    }

    private void cover(Canvas c, Bitmap src, int w, int h) {
        float s = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        float dw = src.getWidth() * s, dh = src.getHeight() * s;
        Rect d = new Rect(Math.round((w - dw) / 2), Math.round((h - dh) / 2),
                Math.round((w + dw) / 2), Math.round((h + dh) / 2));
        c.drawBitmap(src, inner(src), d, new Paint(Paint.FILTER_BITMAP_FLAG));
    }

    /** Fraction of the art's height to drop at the bottom (Netflix bakes "Recently added" there). */
    float cropBottom;

    private Rect inner(Bitmap src) {
        float crop = cropBottom > 0 && labelAt(src, cropBottom) ? cropBottom : 0f;
        return new Rect(0, 0, src.getWidth(), src.getHeight() - Math.round(src.getHeight() * crop));
    }

    /** Is there a red label (Netflix's "Recently added" pill) in the bottom band's centre? */
    private static boolean labelAt(Bitmap src, float band) {
        int red = 0, n = 0;
        for (float f = 0.15f; f < 0.95f; f += 0.1f) {          // several rows: the letters are white
            int y = src.getHeight() - 1 - Math.round(src.getHeight() * band * f);
            for (int i = -4; i <= 4; i++, n++) {
                int px = src.getPixel(src.getWidth() / 2 + i * src.getWidth() / 40, y);
                int r = (px >> 16) & 255, g = (px >> 8) & 255, b = px & 255;
                if (r > 170 && g < 70 && b < 70) red++;
            }
        }
        return red * 4 >= n;   // a quarter of the samples are label red
    }

    /** Tiny, then up again with filtering: a soft wash of the artwork's colours, darkened. */
    private Bitmap bakeSoft(Bitmap src) {
        Bitmap tiny = Bitmap.createBitmap(48, 27, Bitmap.Config.ARGB_8888);
        cover(new Canvas(tiny), src, 48, 27);
        Bitmap mid = Bitmap.createScaledBitmap(tiny, 160, 90, true);
        tiny.recycle();
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(b);
        c.drawBitmap(mid, null, new Rect(0, 0, bw, bh), new Paint(Paint.FILTER_BITMAP_FLAG));
        mid.recycle();
        Paint p = new Paint();
        p.setShader(new LinearGradient(0, 0, 0, bh, new int[]{0x99000000, 0x66000000, 0xE6000000},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, bw, bh, p);
        return b;
    }

    /**
     * Artwork fitted to the panel's height (never enlarged past its own pixels), anchored right.
     * The space to its left takes the art's own left-edge colour, and a left-to-right gradient
     * runs darkest behind the title and dissolves into the art's left edge.
     */
    private Bitmap bakePanel(Bitmap src, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(b);
        Rect in = inner(src);
        // Fit height; small art may grow up to 1.35x to fill it, never more.
        float s = h / (float) in.height();
        if (s > 1.35f) s = 1f;
        s = Math.min(s, w / (float) in.width());
        float dw = in.width() * s, dh = in.height() * s;
        float left = w - dw, top = (h - dh) / 2f;
        int fill = darken(edge(src, in), 0.45f);
        c.drawColor(fill);
        c.drawBitmap(src, in, new android.graphics.RectF(left, top, w, top + dh), new Paint(Paint.FILTER_BITMAP_FLAG));
        if (left > 0) {   // feather the art's left edge into the fill: no seam
            Paint f = new Paint();
            f.setShader(new LinearGradient(left, 0, left + dw * 0.28f, 0, fill, fill & 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRect(left, 0, left + dw * 0.28f, h, f);
        }
        float fadeEnd = Math.min(1f, (left + dw * 0.38f) / w);
        Paint p = new Paint();
        p.setShader(new LinearGradient(0, 0, w, 0, new int[]{0xF2000000, 0xB3000000, 0x00000000},
                new float[]{0f, Math.max(0.01f, left / w * 0.85f), fadeEnd}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        if (dh < h) {   // short art: soften its top and bottom edges into the fill
            p.setShader(new LinearGradient(0, top, 0, top + dh, new int[]{0x99000000, 0x00000000, 0x00000000, 0x99000000},
                    new float[]{0f, 0.12f, 0.88f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(left, top, w, top + dh, p);
        }
        return b;
    }

    /** Average colour of the art's leftmost column. */
    private static int edge(Bitmap src, Rect in) {
        long r = 0, g = 0, bl = 0; int n = 0;
        for (int y = in.top; y < in.bottom; y += 4) {
            int px = src.getPixel(in.left, y);
            r += (px >> 16) & 255; g += (px >> 8) & 255; bl += px & 255; n++;
        }
        return n == 0 ? 0xFF000000 : 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (bl / n);
    }

    private static int darken(int c, float f) {
        return 0xFF000000 | Math.round(((c >> 16) & 255) * f) << 16 | Math.round(((c >> 8) & 255) * f) << 8
                | Math.round((c & 255) * f);
    }

    /** Diagonal gradient of the app's two ambient colours, darkened, with its banner centred as is. */
    private static Bitmap bakeLogo(Bitmap logo, int[] colors, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(b);
        int a = colors != null && colors.length > 0 ? colors[0] : 0xFF202024;
        int z = colors != null && colors.length > 1 ? colors[1] : a;
        Paint p = new Paint();
        p.setShader(new LinearGradient(0, 0, w, h, darken(a, 0.42f), darken(z, 0.22f), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        float lw = logo.getWidth(), lh = logo.getHeight();
        android.graphics.RectF d = new android.graphics.RectF((w - lw) / 2f, (h - lh) / 2f, (w + lw) / 2f, (h + lh) / 2f);
        Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        lp.setShader(new android.graphics.BitmapShader(logo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setTranslate(d.left, d.top);
        lp.getShader().setLocalMatrix(m);
        float r = lh * 0.08f;   // same rounded card as the tiles
        c.drawRoundRect(d, r, r, lp);
        return b;
    }

    @Override protected void onDraw(Canvas c) {
        dst.set(0, 0, getWidth(), getHeight());
        if (cur == null) return;
        if (prev != null && mix < 1f) {
            paint.setAlpha(255);
            c.drawBitmap(prev, null, dst, paint);
            paint.setAlpha(Math.round(255 * mix));
        } else paint.setAlpha(255);
        c.drawBitmap(cur, null, dst, paint);
        if (dim > 0f) {
            shade.setColor(Math.round(200 * dim) << 24);
            c.drawRect(dst, shade);
        }
    }
}
