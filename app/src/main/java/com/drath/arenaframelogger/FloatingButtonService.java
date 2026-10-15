package com.drath.arenaframelogger;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Small draggable button drawn over other apps.
 * Tap: start recording (shows Android's consent dialog) or stop recording.
 * Long-press: hide the button.
 */
public class FloatingButtonService extends Service {

    static volatile boolean showing = false;

    private WindowManager wm;
    private TextView bubble;
    private WindowManager.LayoutParams lp;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            updateLook();
            clampToScreen();
            ui.postDelayed(this, 1000);
        }
    };

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return; }
        wm = getSystemService(WindowManager.class);
        float d = getResources().getDisplayMetrics().density;
        int size = (int) (48 * d);

        bubble = new TextView(this);
        bubble.setGravity(Gravity.CENTER);
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(12);
        bubble.setAlpha(0.85f);

        lp = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getInt("bubble_x", 0);
        lp.y = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getInt("bubble_y", (int) (120 * d));

        bubble.setOnTouchListener(new View.OnTouchListener() {
            int startX, startY;
            float downX, downY;
            boolean moved;
            long downAt;

            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = lp.x; startY = lp.y;
                        downX = e.getRawX(); downY = e.getRawY();
                        moved = false;
                        downAt = System.currentTimeMillis();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (Math.abs(dx) > 12 * d || Math.abs(dy) > 12 * d) moved = true;
                        if (moved) {
                            lp.x = startX + (int) dx;
                            lp.y = startY + (int) dy;
                            wm.updateViewLayout(bubble, lp);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (moved) {
                            getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit()
                                    .putInt("bubble_x", lp.x).putInt("bubble_y", lp.y).apply();
                        } else if (System.currentTimeMillis() - downAt > 700) {
                            Toast.makeText(FloatingButtonService.this, "Floating button hidden", Toast.LENGTH_SHORT).show();
                            getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit()
                                    .putBoolean(MainActivity.KEY_BUBBLE, false).apply();
                            stopSelf();
                        } else {
                            toggle();
                        }
                        return true;
                }
                return false;
            }
        });

        clampPosition();
        wm.addView(bubble, lp);
        showing = true;
        ui.post(refresher);
    }

    /**
     * Keeps the button on screen. A position saved while Arena was in landscape can be far
     * off the edge in portrait, which made the button invisible.
     */
    private boolean clampPosition() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int maxX = Math.max(0, dm.widthPixels - lp.width);
        int maxY = Math.max(0, dm.heightPixels - lp.height);
        int x = Math.min(Math.max(lp.x, 0), maxX);
        int y = Math.min(Math.max(lp.y, 0), maxY);
        boolean moved = x != lp.x || y != lp.y;
        lp.x = x;
        lp.y = y;
        return moved;
    }

    private void clampToScreen() {
        if (bubble != null && wm != null && clampPosition()) {
            try { wm.updateViewLayout(bubble, lp); } catch (Exception ignored) {}
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        clampToScreen();
    }

    private void toggle() {
        if (CaptureService.running) {
            startService(new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_STOP));
        } else if (!CaptureService.zipping) {
            startActivity(new Intent(this, CaptureActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        }
    }

    private void updateLook() {
        if (bubble == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        if (CaptureService.running) {
            bg.setColor(Color.rgb(211, 47, 47));
            bubble.setText("STOP");
        } else if (CaptureService.zipping) {
            bg.setColor(Color.rgb(120, 120, 120));
            bubble.setText("…");
        } else {
            bg.setColor(Color.rgb(48, 48, 48));
            bubble.setText("REC");
        }
        bg.setStroke(3, Color.WHITE);
        bubble.setBackground(bg);
    }

    @Override
    public void onDestroy() {
        ui.removeCallbacks(refresher);
        if (bubble != null && wm != null) {
            try { wm.removeView(bubble); } catch (Exception ignored) {}
        }
        showing = false;
        super.onDestroy();
    }
}
