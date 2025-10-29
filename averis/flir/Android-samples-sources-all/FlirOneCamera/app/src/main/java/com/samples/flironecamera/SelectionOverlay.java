package com.samples.flironecamera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

public class SelectionOverlay extends View {
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float EDGE = 24f;
    private RectF bounds = new RectF(100,100,300,250);
    private enum Mode { NONE, MOVE, RESIZE_L, RESIZE_T, RESIZE_R, RESIZE_B, RESIZE_LT, RESIZE_RT, RESIZE_LB, RESIZE_RB }
    private Mode mode = Mode.NONE;
    private float lastX, lastY;

    public interface OnBoundsChangedListener { void onChanged(RectF b); }
    private OnBoundsChangedListener listener;
    public void setOnBoundsChangedListener(OnBoundsChangedListener l) { this.listener = l; }
    public RectF getBounds() { return new RectF(bounds); }
    public void setBounds(RectF b) { bounds.set(b); invalidate(); if (listener!=null) listener.onChanged(getBounds()); }

    public SelectionOverlay(Context c) {
        super(c);
        border.setStyle(Paint.Style.STROKE); border.setStrokeWidth(4f);
        border.setColor(0xFFFFFFFF);
        shade.setColor(0x33FFFFFF); shade.setStyle(Paint.Style.FILL);
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        c.drawRect(bounds, shade);
        c.drawRect(bounds, border);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mode = hitTest(x, y);
                lastX = x; lastY = y;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = x - lastX, dy = y - lastY;
                applyDrag(dx, dy);
                lastX = x; lastY = y;
                if (listener != null) listener.onChanged(getBounds());
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mode = Mode.NONE;
                return true;
        }
        return super.onTouchEvent(e);
    }

    private Mode hitTest(float x, float y) {
        boolean L = Math.abs(x - bounds.left)   <= EDGE;
        boolean R = Math.abs(x - bounds.right)  <= EDGE;
        boolean T = Math.abs(y - bounds.top)    <= EDGE;
        boolean B = Math.abs(y - bounds.bottom) <= EDGE;
        if (L && T) return Mode.RESIZE_LT;
        if (R && T) return Mode.RESIZE_RT;
        if (L && B) return Mode.RESIZE_LB;
        if (R && B) return Mode.RESIZE_RB;
        if (L) return Mode.RESIZE_L;
        if (R) return Mode.RESIZE_R;
        if (T) return Mode.RESIZE_T;
        if (B) return Mode.RESIZE_B;
        if (bounds.contains(x, y)) return Mode.MOVE;
        return Mode.NONE;
    }

    private void applyDrag(float dx, float dy) {
        RectF b = bounds;
        switch (mode) {
            case MOVE: b.offset(dx, dy); break;
            case RESIZE_L: b.left += dx; break;
            case RESIZE_R: b.right += dx; break;
            case RESIZE_T: b.top += dy; break;
            case RESIZE_B: b.bottom += dy; break;
            case RESIZE_LT: b.left += dx; b.top += dy; break;
            case RESIZE_RT: b.right += dx; b.top += dy; break;
            case RESIZE_LB: b.left += dx; b.bottom += dy; break;
            case RESIZE_RB: b.right += dx; b.bottom += dy; break;
            case NONE: default: break;
        }
        float minW = 40f, minH = 40f;
        if (b.width() < minW) { b.right = b.left + minW; }
        if (b.height() < minH){ b.bottom = b.top + minH; }
        float w = getWidth(), h = getHeight();
        if (b.left < 0) { float d = -b.left; b.offset(d,0); }
        if (b.top  < 0) { float d = -b.top;  b.offset(0,d); }
        if (b.right > w)  { float d = b.right - w;  b.offset(-d,0); }
        if (b.bottom > h) { float d = b.bottom - h; b.offset(0,-d); }
    }
}
