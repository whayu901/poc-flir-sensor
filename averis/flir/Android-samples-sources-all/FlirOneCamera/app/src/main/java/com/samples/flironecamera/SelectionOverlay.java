package com.samples.flironecamera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * SelectionOverlay with two modes:
 *  - MODE_RECT: draggable/resizable rectangle (pinch-zoom supported)
 *  - MODE_POINT: draggable crosshair with floating temperature label above
 */
public class SelectionOverlay extends View {

    // ===== Public modes =====
    public static final int MODE_RECT  = 0;
    public static final int MODE_POINT = 1;

    private int currentUiMode = MODE_RECT;


    // ===== Paint objects =====
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadePaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crosshairPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ===== Constants =====
    private static final float EDGE_TOUCH_THRESHOLD = 48f; // touch target
    private static final float HANDLE_RADIUS = 12f;
    private static final float MIN_SIZE = 50f;
    private static final float BORDER_WIDTH = 3f;
    private static final float TEXT_SIZE_SP = 14f;

    // ===== Rectangle bounds (also used to store point center) =====
    private RectF bounds = new RectF(100, 100, 300, 250);

    // ===== Interaction state (rectangle mode) =====
    private enum Mode {
        NONE, MOVE,
        RESIZE_L, RESIZE_T, RESIZE_R, RESIZE_B,
        RESIZE_LT, RESIZE_RT, RESIZE_LB, RESIZE_RB,
        PINCH_ZOOM
    }
    private Mode currentDragMode = Mode.NONE;
    private float lastX, lastY;

    // ===== Pinch zoom (rectangle mode only) =====
    private ScaleGestureDetector scaleDetector;
    private float initialBoundsWidth, initialBoundsHeight;
    private float boundsCenterX, boundsCenterY;

    // ===== Visual flags =====
    private boolean showHandles = true;
    private boolean showCrosshairDuringPinch = false;

    // ===== Pointing mode label =====
    private String tempLabel = "-- °C";

    // ===== Callbacks =====
    public interface OnBoundsChangedListener { void onChanged(RectF bounds); }
    private OnBoundsChangedListener boundsListener;

    public interface OnPointChangedListener { void onChanged(float cx, float cy); }
    private OnPointChangedListener pointListener;

    public SelectionOverlay(Context context) {
        super(context);
        initPaints();
        initScaleDetector(context);
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    // ---------- Public API ----------
    public void setMode(int mode) {
        this.currentUiMode = mode;
        // Reset some visuals for clarity
        showHandles = (mode == MODE_RECT);
        showCrosshairDuringPinch = false;
        invalidate();
        // Notify once so external code can refresh stats immediately
        if (mode == MODE_POINT && pointListener != null) {
            pointListener.onChanged(bounds.centerX(), bounds.centerY());
        } else if (mode == MODE_RECT && boundsListener != null) {
            boundsListener.onChanged(getBounds());
        }
    }

    public int getMode() {
        return currentUiMode;
    }

    /** Real-time text to draw above crosshair in MODE_POINT (e.g. "36.7°C") */
    public void setTempLabel(String text) {
        this.tempLabel = text;
        invalidate();
    }

    public void setOnBoundsChangedListener(OnBoundsChangedListener l) { this.boundsListener = l; }
    public void setOnPointChangedListener(OnPointChangedListener l)   { this.pointListener  = l; }

    public RectF getBounds() { return new RectF(bounds); }

    /** Sets rectangle or point center (if MODE_POINT, only center matters) */
    public void setBounds(RectF newBounds) {
        bounds.set(newBounds);
        constrainToView();
        invalidate();
        if (currentUiMode == MODE_POINT) {
            if (pointListener != null) pointListener.onChanged(bounds.centerX(), bounds.centerY());
        } else {
            notifyBoundsListener();
        }
    }




    // ---------- Init ----------
    private void initPaints() {
        // Border (white)
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(BORDER_WIDTH);
        borderPaint.setColor(0xFF00CC00);

        // Shade (semi-transparent)
        shadePaint.setColor(0x33FFFFFF);
        shadePaint.setStyle(Paint.Style.FILL);

        // Handle circles
        handlePaint.setStyle(Paint.Style.FILL);
        handlePaint.setColor(0xFF00CC00);
        handlePaint.setShadowLayer(4f, 0f, 2f, 0x80000000);

        // Text (white with shadow)
        float scaled = TEXT_SIZE_SP * getResources().getDisplayMetrics().scaledDensity;
        textPaint.setColor(0xFF00CC00);
        textPaint.setTextSize(scaled);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setShadowLayer(3f, 0f, 1f, 0xFF000000);

        // Crosshair (dashed)
        crosshairPaint.setStyle(Paint.Style.STROKE);
        crosshairPaint.setStrokeWidth(1.5f);
        crosshairPaint.setColor(0xFF00CC00);
        crosshairPaint.setPathEffect(new DashPathEffect(new float[]{10f, 10f}, 0));
    }

    private void initScaleDetector(Context context) {
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                if (currentUiMode != MODE_RECT) return false;
                currentDragMode = Mode.PINCH_ZOOM;
                initialBoundsWidth  = bounds.width();
                initialBoundsHeight = bounds.height();
                boundsCenterX = bounds.centerX();
                boundsCenterY = bounds.centerY();
                showHandles = false;
                showCrosshairDuringPinch = true;
                invalidate();
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (currentUiMode != MODE_RECT) return false;
                float scaleFactor = detector.getScaleFactor();

                float newW = Math.max(MIN_SIZE, initialBoundsWidth  * scaleFactor);
                float newH = Math.max(MIN_SIZE, initialBoundsHeight * scaleFactor);

                // Max size ~80% of view
                float maxW = getWidth()  * 0.8f;
                float maxH = getHeight() * 0.8f;
                newW = Math.min(newW, maxW);
                newH = Math.min(newH, maxH);

                bounds.set(boundsCenterX - newW/2f, boundsCenterY - newH/2f,
                        boundsCenterX + newW/2f, boundsCenterY + newH/2f);
                constrainToView();
                invalidate();
                notifyBoundsListener();
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector detector) {
                if (currentUiMode != MODE_RECT) return;
                showHandles = true;
                showCrosshairDuringPinch = false;
                currentDragMode = Mode.NONE;
                invalidate();
            }
        });
    }

    // ---------- Drawing ----------
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (currentUiMode == MODE_RECT) {
            // Rectangle mode visuals
            canvas.drawRect(bounds, shadePaint);
            canvas.drawRect(bounds, borderPaint);

            if (showCrosshairDuringPinch) {
                float cx = bounds.centerX(), cy = bounds.centerY();
                canvas.drawLine(cx - 20, cy, cx + 20, cy, crosshairPaint);
                canvas.drawLine(cx, cy - 20, cx, cy + 20, crosshairPaint);
            }

            if (showHandles) {
                drawHandle(canvas, bounds.left,  bounds.top);
                drawHandle(canvas, bounds.right, bounds.top);
                drawHandle(canvas, bounds.left,  bounds.bottom);
                drawHandle(canvas, bounds.right, bounds.bottom);
                drawHandle(canvas, bounds.centerX(), bounds.top);
                drawHandle(canvas, bounds.centerX(), bounds.bottom);
                drawHandle(canvas, bounds.left,  bounds.centerY());
                drawHandle(canvas, bounds.right, bounds.centerY());
            }

            // Size label
            String sizeLabel = String.format("%.0f × %.0f", bounds.width(), bounds.height());
            float labelY = bounds.top - 10f;
            if (labelY < textPaint.getTextSize()) {
                labelY = bounds.bottom + textPaint.getTextSize() + 5f;
            }
//            canvas.drawText(sizeLabel, bounds.centerX(), labelY, textPaint);

        } else {
            // Pointing mode visuals
            float cx = bounds.centerX(), cy = bounds.centerY();

            // Crosshair
            canvas.drawLine(cx - 30, cy, cx + 30, cy, borderPaint);
            canvas.drawLine(cx, cy - 30, cx, cy + 30, borderPaint);

            // Floating temperature label above crosshair (if provided)
//            if (tempLabel != null) {
//                canvas.drawText(tempLabel, cx, cy - 40, textPaint);
//            }
        }
    }

    private void drawHandle(Canvas canvas, float x, float y) {
        canvas.drawCircle(x, y, HANDLE_RADIUS, handlePaint);
    }

    // ---------- Touch handling ----------
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Pointing mode: simple drag center; ignore pinch/resize
        if (currentUiMode == MODE_POINT) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = event.getX();
                    lastY = event.getY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getX() - lastX;
                    float dy = event.getY() - lastY;
                    bounds.offset(dx, dy);
                    constrainToView();
                    lastX = event.getX();
                    lastY = event.getY();
                    if (pointListener != null) pointListener.onChanged(bounds.centerX(), bounds.centerY());
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    return true;
            }
            return true;
        }

        // Rectangle mode: allow pinch + drag/resize
        boolean scaled = scaleDetector.onTouchEvent(event);
        if (currentDragMode == Mode.PINCH_ZOOM) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP ||
                    event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                currentDragMode = Mode.NONE;
            }
            return true;
        }

        float x = event.getX(), y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                currentDragMode = hitTest(x, y);
                lastX = x; lastY = y;
                showHandles = true;
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (currentDragMode != Mode.NONE) {
                    float dx = x - lastX, dy = y - lastY;
                    applyDrag(dx, dy);
                    lastX = x; lastY = y;
                    invalidate();
                    notifyBoundsListener();
                    return true;
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                currentDragMode = Mode.NONE;
                showHandles = true;
                invalidate();
                return true;
        }

        return scaled || super.onTouchEvent(event);
    }

    private Mode hitTest(float x, float y) {
        boolean L = Math.abs(x - bounds.left)   <= EDGE_TOUCH_THRESHOLD;
        boolean R = Math.abs(x - bounds.right)  <= EDGE_TOUCH_THRESHOLD;
        boolean T = Math.abs(y - bounds.top)    <= EDGE_TOUCH_THRESHOLD;
        boolean B = Math.abs(y - bounds.bottom) <= EDGE_TOUCH_THRESHOLD;

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
        switch (currentDragMode) {
            case MOVE:       bounds.offset(dx, dy); break;
            case RESIZE_L:   bounds.left   += dx;   break;
            case RESIZE_R:   bounds.right  += dx;   break;
            case RESIZE_T:   bounds.top    += dy;   break;
            case RESIZE_B:   bounds.bottom += dy;   break;
            case RESIZE_LT:  bounds.left   += dx; bounds.top    += dy; break;
            case RESIZE_RT:  bounds.right  += dx; bounds.top    += dy; break;
            case RESIZE_LB:  bounds.left   += dx; bounds.bottom += dy; break;
            case RESIZE_RB:  bounds.right  += dx; bounds.bottom += dy; break;
            default: break;
        }

        // Min size
        if (bounds.width() < MIN_SIZE) {
            if (currentDragMode == Mode.RESIZE_L || currentDragMode == Mode.RESIZE_LT || currentDragMode == Mode.RESIZE_LB)
                bounds.left = bounds.right - MIN_SIZE;
            else
                bounds.right = bounds.left + MIN_SIZE;
        }
        if (bounds.height() < MIN_SIZE) {
            if (currentDragMode == Mode.RESIZE_T || currentDragMode == Mode.RESIZE_LT || currentDragMode == Mode.RESIZE_RT)
                bounds.top = bounds.bottom - MIN_SIZE;
            else
                bounds.bottom = bounds.top + MIN_SIZE;
        }

        constrainToView();
    }

    private void constrainToView() {
        float vw = getWidth(), vh = getHeight();
        if (vw <= 0 || vh <= 0) return;

        if (bounds.left < 0)          bounds.offset(-bounds.left, 0);
        if (bounds.top  < 0)          bounds.offset(0, -bounds.top);
        if (bounds.right  > vw)       bounds.offset(vw - bounds.right, 0);
        if (bounds.bottom > vh)       bounds.offset(0, vh - bounds.bottom);

        if (bounds.width()  > vw) { bounds.right = bounds.left + vw; }
        if (bounds.height() > vh) { bounds.bottom = bounds.top + vh; }
    }

    private void notifyBoundsListener() {
        if (boundsListener != null) boundsListener.onChanged(getBounds());
    }
}
