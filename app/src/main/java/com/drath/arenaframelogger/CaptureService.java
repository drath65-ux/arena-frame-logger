package com.drath.arenaframelogger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Grabs a frame from the screen every couple of seconds, keeps only frames that changed,
 * saves them as small JPEGs, and zips the session into Downloads/ArenaLogger when stopped.
 */
public class CaptureService extends Service {

    static final String ACTION_START = "start";
    static final String ACTION_STOP = "stop";
    static final String EXTRA_CODE = "code";
    static final String EXTRA_DATA = "data";
    static final String EXTRA_COLLECTION = "collection";

    static volatile boolean running = false;
    static volatile boolean zipping = false;
    static volatile int savedCount = 0;

    private static final String TAG = "ArenaLogger";
    private static final String CHANNEL = "capture";
    private static final int NOTIF_RUNNING = 1;
    private static final int NOTIF_DONE = 2;

    // Tuning
    private static final int LONG_SIDE = 1280;        // output width in landscape
    private static final int SIG_W = 48, SIG_H = 27;
    private static final int KEEP_SESSIONS = 5;       // older zips in Downloads/ArenaLogger are deleted

    // Game mode: slow, only keep frames that changed.
    // Collection mode: fast and sharper, for scrolling through the collection screen.
    private long intervalMs;
    private long forceSaveMs;
    private double changeThreshold;   // mean luminance diff (0-255) on a tiny thumbnail
    private int jpegQuality;
    private String prefix;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader reader;
    private HandlerThread thread;
    private Handler handler;
    private File sessionDir;
    private String sessionName;
    private long startMs, lastSaveMs;
    private int[] lastSig;
    private int width, height;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            try {
                grab();
            } catch (Exception e) {
                Log.e(TAG, "grab failed", e);
            }
            handler.postDelayed(this, intervalMs);
        }
    };

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopCapture();
        } else if (ACTION_START.equals(action) && !running && !zipping) {
            startCapture(intent);
        }
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private void startCapture(Intent intent) {
        createChannel();
        // Must be in the foreground before asking for the projection (Android 14+).
        startForeground(NOTIF_RUNNING, runningNotification(0), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);

        boolean collection = intent.getBooleanExtra(EXTRA_COLLECTION, false);
        if (collection) {
            intervalMs = 700; forceSaveMs = 60000; changeThreshold = 2.0; jpegQuality = 85; prefix = "collection_";
        } else {
            intervalMs = 2000; forceSaveMs = 20000; changeThreshold = 3.0; jpegQuality = 70; prefix = "arena_";
        }

        int code = intent.getIntExtra(EXTRA_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        projection = mpm.getMediaProjection(code, data);
        if (projection == null) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }

        thread = new HandlerThread("capture");
        thread.start();
        handler = new Handler(thread.getLooper());

        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { handler.post(CaptureService.this::finishSession); }
        }, handler);

        // Arena is landscape, so size the capture surface as landscape. Portrait screens
        // just get letterboxed.
        DisplayMetrics m = getResources().getDisplayMetrics();
        int longPx = Math.max(m.widthPixels, m.heightPixels);
        int shortPx = Math.min(m.widthPixels, m.heightPixels);
        width = LONG_SIDE;
        height = Math.round(LONG_SIDE * (float) shortPx / longPx) & ~1;

        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        virtualDisplay = projection.createVirtualDisplay("arena-logger", width, height, m.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, handler);

        sessionName = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        sessionDir = new File(getExternalFilesDir("sessions"), sessionName);
        //noinspection ResultOfMethodCallIgnored
        sessionDir.mkdirs();

        savedCount = 0;
        lastSig = null;
        startMs = System.currentTimeMillis();
        lastSaveMs = 0;
        running = true;
        handler.postDelayed(tick, 1000);
    }

    private void grab() throws Exception {
        Image img = reader.acquireLatestImage();
        if (img == null) return;
        Bitmap bmp;
        try {
            Image.Plane plane = img.getPlanes()[0];
            ByteBuffer buf = plane.getBuffer();
            int rowPixels = plane.getRowStride() / plane.getPixelStride();
            Bitmap padded = Bitmap.createBitmap(rowPixels, height, Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buf);
            if (rowPixels != width) {
                bmp = Bitmap.createBitmap(padded, 0, 0, width, height);
                padded.recycle();
            } else {
                bmp = padded;
            }
        } finally {
            img.close();
        }

        long now = System.currentTimeMillis();
        int[] sig = signature(bmp);
        boolean changed = lastSig == null || meanDiff(sig, lastSig) > changeThreshold;
        if (changed || now - lastSaveMs > forceSaveMs) {
            int n = savedCount + 1;
            long sec = (now - startMs) / 1000;
            File out = new File(sessionDir, String.format(Locale.US, "%04d_t%05d.jpg", n, sec));
            try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                bmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality, os);
            }
            lastSig = sig;
            lastSaveMs = now;
            savedCount = n;
            if (n % 10 == 0) notifyRunning(n);
        }
        bmp.recycle();
    }

    private static int[] signature(Bitmap bmp) {
        Bitmap small = Bitmap.createScaledBitmap(bmp, SIG_W, SIG_H, true);
        int[] px = new int[SIG_W * SIG_H];
        small.getPixels(px, 0, SIG_W, 0, 0, SIG_W, SIG_H);
        small.recycle();
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            px[i] = (((c >> 16) & 0xff) * 299 + ((c >> 8) & 0xff) * 587 + (c & 0xff) * 114) / 1000;
        }
        return px;
    }

    private static double meanDiff(int[] a, int[] b) {
        long sum = 0;
        for (int i = 0; i < a.length; i++) sum += Math.abs(a[i] - b[i]);
        return (double) sum / a.length;
    }

    private void stopCapture() {
        if (projection != null) {
            projection.stop(); // triggers onStop -> finishSession
        } else {
            stopSelf();
        }
    }

    /** Runs on the capture thread. */
    private void finishSession() {
        if (!running) return;
        running = false;
        zipping = true;
        handler.removeCallbacks(tick);
        if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
        if (reader != null) { reader.close(); reader = null; }
        projection = null;

        String info;
        try {
            long durSec = (System.currentTimeMillis() - startMs) / 1000;
            writeSessionInfo(durSec);
            Uri zip = zipToDownloads();
            info = savedCount + " frames, " + (durSec / 60) + "m " + (durSec % 60) + "s (" + sessionName + ")";
            getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit()
                    .putString(MainActivity.KEY_LAST_ZIP, zip.toString())
                    .putString(MainActivity.KEY_LAST_INFO, info)
                    .apply();
            deleteDir(sessionDir);
            cleanupOldZips();
            notifyDone("Saved " + info + ". Tap to share.");
        } catch (Exception e) {
            Log.e(TAG, "zip failed", e);
            notifyDone("Saving failed: " + e.getMessage());
        } finally {
            zipping = false;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            thread.quitSafely();
        }
    }

    private void writeSessionInfo(long durSec) throws Exception {
        String txt = "session=" + sessionName + "\n"
                + "frames=" + savedCount + "\n"
                + "duration_sec=" + durSec + "\n"
                + "mode=" + (prefix.startsWith("collection") ? "collection" : "game") + "\n"
                + "interval_ms=" + intervalMs + "\n"
                + "size=" + width + "x" + height + "\n"
                + "filename=NNNN_tSSSSS.jpg (frame number, seconds since start)\n";
        try (OutputStream os = new FileOutputStream(new File(sessionDir, "session.txt"))) {
            os.write(txt.getBytes("UTF-8"));
        }
    }

    private Uri zipToDownloads() throws Exception {
        ContentResolver resolver = getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, prefix + sessionName + ".zip");
        v.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
        v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ArenaLogger");
        v.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IllegalStateException("could not create zip in Downloads");

        File[] files = sessionDir.listFiles();
        if (files == null) files = new File[0];
        Arrays.sort(files);
        byte[] buf = new byte[64 * 1024];
        try (OutputStream os = resolver.openOutputStream(uri);
             ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(os))) {
            for (File f : files) {
                zip.putNextEntry(new ZipEntry(sessionName + "/" + f.getName()));
                try (InputStream in = new FileInputStream(f)) {
                    int r;
                    while ((r = in.read(buf)) > 0) zip.write(buf, 0, r);
                }
                zip.closeEntry();
            }
        }
        v.clear();
        v.put(MediaStore.Downloads.IS_PENDING, 0);
        resolver.update(uri, v, null, null);
        return uri;
    }

    /** Keeps the newest KEEP_SESSIONS zips this app wrote to Downloads/ArenaLogger; deletes the rest. */
    private void cleanupOldZips() {
        try {
            ContentResolver resolver = getContentResolver();
            String sel = MediaStore.Downloads.RELATIVE_PATH + " LIKE ? AND "
                    + MediaStore.Downloads.DISPLAY_NAME + " LIKE ?";
            String[] args = {Environment.DIRECTORY_DOWNLOADS + "/ArenaLogger%", "%.zip"};
            try (android.database.Cursor c = resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Downloads._ID}, sel, args,
                    MediaStore.Downloads.DATE_ADDED + " DESC")) {
                if (c == null) return;
                int kept = 0;
                while (c.moveToNext()) {
                    if (kept < KEEP_SESSIONS) { kept++; continue; }
                    Uri old = android.content.ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0));
                    try { resolver.delete(old, null, null); } catch (Exception e) { Log.w(TAG, "could not delete " + old, e); }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "cleanup failed", e);
        }
    }

    private static void deleteDir(File dir) {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) //noinspection ResultOfMethodCallIgnored
            f.delete();
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
    }

    // --- notifications ---

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Frame capture", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification runningNotification(int frames) {
        PendingIntent stopPi = PendingIntent.getService(this, 1,
                new Intent(this, CaptureService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent openPi = PendingIntent.getActivity(this, 2,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Arena Logger recording")
                .setContentText(frames + " frames saved")
                .setOngoing(true)
                .setContentIntent(openPi)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_media_pause), "Stop", stopPi).build())
                .build();
    }

    private void notifyRunning(int frames) {
        getSystemService(NotificationManager.class).notify(NOTIF_RUNNING, runningNotification(frames));
    }

    private void notifyDone(String text) {
        PendingIntent sharePi = PendingIntent.getActivity(this, 3,
                new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_SHARE, true)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_save)
                .setContentTitle("Arena Logger")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(sharePi)
                .build();
        getSystemService(NotificationManager.class).notify(NOTIF_DONE, n);
    }
}
