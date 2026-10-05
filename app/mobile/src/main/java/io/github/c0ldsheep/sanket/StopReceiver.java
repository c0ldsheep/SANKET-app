package io.github.c0ldsheep.sanket;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The notification's "Stop protection" button. */
public final class StopReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) { GuardService.requestStop(); }
}
