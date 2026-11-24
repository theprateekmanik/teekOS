package space.teek.tvos;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Makes the remote's Home button open teekOS. This TV gives Google TV a Home priority that
 * sideloaded apps can't match, so instead of replacing it we:
 *  - catch KEYCODE_HOME (short press → teekOS, long press → Settings), and
 *  - send Google TV's home screen on to teekOS when it appears on its own (e.g. after boot).
 * Runs in its own process (":hook") so a launcher crash can't take the hook down with it.
 * Google TV stays installed and enabled; turning this service off restores stock behaviour.
 */
public class HomeKeyService extends AccessibilityService {

    static final String ACTION_PASSTHROUGH = "space.teek.tvos.PASSTHROUGH";
    static final String ACTION_POWER_OFF = "space.teek.tvos.POWER_OFF";
    private static final long LONG_PRESS_MS = 600;
    private static final long PASSTHROUGH_MS = 30 * 60 * 1000;

    private long homeDownAt;
    /** Until this time, Google TV may be shown (user opened it on purpose). */
    private long passthroughUntil;
    /** Crash-loop guard: bounces in the current window. */
    private long bounceWindowStart;
    private int bounces;

    private final BroadcastReceiver passthrough = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (ControlCenter.ACTION_SHOW.equals(i.getAction())) { showControlCenter(); return; }
            if (ACTION_POWER_OFF.equals(i.getAction())) {
                performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);   // TV: screen off / standby
            } else {
                passthroughUntil = SystemClock.uptimeMillis() + PASSTHROUGH_MS;
            }
        }
    };

    @Override protected void onServiceConnected() {
        android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
        info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        setServiceInfo(info);
        IntentFilter f = new IntentFilter(ACTION_PASSTHROUGH);
        f.addAction(ACTION_POWER_OFF);
        f.addAction(ControlCenter.ACTION_SHOW);
        registerReceiver(passthrough, f);
    }

    @Override public void onDestroy() {
        try { unregisterReceiver(passthrough); } catch (IllegalArgumentException ignored) { }
        super.onDestroy();
    }

    private void showControlCenter() {
        ControlCenter.show(this, android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null,
                () -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN));
    }

    private final android.os.Handler keys = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean longFired;
    private final Runnable longPress = () -> { longFired = true; showControlCenter(); };

    /**
     * Home: short press opens teekOS (and closes the Control Center if it's up); holding it for
     * 0.6 s opens the Control Center. The remote's settings key can't be intercepted (the system
     * handles it), so it keeps opening Google TV's own panel.
     */
    @Override protected boolean onKeyEvent(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0) {
                homeDownAt = SystemClock.uptimeMillis();
                longFired = false;
                keys.postDelayed(longPress, LONG_PRESS_MS);
            }
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_UP) {
            keys.removeCallbacks(longPress);
            if (longFired) return true;
            passthroughUntil = 0;
            bounces = 0;
            if (ControlCenter.isShowing()) ControlCenter.hide((android.view.WindowManager) getSystemService(WINDOW_SERVICE));
            openTeek();
            return true;
        }
        return true;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent ev) {
        if (ev.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        android.content.ComponentName stock = StockHome.get(this);   // this TV's own launcher
        if (stock == null || !stock.getPackageName().contentEquals(String.valueOf(ev.getPackageName()))) return;
        long now = SystemClock.uptimeMillis();
        // The remote's settings button (key 83) is handled by the system and opens Google TV's own
        // panel; it can't be intercepted, so it's left alone. The Control Center is on long-press Home.
        String cls = String.valueOf(ev.getClassName());
        if (!stock.getClassName().contentEquals(cls)) return;
        if (now < passthroughUntil) return;

        // If teekOS keeps failing to stay up, stop bouncing and let Google TV be.
        if (now - bounceWindowStart > 15_000) { bounceWindowStart = now; bounces = 0; }
        if (++bounces > 3) return;
        openTeek();
    }

    @Override public void onInterrupt() { }

    private void openTeek() {
        start(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setClassName(getPackageName(), HomeActivity.class.getName()));
    }

    private void start(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { startActivity(i); } catch (RuntimeException ignored) { }
    }
}
