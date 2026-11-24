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

import java.util.HashMap;
import java.util.Map;

/**
 * Clean vector banner for apps whose own TV art is missing or icon-only: brand gradient,
 * white vector mark and an Inter wordmark side by side, centred like the PS5 tile.
 */
final class BrandBanner extends Drawable {

    private static final class Spec {
        final int top, bottom, logo, mark;
        final String word;
        final float markH;   // mark height as a fraction of banner height
        Spec(int top, int bottom, int logo, String word) { this(top, bottom, logo, word, 0xFFFFFFFF, 0.34f); }
        Spec(int top, int bottom, int logo, String word, int mark, float markH) {
            this.top = top; this.bottom = bottom; this.logo = logo; this.word = word;
            this.mark = mark; this.markH = markH;
        }
    }

    private static final Map<String, Spec> SPECS = new HashMap<>();
    static {
        SPECS.put("com.jiostb.jiogames", new Spec(0xFF1FA45A, 0xFF0B5A31, R.drawable.logo_jio, "Games+"));
        SPECS.put("com.jio.web.androidtv", new Spec(0xFF2453E0, 0xFF0F2C8C, R.drawable.logo_jio, "Pages"));
        SPECS.put("com.heytap.tv.filebrowser", new Spec(0xFF3B82F6, 0xFF1D4ED8, R.drawable.ic_folder, "Files"));
        SPECS.put("com.android.tv.settings", new Spec(0xFF5B6170, 0xFF2B2E37, R.drawable.ic_settings, "Settings"));
        SPECS.put("com.heytap.tv.assistant.oneplus", new Spec(0xFF26B36C, 0xFF117044, R.drawable.ic_shield, "Smart Manager"));
    }

    /** A replacement banner for this package, or null to keep the app's own art. */
    static Drawable forPackage(Context c, String pkg) {
        Spec s = SPECS.get(pkg);
        return s == null ? null : new BrandBanner(c, s);
    }

    private final Spec spec;
    private final Drawable logo;
    private final Paint bg = new Paint(), light = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float textX, textY;

    private BrandBanner(Context c, Spec s) {
        spec = s;
        logo = c.getDrawable(s.logo).mutate();
        logo.setTint(s.mark);
        Type.init(c);
        text.setColor(0xFFFFFFFF);
        text.setTypeface(Type.semibold);
        text.setLetterSpacing(-0.01f);
    }

    @Override public int getIntrinsicWidth() { return 640; }
    @Override public int getIntrinsicHeight() { return 360; }

    @Override protected void onBoundsChange(Rect b) {
        bg.setShader(new LinearGradient(0, b.top, 0, b.bottom, spec.top, spec.bottom,
                Shader.TileMode.CLAMP));
        light.setShader(new RadialGradient(b.exactCenterX(), b.top, b.width() * 0.6f,
                0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        int lh = Math.round(b.height() * spec.markH);
        int lw = Math.round(lh * logo.getIntrinsicWidth() / (float) logo.getIntrinsicHeight());
        String word = spec.word == null ? "" : spec.word;
        float size = b.height() * (word.length() > 8 ? 0.15f : 0.19f);
        text.setTextSize(size);
        float tw = word.isEmpty() ? 0 : text.measureText(word), gap = word.isEmpty() ? 0 : b.height() * 0.07f;
        float total = lw + gap + tw;
        int l = Math.round(b.exactCenterX() - total / 2f), t = Math.round(b.exactCenterY() - lh / 2f);
        logo.setBounds(l, t, l + lw, t + lh);
        Paint.FontMetrics fm = text.getFontMetrics();
        textX = l + lw + gap;
        textY = b.exactCenterY() - (fm.ascent + fm.descent) / 2f;
    }

    @Override public void draw(Canvas c) {
        Rect b = getBounds();
        c.drawRect(b, bg);
        c.drawRect(b, light);
        logo.draw(c);
        if (spec.word != null) c.drawText(spec.word, textX, textY, text);
    }

    @Override public void setAlpha(int a) { bg.setAlpha(a); text.setAlpha(a); logo.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter f) { logo.setColorFilter(f); }
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
