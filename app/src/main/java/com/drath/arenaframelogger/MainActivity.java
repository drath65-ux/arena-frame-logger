package com.drath.arenaframelogger;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Start/stop screen for the frame logger, plus a button to share the last session zip.
 */
public class MainActivity extends Activity {

    static final String PREFS = "arena_logger";
    static final String KEY_LAST_ZIP = "last_zip";
    static final String KEY_LAST_INFO = "last_info";
    static final String EXTRA_SHARE = "share_last";

    private static final int REQ_CAPTURE = 1;
    private static final int REQ_NOTIF = 2;

    private TextView status;
    private Button startBtn, stopBtn, shareBtn;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            refresh();
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(pad, pad * 2, pad, pad);

        TextView title = new TextView(this);
        title.setText("Arena Frame Logger");
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView help = new TextView(this);
        help.setText("\n1. Tap Start and allow screen capture (pick MTG Arena if it offers a single app).\n"
                + "2. Play your game.\n"
                + "3. Tap Stop in the notification or here.\n"
                + "4. Tap Share and send the zip to Claude.\n");
        help.setTextSize(15);
        root.addView(help);

        startBtn = new Button(this);
        startBtn.setText("Start");
        startBtn.setOnClickListener(v -> start());
        root.addView(startBtn);

        stopBtn = new Button(this);
        stopBtn.setText("Stop");
        stopBtn.setOnClickListener(v -> stop());
        root.addView(stopBtn);

        shareBtn = new Button(this);
        shareBtn.setText("Share last session");
        shareBtn.setOnClickListener(v -> shareLast());
        root.addView(shareBtn);

        status = new TextView(this);
        status.setTextSize(15);
        status.setPadding(0, pad, 0, 0);
        root.addView(status);

        setContentView(root);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra(EXTRA_SHARE, false)) {
            intent.removeExtra(EXTRA_SHARE);
            shareLast();
        }
    }

    @Override protected void onResume() { super.onResume(); ui.post(refresher); }
    @Override protected void onPause() { super.onPause(); ui.removeCallbacks(refresher); }

    private void refresh() {
        boolean running = CaptureService.running;
        startBtn.setEnabled(!running);
        stopBtn.setEnabled(running);
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String last = p.getString(KEY_LAST_INFO, null);
        shareBtn.setEnabled(!running && p.getString(KEY_LAST_ZIP, null) != null);
        if (running) {
            status.setText("Recording… " + CaptureService.savedCount + " frames saved");
        } else if (CaptureService.zipping) {
            status.setText("Saving zip…");
        } else {
            status.setText(last == null ? "Idle" : "Last session: " + last);
        }
    }

    private void start() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            return;
        }
        requestCapture();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIF) requestCapture(); // works either way; notification is just nicer
    }

    private void requestCapture() {
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Screen capture was not allowed", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(this, CaptureService.class)
                .setAction(CaptureService.ACTION_START)
                .putExtra(CaptureService.EXTRA_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_DATA, data);
        startForegroundService(i);
        Toast.makeText(this, "Recording. Switch to Arena.", Toast.LENGTH_SHORT).show();
    }

    private void stop() {
        startService(new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_STOP));
    }

    private void shareLast() {
        String s = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LAST_ZIP, null);
        if (s == null) {
            Toast.makeText(this, "No session saved yet", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("application/zip")
                .putExtra(Intent.EXTRA_STREAM, Uri.parse(s))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Share session"));
    }
}
