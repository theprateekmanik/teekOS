package space.teek.tvos;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;

/** Fallback for the Sleep button when the system can't start the screensaver directly. */
public class StandbyActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 0.15f;   // dimmed, always-on feel
        getWindow().setAttributes(lp);
        setContentView(Standby.build(this));
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        return true;
    }
}
