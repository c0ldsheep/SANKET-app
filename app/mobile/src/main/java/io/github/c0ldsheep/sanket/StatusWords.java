package io.github.c0ldsheep.sanket;

import android.content.Context;
import java.util.List;

/**
 * The words and colours for the current status, shared by the screen and the notification so
 * they always say the same thing. Every colour comes with a sentence; colour is never the only
 * signal.
 */
final class StatusWords {
    final String title;
    final String body;
    final int color;
    final int tint;

    private StatusWords(String title, String body, int color, int tint) {
        this.title = title;
        this.body = body;
        this.color = color;
        this.tint = tint;
    }

    static StatusWords of(Context c, Snapshot s) {
        if (!s.running) {
            return new StatusWords(c.getString(R.string.status_off), c.getString(R.string.status_off_body),
                    R.color.status_off, R.color.status_off_tint);
        }
        if (s.offline == Snapshot.Offline.AIRPLANE) {
            return new StatusWords(c.getString(R.string.status_airplane), c.getString(R.string.status_airplane_body),
                    R.color.status_off, R.color.status_off_tint);
        }
        if (s.offline == Snapshot.Offline.NO_SIM) {
            return new StatusWords(c.getString(R.string.status_no_sim), c.getString(R.string.status_no_sim_body),
                    R.color.status_off, R.color.status_off_tint);
        }
        switch (s.level) {
            case WATCH:
                return new StatusWords(c.getString(R.string.status_watch),
                        c.getString(R.string.status_watch_body, reasons(s.reasons)),
                        R.color.status_watch, R.color.status_watch_tint);
            case PROTECT:
                return new StatusWords(c.getString(R.string.status_protect),
                        c.getString(R.string.status_protect_body, reasons(s.reasons)),
                        R.color.status_protect, R.color.status_protect_tint);
            case OFFLINE:
                return new StatusWords(c.getString(R.string.status_offline), c.getString(R.string.status_offline_body),
                        R.color.status_offline, R.color.status_offline_tint);
            default:
                int body = s.onWifi ? R.string.status_clear_wifi_body
                        : s.tech == Snapshot.Tech.OTHER ? R.string.status_clear_legacy_body
                        : s.protecting || s.demo ? R.string.status_clear_body : R.string.status_clear_downloads_body;
                return new StatusWords(c.getString(R.string.status_clear), c.getString(body),
                        R.color.status_clear, R.color.status_clear_tint);
        }
    }

    /** "Signal falling fast. Known dead zone here" from the policy's reasons. */
    private static String reasons(List<String> reasons) {
        return reasons.isEmpty() ? "" : String.join(". ", reasons);
    }
}
