package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

public class MultiExposeOverlayView extends View {
    private Paint thumbPaint;
    private Bitmap accumulator;
    private Canvas accumulatorCanvas;

    public MultiExposeOverlayView(Context context) {
        super(context);
        thumbPaint = new Paint();
        thumbPaint.setFilterBitmap(true);
    }

    public void addThumbnail(Bitmap thumb) {
        if (thumb == null) return;
        
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            // View hasn't measured yet, use thumb dimensions as fallback
            w = thumb.getWidth();
            h = thumb.getHeight();
        }

        if (accumulator == null) {
            accumulator = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            accumulatorCanvas = new Canvas(accumulator);
            thumbPaint.setAlpha(255); // First shot is base
        } else {
            // Subsequent shots are low opacity ghosts
            thumbPaint.setAlpha(100); 
        }

        Rect dst = new Rect(0, 0, w, h);
        accumulatorCanvas.drawBitmap(thumb, null, dst, thumbPaint);
        
        // CRITICAL: Memory safety
        thumb.recycle(); 
        invalidate();
    }

    public void clearThumbnails() {
        if (accumulator != null && !accumulator.isRecycled()) {
            accumulator.recycle();
        }
        accumulator = null;
        accumulatorCanvas = null;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (accumulator != null && !accumulator.isRecycled()) {
            canvas.drawBitmap(accumulator, 0, 0, null);
        }
    }
}
