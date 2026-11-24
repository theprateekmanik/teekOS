package space.teek.tvos;

import android.service.dreams.DreamService;

/** teekOS screensaver: the cosmos standby scene, backlight dimmed; any key wakes.
 *  Starting it (TV idle) also cleans up background apps. */
public class TeekDream extends DreamService {
    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(false);
        setContentView(Standby.build(this));
    }

    /** The TV has gone idle: close background apps quietly so it's quick when you come back. */
    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        Cleanup.run(this, false);
    }
}
