package com.drath.arenaframelogger;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Start/stop the frame logger, share sessions, and keep a match record
 * (results are auto-detected from the VICTORY / DEFEAT screen, then confirmed here).
 */
public class MainActivity extends Activity {

    static final String PREFS = "arena_logger";
    static final String KEY_LAST_ZIP = "last_zip";
    static final String KEY_LAST_INFO = "last_info";
    static final String KEY_LAST_DECK = "last_deck";
    static final String KEY_PENDING_REVIEW = "pending_review";
    static final String KEY_COLLECTION_MODE = "collection_mode";
    static final String EXTRA_SHARE = "share_last";
    static final String EXTRA_REVIEW = "review";

    private static final int REQ_CAPTURE = 1;
    private static final int REQ_NOTIF = 2;
    private static final String COLORS = "WUBRG";

    private TextView status, recordView;
    private LinearLayout historyList;
    private Button startBtn, stopBtn, shareBtn;
    private CheckBox collectionBox, bubbleBox;
    private int pad;
    private boolean wasBusy;
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

        pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 2, pad, pad * 2);

        TextView title = new TextView(this);
        title.setText("Arena Frame Logger");
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView help = new TextView(this);
        help.setText("\nTap Start, allow screen capture (pick MTG Arena if offered), play, then tap Stop in the "
                + "notification. The app reads life totals and the Victory/Defeat screen as you play; "
                + "confirm the result afterwards. Only your last 5 zips are kept in Downloads/ArenaLogger.\n");
        help.setTextSize(14);
        root.addView(help);

        collectionBox = new CheckBox(this);
        collectionBox.setText("Collection scan mode (fast capture while you scroll your collection)");
        collectionBox.setChecked(getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_COLLECTION_MODE, false));
        collectionBox.setOnCheckedChangeListener((b, on) ->
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_COLLECTION_MODE, on).apply());
        root.addView(collectionBox);

        bubbleBox = new CheckBox(this);
        bubbleBox.setText("Floating REC/STOP button over other apps (long-press it to hide)");
        bubbleBox.setOnClickListener(v -> toggleBubble(bubbleBox.isChecked()));
        root.addView(bubbleBox);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        startBtn = button("Start", v -> start());
        stopBtn = button("Stop", v -> stop());
        shareBtn = button("Share last", v -> shareZip(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LAST_ZIP, null)));
        for (Button b : new Button[]{startBtn, stopBtn, shareBtn})
            buttons.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(buttons);

        status = new TextView(this);
        status.setTextSize(14);
        status.setPadding(0, pad / 2, 0, pad);
        root.addView(status);

        root.addView(header("Record"));
        recordView = new TextView(this);
        recordView.setTextSize(16);
        recordView.setPadding(0, 0, 0, pad);
        root.addView(recordView);

        root.addView(header("Matches (tap to view or edit)"));
        historyList = new LinearLayout(this);
        historyList.setOrientation(LinearLayout.VERTICAL);
        root.addView(historyList);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        loadHistory();
        handleIntent(getIntent());
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(l);
        return b;
    }

    private TextView header(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, pad, 0, pad / 2);
        return t;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        if (intent.getBooleanExtra(EXTRA_SHARE, false)) {
            intent.removeExtra(EXTRA_SHARE);
            shareZip(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LAST_ZIP, null));
        }
        String review = intent.getStringExtra(EXTRA_REVIEW);
        if (review != null) {
            intent.removeExtra(EXTRA_REVIEW);
            showMatch(review);
        }
    }

    private void toggleBubble(boolean on) {
        if (!on) {
            stopService(new Intent(this, FloatingButtonService.class));
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow \"Display over other apps\" for Arena Logger, then come back", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            bubbleBox.setChecked(false);
            return;
        }
        startService(new Intent(this, FloatingButtonService.class));
    }

    @Override protected void onResume() {
        super.onResume();
        ui.post(refresher);
        loadHistory();
        // If a match finished while the app was closed and the notification was dismissed, ask now.
        String pending = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_PENDING_REVIEW, null);
        if (pending != null && !CaptureService.running && !CaptureService.zipping) {
            JSONObject m = MatchStore.find(this, pending);
            if (m != null && !m.optBoolean("reviewed")) showMatch(pending);
            else clearPending();
        }
    }

    @Override protected void onPause() { super.onPause(); ui.removeCallbacks(refresher); }

    private void clearPending() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_PENDING_REVIEW).apply();
    }

    private void refresh() {
        boolean running = CaptureService.running;
        boolean busy = running || CaptureService.zipping;
        startBtn.setEnabled(!busy);
        collectionBox.setEnabled(!busy);
        stopBtn.setEnabled(running);
        bubbleBox.setChecked(FloatingButtonService.showing);
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String last = p.getString(KEY_LAST_INFO, null);
        shareBtn.setEnabled(!busy && p.getString(KEY_LAST_ZIP, null) != null);
        if (running) status.setText("Recording… " + CaptureService.savedCount + " frames saved");
        else if (CaptureService.zipping) status.setText("Saving zip…");
        else status.setText(last == null ? "Idle" : "Last session: " + last);
        if (wasBusy && !busy) loadHistory();
        wasBusy = busy;
    }

    // ---------- match history ----------

    private void loadHistory() {
        List<JSONObject> all = MatchStore.load(this);
        StringBuilder sb = new StringBuilder();
        Map<String, int[]> rec = MatchStore.recordByDeck(all);
        int w = 0, l = 0;
        for (Map.Entry<String, int[]> e : rec.entrySet()) {
            sb.append(e.getKey()).append(":  ").append(e.getValue()[0]).append("-").append(e.getValue()[1]).append('\n');
            w += e.getValue()[0];
            l += e.getValue()[1];
        }
        if (rec.isEmpty()) sb.append("No matches yet.");
        else sb.append("Total:  ").append(w).append("-").append(l);
        recordView.setText(sb.toString());

        historyList.removeAllViews();
        SimpleDateFormat df = new SimpleDateFormat("MMM d  h:mm a", Locale.US);
        for (int i = all.size() - 1; i >= 0 && i >= all.size() - 50; i--) {
            JSONObject m = all.get(i);
            String r = m.optString("result", "?");
            String line = df.format(new Date(m.optLong("time"))) + "   "
                    + (r.equals("W") ? "WIN" : r.equals("L") ? "LOSS" : "?") + "   "
                    + m.optString("deck", "")
                    + (m.optString("opp").isEmpty() ? "" : "  vs " + m.optString("opp"))
                    + finalLife(m)
                    + (m.optBoolean("reviewed") ? "" : "   (tap to confirm)");
            TextView t = new TextView(this);
            t.setText(line);
            t.setTextSize(15);
            t.setPadding(0, pad / 2, 0, pad / 2);
            String id = m.optString("id");
            t.setOnClickListener(v -> showMatch(id));
            historyList.addView(t);
        }
    }

    private static String finalLife(JSONObject m) {
        JSONArray life = m.optJSONArray("life");
        if (life == null || life.length() == 0) return "";
        JSONArray p = life.optJSONArray(life.length() - 1);
        if (p == null) return "";
        int me = p.optInt(1, MatchStore.UNKNOWN), opp = p.optInt(2, MatchStore.UNKNOWN);
        if (me == MatchStore.UNKNOWN || opp == MatchStore.UNKNOWN) return "";
        return "   (" + me + " to " + opp + ")";
    }

    private void showMatch(String id) {
        clearPending();
        JSONObject m = MatchStore.find(this, id);
        if (m == null) {
            Toast.makeText(this, "Match not found", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        String auto = m.optString("autoResult", "?");
        TextView detected = new TextView(this);
        detected.setText(auto.equals("W") ? "Screen reader saw: VICTORY"
                : auto.equals("L") ? "Screen reader saw: DEFEAT" : "Screen reader didn't catch the result.");
        box.addView(detected);

        RadioGroup result = new RadioGroup(this);
        result.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton win = new RadioButton(this); win.setText("Win"); win.setId(View.generateViewId());
        RadioButton loss = new RadioButton(this); loss.setText("Loss"); loss.setId(View.generateViewId());
        result.addView(win);
        result.addView(loss);
        String r = m.optString("result", "?");
        if (r.equals("W")) result.check(win.getId());
        if (r.equals("L")) result.check(loss.getId());
        box.addView(result);

        AutoCompleteTextView deck = new AutoCompleteTextView(this);
        deck.setHint("Deck");
        deck.setSingleLine();
        deck.setThreshold(0);
        Set<String> decks = new LinkedHashSet<>();
        for (JSONObject x : MatchStore.load(this)) if (!x.optString("deck").isEmpty()) decks.add(x.optString("deck"));
        deck.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, new ArrayList<>(decks)));
        String d = m.optString("deck");
        if (d.isEmpty()) d = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LAST_DECK, "");
        deck.setText(d);
        box.addView(deck);

        TextView oppLabel = new TextView(this);
        oppLabel.setText("Opponent colors");
        oppLabel.setPadding(0, pad / 2, 0, 0);
        box.addView(oppLabel);
        LinearLayout colors = new LinearLayout(this);
        String opp = m.optString("opp");
        CheckBox[] boxes = new CheckBox[5];
        for (int i = 0; i < 5; i++) {
            boxes[i] = new CheckBox(this);
            boxes[i].setText(String.valueOf(COLORS.charAt(i)));
            boxes[i].setChecked(opp.indexOf(COLORS.charAt(i)) >= 0);
            colors.addView(boxes[i]);
        }
        box.addView(colors);

        EditText note = new EditText(this);
        note.setHint("Note (what decided the game?)");
        note.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        note.setText(m.optString("note"));
        box.addView(note);

        TextView chartLabel = new TextView(this);
        chartLabel.setText("Life totals (as read from the screen)");
        chartLabel.setPadding(0, pad, 0, pad / 2);
        box.addView(chartLabel);
        box.addView(new LifeChartView(this, m.optJSONArray("life")),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        (int) (180 * getResources().getDisplayMetrics().density)));

        Button share = new Button(this);
        share.setText("Share this game's zip");
        share.setOnClickListener(v -> shareZip(m.optString("zip", null)));
        box.addView(share);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        new AlertDialog.Builder(this)
                .setTitle(new SimpleDateFormat("MMM d, h:mm a", Locale.US).format(new Date(m.optLong("time"))))
                .setView(sv)
                .setPositiveButton("Save", (dlg, which) -> {
                    try {
                        int checked = result.getCheckedRadioButtonId();
                        m.put("result", checked == win.getId() ? "W" : checked == loss.getId() ? "L" : "?");
                        String deckName = deck.getText().toString().trim();
                        m.put("deck", deckName);
                        StringBuilder c = new StringBuilder();
                        for (int i = 0; i < 5; i++) if (boxes[i].isChecked()) c.append(COLORS.charAt(i));
                        m.put("opp", c.toString());
                        m.put("note", note.getText().toString().trim());
                        m.put("reviewed", true);
                        MatchStore.upsert(this, m);
                        if (!deckName.isEmpty())
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_LAST_DECK, deckName).apply();
                    } catch (Exception ignored) {
                    }
                    clearPending();
                    loadHistory();
                })
                .setNeutralButton("Delete", (dlg, which) -> new AlertDialog.Builder(this)
                        .setMessage("Delete this match from your record?")
                        .setPositiveButton("Delete", (d2, w2) -> {
                            MatchStore.delete(this, m.optString("id"));
                            clearPending();
                            loadHistory();
                        })
                        .setNegativeButton("Cancel", null)
                        .show())
                .setNegativeButton("Later", (dlg, which) -> clearPending())
                .show();
    }

    // ---------- capture ----------

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
                .putExtra(CaptureService.EXTRA_DATA, data)
                .putExtra(CaptureService.EXTRA_COLLECTION, collectionBox.isChecked());
        startForegroundService(i);
        Toast.makeText(this, "Recording. Switch to Arena.", Toast.LENGTH_SHORT).show();
    }

    private void stop() {
        startService(new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_STOP));
    }

    private void shareZip(String s) {
        if (s == null || s.isEmpty()) {
            Toast.makeText(this, "No session saved yet", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("application/zip")
                .putExtra(Intent.EXTRA_STREAM, Uri.parse(s))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(send, "Share session"));
        } catch (Exception e) {
            Toast.makeText(this, "That zip is no longer available", Toast.LENGTH_SHORT).show();
        }
    }
}
