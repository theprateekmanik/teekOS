package space.teek.tvos;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** teekOS-styled message: a dark Liquid Glass capsule near the bottom (replaces Android's toast). */
final class GlassToast {
    private GlassToast() { }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static View current;

    static void show(Context c, int icon, String text, long ms) {
        MAIN.post(() -> {
            Type.init(c);
            float dp = c.getResources().getDisplayMetrics().density;
            WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
            if (current != null) { try { wm.removeView(current); } catch (Exception ignored) { } current = null; }

            LinearLayout v = new LinearLayout(c);
            v.setOrientation(LinearLayout.HORIZONTAL);
            v.setGravity(Gravity.CENTER_VERTICAL);
            v.setBackground(ControlCenter.glass(100 * dp, dp, 0xE6141418));
            v.setPadding((int) (18 * dp), (int) (11 * dp), (int) (22 * dp), (int) (11 * dp));
            if (icon != 0) {
                ImageView g = new ImageView(c);
                g.setImageResource(icon);
                g.setImageTintList(android.content.res.ColorStateList.valueOf(0xFF0A84FF));
                v.addView(g, new LinearLayout.LayoutParams((int) (20 * dp), (int) (20 * dp)));
            }
            TextView t = new TextView(c);
            t.setText(text);
            t.setTextSize(14);
            t.setTypeface(Type.semibold);
            t.setTextColor(Color.WHITE);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            tl.leftMargin = icon != 0 ? (int) (10 * dp) : 0;
            v.addView(t, tl);

            boolean inApp = c instanceof Activity;
            WindowManager.LayoutParams w = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    inApp ? WindowManager.LayoutParams.TYPE_APPLICATION_PANEL : WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            w.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            w.y = (int) (48 * dp);
            w.windowAnimations = 0;
            if (inApp) w.token = ((Activity) c).getWindow().getDecorView().getWindowToken();
            try { wm.addView(v, w); } catch (Exception e) { return; }
            current = v;
            v.setAlpha(0f);
            v.setTranslationY(16 * dp);
            v.animate().alpha(1f).translationY(0).setDuration(Motion.FOCUS_MS).setInterpolator(Motion.FOCUS).start();
            MAIN.postDelayed(() -> v.animate().alpha(0f).setDuration(Motion.SETTLE_MS).withEndAction(() -> {
                try { wm.removeView(v); } catch (Exception ignored) { }
                if (current == v) current = null;
            }).start(), ms);
        });
    }
}
