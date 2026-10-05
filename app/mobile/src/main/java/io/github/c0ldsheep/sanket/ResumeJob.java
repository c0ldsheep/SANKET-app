package io.github.c0ldsheep.sanket;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Continues unfinished downloads by themselves, even when SANKET is not running: after the phone
 * restarts, after Android's daily limit for background work, or after a battery saver stopped the
 * app. Android runs this job when a network is available; it resumes the downloads and finishes
 * when nothing is moving any more. One job exists while downloads are unfinished, none otherwise.
 */
public final class ResumeJob extends JobService {
    static final int ID = 4201;
    private static final String TAG = "SanketResume";
    private static final long CHECK_MS = 5_000L;
    private static final long BACKOFF_MS = 30_000L;
    private static volatile boolean executing;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable check = this::check;
    private JobParameters params;
    private Engine engine;

    static void sync(Context ctx, Transfers transfers) {
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        if (js == null || executing) return;
        JobInfo pending = js.getPendingJob(ID);
        if (!transfers.busy()) {
            if (pending != null) js.cancel(ID);
            return;
        }
        boolean wifi = transfers.onlyWifiWaiting();
        if (pending != null) {
            NetworkRequest need = pending.getRequiredNetwork();
            boolean pendingWifi = need != null && need.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
            if (pendingWifi == wifi) return;
            js.cancel(ID);   // the downloads now wait for a different kind of network
        }
        int network = wifi ? JobInfo.NETWORK_TYPE_UNMETERED : JobInfo.NETWORK_TYPE_ANY;
        JobInfo job = new JobInfo.Builder(ID, new ComponentName(ctx, ResumeJob.class))
                .setRequiredNetworkType(network)
                .setPersisted(true)
                .setBackoffCriteria(BACKOFF_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build();
        try {
            js.schedule(job);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not schedule the resume job", e);
        }
    }

    @Override
    public boolean onStartJob(JobParameters p) {
        params = p;
        executing = true;
        engine = Engine.get(this);
        reportNetwork();
        engine.transfers.resumeWaiting();
        handler.postDelayed(check, CHECK_MS);
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters p) {
        handler.removeCallbacks(check);
        params = null;
        executing = false;
        engine.transfers.checkpointAll();
        return engine.transfers.busy();
    }

    private void check() {
        JobParameters p = params;
        if (p == null) return;
        if (engine.transfers.running()) {
            handler.postDelayed(check, CHECK_MS);
            return;
        }
        params = null;
        executing = false;
        // Still unfinished, for example because the server is busy: Android runs the job again later.
        jobFinished(p, engine.transfers.busy());
        sync(this, engine.transfers);   // and on Wi-Fi only, if that is all the downloads now wait for
    }

    /** Without the protection service there is no network listener, so tell the downloads what we have. */
    private void reportNetwork() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        Network n = cm == null ? null : cm.getActiveNetwork();
        NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
        if (caps == null) return;
        engine.onNetwork(true, caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
    }
}
