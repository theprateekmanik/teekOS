package space.teek.tvos;

import android.content.Context;
import android.graphics.Typeface;

/** Inter (bundled, OFL) at fixed weights — the closest open match to Apple's SF Pro. */
final class Type {
    private Type() { }

    static Typeface light, regular, medium, semibold, bold;

    static void init(Context c) {
        if (regular != null) return;
        light = load(c, 300, "sans-serif-light");
        regular = load(c, 400, "sans-serif");
        medium = load(c, 500, "sans-serif-medium");
        semibold = load(c, 600, "sans-serif-medium");
        bold = load(c, 700, "sans-serif");
    }

    private static Typeface load(Context c, int weight, String fallback) {
        try {
            Typeface t = new Typeface.Builder(c.getAssets(), "fonts/Inter.ttf")
                    .setFontVariationSettings("'wght' " + weight)
                    .build();
            if (t != null) return t;
        } catch (RuntimeException ignored) { }
        return Typeface.create(fallback, weight >= 700 ? Typeface.BOLD : Typeface.NORMAL);
    }
}
