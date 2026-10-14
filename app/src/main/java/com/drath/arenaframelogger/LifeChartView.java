package com.drath.arenaframelogger;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import org.json.JSONArray;

/** Simple two-line chart of life totals over a match: you (blue) and opponent (red). */
class LifeChartView extends View {

    private final JSONArray life;
    private final Paint me = line(Color.rgb(66, 133, 244));
    private final Paint opp = line(Color.rgb(219, 68, 55));
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    LifeChartView(Context c, JSONArray life) {
        super(c);
        this.life = life == null ? new JSONArray() : life;
        grid.setColor(Color.argb(60, 128, 128, 128));
        grid.setStrokeWidth(2);
        text.setColor(Color.GRAY);
        text.setTextSize(28 * c.getResources().getDisplayMetrics().density / 2);
        float d = c.getResources().getDisplayMetrics().density;
        setMinimumHeight((int) (180 * d));
    }

    private Paint line(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStrokeWidth(5);
        p.setStyle(Paint.Style.STROKE);
        return p;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        float padL = text.getTextSize() * 2, padB = text.getTextSize() * 1.5f, padT = 10;
        int n = life.length();
        if (n == 0) {
            canvas.drawText("No life totals were read for this match.", padL, h / 2f, text);
            return;
        }
        int maxSec = 1, maxLife = 20, minLife = 0;
        for (int i = 0; i < n; i++) {
            JSONArray p = life.optJSONArray(i);
            if (p == null) continue;
            maxSec = Math.max(maxSec, p.optInt(0));
            for (int k = 1; k <= 2; k++) {
                int v = p.optInt(k, MatchStore.UNKNOWN);
                if (v == MatchStore.UNKNOWN) continue;
                maxLife = Math.max(maxLife, v);
                minLife = Math.min(minLife, v);
            }
        }
        float plotW = w - padL - 10, plotH = h - padT - padB;
        int range = Math.max(1, maxLife - minLife);
        for (int v = 0; v <= maxLife; v += 10) {
            float y = padT + plotH * (maxLife - v) / range;
            canvas.drawLine(padL, y, w - 10, y, grid);
            canvas.drawText(String.valueOf(v), 4, y + text.getTextSize() / 3, text);
        }
        canvas.drawText((maxSec / 60) + "m", w - text.getTextSize() * 2.5f, h - 6, text);
        for (int k = 1; k <= 2; k++) {
            Path path = new Path();
            boolean started = false;
            float lastY = 0;
            for (int i = 0; i < n; i++) {
                JSONArray p = life.optJSONArray(i);
                if (p == null) continue;
                int v = p.optInt(k, MatchStore.UNKNOWN);
                if (v == MatchStore.UNKNOWN) continue;
                float x = padL + plotW * p.optInt(0) / maxSec;
                float y = padT + plotH * (maxLife - v) / range;
                if (!started) { path.moveTo(x, y); started = true; }
                else { path.lineTo(x, lastY); path.lineTo(x, y); } // step chart: life changes in jumps
                lastY = y;
            }
            if (started) canvas.drawPath(path, k == 1 ? me : opp);
        }
        Paint label = new Paint(text);
        label.setColor(me.getColor());
        canvas.drawText("you", padL + 6, h - 6, label);
        label.setColor(opp.getColor());
        canvas.drawText("opponent", padL + text.getTextSize() * 3, h - 6, label);
    }
}
