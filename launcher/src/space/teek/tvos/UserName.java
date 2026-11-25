package space.teek.tvos;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The name teekOS greets you with. Asked once on first launch ("What should teekOS call you?"),
 * changeable from the avatar in the Control Center. The owner's build starts with Owner.NAME.
 * (Android doesn't give apps a Google account's real name, so we ask.)
 */
final class UserName {
    private UserName() { }

    static String get(Context c) {
        String n = ControlCenter.prefs(c).getString("name", null);
        return n != null ? n : Owner.NAME;
    }

    /** First launch of a build without a built-in name: ask once. */
    static boolean shouldAsk(Context c) {
        return Owner.NAME.isEmpty() && !ControlCenter.prefs(c).getBoolean("name_asked", false);
    }

    static void ask(Activity a, Runnable done) {
        Type.init(a);
        float dp = a.getResources().getDisplayMetrics().density;
        Dialog d = new Dialog(a, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        Window w = d.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setDimAmount(0.6f);
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);

        LinearLayout p = new LinearLayout(a);
        p.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (26 * dp);
        p.setPadding(pad, pad, pad, (int) (20 * dp));
        p.setBackground(ControlCenter.glass(28 * dp, dp, 0xF2141418));

        TextView t = new TextView(a);
        t.setText("What should teekOS call you?");
        t.setTextColor(Color.WHITE);
        t.setTextSize(20);
        t.setTypeface(Type.semibold);
        p.addView(t);
        TextView s = new TextView(a);
        s.setText("We'll greet you with it. You can change it from the Control Center.");
        s.setTextColor(0x99FFFFFF);
        s.setTextSize(12);
        s.setTypeface(Type.regular);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = (int) (4 * dp);
        p.addView(s, sl);

        EditText e = new EditText(a);
        e.setSingleLine(true);
        e.setHint("Your name");
        e.setText(get(a));
        e.setSelection(e.getText().length());
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        e.setImeOptions(EditorInfo.IME_ACTION_DONE);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(0x66FFFFFF);
        e.setTextSize(20);
        e.setTypeface(Type.medium);
        GradientDrawable box = new GradientDrawable();
        box.setCornerRadius(14 * dp);
        box.setColor(0x26FFFFFF);
        box.setStroke((int) (1.5f * dp), 0x660A84FF);
        e.setBackground(box);
        int ep = (int) (14 * dp);
        e.setPadding(ep, ep, ep, ep);
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        el.topMargin = (int) (18 * dp);
        p.addView(e, el);

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        Runnable save = () -> {
            String n = e.getText().toString().trim();
            if (n.length() > 20) n = n.substring(0, 20);
            ControlCenter.prefs(a).edit().putString("name", n).putBoolean("name_asked", true).commit();
            hideKeyboard(a, e);
            d.dismiss();
            if (done != null) done.run();
        };
        TextView skip = Ui.pill(a, "Skip", v -> {
            ControlCenter.prefs(a).edit().putBoolean("name_asked", true).commit();
            hideKeyboard(a, e);
            d.dismiss();
            if (done != null) done.run();
        });
        TextView ok = Ui.pill(a, "Save", v -> save.run());
        row.addView(skip);
        row.addView(ok);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rl.topMargin = (int) (18 * dp);
        p.addView(row, rl);
        ok.setId(android.view.View.generateViewId());
        skip.setId(android.view.View.generateViewId());
        e.setNextFocusDownId(ok.getId());   // Down from the box lands on Save
        e.setOnKeyListener((v, code, ev) -> {
            if (code == KeyEvent.KEYCODE_DPAD_DOWN && ev.getAction() == KeyEvent.ACTION_DOWN) { ok.requestFocus(); return true; }
            return false;
        });
        e.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_DONE || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) { ok.requestFocus(); return true; }
            return false;
        });

        d.setContentView(p, new ViewGroup.LayoutParams((int) (460 * dp), ViewGroup.LayoutParams.WRAP_CONTENT));
        d.show();
        e.requestFocus();
    }

    private static void hideKeyboard(Activity a, EditText e) {
        InputMethodManager im = (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(e.getWindowToken(), 0);
    }
}
