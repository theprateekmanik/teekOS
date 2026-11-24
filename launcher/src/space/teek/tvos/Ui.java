package space.teek.tvos;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small shared view builders. */
final class Ui {
    private Ui() { }

    /** Glass capsule button that turns solid white with dark text on focus (tvOS). */
    static TextView pill(Context c, String text, View.OnClickListener click) {
        float dp = c.getResources().getDisplayMetrics().density;
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(0xE6FFFFFF);
        t.setTypeface(Type.semibold);
        t.setPadding((int) (16 * dp), (int) (7 * dp), (int) (16 * dp), (int) (7 * dp));
        Glass glass = new Glass(100 * dp, 1.2f * dp, 1f, true);
        GradientDrawable solid = new GradientDrawable();
        solid.setCornerRadius(100 * dp);
        solid.setColor(0xFFF5F5F7);
        t.setBackground(glass);
        t.setFocusable(true);
        t.setOnClickListener(click);
        t.setOnFocusChangeListener((v, f) -> {
            t.setBackground(f ? solid : glass);
            t.setTextColor(f ? 0xFF111114 : 0xE6FFFFFF);
            v.setTranslationZ(f ? 8 * dp : 0);
            v.animate().scaleX(f ? 1.1f : 1f).scaleY(f ? 1.1f : 1f)
                    .setDuration(f ? Motion.FOCUS_MS : Motion.SETTLE_MS)
                    .setInterpolator(f ? Motion.FOCUS : Motion.SETTLE).start();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = (int) (10 * dp);
        t.setLayoutParams(lp);
        return t;
    }

    /** Round glass icon button (tvOS Control Center style): white disc + dark glyph on focus. */
    static View iconButton(Context c, int icon, String description, View.OnClickListener click) {
        float dp = c.getResources().getDisplayMetrics().density;
        android.widget.ImageView b = new android.widget.ImageView(c);
        b.setImageResource(icon);
        b.setContentDescription(description);
        int pad = (int) (8 * dp);
        b.setPadding(pad, pad, pad, pad);
        Glass glass = new Glass(100 * dp, 1.2f * dp, 1f, true);
        GradientDrawable solid = new GradientDrawable();
        solid.setShape(GradientDrawable.OVAL);
        solid.setColor(0xFFF5F5F7);
        b.setBackground(glass);
        b.setImageTintList(android.content.res.ColorStateList.valueOf(0xE6FFFFFF));
        b.setFocusable(true);
        b.setOnClickListener(click);
        b.setOnFocusChangeListener((v, f) -> {
            b.setBackground(f ? solid : glass);
            b.setImageTintList(android.content.res.ColorStateList.valueOf(f ? 0xFF111114 : 0xE6FFFFFF));
            v.setTranslationZ(f ? 8 * dp : 0);
            v.animate().scaleX(f ? 1.14f : 1f).scaleY(f ? 1.14f : 1f)
                    .setDuration(f ? Motion.FOCUS_MS : Motion.SETTLE_MS)
                    .setInterpolator(f ? Motion.FOCUS : Motion.SETTLE).start();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (34 * dp), (int) (34 * dp));
        lp.leftMargin = (int) (10 * dp);
        b.setLayoutParams(lp);
        return b;
    }

    /** Glass disc holding an app's own full-colour logo (no tint); white disc on focus. */
    static View logoButton(Context c, android.graphics.drawable.Drawable logo, String description, View.OnClickListener click) {
        float dp = c.getResources().getDisplayMetrics().density;
        android.widget.ImageView b = new android.widget.ImageView(c);
        b.setImageDrawable(logo);
        b.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        b.setContentDescription(description);
        int pad = (int) (9 * dp);
        b.setPadding(pad, pad, pad, pad);
        Glass glass = new Glass(100 * dp, 1.2f * dp, 1f, true);
        GradientDrawable solid = new GradientDrawable();
        solid.setShape(GradientDrawable.OVAL);
        solid.setColor(0xFFF5F5F7);
        b.setBackground(glass);
        b.setFocusable(true);
        b.setOnClickListener(click);
        b.setOnFocusChangeListener((v, f) -> {
            b.setBackground(f ? solid : glass);
            v.setTranslationZ(f ? 8 * dp : 0);
            v.animate().scaleX(f ? 1.14f : 1f).scaleY(f ? 1.14f : 1f)
                    .setDuration(f ? Motion.FOCUS_MS : Motion.SETTLE_MS)
                    .setInterpolator(f ? Motion.FOCUS : Motion.SETTLE).start();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (34 * dp), (int) (34 * dp));
        lp.leftMargin = (int) (10 * dp);
        b.setLayoutParams(lp);
        return b;
    }

    static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }
}
