package space.teek.tvos;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/**
 * Rest-mode night sky: one subtle navy-to-blue gradient, three parallax layers of faint stars
 * drifting extremely slowly, and a rare slow shooting star. ~10 fps (30 only while a star falls).
 */
final class CosmosView extends View {

    private static final int STARS = 170;
    private static final long FRAME_MS = 100;

    private final float[] sx = new float[STARS], sy = new float[STARS], size = new float[STARS],
            depth = new float[STARS], phase = new float[STARS], rate = new float[STARS];
    private final int[] tint = new int[STARS];
    private final Paint star = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();
    private final Paint streak = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long nextShoot = 25000, shootAt = -1;
    private float shootX, shootY, shootAngle;
    private final Random rnd = new Random();
    private final long start = SystemClock.uptimeMillis();
    private final float dp;

    CosmosView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF02040B, 0xFF040A1C, 0xFF0A1734}));   // night sky: deep navy to a faint blue horizon
        int[] tints = {0xFFFFFFFF, 0xFFDDE6FF, 0xFFFFF1DD, 0xFFCFE0FF};
        for (int i = 0; i < STARS; i++) {
            sx[i] = rnd.nextFloat();
            sy[i] = rnd.nextFloat();
            depth[i] = i < STARS * 0.6f ? 0.25f : i < STARS * 0.9f ? 0.55f : 1f;
            size[i] = (0.5f + rnd.nextFloat() * 0.7f) * (0.6f + depth[i]) * dp;
            phase[i] = rnd.nextFloat() * 6.283f;
            rate[i] = 0.3f + rnd.nextFloat() * 1.2f;
            tint[i] = tints[rnd.nextInt(tints.length)];
        }
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float t = (SystemClock.uptimeMillis() - start) / 1000f;

        // Stars: leftward parallax drift + twinkle.
        for (int i = 0; i < STARS; i++) {
            float x = sx[i] - t * 0.0004f * depth[i];
            x -= (float) Math.floor(x);
            float tw = 0.75f + 0.25f * (float) Math.sin(t * rate[i] * 0.15f + phase[i]);
            star.setColor(tint[i]);
            star.setAlpha((int) (140 * tw * (0.3f + 0.7f * depth[i])));
            c.drawCircle(x * w, sy[i] * h, size[i], star);
        }

        // A rare, slow shooting star (every 40–90 s, 2.6 s long).
        long ms = SystemClock.uptimeMillis() - start;
        if (shootAt < 0 && ms > nextShoot) {
            shootAt = ms;
            shootX = w * (0.3f + rnd.nextFloat() * 0.55f);
            shootY = h * (0.06f + rnd.nextFloat() * 0.25f);
            shootAngle = (float) Math.toRadians(195 + rnd.nextFloat() * 15);
        }
        if (shootAt >= 0) {
            float k = (ms - shootAt) / 2600f;
            if (k >= 1f) {
                shootAt = -1;
                nextShoot = ms + 40000 + rnd.nextInt(50000);
            } else {
                float len = 120 * dp, travel = 380 * dp * k;
                float hx = shootX + (float) Math.cos(shootAngle) * travel;
                float hy = shootY - (float) Math.sin(shootAngle) * travel;
                float tx = hx - (float) Math.cos(shootAngle) * len, ty = hy + (float) Math.sin(shootAngle) * len;
                int a = (int) (150 * Math.sin(Math.PI * k));
                streak.setShader(new android.graphics.LinearGradient(hx, hy, tx, ty,
                        (a << 24) | 0xFFFFFF, 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP));
                streak.setStrokeWidth(1.4f * dp);
                c.drawLine(hx, hy, tx, ty, streak);
            }
        }
        postInvalidateDelayed(shootAt >= 0 ? 33 : FRAME_MS);   // smooth only while a star is falling
    }
}
