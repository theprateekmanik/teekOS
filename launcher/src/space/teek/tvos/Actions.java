package space.teek.tvos;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.widget.Toast;

import java.util.Locale;

/** Things a tile or button can do, shared by Home and All Apps. */
final class Actions {
    private Actions() { }

    /** HDMI 3, where the PS5 is plugged in; used when CEC doesn't name it (e.g. PS5 unplugged). */
    private static final String PS_FALLBACK = "com.mediatek.tvinput/.hdmi.HDMIInputService/HW7";

    private static final String EXPANDER = "teek.expander";

    /** Press; Home recedes and softens; the app's own window grows out of the tile. */
    static void open(Activity a, TileView t) {
        t.press(() -> expand(a, t, () -> {
            if (AppStore.PS_KEY.equals(t.app.key)) { playStation(a); return true; }
            try {
                // The app dissolves in over the settled, blurred Home (R.anim.hold keeps it).
                a.startActivity(t.app.launch, ActivityOptions.makeCustomAnimation(
                        a, R.anim.app_in, R.anim.hold).toBundle());
                return true;
            } catch (Exception e) {
                Toast.makeText(a, "Couldn't open " + t.app.label, Toast.LENGTH_SHORT).show();
                return false;
            }
        }));
    }

    interface Launch { boolean run(); }

    private static final String BLUR = "teek.blur";

    /**
     * "Home recedes": Home shrinks back a touch, darkens and softly blurs (a tiny blurred snapshot
     * faded in over it: Android 11 has no cheap live blur) while the tile's own art zooms toward
     * full screen and fades out by halfway; then the app fades in. One layer, no duplicate logo.
     */
    private static void expand(Activity a, TileView t, Launch launch) {
        // tvOS launch: the icon glows, its layers slide apart with depth (art drifts one way,
        // the light the other), then the whole card glides forward as the app fades in over it.
        // Translations and uniform scale only, so the art stays crisp and undistorted.
        final android.view.View home = ((android.view.ViewGroup) a.findViewById(android.R.id.content)).getChildAt(0);
        final android.view.View art = t.card.getChildAt(0), gloss = t.card.getChildAt(1), rim = t.card.getChildAt(2);
        final float s0 = t.card.getScaleX(), dp = a.getResources().getDisplayMetrics().density;
        final float g0 = gloss.getAlpha(), r0 = rim.getAlpha(), gy0 = gloss.getTranslationY();
        final android.view.animation.Interpolator ease = new android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f);
        android.animation.ValueAnimator go = android.animation.ValueAnimator.ofFloat(0f, 1f);
        go.setDuration(900);
        go.setInterpolator(new android.view.animation.LinearInterpolator());
        boolean[] asked = {false};
        go.addUpdateListener(an -> {
            float f = (float) an.getAnimatedValue();
            float glow = ease.getInterpolation(Math.min(1f, f / 0.35f));            // 0–35%
            float depth = ease.getInterpolation(Math.min(1f, f / 0.45f));           // 0–45%
            float zoom = ease.getInterpolation(Math.max(0f, (f - 0.25f) / 0.75f));  // 25–100%
            gloss.setAlpha(g0 + (1f - g0) * glow);
            rim.setAlpha(r0 + (1f - r0) * glow);
            art.setTranslationY(-2f * dp * depth);              // art rises toward you
            art.setScaleX(1f + 0.04f * depth); art.setScaleY(1f + 0.04f * depth);  // edges stay covered
            gloss.setTranslationY(gy0 + 10f * dp * depth);      // light slides the other way
            float s = s0 * (1f + 0.32f * zoom);
            t.card.setScaleX(s); t.card.setScaleY(s);
            t.card.setTranslationZ(20f * dp + 30f * dp * zoom);
            home.setAlpha(f < 0.5f ? 1f : Math.max(0f, 1f - (f - 0.5f) / 0.5f));
            if (!asked[0] && f >= 0.4f) {
                asked[0] = true;
                if (!launch.run()) { an.cancel(); settleHome(a); }
            }
        });
        go.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator an) {
                t.card.postDelayed(() -> {
                    gloss.setAlpha(g0); rim.setAlpha(r0); gloss.setTranslationY(gy0); art.setTranslationY(0); art.setScaleX(1f); art.setScaleY(1f);
                    t.card.setScaleX(s0); t.card.setScaleY(s0);
                }, 600);
            }
        });
        go.start();
    }

    static void blur(android.graphics.Bitmap b, int r) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h], tmp = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 2; pass++) {
            for (int y = 0; y < h; y++) line(px, tmp, y * w, 1, w, r);
            for (int x = 0; x < w; x++) line(tmp, px, x, w, h, r);
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static void line(int[] in, int[] out, int start, int step, int n, int r) {
        for (int i = 0; i < n; i++) {
            int rr = 0, g = 0, bb = 0, c = 0;
            for (int j = Math.max(0, i - r); j <= Math.min(n - 1, i + r); j++) {
                int p = in[start + j * step];
                rr += (p >> 16) & 255; g += (p >> 8) & 255; bb += p & 255; c++;
            }
            out[start + i * step] = 0xFF000000 | (rr / c) << 16 | (g / c) << 8 | (bb / c);
        }
    }

    /** Hidden (app took over): drop the expansion and leave Home zoomed-in, ready to settle back. */
    static void endLaunch(Activity a) {
        android.view.ViewGroup decor = (android.view.ViewGroup) a.getWindow().getDecorView();
        for (int i = decor.getChildCount() - 1; i >= 0; i--) {
            Object tag = decor.getChildAt(i).getTag();
            if (EXPANDER.equals(tag) || BLUR.equals(tag)) decor.removeViewAt(i);
        }
    }

    /** Back on screen: if we zoomed through into an app, Home settles back out of the zoom. */
    static void settleHome(Activity a) {
        endLaunch(a);
        android.view.View home = ((android.view.ViewGroup) a.findViewById(android.R.id.content)).getChildAt(0);
        if (home.getScaleX() == 1f && home.getAlpha() == 1f) return;
        home.animate().cancel();
        home.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(Motion.GLIDE_MS)   // sharpen + settle
                .setInterpolator(Motion.GLIDE).start();
    }

    /** Switches the TV to the PS5's HDMI input; selecting it over CEC also wakes the console. */
    static void playStation(Context a) {
        String in = playStationInput(a);
        if (in == null) { Toast.makeText(a, "No PlayStation found on HDMI", Toast.LENGTH_SHORT).show(); return; }
        Intent i = new Intent(Intent.ACTION_VIEW,
                TvContract.buildChannelUriForPassthroughInput(in))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { a.startActivity(i); } catch (Exception e) {
            Toast.makeText(a, "Couldn't switch to PlayStation", Toast.LENGTH_SHORT).show();
        }
    }

    /** The CEC input named "PlayStation…"/"PS5" if the console is announcing itself, else HDMI 3. */
    private static String playStationInput(Context c) {
        SharedPreferences prefs = c.getSharedPreferences("teek", Context.MODE_PRIVATE);
        TvInputManager tim = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim != null) {
            for (TvInputInfo in : tim.getTvInputList()) {
                if (in.getType() != TvInputInfo.TYPE_HDMI) continue;
                CharSequence l = in.loadLabel(c);
                String s = l == null ? "" : l.toString().toLowerCase(Locale.ROOT);
                if (s.contains("playstation") || s.contains("ps5")) {
                    if (in.getParentId() != null) prefs.edit().putString("ps_hw", in.getParentId()).apply();
                    return in.getId();
                }
            }
        }
        return prefs.getString("ps_hw", Owner.PS_ALWAYS ? PS_FALLBACK : null);
    }

    /** Is a PlayStation known on this TV (seen over HDMI-CEC now or before)? */
    static boolean hasPlayStation(Context c) {
        return Owner.PS_ALWAYS || playStationInput(c) != null;
    }

    /** Start the teekOS screensaver now (system dream), or the in-app standby if unavailable. */
    static void sleep(Activity a) {
        try {
            a.startActivity(new Intent(Intent.ACTION_MAIN)
                    .setComponent(new ComponentName("com.android.systemui",
                            "com.android.systemui.Somnambulator"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            a.startActivity(new Intent(a, StandbyActivity.class));
            a.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        }
    }

    /** Turn the TV off (standby), via the Home hook's accessibility global action. */
    static void powerOff(Activity a) {
        new GlassMenu("Turn off TV?", null)
                .destructive("Turn Off", () -> a.sendBroadcast(
                        new Intent(HomeKeyService.ACTION_POWER_OFF).setPackage(a.getPackageName())))
                .add("Cancel", null)
                .show(a);
    }

    static void googleTv(Activity a) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setComponent(new ComponentName("com.google.android.apps.tv.launcherx",
                        "com.google.android.apps.tv.launcherx.home.HomeActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // Tell the Home hook this visit to Google TV is on purpose.
        a.sendBroadcast(new Intent(HomeKeyService.ACTION_PASSTHROUGH).setPackage(a.getPackageName()));
        try { a.startActivity(i); } catch (Exception e) {
            Toast.makeText(a, "Google TV home isn't available", Toast.LENGTH_SHORT).show();
        }
    }
}
