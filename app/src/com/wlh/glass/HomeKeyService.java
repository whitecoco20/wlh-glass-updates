package com.wlh.glass;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/** Catches only the Home key and opens WLH Glass. Everything else passes through untouched. */
public class HomeKeyService extends AccessibilityService {
    private long downAt = 0;
    static volatile HomeKeyService instance;

    @Override protected void onServiceConnected() {
        instance = this;
        boolean justBooted = SystemClock.elapsedRealtime() < 5 * 60 * 1000L;
        boolean bootStart = getSharedPreferences("glass", MODE_PRIVATE).getBoolean("bootStart", true);
        // Switched on by hand: always bring WLH Glass up. At power-on: follow the "Open when TV starts" setting.
        if (!justBooted || bootStart) {
            // Wait a moment so TV Settings can finish closing its confirm dialog (it crashed when we jumped in instantly).
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                public void run() { if (instance != null) openHome(); }
            }, justBooted ? 1000 : 2500);
        }
    }

    @Override public boolean onUnbind(Intent i) { instance = null; return super.onUnbind(i); }

    /** Turns the Home button switch off from inside WLH Glass, then shows Google TV's home. */
    static boolean switchToGoogleTv(android.content.Context c) {
        HomeKeyService s = instance;
        if (s == null) return false;
        try { AccessibilityService.class.getMethod("disableSelf").invoke(s); } catch (Throwable t) { return false; }
        instance = null;
        try {
            Intent h = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setPackage("com.google.android.apps.tv.launcherx").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(h);
        } catch (Throwable t) { }
        return true;
    }

    @Override protected boolean onKeyEvent(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0) downAt = e.getEventTime();
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_UP) {
            long held = e.getEventTime() - downAt;
            if (held > 600) {
                // Long press keeps its usual job: the TV's quick panel.
                performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
                return true;
            }
            return openHome();
        }
        return true;
    }

    private boolean openHome() {
        try {
            Intent i = new Intent(this, HomeActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
            return true;
        } catch (Throwable t) {
            // If we can't open, fall back to Google TV's Home.
            try { performGlobalAction(GLOBAL_ACTION_HOME); } catch (Throwable ignored) { }
            return true;
        }
    }

    // ---------- sleep timer (runs here so it keeps counting while you watch other apps) ----------
    static final android.os.Handler sleepH = new android.os.Handler(android.os.Looper.getMainLooper());
    static long sleepAt = 0;
    static final Runnable sleepRun = new Runnable() { public void run() { sleepAt = 0; sleepNow(); } };

    static void setSleepMinutes(int minutes) {
        sleepH.removeCallbacks(sleepRun);
        sleepAt = minutes <= 0 ? 0 : System.currentTimeMillis() + minutes * 60000L;
        if (minutes > 0) sleepH.postDelayed(sleepRun, minutes * 60000L);
    }

    static int sleepMinutesLeft() {
        if (sleepAt == 0) return 0;
        return (int) Math.max(1, (sleepAt - System.currentTimeMillis() + 59999) / 60000);
    }

    /** Puts the TV to sleep: Android's "lock screen" action sends Google TV to standby. */
    static boolean sleepNow() {
        HomeKeyService s = instance;
        if (s == null) return false;
        try { return s.performGlobalAction(8 /* GLOBAL_ACTION_LOCK_SCREEN, Android 9+ */); } catch (Throwable t) { return false; }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
}
