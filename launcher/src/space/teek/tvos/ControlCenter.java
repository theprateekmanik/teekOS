package space.teek.tvos;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * teekOS Control Center, compact like tvOS: a status capsule (time · network · profile) and a
 * small dark-glass panel under it, top-right, drawn by the Home hook over any app.
 *
 *   [ Power Off ]  [ Wi-Fi  ]
 *   [  (large)  ]  [ Sleep  ]
 *   [ Now Playing ........ ]   one focus stop: OK play/pause, Left/Right previous/next
 *   ( PS5 ) ( Google TV ) ( Settings )
 */
final class ControlCenter {
    private ControlCenter() { }

    static final String ACTION_SHOW = "space.teek.tvos.SHOW_CC";
    static final String ACTION_CINEMATIC = "space.teek.tvos.CINEMATIC_CHANGED";
    /** Sent with extra "shown" so Home can blur itself behind the drawer (real blur over teekOS). */
    static final String ACTION_STATE = "space.teek.tvos.CC_STATE";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static View showing;

    static boolean isShowing() { return showing != null; }

    /** {@code powerOff} turns the TV off (the hook's global action). */
    static void show(Context c, int windowType, android.os.IBinder token, Runnable powerOff) {
        if (showing != null) return;
        Type.init(c);
        float dp = c.getResources().getDisplayMetrics().density;
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);

        int panelW = (int) (288 * dp), pad = (int) (10 * dp), gap = (int) (8 * dp);
        int inner = panelW - 2 * pad, half = (inner - gap) / 2, unit = (int) (54 * dp);

        FrameLayout root = new FrameLayout(c) {
            @Override public boolean dispatchKeyEvent(KeyEvent e) {
                int k = e.getKeyCode();
                if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) {
                    if (e.getAction() == KeyEvent.ACTION_UP) hide(wm);
                    return true;
                }
                return super.dispatchKeyEvent(e);
            }
        };
        root.setBackgroundColor(0x26000000);   // barely-there dim: blur sits only behind the glass   // light dim: the screen stays visible, like tvOS
        Runnable close = () -> hide(wm);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.END);
        col.setClipChildren(false);

        // Status capsule.
        LinearLayout status = new LinearLayout(c);
        status.setOrientation(LinearLayout.HORIZONTAL);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setBackground(glass(100 * dp, dp, 0x991C1C22));
        status.setPadding((int) (14 * dp), (int) (5 * dp), (int) (5 * dp), (int) (5 * dp));
        boolean h24 = android.text.format.DateFormat.is24HourFormat(c);
        status.addView(text(c, new SimpleDateFormat(h24 ? "HH:mm" : "h:mm a", Locale.US).format(new Date()), 13, Type.semibold, Color.WHITE));
        boolean online = online(c);
        ImageView sw = glyph(c, online ? R.drawable.ic_wifi : R.drawable.ic_wifi_off, 14, dp);
        if (online) sw.setImageTintList(ColorStateList.valueOf(0xFF0A84FF));
        status.addView(sw, margin((int) (10 * dp)));
        TextView av = avatar(c, dp, 24);
        if (Owner.NAME.isEmpty()) av.setVisibility(View.GONE);
        ((LinearLayout.LayoutParams) av.getLayoutParams()).leftMargin = (int) (8 * dp);
        status.addView(av);
        col.addView(status, wrap());

        LinearLayout panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(pad, pad, pad, pad);
        panel.setBackground(glass(24 * dp, dp, 0x991C1C22));
        panel.setClipChildren(false);
        panel.setClipToPadding(false);

        // Power Off (large) | Wi-Fi + Sleep.
        LinearLayout r1 = new LinearLayout(c);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        r1.setClipChildren(false);
        View power = bigTile(c, dp, half, 2 * unit + gap, R.drawable.ic_power, "Power Off");
        TextView powerLabel = (TextView) power.findViewWithTag("label");
        boolean[] armed = {false};
        power.setOnClickListener(v -> {
            if (!armed[0]) {
                armed[0] = true;
                powerLabel.setText("Press again");
                MAIN.postDelayed(() -> { armed[0] = false; powerLabel.setText("Power Off"); }, 4000);
                return;
            }
            close.run();
            MAIN.post(powerOff);
        });
        r1.addView(power);
        LinearLayout stack = new LinearLayout(c);
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.setClipChildren(false);
        View wifi = tile(c, dp, half, unit, online ? R.drawable.ic_wifi : R.drawable.ic_wifi_off,
                "Wi-Fi", online ? "Connected" : "Offline", () -> { close.run(); open(c, Settings.ACTION_WIFI_SETTINGS); });
        if (online) accent((View) wifi, 0xFF0A84FF);
        stack.addView(wifi);
        View sleep = tile(c, dp, half, unit, R.drawable.ic_sleep, "Sleep", "Standby", () -> { close.run(); MAIN.post(() -> sleep(c)); });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(half, unit);
        sl.topMargin = gap;
        stack.addView(sleep, sl);
        LinearLayout.LayoutParams stl = wrap();
        stl.leftMargin = gap;
        r1.addView(stack, stl);
        panel.addView(r1);

        // Now Playing (single focus stop).
        NowPlaying np = new NowPlaying(c, inner, (int) (60 * dp), dp);
        np.closer = close;
        LinearLayout.LayoutParams npl = new LinearLayout.LayoutParams(inner, (int) (60 * dp));
        npl.topMargin = gap;
        panel.addView(np.view, npl);

        // Picture: backlight and brightness, live (the TV's picture service applies them at once).
        if (canWrite(c)) {
            LinearLayout.LayoutParams xl = new LinearLayout.LayoutParams(inner, (int) (40 * dp));
            xl.topMargin = gap;
            panel.addView(slider(c, dp, inner, R.drawable.ic_backlight, "picture_backlight"), xl);
            LinearLayout.LayoutParams yl = new LinearLayout.LayoutParams(inner, (int) (40 * dp));
            yl.topMargin = gap;
            panel.addView(slider(c, dp, inner, R.drawable.ic_sun, "picture_brightness"), yl);
        }

        // Round shortcuts.
        LinearLayout r3 = new LinearLayout(c);
        r3.setOrientation(LinearLayout.HORIZONTAL);
        r3.setGravity(Gravity.CENTER);
        r3.setClipChildren(false);
        if (Actions.hasPlayStation(c))
            r3.addView(round(c, R.drawable.ic_games, "PlayStation", dp, () -> { close.run(); MAIN.post(() -> Actions.playStation(c)); }));
        r3.addView(round(c, R.drawable.ic_display, "Picture", dp, () -> { close.run(); picture(c); }));
        r3.addView(round(c, R.drawable.ic_settings, "Settings", dp, () -> { close.run(); open(c, Settings.ACTION_SETTINGS); }));
        LinearLayout.LayoutParams r3l = new LinearLayout.LayoutParams(inner, ViewGroup.LayoutParams.WRAP_CONTENT);
        r3l.topMargin = (int) (6 * dp);
        panel.addView(r3, r3l);

        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(panelW, ViewGroup.LayoutParams.WRAP_CONTENT);
        pl.topMargin = (int) (8 * dp);
        col.addView(panel, pl);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(panelW, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.TOP);
        lp.topMargin = (int) (24 * dp);
        lp.rightMargin = (int) (40 * dp);
        root.addView(col, lp);

        WindowManager.LayoutParams w = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, windowType,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        w.windowAnimations = 0;
        w.token = token;
        wm.addView(root, w);
        showing = root;
        root.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                root.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                int x = col.getLeft(), y = col.getTop();
                int[] rects = {x + status.getLeft(), y + status.getTop(), x + status.getRight(), y + status.getBottom(),
                        x + panel.getLeft(), y + panel.getTop(), x + panel.getRight(), y + panel.getBottom()};
                c.sendBroadcast(new Intent(ACTION_STATE).setPackage(c.getPackageName()).putExtra("shown", true)
                        .putExtra("rects", rects).putExtra("radii", new float[]{status.getHeight() / 2f, 24 * dp}));
            }
        });
        root.setTag(np);
        np.start();

        // Drops in from the top-right corner on the shared spring.
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(Motion.FADE_MS).start();
        col.setPivotX(panelW);
        col.setPivotY(0);
        col.setScaleX(0.92f);
        col.setScaleY(0.92f);
        col.setTranslationY(-12 * dp);
        col.animate().scaleX(1f).scaleY(1f).translationY(0).setDuration(Motion.FOCUS_MS).setInterpolator(Motion.FOCUS).start();
        (np.hasSession() ? np.view : power).requestFocus();
    }

    static void hide(WindowManager wm) {
        View v = showing;
        if (v == null) return;
        showing = null;
        v.getContext().sendBroadcast(new Intent(ACTION_STATE).setPackage(v.getContext().getPackageName()).putExtra("shown", false));
        if (v.getTag() instanceof NowPlaying) ((NowPlaying) v.getTag()).stop();
        v.animate().alpha(0f).setDuration(Motion.SETTLE_MS).withEndAction(() -> {
            try { wm.removeView(v); } catch (Exception ignored) { }
        }).start();
    }

    // ---------------------------------------------------------------- actions (any context)

    @SuppressWarnings("deprecation")
    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("teek", Context.MODE_MULTI_PROCESS);   // hook + launcher share it
    }

    private static void sleep(Context c) {
        try {
            c.startActivity(new Intent(Intent.ACTION_MAIN)
                    .setComponent(new ComponentName("com.android.systemui", "com.android.systemui.Somnambulator"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            c.startActivity(new Intent(c, StandbyActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    /** The TV's own Picture settings (mode, backlight, brightness, contrast…). */
    private static void picture(Context c) {
        try {
            c.startActivity(new Intent().setComponent(new ComponentName("com.android.tv.settings",
                    "com.android.tv.settings.partnercustomizer.picture.PictureActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) { open(c, Settings.ACTION_DISPLAY_SETTINGS); }
    }

    private static void googleTv(Context c) {
        c.sendBroadcast(new Intent(HomeKeyService.ACTION_PASSTHROUGH).setPackage(c.getPackageName()));
        try {
            StockHome.open(c);
        } catch (Exception ignored) { }
    }

    private static void open(Context c, String action) {
        try { c.startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception e) { c.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
    }

    private static boolean online(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) { return false; }
    }

    // ---------------------------------------------------------------- Now Playing

    /** One focusable row: art, title, artist, a hairline of progress; OK play/pause, Left/Right skip. */
    private static final class NowPlaying {
        final FrameLayout view;
        private final Context c;
        private final ImageView art, state;
        private final TextView title, artist;
        private final View fill;
        private final int trackW;
        private final float dp;
        private MediaController ctl;
        Runnable closer;
        private final MediaController.Callback cb = new MediaController.Callback() {
            @Override public void onMetadataChanged(MediaMetadata md) { MAIN.post(NowPlaying.this::bind); }
            @Override public void onPlaybackStateChanged(PlaybackState s) { MAIN.post(NowPlaying.this::bind); }
            @Override public void onSessionDestroyed() { MAIN.post(NowPlaying.this::attach); }
        };
        private final Runnable tick = new Runnable() {
            @Override public void run() { progress(); MAIN.postDelayed(this, 1000); }
        };

        NowPlaying(Context c, int w, int h, float dp) {
            this.c = c;
            this.dp = dp;
            view = new FrameLayout(c);
            Drawable idle = glass(18 * dp, dp, 0x14FFFFFF);
            view.setBackground(idle);
            int p = (int) (8 * dp);
            view.setPadding(p, p, (int) (12 * dp), p);

            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            art = new ImageView(c);
            GradientDrawable ab = new GradientDrawable();
            ab.setCornerRadius(9 * dp);
            ab.setColor(0x1FFFFFFF);
            art.setBackground(ab);
            art.setClipToOutline(true);
            art.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
            int as = h - 2 * p;
            row.addView(art, new LinearLayout.LayoutParams(as, as));
            LinearLayout t = new LinearLayout(c);
            t.setOrientation(LinearLayout.VERTICAL);
            title = text(c, "Not Playing", 13, Type.semibold, Color.WHITE);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.addView(title);
            artist = text(c, "", 11, Type.medium, 0x99FFFFFF);
            artist.setSingleLine(true);
            artist.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.addView(artist);
            FrameLayout track = new FrameLayout(c);
            GradientDrawable tb = new GradientDrawable();
            tb.setCornerRadius(2 * dp);
            tb.setColor(0x33FFFFFF);
            track.setBackground(tb);
            track.setClipToOutline(true);
            track.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
            fill = new View(c);
            fill.setBackgroundColor(Color.WHITE);
            track.addView(fill, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
            trackW = w - as - (int) (10 * dp) - (int) (30 * dp) - p - (int) (12 * dp);
            LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(trackW, (int) (3 * dp));
            kl.topMargin = (int) (5 * dp);
            t.addView(track, kl);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tl.leftMargin = (int) (10 * dp);
            row.addView(t, tl);
            state = new ImageView(c);
            state.setImageResource(R.drawable.ic_play);
            state.setImageTintList(ColorStateList.valueOf(0xF2FFFFFF));
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams((int) (20 * dp), (int) (20 * dp));
            sl.leftMargin = (int) (10 * dp);
            row.addView(state, sl);
            view.addView(row, Ui.match());

            GradientDrawable lit = new GradientDrawable();
            lit.setCornerRadius(18 * dp);
            lit.setColor(0xF2F5F5F7);
            view.setFocusable(true);
            view.setOnClickListener(v -> toggle());
            view.setOnKeyListener((v, code, e) -> {
                if (ctl == null || e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) { ctl.getTransportControls().skipToPrevious(); return true; }
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { ctl.getTransportControls().skipToNext(); return true; }
                return false;
            });
            view.setOnFocusChangeListener((v, f) -> {
                view.setBackground(f ? lit : idle);
                title.setTextColor(f ? 0xFF111114 : Color.WHITE);
                artist.setTextColor(f ? 0x99111114 : 0x99FFFFFF);
                state.setImageTintList(ColorStateList.valueOf(f ? 0xFF111114 : 0xF2FFFFFF));
                tb.setColor(f ? 0x26111114 : 0x33FFFFFF);
                v.setTranslationZ(f ? 8 * dp : 0);
                v.animate().scaleX(f ? 1.04f : 1f).scaleY(f ? 1.04f : 1f)
                        .setDuration(f ? Motion.FOCUS_MS : Motion.SETTLE_MS)
                        .setInterpolator(f ? Motion.FOCUS : Motion.SETTLE).start();
            });
        }

        boolean hasSession() { return ctl != null; }

        void start() { attach(); MAIN.post(tick); }

        void stop() {
            MAIN.removeCallbacks(tick);
            if (ctl != null) ctl.unregisterCallback(cb);
        }

        void attach() {
            if (ctl != null) ctl.unregisterCallback(cb);
            ctl = null;
            try {
                MediaSessionManager msm = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
                List<MediaController> l = msm.getActiveSessions(new ComponentName(c, MediaListener.class));
                for (MediaController mc : l) {
                    PlaybackState s = mc.getPlaybackState();
                    if (ctl == null || (s != null && s.getState() == PlaybackState.STATE_PLAYING)) ctl = mc;
                }
            } catch (Exception ignored) { }
            if (ctl != null) ctl.registerCallback(cb, MAIN);
            bind();
        }

        private void placeholder() {
            art.setImageResource(R.drawable.ic_music);
            art.setImageTintList(ColorStateList.valueOf(0x80FFFFFF));
            art.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int ap = (int) (10 * dp);
            art.setPadding(ap, ap, ap, ap);
        }

        void bind() {
            if (ctl == null) {
                fill.getLayoutParams().width = 0;
                fill.requestLayout();
                state.setVisibility(View.INVISIBLE);
                SharedPreferences p = prefs(c);
                String lt = p.getString("np_title", null), pkg = p.getString("np_pkg", null);
                if (lt == null || pkg == null) {   // nothing remembered yet
                    title.setText("Not Playing");
                    artist.setText("Nothing played yet");
                    placeholder();
                    return;
                }
                String app = pkg;
                try { app = String.valueOf(c.getPackageManager().getApplicationLabel(c.getPackageManager().getApplicationInfo(pkg, 0))); }
                catch (Exception ignored) { }
                String la = p.getString("np_artist", null);
                boolean hasTitle = !lt.trim().isEmpty();
                title.setText(hasTitle ? lt : app);
                artist.setText(hasTitle && la != null && !la.trim().isEmpty() && !la.equals(lt)
                        ? "Last played  ·  " + la : hasTitle ? "Last played  ·  " + app : "Last played");
                Bitmap b = android.graphics.BitmapFactory.decodeFile(new java.io.File(c.getFilesDir(), MediaListener.ART_FILE).getPath());
                if (b != null) {
                    art.setImageTintList(null);
                    art.setPadding(0, 0, 0, 0);
                    art.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    art.setImageBitmap(b);
                } else placeholder();
                state.setImageResource(R.drawable.ic_play);
                state.setVisibility(View.VISIBLE);
                return;
            }
            MediaMetadata md = ctl.getMetadata();
            String t = md == null ? null : md.getString(MediaMetadata.METADATA_KEY_TITLE);
            if (t == null && md != null) t = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            String ar = md == null ? null : md.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (ar == null && md != null) ar = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            if (ar == null) {
                try { ar = String.valueOf(c.getPackageManager().getApplicationLabel(
                        c.getPackageManager().getApplicationInfo(ctl.getPackageName(), 0))); } catch (Exception ignored) { }
            }
            title.setText(t != null ? t : "Unknown title");
            artist.setText(ar != null ? ar : "");
            Bitmap b = null;
            if (md != null) {
                b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
                if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            }
            if (b != null) {
                art.setImageTintList(null);
                art.setPadding(0, 0, 0, 0);
                art.setScaleType(ImageView.ScaleType.CENTER_CROP);
                art.setImageBitmap(b);
                fill.setBackgroundColor(Backdrop.accent(b));
            } else placeholder();
            PlaybackState s = ctl.getPlaybackState();
            boolean playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
            state.setVisibility(View.VISIBLE);
            state.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
            progress();
        }

        void toggle() {
            if (ctl == null) {   // last played: open the app it came from
                String pkg = prefs(c).getString("np_pkg", null);
                if (pkg == null) return;
                android.content.pm.PackageManager pm = c.getPackageManager();
                Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
                if (i == null) i = pm.getLaunchIntentForPackage(pkg);
                if (i == null) return;
                if (closer != null) closer.run();
                c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
            PlaybackState s = ctl.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) ctl.getTransportControls().pause();
            else ctl.getTransportControls().play();
        }

        void progress() {
            if (ctl == null) return;
            MediaMetadata md = ctl.getMetadata();
            PlaybackState s = ctl.getPlaybackState();
            long dur = md == null ? 0 : md.getLong(MediaMetadata.METADATA_KEY_DURATION);
            if (s == null || dur <= 0) { fill.getLayoutParams().width = 0; fill.requestLayout(); return; }
            long pos = s.getPosition();
            if (s.getState() == PlaybackState.STATE_PLAYING)
                pos += (long) ((android.os.SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
            pos = Math.max(0, Math.min(dur, pos));
            fill.getLayoutParams().width = Math.round(trackW * pos / (float) dur);
            fill.requestLayout();
        }
    }

    /** Sliders only where they'll work: permission granted and this TV keeps picture_* settings (MediaTek). */
    private static boolean canWrite(Context c) {
        return c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == android.content.pm.PackageManager.PERMISSION_GRANTED
                && Settings.Global.getString(c.getContentResolver(), "picture_backlight") != null;
    }

    /** iOS-style glass slider bound to a 0–100 global setting; Left/Right change it by 5. */
    private static View slider(Context c, float dp, int w, int icon, String key) {
        FrameLayout f = new FrameLayout(c);
        float r = 20 * dp;
        Drawable idle = glass(r, dp, 0x14FFFFFF);
        f.setBackground(idle);
        f.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
        });
        f.setClipToOutline(true);
        View fill = new View(c);
        fill.setBackgroundColor(0x40FFFFFF);
        f.addView(fill, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int p = (int) (12 * dp);
        row.setPadding(p, 0, p, 0);
        ImageView g = glyph(c, icon, 18, dp);
        row.addView(g);
        row.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView val = text(c, "", 12, Type.semibold, 0xE6FFFFFF);
        row.addView(val);
        f.addView(row, Ui.match());
        int[] v = {Settings.Global.getInt(c.getContentResolver(), key, 50)};
        Runnable show = () -> {
            fill.getLayoutParams().width = Math.round(w * v[0] / 100f);
            fill.requestLayout();
            val.setText(String.valueOf(v[0]));
        };
        show.run();
        f.setFocusable(true);
        f.setContentDescription(key);
        f.setOnKeyListener((x, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            int d = code == KeyEvent.KEYCODE_DPAD_RIGHT ? 5 : code == KeyEvent.KEYCODE_DPAD_LEFT ? -5 : 0;
            if (d == 0) return false;
            v[0] = Math.max(0, Math.min(100, v[0] + d));
            try { Settings.Global.putInt(c.getContentResolver(), key, v[0]); } catch (Exception ignored) { }
            show.run();
            return true;
        });
        f.setOnFocusChangeListener((x, on) -> {
            fill.setBackgroundColor(on ? 0xF2F5F5F7 : 0x40FFFFFF);
            g.setImageTintList(ColorStateList.valueOf(on ? 0xFF111114 : 0xF2FFFFFF));
            val.setTextColor(on ? 0xFF111114 : 0xE6FFFFFF);
            x.setTranslationZ(on ? 8 * dp : 0);
            x.animate().scaleX(on ? 1.03f : 1f).scaleY(on ? 1.03f : 1f)
                    .setDuration(on ? Motion.FOCUS_MS : Motion.SETTLE_MS).setInterpolator(on ? Motion.FOCUS : Motion.SETTLE).start();
        });
        return f;
    }

    // ---------------------------------------------------------------- components

    static Drawable glass(float radius, float dp, int tint) {
        Glass g = new Glass(radius, 1.2f * dp, 1f, true);
        if (tint == 0) return g;
        GradientDrawable t = new GradientDrawable();
        t.setCornerRadius(radius);
        t.setColor(tint);
        return new LayerDrawable(new Drawable[]{t, g});
    }

    /** Large square: glyph centred above its label. */
    private static View bigTile(Context c, float dp, int w, int h, int icon, String label) {
        LinearLayout t = new LinearLayout(c);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        Drawable idle = glass(18 * dp, dp, 0x14FFFFFF);
        t.setBackground(idle);
        ImageView g = glyph(c, icon, 34, dp);
        t.addView(g);
        TextView l = text(c, label, 12, Type.semibold, Color.WHITE);
        l.setTag("label");
        LinearLayout.LayoutParams ll = wrap();
        ll.topMargin = (int) (10 * dp);
        t.addView(l, ll);
        focusable(t, idle, dp, new TextView[]{l}, g, null);
        t.setLayoutParams(new LinearLayout.LayoutParams(w, h));
        return t;
    }

    /** Small tile: glyph left, label + state. */
    private static View tile(Context c, float dp, int w, int h, int icon, String label, String state, Runnable act) {
        LinearLayout t = new LinearLayout(c);
        t.setOrientation(LinearLayout.HORIZONTAL);
        t.setGravity(Gravity.CENTER_VERTICAL);
        int p = (int) (10 * dp);
        t.setPadding(p, 0, p, 0);
        Drawable idle = glass(18 * dp, dp, 0x14FFFFFF);
        t.setBackground(idle);
        ImageView g = glyph(c, icon, 18, dp);
        t.addView(g);
        LinearLayout txt = new LinearLayout(c);
        txt.setOrientation(LinearLayout.VERTICAL);
        TextView l = text(c, label, 12, Type.semibold, Color.WHITE);
        l.setSingleLine(true);
        txt.addView(l);
        TextView s = text(c, state, 10, Type.medium, 0x99FFFFFF);
        s.setSingleLine(true);
        txt.addView(s);
        t.addView(txt, margin((int) (9 * dp)));
        focusable(t, idle, dp, new TextView[]{l, s}, g, act);
        t.setLayoutParams(new LinearLayout.LayoutParams(w, h));
        return t;
    }

    /** Give a tile's glyph a resting colour (e.g. Wi-Fi blue); focus still inks it dark. */
    private static void accent(View tile, int color) {
        ImageView g = (ImageView) ((ViewGroup) tile).getChildAt(0);
        g.setImageTintList(ColorStateList.valueOf(color));
        g.setTag(color);
    }

    private static void focusable(View t, Drawable idle, float dp, TextView[] texts, ImageView g, Runnable act) {
        GradientDrawable lit = new GradientDrawable();
        lit.setCornerRadius(18 * dp);
        lit.setColor(0xF2F5F5F7);
        int[] base = new int[texts.length];
        for (int i = 0; i < texts.length; i++) base[i] = texts[i].getCurrentTextColor();
        t.setFocusable(true);
        if (act != null) t.setOnClickListener(v -> act.run());
        t.setOnFocusChangeListener((v, f) -> {
            t.setBackground(f ? lit : idle);
            for (int i = 0; i < texts.length; i++)
                texts[i].setTextColor(f ? ((base[i] & 0xFF000000) | 0x111114) : base[i]);
            if (g != null) g.setImageTintList(ColorStateList.valueOf(f ? (g.getTag() instanceof Integer ? (Integer) g.getTag() : 0xFF111114)
                    : (g.getTag() instanceof Integer ? (Integer) g.getTag() : 0xF2FFFFFF)));
            v.setTranslationZ(f ? 8 * dp : 0);
            v.animate().scaleX(f ? 1.05f : 1f).scaleY(f ? 1.05f : 1f)
                    .setDuration(f ? Motion.FOCUS_MS : Motion.SETTLE_MS)
                    .setInterpolator(f ? Motion.FOCUS : Motion.SETTLE).start();
        });
    }

    private static View round(Context c, int icon, String label, float dp, Runnable act) {
        View b = Ui.iconButton(c, icon, label, v -> act.run());
        int s = (int) (42 * dp);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(s, s);
        bl.leftMargin = bl.rightMargin = (int) (12 * dp);
        bl.topMargin = bl.bottomMargin = (int) (4 * dp);
        b.setLayoutParams(bl);
        int ip = (int) (11 * dp);
        b.setPadding(ip, ip, ip, ip);
        return b;
    }

    private static ImageView glyph(Context c, int icon, int sizeDp, float dp) {
        ImageView g = new ImageView(c);
        g.setImageResource(icon);
        g.setImageTintList(ColorStateList.valueOf(0xF2FFFFFF));
        g.setLayoutParams(new LinearLayout.LayoutParams((int) (sizeDp * dp), (int) (sizeDp * dp)));
        return g;
    }

    private static TextView avatar(Context c, float dp, int sizeDp) {
        TextView t = text(c, Owner.NAME.isEmpty() ? "" : Owner.NAME.substring(0, 1).toUpperCase(Locale.ROOT), 11, Type.bold, Color.WHITE);
        t.setGravity(Gravity.CENTER);
        GradientDrawable av = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF7B61FF, 0xFFFF5E8A});
        av.setShape(GradientDrawable.OVAL);
        t.setBackground(av);
        t.setLayoutParams(new LinearLayout.LayoutParams((int) (sizeDp * dp), (int) (sizeDp * dp)));
        return t;
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams margin(int left) {
        LinearLayout.LayoutParams l = wrap();
        l.leftMargin = left;
        return l;
    }

    private static TextView text(Context c, String s, float sp, android.graphics.Typeface tf, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTypeface(tf);
        t.setTextColor(color);
        return t;
    }
}
