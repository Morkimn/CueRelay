package com.codex.pocketplayer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!(Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action))) return;
        if (Pairing.prefs(context).getBoolean(TvHostService.ENABLED, false) &&
            Pairing.prefs(context).getBoolean(TvHostService.KEEP_AT_BOOT, true) && PhoneMedia.hasAccess(context)) {
            try { TvHostService.start(context); } catch (IllegalStateException ignored) {}
        }
        if (PhoneLink.connection(context) == null) return;
        if (!PlayerWidget.hasWidgets(context) && !PhoneMedia.enabled(context)) return;
        try { PhoneLink.start(context, "refresh", null); }
        catch (IllegalStateException ignored) { PlayerWidget.updateAll(context); }
    }
}
