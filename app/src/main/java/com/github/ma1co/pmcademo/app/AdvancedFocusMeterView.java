package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * filmOS UI: Advanced Focus Meter
 * Renders a cinematic distance scale with dynamic DOF calculation and live plot points.
 */
public class AdvancedFocusMeterView extends View {
    private Paint trackPaint, needlePaint, dofPaint, markPaint, liveTextPaint, rulerTextPaint;
    private float ratio = 0.5f; 
    private float aperture = 2.8f;
    private float liveDistance = -1.0f; // Fed directly from LensProfileManager math
    
    // Holds the dynamic calibration points to draw the dots
    private List<LensProfileManager.CalPoint> calPoints = new ArrayList<LensProfileManager.CalPoint>();

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
        
        markPaint = new Paint();
        markPaint.setColor(Color.rgb(200, 200, 200));
        markPaint.setAntiAlias(true);

        liveTextPaint = new Paint();
        liveTextPaint.setColor(Color.WHITE);
        liveTextPaint.setTextSize(22);
        liveTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
        liveTextPaint.setAntiAlias(true);
        liveTextPaint.setTextAlign(Paint.Align.CENTER);
        liveTextPaint.setShadowLayer(4, 0, 0, Color.BLACK);

        // Add this to the constructor!
        rulerTextPaint = new Paint();
        rulerTextPaint.setColor(Color.LTGRAY);
        rulerTextPaint.setTextSize(16);
        rulerTextPaint.setAntiAlias(true);
        rulerTextPaint.setTextAlign(Paint.Align.CENTER);
        rulerTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
    }

    // Feeds the view the UI dots AND the math result
    public void update(float currentRatio, float fStop, float calculatedDistance, List<LensProfileManager.CalPoint> points) {
        this.ratio = currentRatio;
        this.aperture = fStop;
        this.liveDistance = calculatedDistance;
        
        this.calPoints.clear();
        if (points != null) {
            this.calPoints.addAll(points);
        }
        invalidate(); 
    }

    private String getLiveDistanceString() {
        if (calPoints.isEmpty()) {
            return "UNMAPPED LENS";
        }
        if (liveDistance >= 999.0f) {
            return "INFINITY";
        } else if (liveDistance < 0) {
            return "--";
        } else {
            float totalInches = liveDistance * 39.3701f;
            int ft = (int) (totalInches / 12);
            int in = (int) (totalInches % 12);
            return String.format("%.2fm / %d'%d\"", liveDistance, ft, in);
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        int pad = 50;
        int trackW = w - (pad * 2);
        int y = h / 2 + 10;
        
        float safeRatio = Math.max(0.0f, Math.min(1.0f, ratio));
        float needleX = pad + (trackW * safeRatio);

        // 1. Draw the Base Track
        canvas.drawLine(pad, y, w - pad, y, trackPaint);
        
        // 2. Draw Dynamic Plot Marks & The Ruler!
        for (LensProfileManager.CalPoint pt : calPoints) {
            float markX = pad + (trackW * pt.ratio);
            canvas.drawCircle(markX, y, 5, markPaint);
            
            // Draw numerical ruler marks if it's not the 0 position
            if (pt.distance > 0f && pt.distance < 999.0f) {
                String mStr = String.format("%.1fm", pt.distance);
                float totalInches = pt.distance * 39.3701f;
                int ft = (int) (totalInches / 12);
                String fStr = ft + "'";
                
                canvas.drawText(fStr, markX, y - 12, rulerTextPaint); // Feet above the line
                canvas.drawText(mStr, markX, y + 26, rulerTextPaint); // Meters below the line
            } else if (pt.distance >= 999.0f) {
                canvas.drawText("INF", markX, y + 26, rulerTextPaint);
            }
        }

        // 3. DOF Calculation & Orange Spread
        float apFactor = aperture / 22.0f;
        float ratioExp = safeRatio * safeRatio; 
        float dofSpread = (trackW * 0.015f) + (trackW * 0.35f * apFactor * ratioExp);
        float leftRadius = dofSpread * 0.35f;
        float rightRadius = dofSpread * 0.65f; 
        if (safeRatio > 0.95f) rightRadius = trackW;

        canvas.save();
        canvas.clipRect(pad, 0, w - pad, h);
        canvas.drawLine(needleX - leftRadius, y, needleX + rightRadius, y, dofPaint);
        canvas.restore();
        
        // 4. Draw the Needle & Triangle
        canvas.drawLine(needleX, y - 18, needleX, y + 18, needlePaint);
        Path path = new Path();
        path.moveTo(needleX, y - 24);
        path.lineTo(needleX - 8, y - 36);
        path.lineTo(needleX + 8, y - 36);
        path.close();
        canvas.drawPath(path, needlePaint);

        // 5. Draw Live Interpolated Distance Text!
        canvas.drawText(getLiveDistanceString(), needleX, y - 45, liveTextPaint);
    }
}