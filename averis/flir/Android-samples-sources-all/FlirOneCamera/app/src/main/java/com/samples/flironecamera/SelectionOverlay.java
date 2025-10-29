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
 * Enhanced SelectionOverlay with:
 * - Single touch: Move and resize rectangle
 * - Two-finger pinch: Scale rectangle size
 * - Visual feedback for better UX
 * - Temperature display integration
 */
public class SelectionOverlay extends View {

    // Paint objects
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crosshairPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Constants
    private static final float EDGE_TOUCH_THRESHOLD = 48f; // Larger touch target
    private static final float HANDLE_RADIUS = 12f;
    private static final float MIN_SIZE = 50f;
    private static final float BORDER_WIDTH = 3f;
    private static final float TEXT_SIZE = 14f;

    // Selection bounds
    private RectF bounds = new RectF(100, 100, 300, 250);

    // Interaction state
    private enum Mode {
        NONE, MOVE,
        RESIZE_L, RESIZE_T, RESIZE_R, RESIZE_B,
        RESIZE_LT, RESIZE_RT, RESIZE_LB, RESIZE_RB,
        PINCH_ZOOM
    }
    private Mode currentMode = Mode.NONE;
    private float lastX, lastY;

    // Multi-touch support
    private ScaleGestureDetector scaleDetector;
    private float initialBoundsWidth, initialBoundsHeight;
    private float boundsCenterX, boundsCenterY;

    // Visual feedback
    private boolean showHandles = true;
    private boolean showCrosshair = false;

    // Callback
    public interface OnBoundsChangedListener {
        void onChanged(RectF bounds);
    }
    private OnBoundsChangedListener listener;

    public SelectionOverlay(Context context) {
        super(context);
        initPaints();
        initScaleDetector(context);
    }

    private void initPaints() {
        // Border paint (solid white)
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(BORDER_WIDTH);
        borderPaint.setColor(0xFFFFFFFF);

        // Shade paint (semi-transparent white)
        shadePaint.setColor(0x33FFFFFF);
        shadePaint.setStyle(Paint.Style.FILL);

        // Handle paint (solid white circles)
        handlePaint.setStyle(Paint.Style.FILL);
        handlePaint.setColor(0xFFFFFFFF);
        handlePaint.setShadowLayer(4f, 0f, 2f, 0x80000000);

        // Text paint (white with shadow)
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextSize(TEXT_SIZE * getResources().getDisplayMetrics().density);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setShadowLayer(3f, 0f, 1f, 0xFF000000);

        // Crosshair paint (dashed lines)
        crosshairPaint.setStyle(Paint.Style.STROKE);
        crosshairPaint.setStrokeWidth(1.5f);
        crosshairPaint.setColor(0xAAFFFFFF);
        crosshairPaint.setPathEffect(new DashPathEffect(new float[]{10f, 10f}, 0));
    }

    private void initScaleDetector(Context context) {
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                currentMode = Mode.PINCH_ZOOM;
                initialBoundsWidth = bounds.width();
                initialBoundsHeight = bounds.height();
                boundsCenterX = bounds.centerX();
                boundsCenterY = bounds.centerY();
                showHandles = false;
                showCrosshair = true;
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float scaleFactor = detector.getScaleFactor();

                // Calculate new size
                float newWidth = initialBoundsWidth * scaleFactor;
                float newHeight = initialBoundsHeight * scaleFactor;

                // Apply minimum size constraint
                newWidth = Math.max(newWidth, MIN_SIZE);
                newHeight = Math.max(newHeight, MIN_SIZE);

                // Apply maximum size constraint (80% of view)
                float maxWidth = getWidth() * 0.8f;
                float maxHeight = getHeight() * 0.8f;
                newWidth = Math.min(newWidth, maxWidth);
                newHeight = Math.min(newHeight, maxHeight);

                // Update bounds centered on original center
                bounds.set(
                        boundsCenterX - newWidth / 2f,
                        boundsCenterY - newHeight / 2f,
                        boundsCenterX + newWidth / 2f,
                        boundsCenterY + newHeight / 2f
                );

                // Keep within view bounds
                constrainToView();

                invalidate();
                notifyListener();
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector detector) {
                showHandles = true;
                showCrosshair = false;
                invalidate();
            }
        });
    }

    // Public API
    public void setOnBoundsChangedListener(OnBoundsChangedListener listener) {
        this.listener = listener;
    }

    public RectF getBounds() {
        return new RectF(bounds);
    }

    public void setBounds(RectF newBounds) {
        bounds.set(newBounds);
        constrainToView();
        invalidate();
        notifyListener();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Draw semi-transparent overlay inside selection
        canvas.drawRect(bounds, shadePaint);

        // Draw border rectangle
        canvas.drawRect(bounds, borderPaint);

        // Draw crosshair at center (during pinch zoom)
        if (showCrosshair) {
            float cx = bounds.centerX();
            float cy = bounds.centerY();
            canvas.drawLine(cx - 20, cy, cx + 20, cy, crosshairPaint);
            canvas.drawLine(cx, cy - 20, cx, cy + 20, crosshairPaint);
        }

        // Draw corner/edge handles
        if (showHandles) {
            drawHandle(canvas, bounds.left, bounds.top);           // Top-left
            drawHandle(canvas, bounds.right, bounds.top);          // Top-right
            drawHandle(canvas, bounds.left, bounds.bottom);        // Bottom-left
            drawHandle(canvas, bounds.right, bounds.bottom);       // Bottom-right
            drawHandle(canvas, bounds.centerX(), bounds.top);      // Top-center
            drawHandle(canvas, bounds.centerX(), bounds.bottom);   // Bottom-center
            drawHandle(canvas, bounds.left, bounds.centerY());     // Left-center
            drawHandle(canvas, bounds.right, bounds.centerY());    // Right-center
        }

        // Draw size label
        String sizeLabel = String.format("%.0f × %.0f", bounds.width(), bounds.height());
        float labelY = bounds.top - 10f;
        if (labelY < textPaint.getTextSize()) {
            labelY = bounds.bottom + textPaint.getTextSize() + 5f;
        }
        canvas.drawText(sizeLabel, bounds.centerX(), labelY, textPaint);
    }

    private void drawHandle(Canvas canvas, float x, float y) {
        canvas.drawCircle(x, y, HANDLE_RADIUS, handlePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Let scale detector handle multi-touch first
        boolean scaledHandled = scaleDetector.onTouchEvent(event);

        // If scale detector is active, don't process other gestures
        if (currentMode == Mode.PINCH_ZOOM) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP ||
                    event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                currentMode = Mode.NONE;
            }
            return true;
        }

        // Handle single-touch gestures
        float x = event.getX();
        float y = event.getY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                currentMode = hitTest(x, y);
                lastX = x;
                lastY = y;
                showHandles = true;
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (currentMode != Mode.NONE) {
                    float dx = x - lastX;
                    float dy = y - lastY;
                    applyDrag(dx, dy);
                    lastX = x;
                    lastY = y;
                    invalidate();
                    notifyListener();
                    return true;
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                currentMode = Mode.NONE;
                showHandles = true;
                invalidate();
                return true;
        }

        return scaledHandled || super.onTouchEvent(event);
    }

    /**
     * Determine what part of the rectangle was touched
     */
    private Mode hitTest(float x, float y) {
        boolean nearLeft = Math.abs(x - bounds.left) <= EDGE_TOUCH_THRESHOLD;
        boolean nearRight = Math.abs(x - bounds.right) <= EDGE_TOUCH_THRESHOLD;
        boolean nearTop = Math.abs(y - bounds.top) <= EDGE_TOUCH_THRESHOLD;
        boolean nearBottom = Math.abs(y - bounds.bottom) <= EDGE_TOUCH_THRESHOLD;

        // Check corners first (higher priority)
        if (nearLeft && nearTop) return Mode.RESIZE_LT;
        if (nearRight && nearTop) return Mode.RESIZE_RT;
        if (nearLeft && nearBottom) return Mode.RESIZE_LB;
        if (nearRight && nearBottom) return Mode.RESIZE_RB;

        // Check edges
        if (nearLeft) return Mode.RESIZE_L;
        if (nearRight) return Mode.RESIZE_R;
        if (nearTop) return Mode.RESIZE_T;
        if (nearBottom) return Mode.RESIZE_B;

        // Check if inside rectangle (move mode)
        if (bounds.contains(x, y)) return Mode.MOVE;

        return Mode.NONE;
    }

    /**
     * Apply drag transformation based on current mode
     */
    private void applyDrag(float dx, float dy) {
        switch (currentMode) {
            case MOVE:
                bounds.offset(dx, dy);
                break;
            case RESIZE_L:
                bounds.left += dx;
                break;
            case RESIZE_R:
                bounds.right += dx;
                break;
            case RESIZE_T:
                bounds.top += dy;
                break;
            case RESIZE_B:
                bounds.bottom += dy;
                break;
            case RESIZE_LT:
                bounds.left += dx;
                bounds.top += dy;
                break;
            case RESIZE_RT:
                bounds.right += dx;
                bounds.top += dy;
                break;
            case RESIZE_LB:
                bounds.left += dx;
                bounds.bottom += dy;
                break;
            case RESIZE_RB:
                bounds.right += dx;
                bounds.bottom += dy;
                break;
            default:
                return;
        }

        // Apply minimum size constraint
        if (bounds.width() < MIN_SIZE) {
            if (currentMode == Mode.RESIZE_L || currentMode == Mode.RESIZE_LT || currentMode == Mode.RESIZE_LB) {
                bounds.left = bounds.right - MIN_SIZE;
            } else {
                bounds.right = bounds.left + MIN_SIZE;
            }
        }

        if (bounds.height() < MIN_SIZE) {
            if (currentMode == Mode.RESIZE_T || currentMode == Mode.RESIZE_LT || currentMode == Mode.RESIZE_RT) {
                bounds.top = bounds.bottom - MIN_SIZE;
            } else {
                bounds.bottom = bounds.top + MIN_SIZE;
            }
        }

        // Keep within view bounds
        constrainToView();
    }

    /**
     * Ensure rectangle stays within view bounds
     */
    private void constrainToView() {
        float viewWidth = getWidth();
        float viewHeight = getHeight();

        if (viewWidth == 0 || viewHeight == 0) return;

        // Constrain to view bounds
        if (bounds.left < 0) {
            float shift = -bounds.left;
            bounds.offset(shift, 0);
        }

        if (bounds.top < 0) {
            float shift = -bounds.top;
            bounds.offset(0, shift);
        }

        if (bounds.right > viewWidth) {
            float shift = bounds.right - viewWidth;
            bounds.offset(-shift, 0);
        }

        if (bounds.bottom > viewHeight) {
            float shift = bounds.bottom - viewHeight;
            bounds.offset(0, -shift);
        }

        // If rectangle is larger than view (shouldn't happen, but just in case)
        if (bounds.width() > viewWidth) {
            bounds.right = bounds.left + viewWidth;
        }

        if (bounds.height() > viewHeight) {
            bounds.bottom = bounds.top + viewHeight;
        }
    }

    /**
     * Notify listener of bounds change
     */
    private void notifyListener() {
        if (listener != null) {
            listener.onChanged(getBounds());
        }
    }

    /**
     * Enable/disable hardware acceleration layer for smoother performance
     */
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }
}