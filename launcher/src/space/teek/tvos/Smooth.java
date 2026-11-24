package space.teek.tvos;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.widget.ScrollView;

/** ScrollView that glides on a critically damped spring instead of jumping to focus. */
final class Smooth extends ScrollView {
    private ValueAnimator anim;

    Smooth(Context c) {
        super(c);
        setVerticalScrollBarEnabled(false);
        setClipChildren(false);
        setClipToPadding(false);
        setOverScrollMode(View.OVER_SCROLL_NEVER);
        setFillViewport(true);
    }

    @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) { return 0; }

    void glideTo(int y) {
        int max = Math.max(0, getChildAt(0).getHeight() - getHeight());
        int target = Math.min(Math.max(0, y), max);
        if (anim != null) anim.cancel();
        if (target == getScrollY()) return;
        anim = ValueAnimator.ofInt(getScrollY(), target);
        anim.setDuration(Motion.GLIDE_MS);
        anim.setInterpolator(Motion.GLIDE);
        anim.addUpdateListener(a -> scrollTo(0, (int) a.getAnimatedValue()));
        anim.start();
    }

    /** Scroll that puts the centre of a descendant at the given fraction of the viewport. */
    int centerOf(View descendant, float at) {
        Rect r = new Rect();
        descendant.getDrawingRect(r);
        ((android.view.ViewGroup) getChildAt(0)).offsetDescendantRectToMyCoords(descendant, r);
        return Math.max(0, r.centerY() - (int) (getHeight() * at));
    }
}
