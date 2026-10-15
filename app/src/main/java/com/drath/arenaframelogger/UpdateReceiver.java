package com.drath.arenaframelogger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/** Restores the floating button right after the app is updated, if it was turned on. */
public class UpdateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        boolean on = c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .getBoolean(MainActivity.KEY_BUBBLE, false);
        if (!on || !Settings.canDrawOverlays(c)) return;
        try {
            c.startService(new Intent(c, FloatingButtonService.class));
        } catch (Exception ignored) {
            // Background start not allowed on this Android version; opening the app restores it.
        }
    }
}
