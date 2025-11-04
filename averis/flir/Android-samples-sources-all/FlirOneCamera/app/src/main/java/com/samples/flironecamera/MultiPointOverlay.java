package com.samples.flironecamera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;

public class MultiPointOverlay extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<PointData> points = new ArrayList<>();

    private static final float RADIUS = 24f;
    private static final float STROKE = 3f;

    public interface OnPointsChangedListener {
        void onChanged(ArrayList<PointData> points);
    }
    private OnPointsChangedListener listener;

    public MultiPointOverlay(Context ctx) {
        super(ctx);
        paint.setStrokeWidth(STROKE);
        paint.setColor(0xFFFFFFFF);
    }

    public void setOnPointsChangedListener(OnPointsChangedListener l) {
        this.listener = l;
    }

    public static class PointData {
        float x, y;
        double temp;
        int index;
        PointData(float x, float y, int index) {
            this.x = x; this.y = y; this.index = index;
        }
    }

    private int maxPoints = 3; // default

    public void setMaxPoints(int max) {
        if (max < 1) max = 1;
        this.maxPoints = max;
        // Trim if over limit
        while (points.size() > maxPoints) points.remove(points.size() - 1);
        invalidate();
        notifyChange();
    }

    public int getMaxPoints() { return maxPoints; }
    public int getPointCount() { return points.size(); }

    public void addPoint(float x, float y) {
        if (points.size() < maxPoints) {
            points.add(new PointData(x, y, points.size() + 1));
            invalidate();
            notifyChange();
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        for (PointData p : points) {
            // draw crosshair
            c.drawLine(p.x - RADIUS, p.y, p.x + RADIUS, p.y, paint);
            c.drawLine(p.x, p.y - RADIUS, p.x, p.y + RADIUS, paint);
        }
    }

    private PointData activePoint = null;

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                activePoint = hit(x, y);
                if (activePoint == null && points.size() < 3) {
                    addPoint(x, y);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (activePoint != null) {
                    activePoint.x = x;
                    activePoint.y = y;
                    invalidate();
                    notifyChange();
                }
                return true;
            case MotionEvent.ACTION_UP:
                activePoint = null;
                return true;
        }
        return true;
    }

    private PointData hit(float x, float y) {
        for (PointData p : points) {
            if (Math.hypot(x - p.x, y - p.y) < RADIUS * 1.5f) return p;
        }
        return null;
    }

    private void notifyChange() {
        if (listener != null) listener.onChanged(points);
    }
}
