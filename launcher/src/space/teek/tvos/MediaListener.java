package space.teek.tvos;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.service.notification.NotificationListenerService;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/**
 * Exists only so teekOS may read the active media sessions (title, artist, art, playback state)
 * for the Control Center's Now Playing row. It never reads or touches notifications.
 * It also remembers the last thing played ("Last played"), so the row isn't empty afterwards.
 * Enabled with `scripts/install.sh --now-playing`; rollback.sh disallows it again.
 */
public class MediaListener extends NotificationListenerService {

    static final String ART_FILE = "np_last.png";

    private MediaSessionManager msm;
    private final java.util.Map<MediaController, MediaController.Callback> watched = new java.util.HashMap<>();
    private final MediaSessionManager.OnActiveSessionsChangedListener changed = this::watch;

    @Override public void onListenerConnected() {
        msm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        try {
            ComponentName me = new ComponentName(this, MediaListener.class);
            msm.addOnActiveSessionsChangedListener(changed, me);
            watch(msm.getActiveSessions(me));
        } catch (Exception ignored) { }
    }

    @Override public void onListenerDisconnected() {
        try { msm.removeOnActiveSessionsChangedListener(changed); } catch (Exception ignored) { }
        for (java.util.Map.Entry<MediaController, MediaController.Callback> e : watched.entrySet()) e.getKey().unregisterCallback(e.getValue());
        watched.clear();
    }

    private void watch(List<MediaController> list) {
        if (list == null) return;
        for (MediaController c : list) {
            boolean known = false;
            for (MediaController w : watched.keySet()) if (w.getSessionToken().equals(c.getSessionToken())) known = true;
            if (known) continue;
            MediaController.Callback k = cb(c);
            watched.put(c, k);
            c.registerCallback(k);
            remember(c);
        }
    }

    private MediaController.Callback cb(MediaController c) {
        return new MediaController.Callback() {
            @Override public void onMetadataChanged(MediaMetadata m) { remember(c); }
            @Override public void onPlaybackStateChanged(PlaybackState s) { remember(c); }
        };
    }

    /** Save what's playing (only while it is actually playing, so "last played" is real). */
    private void remember(MediaController c) {
        PlaybackState s = c.getPlaybackState();
        MediaMetadata m = c.getMetadata();
        if (m == null || s == null || s.getState() != PlaybackState.STATE_PLAYING) return;
        String title = m.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (title == null || title.trim().isEmpty()) title = m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        if (title == null) title = "";   // some apps (YouTube between videos) send none: the row shows the app name
        String artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (artist == null) artist = m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        SharedPreferences p = ControlCenter.prefs(this);
        boolean same = title.equals(p.getString("np_title", null)) && c.getPackageName().equals(p.getString("np_pkg", null));
        p.edit().putString("np_title", title).putString("np_artist", artist).putString("np_pkg", c.getPackageName())
                .putLong("np_at", System.currentTimeMillis()).commit();
        if (same) return;
        Bitmap b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        File f = new File(getFilesDir(), ART_FILE);
        if (b == null) { f.delete(); return; }
        try (FileOutputStream o = new FileOutputStream(f)) {
            Bitmap small = Bitmap.createScaledBitmap(b, Math.min(256, b.getWidth()),
                    Math.round(Math.min(256, b.getWidth()) * b.getHeight() / (float) b.getWidth()), true);
            small.compress(Bitmap.CompressFormat.PNG, 100, o);
        } catch (Exception ignored) { }
    }
}
