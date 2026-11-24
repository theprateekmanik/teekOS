package space.teek.tvos;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Banner art for the PlayStation 5 tile, styled like the streaming apps' banners:
 * the PS5 logo + wordmark in white, centred on PlayStation blue with a soft top-light.
 */
final class PsBanner extends Drawable {

    private static final int BLUE_TOP = 0xFF0A6FD8, BLUE_BOTTOM = 0xFF00327F;
    private final Drawable logo;
    private final Paint bg = new Paint(), light = new Paint(Paint.ANTI_ALIAS_FLAG);

    PsBanner(Context c) {
        logo = c.getDrawable(R.drawable.ps5_lockup).mutate();
    }

    @Override public int getIntrinsicWidth() { return 640; }
    @Override public int getIntrinsicHeight() { return 360; }

    @Override protected void onBoundsChange(Rect b) {
        bg.setShader(new LinearGradient(0, b.top, 0, b.bottom, BLUE_TOP, BLUE_BOTTOM,
                Shader.TileMode.CLAMP));
        light.setShader(new RadialGradient(b.exactCenterX(), b.top, b.width() * 0.6f,
                0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        int lw = Math.round(b.width() * 0.62f);
        int lh = Math.round(lw * logo.getIntrinsicHeight() / (float) logo.getIntrinsicWidth());
        int l = b.left + (b.width() - lw) / 2, t = b.top + (b.height() - lh) / 2;
        logo.setBounds(l, t, l + lw, t + lh);
    }

    @Override public void draw(Canvas c) {
        Rect b = getBounds();
        c.drawRect(b, bg);
        c.drawRect(b, light);
        logo.draw(c);
    }

    @Override public void setAlpha(int a) { bg.setAlpha(a); logo.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter f) { logo.setColorFilter(f); }
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
