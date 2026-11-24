package space.teek.tvos;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.util.HashMap;
import java.util.Map;

/**
 * FLIP reordering: remember where every child is drawn, change the layout, then spring each
 * child from its old spot to its new one. Neighbours glide aside as a tile moves through them.
 */
final class Flip {
    private Flip() { }

    static Map<View, int[]> capture(ViewGroup parent) {
        Map<View, int[]> m = new HashMap<>();
        for (int i = 0; i < parent.getChildCount(); i++) {
            View v = parent.getChildAt(i);
            int[] p = new int[2];
            v.getLocationInWindow(p);   // includes any in-flight translation: start from what's seen
            m.put(v, p);
        }
        return m;
    }

    /** Call right after changing the layout; animates on the next frame. */
    static void play(ViewGroup parent, Map<View, int[]> before, View lead) {
        parent.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                parent.getViewTreeObserver().removeOnPreDrawListener(this);
                for (Map.Entry<View, int[]> e : before.entrySet()) {
                    View v = e.getKey();
                    if (v.getParent() != parent) continue;
                    int[] now = new int[2];
                    v.getLocationInWindow(now);
                    float lx = now[0] - v.getTranslationX(), ly = now[1] - v.getTranslationY();
                    float dx = e.getValue()[0] - lx, dy = e.getValue()[1] - ly;
                    if (Math.abs(dx) < 1 && Math.abs(dy) < 1) continue;
                    v.animate().cancel();
                    v.setTranslationX(dx);
                    v.setTranslationY(dy);
                    boolean isLead = v == lead;
                    v.animate().translationX(0).translationY(0)
                            .setDuration(isLead ? Motion.FOCUS_MS : Motion.SETTLE_MS + 80)
                            .setInterpolator(isLead ? Motion.FOCUS : Motion.SETTLE).start();
                }
                return true;
            }
        });
    }
}
