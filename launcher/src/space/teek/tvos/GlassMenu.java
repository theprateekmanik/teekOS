package space.teek.tvos;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * tvOS-style context menu: a dark Liquid Glass panel over a dimmed screen. The focused row
 * becomes a white capsule with dark text; destructive rows are red. Springs in, fades out.
 */
final class GlassMenu {

    static final class Item {
        final String label;
        final boolean destructive;
        final Runnable action;

        Item(String label, boolean destructive, Runnable action) {
            this.label = label;
            this.destructive = destructive;
            this.action = action;
        }
    }

    /** A round icon control. {@code stay} controls act without closing the menu (moves). */
    static final class Control {
        final int icon;
        final String label;
        final boolean destructive, stay;
        final Runnable action;

        private Control(int icon, String label, boolean destructive, boolean stay, Runnable action) {
            this.icon = icon; this.label = label; this.destructive = destructive;
            this.stay = stay; this.action = action;
        }
    }

    static Control move(int icon, String label, Runnable a) { return new Control(icon, label, false, true, a); }
    static Control act(int icon, String label, Runnable a) { return new Control(icon, label, false, false, a); }
    static Control danger(int icon, String label, Runnable a) { return new Control(icon, label, true, false, a); }

    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());

    private final List<Item> items = new ArrayList<>();
    private final List<String> rowHeads = new ArrayList<>();
    private final List<Control[]> rows = new ArrayList<>();
    private final String title, subtitle;

    GlassMenu(String title, String subtitle) {
        this.title = title;
        this.subtitle = subtitle;
    }

    /** A centred row of icon controls under an optional small caps heading. */
    GlassMenu row(String heading, Control... controls) {
        rowHeads.add(heading);
        rows.add(controls);
        return this;
    }

    GlassMenu add(String label, Runnable action) { items.add(new Item(label, false, action)); return this; }
    GlassMenu destructive(String label, Runnable action) { items.add(new Item(label, true, action)); return this; }

    void show(Activity a) {
        Type.init(a);
        float dp = a.getResources().getDisplayMetrics().density;
        Dialog d = new Dialog(a, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        Window w = d.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setDimAmount(rows.isEmpty() ? 0.55f : 0.4f);   // lighter when moves happen live behind
        w.setWindowAnimations(0);

        LinearLayout panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding((int) (12 * dp), (int) (18 * dp), (int) (12 * dp), (int) (12 * dp));
        GradientDrawable body = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xF52A2A30, 0xF718181C});
        body.setCornerRadius(26 * dp);
        panel.setBackground(new LayerDrawable(new Drawable[]{body, new Glass(26 * dp, 1.2f * dp, 1f, false)}));
        panel.setClipChildren(false);
        panel.setClipToPadding(false);

        TextView t = new TextView(a);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(17);
        t.setTypeface(Type.semibold);
        t.setPadding((int) (14 * dp), 0, (int) (14 * dp), 0);
        panel.addView(t);
        if (subtitle != null) {
            TextView s = new TextView(a);
            s.setText(subtitle);
            s.setTextColor(0x8CFFFFFF);
            s.setTextSize(12);
            s.setTypeface(Type.regular);
            s.setPadding((int) (14 * dp), (int) (2 * dp), (int) (14 * dp), 0);
            panel.addView(s);
        }
        View gap = new View(a);
        panel.addView(gap, new LinearLayout.LayoutParams(1, (int) (10 * dp)));

        // Text actions first, as before.
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        list.setClipToPadding(false);
        list.setPadding(0, (int) (2 * dp), 0, (int) (2 * dp));
        for (Item it : items) list.addView(row(a, d, it, dp));
        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipChildren(false);
        sv.setClipToPadding(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.addView(list);
        panel.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // Then a small section of icon-only controls at the bottom (e.g. "Move").
        View firstControl = null;
        TextView caption = new TextView(a);
        caption.setTextSize(12);
        caption.setTypeface(Type.medium);
        caption.setGravity(Gravity.CENTER);
        caption.setTextColor(0x99FFFFFF);
        caption.setText(" ");
        if (!rows.isEmpty() && !items.isEmpty()) {
            View rule = new View(a);
            rule.setBackgroundColor(0x1FFFFFFF);
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, (int) dp));
            rl.topMargin = (int) (10 * dp);
            rl.leftMargin = rl.rightMargin = (int) (14 * dp);
            panel.addView(rule, rl);
        }
        for (int r = 0; r < rows.size(); r++) {
            if (rowHeads.get(r) != null) {
                TextView hd = new TextView(a);
                hd.setText(rowHeads.get(r).toUpperCase(java.util.Locale.US));
                hd.setTextSize(10);
                hd.setTypeface(Type.semibold);
                hd.setLetterSpacing(0.16f);
                hd.setTextColor(0x66FFFFFF);
                hd.setPadding((int) (14 * dp), 0, (int) (14 * dp), 0);
                LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                hl.topMargin = (int) (12 * dp);
                hl.bottomMargin = (int) (8 * dp);
                panel.addView(hd, hl);
            }
            LinearLayout line = new LinearLayout(a);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER);
            line.setClipChildren(false);
            line.setClipToPadding(false);
            for (Control c : rows.get(r)) {
                View btn = control(a, d, c, caption, dp);
                if (firstControl == null) firstControl = btn;
                line.addView(btn);
            }
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            ll.topMargin = rowHeads.get(r) == null && r > 0 ? (int) (14 * dp) : 0;
            panel.addView(line, ll);
        }
        if (!rows.isEmpty()) {
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cl.topMargin = (int) (10 * dp);
            panel.addView(caption, cl);
        }

        FrameLayout host = new FrameLayout(a);
        host.setClipChildren(false);
        host.addView(panel, new FrameLayout.LayoutParams((int) (340 * dp),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        d.setContentView(host);
        w.setLayout((int) (380 * dp), ViewGroup.LayoutParams.WRAP_CONTENT);

        panel.setAlpha(0f);
        panel.setScaleX(0.9f);
        panel.setScaleY(0.9f);
        d.setOnShowListener(x -> panel.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(Motion.FOCUS_MS).setInterpolator(Motion.FOCUS).start());
        d.show();
        if (list.getChildCount() > 0) list.getChildAt(0).requestFocus();
        else if (firstControl != null) firstControl.requestFocus();
    }

    private static View control(Activity a, Dialog d, Control c, TextView caption, float dp) {
        android.widget.ImageView b = new android.widget.ImageView(a);
        b.setImageResource(c.icon);
        b.setContentDescription(c.label);
        int pad = (int) (13 * dp);
        b.setPadding(pad, pad, pad, pad);
        GradientDrawable idle = new GradientDrawable();
        idle.setShape(GradientDrawable.OVAL);
        idle.setColor(0x1FFFFFFF);
        Drawable idleBg = new LayerDrawable(new Drawable[]{idle, new Glass(100 * dp, 1.2f * dp, 1f, false)});
        GradientDrawable on = new GradientDrawable();
        on.setShape(GradientDrawable.OVAL);
        on.setColor(0xFFF5F5F7);
        int iconIdle = c.destructive ? 0xFFFF6961 : 0xE6FFFFFF;
        int iconOn = c.destructive ? 0xFFD70015 : 0xFF111114;
        b.setBackground(idleBg);
        b.setImageTintList(android.content.res.ColorStateList.valueOf(iconIdle));
        b.setFocusable(true);
        b.setOnFocusChangeListener((v, f) -> {
            b.setBackground(f ? on : idleBg);
            b.setImageTintList(android.content.res.ColorStateList.valueOf(f ? iconOn : iconIdle));
            v.setTranslationZ(f ? 8 * dp : 0);
            v.animate().scaleX(f ? 1.14f : 1f).scaleY(f ? 1.14f : 1f)
                    .setDuration(f ? Motion.FOCUS_MS : Motion.SETTLE_MS)
                    .setInterpolator(f ? Motion.FOCUS : Motion.SETTLE).start();
            if (!f) caption.setText(" ");
            if (f) {
                caption.setText(c.label);   // icons carry no text; name the focused one
                caption.setTextColor(c.destructive ? 0xFFFF6961 : 0xCCFFFFFF);
            }
        });
        b.setOnClickListener(v -> {
            if (c.stay) {
                // Act in place: a quick press bounce; the grid updates live behind the menu.
                v.animate().scaleX(1f).scaleY(1f).setDuration(Motion.PRESS_MS).setInterpolator(Motion.EASE)
                        .withEndAction(() -> v.animate().scaleX(1.14f).scaleY(1.14f)
                                .setDuration(Motion.FOCUS_MS).setInterpolator(Motion.FOCUS).start()).start();
                if (c.action != null) c.action.run();
            } else {
                d.dismiss();
                if (c.action != null) MAIN.post(c.action);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (50 * dp), (int) (50 * dp));
        lp.leftMargin = lp.rightMargin = (int) (6 * dp);
        b.setLayoutParams(lp);
        return b;
    }

    private static View row(Activity a, Dialog d, Item it, float dp) {
        TextView r = new TextView(a);
        r.setText(it.label);
        r.setTextSize(15);
        r.setTypeface(Type.medium);
        int idle = it.destructive ? 0xFFFF6961 : 0xE6FFFFFF;
        int active = it.destructive ? 0xFFD70015 : 0xFF111114;
        r.setTextColor(idle);
        r.setPadding((int) (14 * dp), (int) (11 * dp), (int) (14 * dp), (int) (11 * dp));
        GradientDrawable sel = new GradientDrawable();
        sel.setCornerRadius(14 * dp);
        sel.setColor(0xFFF5F5F7);
        r.setFocusable(true);
        r.setOnFocusChangeListener((v, f) -> {
            r.setBackground(f ? sel : null);
            r.setTextColor(f ? active : idle);
            v.setTranslationZ(f ? 6 * dp : 0);
            v.animate().scaleX(f ? 1.04f : 1f).scaleY(f ? 1.04f : 1f)
                    .setDuration(f ? Motion.FOCUS_MS : Motion.SETTLE_MS)
                    .setInterpolator(f ? Motion.FOCUS : Motion.SETTLE).start();
        });
        r.setOnClickListener(v -> {
            d.dismiss();
            if (it.action != null) MAIN.post(it.action);   // not v.post: v is in the dismissed window
        });
        return r;
    }
}
