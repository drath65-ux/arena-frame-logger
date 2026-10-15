package com.drath.arenaframelogger;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Process;

/**
 * Tracks which app is in the foreground (needs the "Usage access" permission) so that
 * entire-screen recording only keeps frames while MTG Arena is on screen.
 */
final class AppWatcher {

    private static String foreground;
    private static long lastQuery;

    private AppWatcher() {}

    static boolean hasUsageAccess(Context c) {
        AppOpsManager ops = c.getSystemService(AppOpsManager.class);
        int mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), c.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /** Arena's package is believed to be com.wizards.mtga; match loosely in case it differs. */
    static boolean isArena(String pkg) {
        if (pkg == null) return false;
        String p = pkg.toLowerCase(java.util.Locale.US);
        return p.contains("mtga") || p.contains("wizards");
    }

    /** Latest foreground package, or null if unknown. */
    static String foreground(Context c) {
        long now = System.currentTimeMillis();
        if (now - lastQuery < 1000) return foreground;
        UsageStatsManager usm = c.getSystemService(UsageStatsManager.class);
        long from = lastQuery == 0 ? now - 60_000 : lastQuery - 2_000;
        lastQuery = now;
        UsageEvents events = usm.queryEvents(from, now);
        UsageEvents.Event e = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) foreground = e.getPackageName();
        }
        return foreground;
    }

    static void reset() {
        foreground = null;
        lastQuery = 0;
    }

    /**
     * True if the current frame should be kept: always in app-picker mode, and in entire-screen
     * mode only while Arena (or this app's own consent screen) is in front.
     */
    static boolean allowFrame(Context c) {
        boolean full = c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .getBoolean(MainActivity.KEY_FULL_SCREEN, true);
        if (!full || !hasUsageAccess(c)) return true;
        String fg = foreground(c);
        return fg == null || isArena(fg) || fg.equals(c.getPackageName());
    }
}
