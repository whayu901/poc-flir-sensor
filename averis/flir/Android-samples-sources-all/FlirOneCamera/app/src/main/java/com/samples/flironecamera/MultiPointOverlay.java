package com.samples.flironecamera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;

/**
 * FIXED MultiPointOverlay - respects maxPoints limit and constrains to view bounds
 */
public class MultiPointOverlay extends View {

    private final Paint crosshairPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<PointData> points = new ArrayList<>();

    private static final float RADIUS = 24f;
    private static final float STROKE = 3f;
    private static final float TEXT_SIZE_SP = 12f;

    public interface OnPointsChangedListener {
        void onChanged(ArrayList<PointData> points);
    }
    private OnPointsChangedListener listener;

    public MultiPointOverlay(Context ctx) {
        super(ctx);
        initPaints(ctx);
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    private void initPaints(Context ctx) {
        crosshairPaint.setStrokeWidth(STROKE);
        crosshairPaint.setColor(0xFFFFFFFF);
        crosshairPaint.setStyle(Paint.Style.STROKE);
        crosshairPaint.setShadowLayer(3f, 0f, 1f, 0xFF000000);

        float scaled = TEXT_SIZE_SP * ctx.getResources().getDisplayMetrics().scaledDensity;
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextSize(scaled);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setShadowLayer(3f, 0f, 1f, 0xFF000000);
    }

    public void setOnPointsChangedListener(OnPointsChangedListener l) {
        this.listener = l;
    }

    public static class PointData {
        float x, y;
        double temp;
        int index;
        PointData(float x, float y, int index) {
            this.x = x;
            this.y = y;
            this.index = index;
            this.temp = Double.NaN;
        }
    }

    private int maxPoints = 3; // default

    public void setMaxPoints(int max) {
        if (max < 1) max = 1;
        this.maxPoints = max;

        // Trim excess points if over new limit
        while (points.size() > maxPoints) {
            points.remove(points.size() - 1);
        }

        // Re-index remaining points
        for (int i = 0; i < points.size(); i++) {
            points.get(i).index = i + 1;
        }

        invalidate();
        notifyChange();
    }

    public int getMaxPoints() {
        return maxPoints;
    }

    public int getPointCount() {
        return points.size();
    }

    public void addPoint(float x, float y) {
        // FIXED: Check against maxPoints instead of hardcoded 3
        if (points.size() < maxPoints) {
            // Constrain to view bounds
            x = Math.max(0, Math.min(x, getWidth()));
            y = Math.max(0, Math.min(y, getHeight()));

            points.add(new PointData(x, y, points.size() + 1));
            invalidate();
            notifyChange();
        }
    }

    public void clearPoints() {
        points.clear();
        invalidate();
        notifyChange();
    }

    public ArrayList<PointData> getPoints() {
        return new ArrayList<>(points);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);

        for (PointData p : points) {
            // Draw crosshair
            c.drawLine(p.x - RADIUS, p.y, p.x + RADIUS, p.y, crosshairPaint);
            c.drawLine(p.x, p.y - RADIUS, p.x, p.y + RADIUS, crosshairPaint);

            // Draw point number label
            String label = String.valueOf(p.index);
            float labelY = p.y - RADIUS - 10f;
            if (labelY < textPaint.getTextSize()) {
                labelY = p.y + RADIUS + textPaint.getTextSize() + 5f;
            }
            c.drawText(label, p.x, labelY, textPaint);
        }
    }

    private PointData activePoint = null;

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX();
        float y = e.getY();

        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                // Check if touching existing point
                activePoint = hit(x, y);

                // FIXED: If not touching existing point and under limit, add new one
                if (activePoint == null && points.size() < maxPoints) {
                    addPoint(x, y);
                    // Set as active so it can be immediately adjusted
                    activePoint = points.get(points.size() - 1);
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (activePoint != null) {
                    // FIXED: Constrain movement to view bounds
                    activePoint.x = Math.max(0, Math.min(x, getWidth()));
                    activePoint.y = Math.max(0, Math.min(y, getHeight()));
                    invalidate();
                    notifyChange();
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                activePoint = null;
                return true;
        }

        return super.onTouchEvent(e);
    }

    /**
     * Hit test to find which point (if any) was touched
     */
    private PointData hit(float x, float y) {
        // Check in reverse order so topmost points are selected first
        for (int i = points.size() - 1; i >= 0; i--) {
            PointData p = points.get(i);
            double distance = Math.hypot(x - p.x, y - p.y);
            if (distance < RADIUS * 2f) { // Larger touch target
                return p;
            }
        }
        return null;
    }

    private void notifyChange() {
        if (listener != null) {
            listener.onChanged(new ArrayList<>(points));
        }
    }
}