package space.teek.tvos;

import java.util.Calendar;
import java.util.Random;

/** Twelve short greetings, three per part of the day; a fresh one each time you come home.
 *  {N} becomes ", <name>" (UserName), or nothing when no name is set. */
final class Greetings {
    private Greetings() { }

    private static final String[] MORNING = {        // 5:00–11:59
            "Good morning{N}.",
            "Morning{N}. Fresh start loaded.",
            "Rise and stream{N}.",
    };
    private static final String[] AFTERNOON = {      // 12:00–16:59
            "Welcome back{N}.",
            "Afternoon{N}. Plenty of day left.",
            "A little break{N}?",
    };
    private static final String[] EVENING = {        // 17:00–21:59
            "Good evening{N}.",
            "Evening{N}. What's on tonight?",
            "The couch is calling{N}.",
    };
    private static final String[] NIGHT = {          // 22:00–4:59
            "Still up{N}?",
            "Late night{N}. One more?",
            "Night owl hours{N}.",
    };

    private static final Random RND = new Random();

    /** A greeting for the current hour, never the same as {@code last}. */
    static String pick(android.content.Context c, String last) {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String[] set = h >= 5 && h < 12 ? MORNING : h < 17 && h >= 12 ? AFTERNOON
                : h >= 17 && h < 22 ? EVENING : NIGHT;
        String g;
        String n = UserName.get(c), name = n.isEmpty() ? "" : ", " + n;
        do { g = set[RND.nextInt(set.length)].replace("{N}", name); } while (g.equals(last) && set.length > 1);
        return g;
    }
}
