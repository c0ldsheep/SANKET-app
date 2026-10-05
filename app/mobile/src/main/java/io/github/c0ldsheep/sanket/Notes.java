package io.github.c0ldsheep.sanket;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;

/**
 * SANKET's notifications. The status notification is quiet and shows download progress with a
 * Stop button. Warnings vibrate twice, short, so a rider feels them without looking. A finished
 * download gets its own silent notice that opens the file.
 */
final class Notes {
    static final String STATUS = "status";
    static final String ALERTS = "alerts_v2";
    static final String DOWNLOADS = "downloads";
    static final int STATUS_ID = 1;
    static final int ALERT_ID = 2;
    private static final String OLD_ALERTS = "alerts";
    private static final long[] TWO_TAPS = {0L, 180L, 120L, 180L};

    private Notes() {}

    static void createChannels(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(STATUS, ctx.getString(R.string.channel_status),
                NotificationManager.IMPORTANCE_LOW));
        NotificationChannel alerts = new NotificationChannel(ALERTS, ctx.getString(R.string.channel_alerts),
                NotificationManager.IMPORTANCE_DEFAULT);
        alerts.enableVibration(true);
        alerts.setVibrationPattern(TWO_TAPS);
        nm.createNotificationChannel(alerts);
        nm.createNotificationChannel(new NotificationChannel(DOWNLOADS, ctx.getString(R.string.channel_downloads),
                NotificationManager.IMPORTANCE_LOW));
        // Channel settings cannot change after creation, so version 0.1's channel is replaced.
        nm.deleteNotificationChannel(OLD_ALERTS);
    }

    static Notification status(Context ctx, Snapshot s, Transfers transfers) {
        StatusWords w = StatusWords.of(ctx, s);
        Notification.Builder b = new Notification.Builder(ctx, STATUS)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(ctx.getColor(w.color))
                .setContentTitle(w.title)
                .setContentIntent(openApp(ctx))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE);
        int running = 0;
        int waiting = 0;
        long done = 0L;
        long total = 0L;
        boolean sizeKnown = true;
        for (Transfers.Item it : transfers.items()) {
            if (it.state == Transfers.State.RUNNING || it.state == Transfers.State.QUEUED) {
                running++;
                if (it.size > 0) {
                    done += it.shown();
                    total += it.size;
                } else {
                    sizeKnown = false;
                }
            } else if (it.state == Transfers.State.WAITING) {
                waiting++;
            }
        }
        String text;
        if (running > 0 && sizeKnown && total > 0) {
            int pct = (int) Math.min(100L, done * 100L / total);
            text = ctx.getResources().getQuantityString(R.plurals.note_downloading, running, running, pct);
            b.setProgress(1000, (int) Math.min(1000L, done * 1000L / total), false);
        } else if (running > 0) {
            text = ctx.getResources().getQuantityString(R.plurals.note_downloading_size_unknown, running, running);
            b.setProgress(0, 0, true);
        } else if (waiting > 0) {
            text = ctx.getResources().getQuantityString(R.plurals.note_waiting, waiting, waiting);
        } else {
            text = s.reasons.isEmpty() || !s.running ? ctx.getString(R.string.note_watching) : String.join(". ", s.reasons);
        }
        if (s.demo) {
            // The demo must never read like a real warning.
            b.setContentTitle(ctx.getString(R.string.demo_label, Math.round(s.demoSeconds)));
            text = w.title;
        }
        b.setContentText(text);
        if (s.protecting) {
            PendingIntent stop = PendingIntent.getBroadcast(ctx, 0, new Intent(ctx, StopReceiver.class),
                    PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_notification),
                    ctx.getString(R.string.action_stop), stop).build());
        }
        return b.build();
    }

    static void alert(Context ctx, String title, String text) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.notify(ALERT_ID, new Notification.Builder(ctx, ALERTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(ctx.getColor(R.color.status_protect))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_STATUS)
                .setContentIntent(openApp(ctx))
                .setAutoCancel(true)
                .build());
    }

    static void downloadDone(Context ctx, Transfers.Item it) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        Intent open = new Intent(ctx, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_DOWNLOAD, it.id);
        int id = 1000 + (it.id.hashCode() & 0xFFFF);
        nm.notify(id, new Notification.Builder(ctx, DOWNLOADS)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(ctx.getColor(R.color.status_clear))
                .setContentTitle(ctx.getString(R.string.note_done_title))
                .setContentText(ctx.getString(R.string.note_done_text, it.name))
                .setContentIntent(PendingIntent.getActivity(ctx, id, open,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .setAutoCancel(true)
                .build());
    }

    private static PendingIntent openApp(Context ctx) {
        Intent open = new Intent(ctx, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
