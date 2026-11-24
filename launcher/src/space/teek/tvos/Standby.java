package space.teek.tvos;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The rest-mode scene, shared by the screensaver (TeekDream) and the Sleep button
 * (StandbyActivity): near-black cosmos, the teekOS wordmark at the top and a quiet
 * "STANDBY · time" line under it. No text animation.
 */
final class Standby {
    private Standby() { }

    static View build(Context c) {
        Type.init(c);
        float dp = c.getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(c);
        root.addView(new CosmosView(c), Ui.match());

        LinearLayout text = new LinearLayout(c);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView mark = new TextView(c);
        SpannableStringBuilder s = new SpannableStringBuilder("teekOS");
        s.setSpan(new TypefaceSpan(Type.bold), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new TypefaceSpan(Type.light), 4, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        mark.setText(s);
        mark.setTextColor(0x8CFFFFFF);
        mark.setTextSize(24);
        mark.setLetterSpacing(-0.02f);
        TextView status = new TextView(c);
        status.setTextColor(0x4DFFFFFF);
        status.setTextSize(11);
        status.setTypeface(Type.regular);
        status.setLetterSpacing(0.16f);
        text.addView(mark, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = (int) (6 * dp);
        text.addView(status, sl);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tl.topMargin = (int) (52 * dp);
        root.addView(text, tl);

        // Static text: only the time changes, once a minute. No fades, no drift.
        Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            status.setText(("Standby  ·  " + new SimpleDateFormat("h:mm a", Locale.US).format(new Date()))
                    .toUpperCase(Locale.US));
            long ms = System.currentTimeMillis();
            status.postDelayed(tick[0], 60_000 - ms % 60_000 + 50);
        };
        tick[0].run();
        status.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { }
            @Override public void onViewDetachedFromWindow(View v) { v.removeCallbacks(tick[0]); }
        });
        return root;
    }
}
