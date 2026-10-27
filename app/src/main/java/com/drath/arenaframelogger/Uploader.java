package com.drath.arenaframelogger;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashSet;
import java.util.Set;

/**
 * Uploads finished game zips as assets on a GitHub release (tag "games") so they can be
 * pulled for review without sharing them by hand. Release assets don't bloat the git repo.
 *
 * Needs a fine-grained personal access token limited to the one repo with
 * "Contents: Read and write". It is stored only in this app's private preferences.
 */
final class Uploader {

    static final String KEY_TOKEN = "gh_token";
    static final String KEY_AUTO = "gh_auto_upload";
    static final String KEY_UPLOADED = "gh_uploaded";      // set of uploaded file names
    static final String KEY_PENDING = "gh_pending";        // set of zip uris not yet uploaded
    static final String REPO = "drath65-ux/arena-frame-logger";
    static final String TAG_NAME = "games";
    private static final String TAG = "Uploader";

    private Uploader() {}

    static boolean enabled(Context c) {
        SharedPreferences p = prefs(c);
        return p.getBoolean(KEY_AUTO, false) && !p.getString(KEY_TOKEN, "").trim().isEmpty();
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
    }

    static synchronized void addPending(Context c, String uri) {
        Set<String> s = new HashSet<>(prefs(c).getStringSet(KEY_PENDING, new HashSet<>()));
        s.add(uri);
        prefs(c).edit().putStringSet(KEY_PENDING, s).apply();
    }

    private static synchronized void removePending(Context c, String uri) {
        Set<String> s = new HashSet<>(prefs(c).getStringSet(KEY_PENDING, new HashSet<>()));
        s.remove(uri);
        prefs(c).edit().putStringSet(KEY_PENDING, s).apply();
    }

    static int pendingCount(Context c) {
        return prefs(c).getStringSet(KEY_PENDING, new HashSet<>()).size();
    }

    /** Uploads every pending zip. Blocking; call off the main thread. Returns a status line. */
    static String uploadPending(Context c) {
        Set<String> s = new HashSet<>(prefs(c).getStringSet(KEY_PENDING, new HashSet<>()));
        int ok = 0, fail = 0;
        String lastErr = null;
        for (String u : s) {
            try {
                upload(c, Uri.parse(u));
                removePending(c, u);
                ok++;
            } catch (java.io.FileNotFoundException gone) {
                removePending(c, u);   // zip was cleaned up; nothing to send
            } catch (Exception e) {
                Log.w(TAG, "upload failed", e);
                lastErr = e.getMessage();
                fail++;
            }
        }
        if (ok == 0 && fail == 0) return "Nothing to upload.";
        return "Uploaded " + ok + (fail > 0 ? ", " + fail + " failed (" + lastErr + ")" : "") + ".";
    }

    /** Uploads one zip. Blocking. */
    static void upload(Context c, Uri zip) throws Exception {
        String token = prefs(c).getString(KEY_TOKEN, "").trim();
        if (token.isEmpty()) throw new IllegalStateException("no GitHub token set");

        String name = null;
        long size = -1;
        try (Cursor cur = c.getContentResolver().query(zip,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                name = cur.getString(0);
                size = cur.getLong(1);
            }
        }
        if (name == null) throw new java.io.FileNotFoundException("zip no longer exists");
        if (prefs(c).getStringSet(KEY_UPLOADED, new HashSet<>()).contains(name)) return;

        long releaseId = releaseId(token);
        URL url = new URL("https://uploads.github.com/repos/" + REPO + "/releases/" + releaseId
                + "/assets?name=" + URLEncoder.encode(name, "UTF-8"));
        HttpURLConnection h = (HttpURLConnection) url.openConnection();
        h.setRequestMethod("POST");
        h.setDoOutput(true);
        h.setConnectTimeout(20000);
        h.setReadTimeout(120000);
        h.setRequestProperty("Authorization", "Bearer " + token);
        h.setRequestProperty("Accept", "application/vnd.github+json");
        h.setRequestProperty("Content-Type", "application/zip");
        if (size > 0) h.setFixedLengthStreamingMode(size); else h.setChunkedStreamingMode(64 * 1024);
        try (InputStream in = c.getContentResolver().openInputStream(zip);
             OutputStream out = h.getOutputStream()) {
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        }
        int code = h.getResponseCode();
        String body = read(code >= 400 ? h.getErrorStream() : h.getInputStream());
        h.disconnect();
        // 422 = an asset with this name already exists, which counts as done.
        if (code != 201 && code != 422) throw new IllegalStateException("HTTP " + code + " " + trim(body));

        Set<String> done = new HashSet<>(prefs(c).getStringSet(KEY_UPLOADED, new HashSet<>()));
        done.add(name);
        prefs(c).edit().putStringSet(KEY_UPLOADED, done).apply();
    }

    private static long releaseId(String token) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(
                "https://api.github.com/repos/" + REPO + "/releases/tags/" + TAG_NAME).openConnection();
        h.setConnectTimeout(20000);
        h.setReadTimeout(30000);
        h.setRequestProperty("Authorization", "Bearer " + token);
        h.setRequestProperty("Accept", "application/vnd.github+json");
        int code = h.getResponseCode();
        String body = read(code >= 400 ? h.getErrorStream() : h.getInputStream());
        h.disconnect();
        if (code == 401) throw new IllegalStateException("token rejected (401)");
        if (code == 404) return createRelease(token);
        if (code != 200) throw new IllegalStateException("release lookup HTTP " + code + " " + trim(body));
        return new JSONObject(body).getLong("id");
    }

    /** First upload: make the "games" release (prerelease, so it never shows as the app's latest build). */
    private static long createRelease(String token) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(
                "https://api.github.com/repos/" + REPO + "/releases").openConnection();
        h.setRequestMethod("POST");
        h.setDoOutput(true);
        h.setConnectTimeout(20000);
        h.setReadTimeout(30000);
        h.setRequestProperty("Authorization", "Bearer " + token);
        h.setRequestProperty("Accept", "application/vnd.github+json");
        h.setRequestProperty("Content-Type", "application/json");
        JSONObject req = new JSONObject()
                .put("tag_name", TAG_NAME)
                .put("name", "Game recordings")
                .put("body", "Uploaded automatically by Arena Logger. One zip per game.")
                .put("prerelease", true)
                .put("make_latest", "false");
        try (OutputStream out = h.getOutputStream()) {
            out.write(req.toString().getBytes("UTF-8"));
        }
        int code = h.getResponseCode();
        String body = read(code >= 400 ? h.getErrorStream() : h.getInputStream());
        h.disconnect();
        if (code != 201) throw new IllegalStateException("could not create release: HTTP " + code + " " + trim(body));
        return new JSONObject(body).getLong("id");
    }

    /** Quick check from the settings screen. Blocking. */
    static String test(Context c) {
        try {
            releaseId(prefs(c).getString(KEY_TOKEN, "").trim());
            return "Token works. Games will upload after each match.";
        } catch (Exception e) {
            return "Token check failed: " + e.getMessage();
        }
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream i = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = i.read(buf)) > 0) b.write(buf, 0, r);
            return b.toString("UTF-8");
        }
    }

    private static String trim(String s) {
        return s.length() > 120 ? s.substring(0, 120) : s;
    }
}
