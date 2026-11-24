package space.teek.tvos;

import android.animation.TimeInterpolator;
import android.view.animation.PathInterpolator;

/**
 * One place for every curve and duration, so all motion in teekOS feels like one system.
 * Springs are real damped harmonic oscillators (SwiftUI-style response + damping),
 * sampled as interpolators over a duration long enough for them to settle.
 */
final class Motion {
    private Motion() { }

    static final long FOCUS_MS = 560;
    static final long SETTLE_MS = 420;
    static final long GLIDE_MS = 650;
    static final long TILT_MS = 720;
    static final long FADE_MS = 420;
    static final long PRESS_MS = 110;

    /** Focus lift: lively, ~4% overshoot. */
    static final TimeInterpolator FOCUS = new Spring(0.42f, 0.68f, FOCUS_MS);
    /** Returning to rest: critically-near damped, no bounce. */
    static final TimeInterpolator SETTLE = new Spring(0.36f, 0.9f, SETTLE_MS);
    /** Scrolling: critically damped glide. */
    static final TimeInterpolator GLIDE = new Spring(0.55f, 1f, GLIDE_MS);
    /** Parallax tilt and edge bump recovery: soft wobble. */
    static final TimeInterpolator TILT = new Spring(0.5f, 0.55f, TILT_MS);
    /** Opacity changes. */
    static final TimeInterpolator EASE = new PathInterpolator(0.25f, 0.1f, 0.25f, 1f);

    static final class Spring implements TimeInterpolator {
        private final double zeta, omega, seconds;

        /** @param response period of the undamped spring in seconds; @param damping 0..1 */
        Spring(float response, float damping, long durationMs) {
            omega = 2 * Math.PI / response;
            zeta = damping;
            seconds = durationMs / 1000.0;
        }

        @Override public float getInterpolation(float t) {
            if (t >= 1f) return 1f;
            double x = t * seconds;
            if (zeta < 1) {
                double wd = omega * Math.sqrt(1 - zeta * zeta);
                double env = Math.exp(-zeta * omega * x);
                return (float) (1 - env * (Math.cos(wd * x) + (zeta * omega / wd) * Math.sin(wd * x)));
            }
            return (float) (1 - Math.exp(-omega * x) * (1 + omega * x));
        }
    }
}
