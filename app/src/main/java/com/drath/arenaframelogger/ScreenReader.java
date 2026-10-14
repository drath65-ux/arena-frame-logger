package com.drath.arenaframelogger;

import android.graphics.Bitmap;
import android.graphics.Rect;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Reads life totals and the VICTORY / DEFEAT banner from Arena screenshots with on-device OCR.
 * Must be called off the main thread (it blocks on the recognizer).
 *
 * Life totals sit in the circles at the top (opponent) and bottom (you), centered just right of
 * the middle of the screen. Positions were measured from 1280x632 captures of a Pixel in landscape.
 */
final class ScreenReader {

    // Fractions of the captured frame.
    private static final float X0 = 0.485f, X1 = 0.585f;
    private static final float OPP_Y0 = 0.0f, OPP_Y1 = 0.15f;   // wide: life circle sits at ~0.056 on 1280x582, ~0.09 on 1280x632
    private static final float ME_Y0 = 0.83f, ME_Y1 = 1.0f;

    private final TextRecognizer recognizer =
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

    // A value is only accepted once it has been read the same twice in a row,
    // which filters out most misreads while cards animate over the numbers.
    private Integer pendingMe, pendingOpp;
    int me = MatchStore.UNKNOWN, opp = MatchStore.UNKNOWN;

    /** Returns true if either confirmed life total changed. */
    boolean readLife(Bitmap frame) {
        Integer m = readNumber(crop(frame, ME_Y0, ME_Y1));
        Integer o = readNumber(crop(frame, OPP_Y0, OPP_Y1));
        boolean changed = false;
        if (m != null) {
            if (m.equals(pendingMe) && m != me) { me = m; changed = true; }
            pendingMe = m;
        }
        if (o != null) {
            if (o.equals(pendingOpp) && o != opp) { opp = o; changed = true; }
            pendingOpp = o;
        }
        return changed;
    }

    /** "W", "L" or null. */
    String readResult(Bitmap frame) {
        Bitmap small = Bitmap.createScaledBitmap(frame, frame.getWidth() / 2, frame.getHeight() / 2, true);
        try {
            Text t = Tasks.await(recognizer.process(InputImage.fromBitmap(small, 0)), 3, TimeUnit.SECONDS);
            String s = t.getText().toUpperCase(Locale.US).replace(" ", "");
            if (s.contains("VICTORY")) return "W";
            if (s.contains("DEFEAT")) return "L";
        } catch (Exception ignored) {
        } finally {
            small.recycle();
        }
        return null;
    }

    private static Bitmap crop(Bitmap b, float y0, float y1) {
        int w = b.getWidth(), h = b.getHeight();
        Rect r = new Rect((int) (X0 * w), (int) (y0 * h), (int) (X1 * w), (int) (y1 * h));
        Bitmap c = Bitmap.createBitmap(b, r.left, r.top, r.width(), r.height());
        // Upscale: ML Kit does better on larger glyphs.
        Bitmap big = Bitmap.createScaledBitmap(c, c.getWidth() * 3, c.getHeight() * 3, true);
        c.recycle();
        return big;
    }

    /** Picks the tallest numeric word in the crop (the life total is the biggest text there). */
    private Integer readNumber(Bitmap crop) {
        try {
            Text t = Tasks.await(recognizer.process(InputImage.fromBitmap(crop, 0)), 2, TimeUnit.SECONDS);
            Integer best = null;
            int bestH = 0;
            for (Text.TextBlock block : t.getTextBlocks())
                for (Text.Line line : block.getLines())
                    for (Text.Element el : line.getElements()) {
                        String s = el.getText().replace('O', '0').replace('o', '0')
                                .replace('—', '-').replace('–', '-');
                        if (!s.matches("-?\\d{1,2}")) continue;
                        Rect r = el.getBoundingBox();
                        int hgt = r == null ? 0 : r.height();
                        if (hgt > bestH) { bestH = hgt; best = Integer.parseInt(s); }
                    }
            return best;
        } catch (Exception e) {
            return null;
        } finally {
            crop.recycle();
        }
    }

    void close() { recognizer.close(); }
}
