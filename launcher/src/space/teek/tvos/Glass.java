package space.teek.tvos;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Liquid Glass surface without a live blur (too costly on this GPU): the backdrop is already
 * a soft, blurred image, so a translucent body plus a light-catching rim reads as glass.
 *  - body: faint white, brighter at the top edge, a touch lighter again at the bottom (refraction)
 *  - rim: hairline that catches light strongly at the top and faintly at the bottom
 * fill = false gives a rim-only overlay (used on tiles).
 */
final class Glass extends Drawable {

    private final float radius, rim, strength;
    private final boolean fill;
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    Glass(float radius, float rimWidth, float strength, boolean fill) {
        this.radius = radius;
        this.rim = rimWidth;
        this.strength = strength;
        this.fill = fill;
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(rimWidth);
    }

    private int white(float a) {
        return ((int) (Math.min(1f, a * strength) * 255) << 24) | 0xFFFFFF;
    }

    @Override protected void onBoundsChange(Rect b) {
        rect.set(b);
        rect.inset(rim / 2f, rim / 2f);
        body.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                new int[]{white(0.17f), white(0.075f), white(0.06f), white(0.11f)},
                new float[]{0f, 0.35f, 0.8f, 1f}, Shader.TileMode.CLAMP));
        edge.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                new int[]{white(0.55f), white(0.10f), white(0.02f), white(0.20f)},
                new float[]{0f, 0.22f, 0.65f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override public void draw(Canvas c) {
        float r = Math.min(radius, rect.height() / 2f);
        if (fill) c.drawRoundRect(rect, r, r, body);
        c.drawRoundRect(rect, r, r, edge);
    }

    @Override public void setAlpha(int a) { body.setAlpha(a); edge.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter f) { body.setColorFilter(f); edge.setColorFilter(f); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
