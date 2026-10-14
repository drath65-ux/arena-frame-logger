package com.drath.arenaframelogger;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Match history kept in the app's private storage as a single JSON file.
 *
 * Each match: id (session name), time (epoch ms), durationSec, result ("W", "L" or "?"),
 * autoResult (what the screen reader saw), deck, opp (colors like "UB"), note, zip (uri),
 * life ([[sec, me, opp], ...]; -999 means unknown at that point).
 */
final class MatchStore {

    private static final String FILE = "matches.json";
    static final int UNKNOWN = -999;

    private MatchStore() {}

    static synchronized List<JSONObject> load(Context c) {
        List<JSONObject> out = new ArrayList<>();
        File f = new File(c.getFilesDir(), FILE);
        if (!f.exists()) return out;
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0, r;
            while (off < b.length && (r = in.read(b, off, b.length - off)) > 0) off += r;
            JSONArray a = new JSONArray(new String(b, 0, off, StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    static synchronized void save(Context c, List<JSONObject> matches) {
        JSONArray a = new JSONArray();
        for (JSONObject m : matches) a.put(m);
        File tmp = new File(c.getFilesDir(), FILE + ".tmp");
        try (OutputStream os = new FileOutputStream(tmp)) {
            os.write(a.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(new File(c.getFilesDir(), FILE));
    }

    static synchronized void upsert(Context c, JSONObject match) {
        List<JSONObject> all = load(c);
        String id = match.optString("id");
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).optString("id").equals(id)) { all.set(i, match); replaced = true; break; }
        }
        if (!replaced) all.add(match);
        save(c, all);
    }

    static synchronized JSONObject find(Context c, String id) {
        for (JSONObject m : load(c)) if (m.optString("id").equals(id)) return m;
        return null;
    }

    static synchronized void delete(Context c, String id) {
        List<JSONObject> all = load(c);
        all.removeIf(m -> m.optString("id").equals(id));
        save(c, all);
    }

    /** deck name -> {wins, losses}, in order of most recent use. */
    static Map<String, int[]> recordByDeck(List<JSONObject> matches) {
        Map<String, int[]> rec = new LinkedHashMap<>();
        for (int i = matches.size() - 1; i >= 0; i--) {
            JSONObject m = matches.get(i);
            String deck = m.optString("deck", "");
            if (deck.isEmpty()) deck = "(no deck)";
            int[] wl = rec.get(deck);
            if (wl == null) { wl = new int[2]; rec.put(deck, wl); }
            String r = m.optString("result", "?");
            if (r.equals("W")) wl[0]++;
            else if (r.equals("L")) wl[1]++;
        }
        return rec;
    }
}
