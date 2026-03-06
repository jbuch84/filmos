package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/**
 * filmOS UI: Legacy Sony Battery Icon
 * Replicates the specific 3-segment battery drawing logic from the original project.
 */
public class BatteryView extends View {
    private Paint paint;
    private int level = 100;

    public BatteryView(Context context) {
        super(context);
        paint = new Paint();
        paint.setAntiAlias(true);
        paint.setStrokeWidth(2);
    }

    public void setLevel(int level) {
        this.level = level;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();

        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.STROKE);
        // Draw battery body
        canvas.drawRect(2, 2, w - 8, h - 2, paint);
        
        paint.setStyle(Paint.Style.FILL);
        // Draw battery "nub"
        canvas.drawRect(w - 8, h / 2 - 4, w - 2, h / 2 + 4, paint);

        int barColor = (level < 15) ? Color.RED : Color.WHITE;
        paint.setColor(barColor);
        int fillW = (w - 14);

        // Draw segments based on level
        if (level > 10) canvas.drawRect(6, 6, 6 + (fillW / 3) - 2, h - 6, paint);
        if (level > 40) canvas.drawRect(6 + (fillW / 3) + 2, 6, 6 + (2 * fillW / 3) - 2, h - 6, paint);
        if (level > 70) canvas.drawRect(6 + (2 * fillW / 3) + 2, 6, w - 12, h - 6, paint);
    }
}