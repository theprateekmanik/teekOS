package space.teek.tvos;

import android.service.dreams.DreamService;

/** teekOS screensaver: the cosmos standby scene, backlight dimmed; any key wakes. */
public class TeekDream extends DreamService {
    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(false);
        setContentView(Standby.build(this));
    }
}
