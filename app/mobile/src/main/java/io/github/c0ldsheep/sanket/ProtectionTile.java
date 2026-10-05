package io.github.c0ldsheep.sanket;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * A Quick Settings tile, so protection can be switched on or off from the notification shade
 * before stepping into a lift. Turning it on opens the app, which asks for consent and
 * permissions the first time; turning it off works straight from the tile.
 */
public final class ProtectionTile extends TileService {
    static void refresh(Context ctx) {
        TileService.requestListeningState(ctx, new ComponentName(ctx, ProtectionTile.class));
    }

    @Override
    public void onStartListening() { update(); }

    @Override
    public void onClick() {
        if (GuardService.protecting()) {
            GuardService.requestStop();
            update();
            return;
        }
        Intent open = new Intent(this, MainActivity.class)
                .setAction(MainActivity.ACTION_START_PROTECTION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE));
        } else {
            startOld(open);
        }
    }

    /** Android 13 and older only, where this is the way to open an app from a tile. */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @SuppressWarnings("deprecation")
    private void startOld(Intent open) { startActivityAndCollapse(open); }

    private void update() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean on = GuardService.protecting();
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(getString(R.string.tile_label));
        tile.setSubtitle(getString(on ? R.string.tile_on : R.string.tile_off));
        tile.updateTile();
    }
}
