package com.drath.arenaframelogger;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Invisible activity used by the floating button: it only shows Android's
 * "Start recording?" consent dialog, starts the capture service, and closes,
 * so you stay in Arena.
 */
public class CaptureActivity extends Activity {

    private static final int REQ = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (CaptureService.running || CaptureService.zipping) { finish(); return; }
        startActivityForResult(MainActivity.captureIntent(this), REQ);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ && resultCode == RESULT_OK && data != null) {
            boolean collection = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                    .getBoolean(MainActivity.KEY_COLLECTION_MODE, false);
            startForegroundService(new Intent(this, CaptureService.class)
                    .setAction(CaptureService.ACTION_START)
                    .putExtra(CaptureService.EXTRA_CODE, resultCode)
                    .putExtra(CaptureService.EXTRA_DATA, data)
                    .putExtra(CaptureService.EXTRA_COLLECTION, collection));
        } else if (requestCode == REQ) {
            Toast.makeText(this, "Recording not started", Toast.LENGTH_SHORT).show();
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
