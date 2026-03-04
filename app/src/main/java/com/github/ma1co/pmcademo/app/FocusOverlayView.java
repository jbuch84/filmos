package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.Camera;
import android.view.View;

public class FocusOverlayView extends View {
    private Paint paint;
    private ScalarWebAPIWrapper scalarWrapper;
    private boolean isPolling = false;

    public FocusOverlayView(Context context) {
        super(context);
        paint = new Paint();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(6);
        paint.setAntiAlias(true);

        // Initialize our manual IPC wrapper
        scalarWrapper = new ScalarWebAPIWrapper(context);
    }

    public void startPolling() {
        if (!isPolling && scalarWrapper.isAvailable()) {
            isPolling = true;
            invalidate();
        }
    }

    public void stopPolling() {
        isPolling = false;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        if (!isPolling || !scalarWrapper.isAvailable()) return;

        try {
            // 1. Get AF Status (0 = Searching/Yellow, 1 = Locked/Green)
            int afStatus = scalarWrapper.getInt("afStatus");
            paint.setColor(afStatus == 1 ? Color.GREEN : Color.YELLOW);

            // 2. Extract and draw the boxes
            Camera.Area[] areas = scalarWrapper.getFocusAreas();
            if (areas != null && areas.length > 0) {
                for (Camera.Area area : areas) {
                    int left = area.rect.left;
                    int top = area.rect.top;
                    int right = area.rect.right;
                    int bottom = area.rect.bottom;

                    // Matrix conversion math
                    float dLeft, dTop, dRight, dBottom;
                    if (right <= 1000 && bottom <= 1000) { 
                        dLeft = ((left + 1000) / 2000f) * getWidth();
                        dTop = ((top + 1000) / 2000f) * getHeight();
                        dRight = ((right + 1000) / 2000f) * getWidth();
                        dBottom = ((bottom + 1000) / 2000f) * getHeight();
                    } else { 
                        dLeft = (left / 6000f) * getWidth();
                        dTop = (top / 4000f) * getHeight();
                        dRight = (right / 6000f) * getWidth();
                        dBottom = (bottom / 4000f) * getHeight();
                    }

                    canvas.drawRect(dLeft, dTop, dRight, dBottom, paint);
                }
            }
        } catch (Exception e) {}

        // Poll Loop
        postInvalidateDelayed(50);
    }
}