package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.View;

/**
 * filmOS UI: Advanced Focus Meter
 * Renders a cinematic distance scale with dynamic DOF calculation.
 */
public class AdvancedFocusMeterView extends View {
    private Paint trackPaint, needlePaint, dofPaint, textPaint;
    private float ratio = 0.5f; 
    private float aperture = 2.8f;
    private boolean isActive = false;
    private Bitmap bgBitmap;

    public AdvancedFocusMeterView(Context context) {
        super(context);
        trackPaint = new Paint(); 
        trackPaint.setColor(Color.argb(150, 100, 100, 100)); 
        trackPaint.setStrokeWidth(4);
        
        dofPaint = new Paint(); 
        dofPaint.setColor(Color.argb(180, 230, 50, 15)); // filmOS Orange
        dofPaint.setStrokeWidth(12);
        dofPaint.setStrokeCap(Paint.Cap.ROUND);
        
        needlePaint = new Paint(); 
        needlePaint.setColor(Color.WHITE); 
        needlePaint.setStrokeWidth(6);
        needlePaint.setStrokeCap(Paint.Cap.ROUND);
        
        textPaint = new Paint(); 
        textPaint.setColor(Color.WHITE); 
        textPaint.setTextSize(18); 
        textPaint.setAntiAlias(true); 
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            bgBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas bgCanvas = new Canvas(bgBitmap);
            int pad = 50;
            int trackW = w - (pad * 2);
            int y = h / 2 + 10;

            bgCanvas.drawLine(pad, y, w - pad, y, trackPaint);
            
            for (int i = 0; i <= 4; i++) {
                float tickX = pad + (trackW * (i / 4.0f));
                bgCanvas.drawLine(tickX, y - 8, tickX, y + 8, trackPaint);
            }

            bgCanvas.drawText("MACRO", pad, y - 20, textPaint);
            bgCanvas.drawText("0.5m", pad + trackW * 0.25f, y - 20, textPaint);
            bgCanvas.drawText("1.0m", pad + trackW * 0.5f, y - 20, textPaint);
            bgCanvas.drawText("3.0m", pad + trackW * 0.75f, y - 20, textPaint);
            bgCanvas.drawText("INF", w - pad, y - 20, textPaint);
        }
    }

    public void update(float currentRatio, float fStop, boolean active) {
        this.ratio = currentRatio;
        this.aperture = fStop;
        this.isActive = active;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        if (!isActive) return;
        int w = getWidth();
        int h = getHeight();
        
        if (bgBitmap != null) {
            canvas.drawBitmap(bgBitmap, 0, 0, null);
        }

        int pad = 50;
        int trackW = w - (pad * 2);
        int y = h / 2 + 10;
        float needleX = pad + (trackW * ratio);

        // DOF Calculation logic extracted from MainActivity
        float apFactor = aperture / 22.0f;
        float ratioExp = ratio * ratio; 
        float dofSpread = (trackW * 0.015f) + (trackW * 0.35f * apFactor * ratioExp);
        float leftRadius = dofSpread * 0.35f;
        float rightRadius = dofSpread * 0.65f; 
        
        if (ratio > 0.95f) rightRadius = trackW; 

        canvas.save();
        canvas.clipRect(pad, 0, w - pad, h);
        canvas.drawLine(needleX - leftRadius, y, needleX + rightRadius, y, dofPaint);
        canvas.restore();
        
        canvas.drawLine(needleX, y - 18, needleX, y + 18, needlePaint);
        
        Path path = new Path();
        path.moveTo(needleX, y - 24);
        path.lineTo(needleX - 8, y - 36);
        path.lineTo(needleX + 8, y - 36);
        path.close();
        canvas.drawPath(path, needlePaint);
    }
}