/*
 * ******************************************************************
 * @title FLIR Atlas Android SDK - OPTIMIZED
 * @file MainActivity.java
 * @Author Teledyne FLIR
 *
 * @brief  Optimized Main UI with efficient frame handling
 *
 * Copyright 2023:    Teledyne FLIR
 * ******************************************************************/
package com.samples.flironecamera;

import static com.samples.flironecamera.R.*;

import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.flir.thermalsdk.ErrorCode;
import com.flir.thermalsdk.androidsdk.ThermalSdkAndroid;
import com.flir.thermalsdk.androidsdk.live.connectivity.UsbPermissionHandler;
import com.flir.thermalsdk.live.CommunicationInterface;
import com.flir.thermalsdk.live.Identity;
import com.flir.thermalsdk.live.connectivity.ConnectionStatusListener;
import com.flir.thermalsdk.live.discovery.DiscoveredCamera;
import com.flir.thermalsdk.live.discovery.DiscoveryEventListener;
import com.flir.thermalsdk.log.ThermalLog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OPTIMIZED MainActivity with:
 * - Efficient frame buffering
 * - Proper UI thread management
 * - Reduced memory allocations
 * - Frame capture with thermal data snapshot
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int UI_UPDATE_INTERVAL_MS = 50; // ~20 FPS UI cap

    // Camera handler
    private CameraHandler cameraHandler;
    private Identity connectedIdentity = null;

    // UI components
    private TextView connectionStatus;
    private TextView discoveryStatus;
    private TextView deviceInfo;
    private ImageView msxImage;
    private ImageView photoImage;

    // Frame management - using volatile for thread safety
    private volatile Bitmap lastMsxBitmap;
    private volatile Bitmap lastDcBitmap;
    private final AtomicBoolean isUpdatingUI = new AtomicBoolean(false);
    private long lastUiUpdateMs = 0;

    // Capture state
    private Bitmap capturedMsxBitmap;
    private CameraHandler.TempFrameSnapshot capturedTempSnapshot;
    private final AtomicBoolean isCapturing = new AtomicBoolean(false);

    // USB permission handler
    private final UsbPermissionHandler usbPermissionHandler = new UsbPermissionHandler();

    // UI Handler for throttled updates
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable updateUIRunnable = this::updateUIWithLatestFrame;

    private SelectionOverlay selectionOverlay;
    private MultiPointOverlay multiPointOverlay;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize Thermal SDK
        ThermalSdkAndroid.init(getApplicationContext(), ThermalLog.LogLevel.WARNING); // Changed to WARNING to reduce log spam

        cameraHandler = new CameraHandler();
        setupViews();

        showSDKversion(ThermalSdkAndroid.getVersion());
        showSDKCommitHash(ThermalSdkAndroid.getCommitHash());
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.d(TAG, "onStop() - Disconnecting camera");
        disconnect();
        uiHandler.removeCallbacks(updateUIRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Clean up bitmaps to prevent memory leaks
        recycleBitmaps();
    }

    // ==================== Button Click Handlers ====================

    public void startDiscovery(View view) {
        startDiscovery();
    }

    public void stopDiscovery(View view) {
        stopDiscovery();
    }

    public void connectFlirOne(View view) {
        connect(cameraHandler.getFlirOne());
    }

    public void connectSimulatorOne(View view) {
        connect(cameraHandler.getCppEmulator());
    }

    public void connectSimulatorTwo(View view) {
        connect(cameraHandler.getFlirOneEmulator());
    }

    public void disconnect(View view) {
        disconnect();
    }

    public void performNuc(View view) {
        cameraHandler.performNuc();
    }




    /**
     * OPTIMIZED frame capture with thermal data snapshot
     */
    public void captureFrame(View view) {
        if (lastMsxBitmap == null) {
            showMessage.show("No frame available yet");
            return;
        }

        // Prevent multiple simultaneous captures
        if (!isCapturing.compareAndSet(false, true)) {
            showMessage.show("Capture already in progress...");
            return;
        }

        // Show progress indicator
        showMessage.show("Capturing thermal data...");

        // Capture on background thread to avoid UI lag
        new Thread(() -> {
            try {
                // 1. Copy the displayed MSX bitmap (fast)
                Bitmap msxCopy = copyBitmap(lastMsxBitmap);

                // 2. Build full temperature snapshot (HEAVY - runs on background thread)
                CameraHandler.TempFrameSnapshot tempSnap = cameraHandler.buildTempSnapshotSync();

                // 3. Get min/max temperatures
                double minC = cameraHandler.getLastMinTempC();
                double maxC = cameraHandler.getLastMaxTempC();

                // Store for dialog
                capturedMsxBitmap = msxCopy;
                capturedTempSnapshot = tempSnap;

                // 4. Save to gallery (background)
                String filename = "FLIR_MSX_" + System.currentTimeMillis() + ".jpg";
                saveBitmapToGallery(msxCopy, filename);

                // 5. Show preview dialog on UI thread
                runOnUiThread(() -> {
                    if (tempSnap != null) {
                        showCapturePopupWithRoi(msxCopy, minC, maxC, tempSnap);
                    } else {
                        showMessage.show("Capture saved, but thermal data unavailable");
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Capture error", e);
                runOnUiThread(() -> showMessage.show("Capture failed: " + e.getMessage()));
            } finally {
                isCapturing.set(false);
            }
        }).start();
    }

    // ==================== Camera Connection ====================

    private void connect(Identity identity) {
        cameraHandler.stopDiscovery(discoveryStatusListener);

        if (connectedIdentity != null) {
            showMessage.show("Already connected to a camera");
            return;
        }

        if (identity == null) {
            showMessage.show("No camera available to connect");
            return;
        }

        connectedIdentity = identity;
        updateConnectionText(identity, "CONNECTING");

        if (UsbPermissionHandler.isFlirOne(identity)) {
            usbPermissionHandler.requestFlirOnePermisson(identity, this, permissionListener);
        } else {
            doConnect(identity);
        }
    }

    private void doConnect(Identity identity) {
        new Thread(() -> {
            try {
                cameraHandler.connect(identity, connectionStatusListener);

                runOnUiThread(() -> {
                    updateConnectionText(identity, "CONNECTED");
                    deviceInfo.setText(cameraHandler.getDeviceInfo());
                });

                // Start streaming with optimized listener
                cameraHandler.startStream(streamDataListener);

            } catch (IOException e) {
                Log.e(TAG, "Connection failed", e);
                runOnUiThread(() -> {
                    updateConnectionText(identity, "DISCONNECTED");
                    showMessage.show("Connection failed: " + e.getMessage());
                });
            }
        }).start();
    }

    private void disconnect() {
        if (connectedIdentity == null) return;

        updateConnectionText(connectedIdentity, "DISCONNECTING");
        connectedIdentity = null;

        new Thread(() -> {
            cameraHandler.disconnect();
            runOnUiThread(() -> {
                updateConnectionText(null, "DISCONNECTED");
                recycleBitmaps(); // Clean up when disconnecting
            });
        }).start();
    }

    // ==================== Camera Discovery ====================

    private void startDiscovery() {
        cameraHandler.startDiscovery(cameraDiscoveryListener, discoveryStatusListener);
    }

    private void stopDiscovery() {
        cameraHandler.stopDiscovery(discoveryStatusListener);
    }

    // ==================== Stream Data Handling (OPTIMIZED) ====================

    /**
     * OPTIMIZED stream listener - minimal work on callback thread
     */
    private final CameraHandler.StreamDataListener streamDataListener = new CameraHandler.StreamDataListener() {
        @Override
        public void images(Bitmap msxBitmap, Bitmap dcBitmap) {
            // Just store the latest bitmaps - no UI work here
            lastMsxBitmap = msxBitmap;
            lastDcBitmap = dcBitmap;

            // Schedule UI update (throttled)
            long now = System.currentTimeMillis();
            if (now - lastUiUpdateMs >= UI_UPDATE_INTERVAL_MS) {
                lastUiUpdateMs = now;

                // Remove any pending updates and post new one
                uiHandler.removeCallbacks(updateUIRunnable);
                uiHandler.post(updateUIRunnable);
            }
        }
    };

    /**
     * Update UI with latest frame (runs on UI thread, throttled)
     */
    private void updateUIWithLatestFrame() {
        // Skip if already updating
        if (!isUpdatingUI.compareAndSet(false, true)) {
            return;
        }

        try {
            Bitmap msx = lastMsxBitmap;
            Bitmap dc = lastDcBitmap;

            if (msx != null) {
                msxImage.setImageBitmap(msx);
            }

            if (dc != null) {
                photoImage.setImageBitmap(dc);
            }
        } finally {
            isUpdatingUI.set(false);
        }
    }

    private Bitmap colorizeBlueRed(@NonNull CameraHandler.TempFrameSnapshot snap, int outW, int outH) {
        Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        final float tMin = (float) snap.minC;
        final float tMax = (float) snap.maxC;
        final float range = Math.max(0.01f, tMax - tMin);

        int[] row = new int[outW];
        for (int y = 0; y < outH; y++) {
            int ty = Math.min(snap.h - 1, Math.round((y / (float) outH) * (snap.h - 1)));
            int srcRow = ty * snap.w;
            for (int x = 0; x < outW; x++) {
                int tx = Math.min(snap.w - 1, Math.round((x / (float) outW) * (snap.w - 1)));
                float c = snap.tempC[srcRow + tx];
                float n = (c - tMin) / range; n = Math.max(0f, Math.min(1f, n));
                int r = (int)(255f * n);
                int g = 0;
                int b = (int)(255f * (1f - n));
                row[x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            out.setPixels(row, 0, outW, 0, y, outW, 1);
        }
        return out;
    }


    // ==================== Capture Preview Dialog ====================
    private void updatePointRealtimeLabel(SelectionOverlay overlay, ImageView iv, Bitmap bmp) {
        try {
            // Map overlay center (view coords) -> bitmap coords
            RectF b = overlay.getBounds();
            float vx = b.centerX(), vy = b.centerY();

            // Uses your existing helper; if you don’t have it yet, paste the one you used for ROI mapping
            PointF bp = mapViewToBitmap(iv, vx, vy, bmp); // returns bitmap-space x/y (clamped)

            // Map bitmap -> thermal grid using the *latest* live snapshot
            CameraHandler.TempFrameSnapshot live = cameraHandler.getLastTempFrameSnapshot();
            if (live == null || live.tempC == null) {
                overlay.setTempLabel("-- °C");
                return;
            }

            float gx = (float) live.w / bmp.getWidth();
            float gy = (float) live.h / bmp.getHeight();
            int tx = clamp(Math.round(bp.x * gx), 0, live.w - 1);
            int ty = clamp(Math.round(bp.y * gy), 0, live.h - 1);

            float center = live.tempC[ty * live.w + tx];

            overlay.setTempLabel(String.format(java.util.Locale.US, "%.1f°C", center));
        } catch (Exception e) {
            Log.w(TAG, "updatePointRealtimeLabel failed", e);
            overlay.setTempLabel("-- °C");
        }
    }


    private static final int MODE_RECT = 1;
    private static final int MODE_POINT = 2;
    private static final int MODE_POINT_MULTI = 3;
    private int currentMode = MODE_RECT;

    private void showCapturePopupWithRoi(@NonNull Bitmap bitmap, double minC, double maxC,
                                         @NonNull CameraHandler.TempFrameSnapshot snap) {

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_captured_preview, null);
        ImageView imageView = dialogView.findViewById(R.id.capturedImageView);
        TextView statsText = dialogView.findViewById(R.id.tempStatsText);
        Button closeBtn = dialogView.findViewById(R.id.closeDialogBtn);
        FrameLayout container = dialogView.findViewById(R.id.previewContainer);
        Spinner modeSpinner = dialogView.findViewById(R.id.modeSpinner);
        LinearLayout countRow = dialogView.findViewById(R.id.countRow);
        Spinner countSpinner = dialogView.findViewById(R.id.countSpinner);


        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);

        imageView.setImageBitmap(bitmap);

        statsText.setText(String.format(java.util.Locale.US,
                "High: %.1f°C   Low: %.1f°C", maxC, minC));

        selectionOverlay = new SelectionOverlay(this);
        container.addView(selectionOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // adapter for counts
        ArrayAdapter<CharSequence> countAdapter = ArrayAdapter.createFromResource(
                this, R.array.point_counts, android.R.layout.simple_spinner_dropdown_item);
        countSpinner.setAdapter(countAdapter);
        countSpinner.setSelection(0); // default "3"

        // adapter from resources
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this, R.array.modes_array, android.R.layout.simple_spinner_dropdown_item);
        modeSpinner.setAdapter(adapter);

// default selection (choose what you prefer)
        modeSpinner.setSelection(2); // 0=single point, 1=multi point (3), 2=rectangle

        modeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (pos == 0) { // Pointing (1 spot)
                    enableSinglePoint(container, statsText, imageView, bitmap, snap);
                } else if (pos == 1) { // Pointing (3 spots)
                    countRow.setVisibility(View.VISIBLE);



                    // current selection (3/5/10)
                    int maxPoints = Integer.parseInt((String) countSpinner.getSelectedItem());
                    enableMultiPoint(container, statsText, imageView, bitmap, snap, maxPoints);
                } else { // Rectangle
                    enableRectangleMode(container, statsText, imageView, bitmap, snap);
                }
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        countSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int i, long id) {
                if (currentMode == MODE_POINT_MULTI) {
                    int maxPoints = Integer.parseInt((String) countSpinner.getSelectedItem());
                    // Update existing overlay in place (no recreation)
                    if (multiPointOverlay != null) {
                        multiPointOverlay.setMaxPoints(maxPoints);
                        // If we increased the limit, auto-seed more points to reach new max
                        enableMultiPoint(container, statsText, imageView, bitmap, snap, maxPoints);
                    }
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        imageView.post(() -> {
            RectF imgRect = getDisplayedImageRect(imageView);
            float w = imgRect.width() * 0.4f;
            float h = imgRect.height() * 0.4f;
            float left = imgRect.centerX() - w / 2f;
            float top = imgRect.centerY() - h / 2f;
            selectionOverlay.setBounds(new RectF(left, top, left + w, top + h));
            updateRoiStats(statsText, selectionOverlay.getBounds(), imageView, bitmap, snap);
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(true)
                .create();

        closeBtn.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }


    /**
     * Update ROI temperature statistics
     */
    private void updateRoiStats(TextView statsText, RectF roiView, ImageView iv,
                                Bitmap bmp, CameraHandler.TempFrameSnapshot snap) {
        try {
            if (bmp == null || snap == null || snap.tempC == null) return;

            // 1) Map the ROI corners from VIEW space -> BITMAP pixel space using the actual image matrix
            //    (This is more precise than scaling via displayed rect.)
            PointF tl = mapViewToBitmap(iv, roiView.left,  roiView.top,    bmp);
            PointF tr = mapViewToBitmap(iv, roiView.right, roiView.top,    bmp);
            PointF bl = mapViewToBitmap(iv, roiView.left,  roiView.bottom, bmp);
            PointF br = mapViewToBitmap(iv, roiView.right, roiView.bottom, bmp);

            // Bounding box in bitmap space (handles any non-uniform scale)
            float bx0f = Math.min(Math.min(tl.x, tr.x), Math.min(bl.x, br.x));
            float by0f = Math.min(Math.min(tl.y, tr.y), Math.min(bl.y, br.y));
            float bx1f = Math.max(Math.max(tl.x, tr.x), Math.max(bl.x, br.x));
            float by1f = Math.max(Math.max(tl.y, tr.y), Math.max(bl.y, br.y));

            // Clamp to bitmap bounds
            bx0f = Math.max(0, Math.min(bx0f, bmp.getWidth()  - 1));
            by0f = Math.max(0, Math.min(by0f, bmp.getHeight() - 1));
            bx1f = Math.max(0, Math.min(bx1f, bmp.getWidth()  - 1));
            by1f = Math.max(0, Math.min(by1f, bmp.getHeight() - 1));

            // 2) Map bitmap -> THERMAL grid (snap.w x snap.h)
            float gx = (float) snap.w / bmp.getWidth();
            float gy = (float) snap.h / bmp.getHeight();
            float tx0f = bx0f * gx, ty0f = by0f * gy;
            float tx1f = bx1f * gx, ty1f = by1f * gy;

            // Convert to integer index range (half-open) and ensure at least 1 pixel
            int tx0 = clamp((int) Math.floor(tx0f), 0, snap.w - 1);
            int ty0 = clamp((int) Math.floor(ty0f), 0, snap.h - 1);
            int tx1 = clamp((int) Math.ceil (tx1f), 1, snap.w);   // half-open end
            int ty1 = clamp((int) Math.ceil (ty1f), 1, snap.h);   // half-open end

            // If the box is tiny and collapses to <1 thermal pixel, expand to a small 3×3 neighborhood
            if (tx1 - tx0 < 1) { tx0 = clamp(tx0 - 1, 0, snap.w - 1); tx1 = clamp(tx0 + 2, 1, snap.w); }
            if (ty1 - ty0 < 1) { ty0 = clamp(ty0 - 1, 0, snap.h - 1); ty1 = clamp(ty0 + 2, 1, snap.h); }

            // 3) Scan the thermal snapshot in that ROI precisely
            float[] t = snap.tempC;
            double minC = Double.POSITIVE_INFINITY, maxC = Double.NEGATIVE_INFINITY;

            for (int y = ty0; y < ty1; y++) {
                int row = y * snap.w;
                for (int x = tx0; x < tx1; x++) {
                    float v = t[row + x];
                    if (v < minC) minC = v;
                    if (v > maxC) maxC = v;
                }
            }

            if (minC == Double.POSITIVE_INFINITY) {
                statsText.setText("High: --°C   Low: --°C");
            } else {
                statsText.setText(String.format(java.util.Locale.US, "High: %.1f°C   Low: %.1f°C", maxC, minC));
            }
        } catch (Exception e) {
            Log.e(TAG, "ROI stats calculation error", e);
            statsText.setText("High: --°C   Low: --°C");
        }
    }

    private void clearOverlaysKeepImage(FrameLayout container) {
        for (int i = container.getChildCount() - 1; i >= 0; i--) {
            View v = container.getChildAt(i);
            if (v.getId() != R.id.capturedImageView) {
                container.removeViewAt(i);
            }
        }
    }


    private void enableSinglePoint(FrameLayout container, TextView statsText,
                                   ImageView iv, Bitmap bmp,
                                   CameraHandler.TempFrameSnapshot snap) {
        currentMode = MODE_POINT;
        clearOverlaysKeepImage(container);

        if (selectionOverlay == null) {
            selectionOverlay = new SelectionOverlay(this);
        }
        if (selectionOverlay.getParent() == null) {
            container.addView(selectionOverlay, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        }

        selectionOverlay.setMode(SelectionOverlay.MODE_POINT);
        selectionOverlay.setOnBoundsChangedListener(null);

        // Initialize crosshair at image center
        iv.post(() -> {
            RectF img = getDisplayedImageRect(iv);
            if (img.width() <= 1f || img.height() <= 1f) return;

            float cx = img.centerX();
            float cy = img.centerY();
            float half = 10f;

            selectionOverlay.setBounds(new RectF(cx - half, cy - half, cx + half, cy + half));
            updatePointTemp(statsText, cx, cy, iv, bmp, snap);
        });

        // ENHANCED: Constrain point movement to image bounds
        selectionOverlay.setOnPointChangedListener((cx, cy) -> {
            RectF imgBounds = getDisplayedImageRect(iv);

            // Clamp to image area
            float clampedX = Math.max(imgBounds.left, Math.min(cx, imgBounds.right));
            float clampedY = Math.max(imgBounds.top, Math.min(cy, imgBounds.bottom));

            // If point was dragged outside, move it back
            if (clampedX != cx || clampedY != cy) {
                float half = 10f;
                selectionOverlay.setBounds(new RectF(
                        clampedX - half, clampedY - half,
                        clampedX + half, clampedY + half));
            }

            updatePointTemp(statsText, clampedX, clampedY, iv, bmp, snap);
        });

        selectionOverlay.setClickable(true);
        selectionOverlay.bringToFront();
        container.invalidate();
    }


    private void enableMultiPoint(FrameLayout container, TextView statsText,
                                  ImageView iv, Bitmap bmp,
                                  CameraHandler.TempFrameSnapshot snap,
                                  int maxPoints) {
        currentMode = MODE_POINT_MULTI;
        clearOverlaysKeepImage(container);

        if (selectionOverlay != null && selectionOverlay.getParent() == container) {
            container.removeView(selectionOverlay);
        }

        if (multiPointOverlay == null) {
            multiPointOverlay = new MultiPointOverlay(this);
            container.addView(multiPointOverlay, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        } else if (multiPointOverlay.getParent() != container) {
            container.addView(multiPointOverlay);
        }

        multiPointOverlay.setMaxPoints(maxPoints);
        multiPointOverlay.bringToFront();

        // ENHANCED: Validate points are within image bounds after updates
        multiPointOverlay.setOnPointsChangedListener(points -> {
            RectF imgBounds = getDisplayedImageRect(iv);

            // Clamp all points to image area
            boolean needsCorrection = false;
            for (MultiPointOverlay.PointData p : points) {
                float originalX = p.x;
                float originalY = p.y;

                p.x = Math.max(imgBounds.left, Math.min(p.x, imgBounds.right));
                p.y = Math.max(imgBounds.top, Math.min(p.y, imgBounds.bottom));

                if (p.x != originalX || p.y != originalY) {
                    needsCorrection = true;
                }
            }

            if (needsCorrection) {
                multiPointOverlay.invalidate();
            }

            updateMultiPointTemps(statsText, points, iv, bmp, snap);
        });

        // Auto-seed points within image bounds
        iv.post(() -> {
            RectF img = getDisplayedImageRect(iv);
            if (img.width() <= 0 || img.height() <= 0) return;

            multiPointOverlay.clearPoints();

            float cx = img.centerX();
            float cy = img.centerY();
            float spanX = img.width() * 0.25f;
            float spanY = img.height() * 0.25f;

            // Add points in a grid pattern within image bounds
            switch (maxPoints) {
                case 1:
                    multiPointOverlay.addPoint(cx, cy);
                    break;
                case 3:
                    multiPointOverlay.addPoint(cx, cy);
                    multiPointOverlay.addPoint(cx - spanX, cy);
                    multiPointOverlay.addPoint(cx + spanX, cy);
                    break;
                case 5:
                    multiPointOverlay.addPoint(cx, cy);
                    multiPointOverlay.addPoint(cx - spanX, cy);
                    multiPointOverlay.addPoint(cx + spanX, cy);
                    multiPointOverlay.addPoint(cx, cy - spanY);
                    multiPointOverlay.addPoint(cx, cy + spanY);
                    break;
                case 10:
                    // Center
                    multiPointOverlay.addPoint(cx, cy);
                    // Cross pattern
                    multiPointOverlay.addPoint(cx - spanX, cy);
                    multiPointOverlay.addPoint(cx + spanX, cy);
                    multiPointOverlay.addPoint(cx, cy - spanY);
                    multiPointOverlay.addPoint(cx, cy + spanY);
                    // Diagonals
                    multiPointOverlay.addPoint(cx - spanX*0.7f, cy - spanY*0.7f);
                    multiPointOverlay.addPoint(cx + spanX*0.7f, cy + spanY*0.7f);
                    multiPointOverlay.addPoint(cx - spanX*0.7f, cy + spanY*0.7f);
                    multiPointOverlay.addPoint(cx + spanX*0.7f, cy - spanY*0.7f);
                    // Extra center offset
                    multiPointOverlay.addPoint(cx, cy - spanY*0.5f);
                    break;
                default:
                    // Generic grid
                    multiPointOverlay.addPoint(cx, cy);
                    for (int i = 1; i < maxPoints; i++) {
                        float angle = (float)(2 * Math.PI * i / maxPoints);
                        float x = cx + spanX * (float)Math.cos(angle);
                        float y = cy + spanY * (float)Math.sin(angle);
                        multiPointOverlay.addPoint(x, y);
                    }
                    break;
            }
        });
    }

    /**
     * ENHANCED: Rectangle mode with image boundary enforcement
     */
    private void enableRectangleMode(FrameLayout container, TextView statsText,
                                     ImageView iv, Bitmap bmp,
                                     CameraHandler.TempFrameSnapshot snap) {
        currentMode = MODE_RECT;
        clearOverlaysKeepImage(container);

        if (multiPointOverlay != null && multiPointOverlay.getParent() == container) {
            container.removeView(multiPointOverlay);
        }

        if (selectionOverlay == null) {
            selectionOverlay = new SelectionOverlay(this);
        }
        if (selectionOverlay.getParent() == null) {
            container.addView(selectionOverlay, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        }

        selectionOverlay.setMode(SelectionOverlay.MODE_RECT);
        selectionOverlay.setOnPointChangedListener(null);

        // ENHANCED: Constrain rectangle to image bounds during dragging
        selectionOverlay.setOnBoundsChangedListener(bounds -> {
            RectF imgBounds = getDisplayedImageRect(iv);

            // Clamp rectangle to image area
            RectF constrainedBounds = new RectF(bounds);

            // Ensure rectangle stays within image
            if (constrainedBounds.left < imgBounds.left) {
                float shift = imgBounds.left - constrainedBounds.left;
                constrainedBounds.left += shift;
                constrainedBounds.right += shift;
            }
            if (constrainedBounds.top < imgBounds.top) {
                float shift = imgBounds.top - constrainedBounds.top;
                constrainedBounds.top += shift;
                constrainedBounds.bottom += shift;
            }
            if (constrainedBounds.right > imgBounds.right) {
                float shift = constrainedBounds.right - imgBounds.right;
                constrainedBounds.left -= shift;
                constrainedBounds.right -= shift;
            }
            if (constrainedBounds.bottom > imgBounds.bottom) {
                float shift = constrainedBounds.bottom - imgBounds.bottom;
                constrainedBounds.top -= shift;
                constrainedBounds.bottom -= shift;
            }

            // Final hard clamp
            constrainedBounds.left = Math.max(imgBounds.left, constrainedBounds.left);
            constrainedBounds.top = Math.max(imgBounds.top, constrainedBounds.top);
            constrainedBounds.right = Math.min(imgBounds.right, constrainedBounds.right);
            constrainedBounds.bottom = Math.min(imgBounds.bottom, constrainedBounds.bottom);

            // Update if needed
            if (!constrainedBounds.equals(bounds)) {
                selectionOverlay.setBounds(constrainedBounds);
            }

            updateRoiStats(statsText, constrainedBounds, iv, bmp, snap);
        });

        selectionOverlay.bringToFront();

        // Initialize rectangle within image bounds
        iv.post(() -> {
            RectF imgRect = getDisplayedImageRect(iv);
            if (imgRect.width() <= 1f || imgRect.height() <= 1f) return;

            float w = Math.min(imgRect.width() * 0.4f, imgRect.width() - 20);
            float h = Math.min(imgRect.height() * 0.4f, imgRect.height() - 20);
            float left = imgRect.centerX() - w / 2f;
            float top = imgRect.centerY() - h / 2f;

            // Ensure within bounds
            left = Math.max(imgRect.left, Math.min(left, imgRect.right - w));
            top = Math.max(imgRect.top, Math.min(top, imgRect.bottom - h));

            selectionOverlay.setBounds(new RectF(left, top, left + w, top + h));
            updateRoiStats(statsText, selectionOverlay.getBounds(), iv, bmp, snap);
        });
    }




//    private void updatePointTemp(TextView statsText, float cx, float cy,
//                                 ImageView iv, Bitmap bmp,
//                                 CameraHandler.TempFrameSnapshot snap) {
//        try {
//            // View → displayed image rect
//            RectF imgRect = getDisplayedImageRect(iv);
//            if (imgRect.width() <= 1f || imgRect.height() <= 1f) {
//                statsText.setText("Temp: --°C");
//                return;
//            }
//
//            // Clamp crosshair to image area (so it works fully to the edges)
//            float px = Math.max(imgRect.left, Math.min(cx, imgRect.right));
//            float py = Math.max(imgRect.top,  Math.min(cy, imgRect.bottom));
//
//            // View → bitmap coords
//            float scaleX = (float) bmp.getWidth()  / imgRect.width();
//            float scaleY = (float) bmp.getHeight() / imgRect.height();
//            float bx = (px - imgRect.left) * scaleX;
//            float by = (py - imgRect.top)  * scaleY;
//
//            // Bitmap → thermal grid (continuous coords for bilinear)
//            float gx = (float) snap.w / bmp.getWidth();
//            float gy = (float) snap.h / bmp.getHeight();
//            float txf = bx * gx;
//            float tyf = by * gy;
//
//            // Bilinear sampling on thermal grid
//            int x0 = clamp((int) Math.floor(txf), 0, snap.w - 1);
//            int y0 = clamp((int) Math.floor(tyf), 0, snap.h - 1);
//            int x1 = clamp(x0 + 1, 0, snap.w - 1);
//            int y1 = clamp(y0 + 1, 0, snap.h - 1);
//
//            float wx = txf - x0;
//            float wy = tyf - y0;
//
//            float[] t = snap.tempC;
//            float t00 = t[y0 * snap.w + x0];
//            float t10 = t[y0 * snap.w + x1];
//            float t01 = t[y1 * snap.w + x0];
//            float t11 = t[y1 * snap.w + x1];
//
//            // bilinear interpolation
//            float top = t00 * (1 - wx) + t10 * wx;
//            float bot = t01 * (1 - wx) + t11 * wx;
//            float temp = top * (1 - wy) + bot * wy;
//
//            // (Optional) micro-average around the point for stability
//            // temp = smooth3x3(snap, txf, tyf); // skip if not needed
//
//            statsText.setText(String.format(java.util.Locale.US, "Temp: %.1f°C", temp));
//
//        } catch (Exception e) {
//            Log.w(TAG, "updatePointTemp failed", e);
//            statsText.setText("Temp: --°C");
//        }
//    }

    private void updatePointTemp(TextView statsText, float cx, float cy,
                                 ImageView iv, Bitmap bmp,
                                 CameraHandler.TempFrameSnapshot snap) {
        try {
            RectF imgRect = getDisplayedImageRect(iv);
            if (imgRect.width() <= 1f || imgRect.height() <= 1f) {
                statsText.setText("Temp: --°C");
                return;
            }

            // Clamp touch to displayed image area
            float px = Math.max(imgRect.left, Math.min(cx, imgRect.right));
            float py = Math.max(imgRect.top,  Math.min(cy, imgRect.bottom));

            // View -> bitmap coords
            float bx = (px - imgRect.left) * ((float) bmp.getWidth()  / imgRect.width());
            float by = (py - imgRect.top)  * ((float) bmp.getHeight() / imgRect.height());

            // Bitmap -> thermal grid (continuous)
            float txf = bx * ((float) snap.w / bmp.getWidth());
            float tyf = by * ((float) snap.h / bmp.getHeight());

            // 3×3 neighborhood average in the thermal grid
            int cxT = clamp(Math.round(txf), 0, snap.w - 1);
            int cyT = clamp(Math.round(tyf), 0, snap.h - 1);

            double sum = 0;
            int cnt = 0;
            for (int dy = -1; dy <= 1; dy++) {
                int yy = clamp(cyT + dy, 0, snap.h - 1);
                int rowOff = yy * snap.w;
                for (int dx = -1; dx <= 1; dx++) {
                    int xx = clamp(cxT + dx, 0, snap.w - 1);
                    sum += snap.tempC[rowOff + xx];
                    cnt++;
                }
            }
            double temp = (cnt > 0) ? (sum / cnt) : Double.NaN;

            if (Double.isFinite(temp)) {
                statsText.setText(String.format(java.util.Locale.US, "Temp: %.1f°C", temp));
            } else {
                statsText.setText("Temp: --°C");
            }

        } catch (Exception e) {
            statsText.setText("Temp: --°C");
        }
    }


    private void updateMultiPointTemps(TextView statsText, ArrayList<MultiPointOverlay.PointData> pts,
                                       ImageView iv, Bitmap bmp,
                                       CameraHandler.TempFrameSnapshot snap) {

        StringBuilder sb = new StringBuilder();

        for (MultiPointOverlay.PointData p : pts) {
            double t = getPointTemp(iv, bmp, snap, p.x, p.y);
            p.temp = t;
            sb.append(String.format(java.util.Locale.US,
                    "Point %d: %.1f°C\n", p.index, t));
        }

        statsText.setText(sb.toString().trim());
    }

    private double getPointTemp(ImageView iv, Bitmap bmp,
                                CameraHandler.TempFrameSnapshot snap,
                                float cx, float cy) {

        RectF imgRect = getDisplayedImageRect(iv);

        float px = Math.max(imgRect.left, Math.min(cx, imgRect.right));
        float py = Math.max(imgRect.top,  Math.min(cy, imgRect.bottom));

        float scaleX = (float) bmp.getWidth() / imgRect.width();
        float scaleY = (float) bmp.getHeight() / imgRect.height();

        float bx = (px - imgRect.left) * scaleX;
        float by = (py - imgRect.top)  * scaleY;

        float gx = (float) snap.w / bmp.getWidth();
        float gy = (float) snap.h / bmp.getHeight();

        float txf = bx * gx;
        float tyf = by * gy;

        int x0 = clamp((int) txf, 0, snap.w - 1);
        int y0 = clamp((int) tyf, 0, snap.h - 1);

        return snap.tempC[y0 * snap.w + x0];
    }




    private PointF mapViewToBitmap(ImageView iv, float vx, float vy, Bitmap bmp) {
        // Inverse of the actual image matrix gives true mapping (handles fitCenter, etc.)
        android.graphics.Matrix inv = new android.graphics.Matrix();
        android.graphics.Matrix m = new android.graphics.Matrix(iv.getImageMatrix());

        // Account for ImageView's internal centering padding (same math your getDisplayedImageRect did)
        RectF imgRectOnView = getDisplayedImageRect(iv);
        float dx = (iv.getWidth() - imgRectOnView.width()) / 2f;
        float dy = (iv.getHeight() - imgRectOnView.height()) / 2f;
        m.postTranslate(-dx, -dy);

        if (!m.invert(inv)) return new PointF(0, 0);

        float[] pts = new float[] { vx, vy };
        inv.mapPoints(pts);

        // Clamp to bitmap
        float x = Math.max(0, Math.min(pts[0], bmp.getWidth()  - 1));
        float y = Math.max(0, Math.min(pts[1], bmp.getHeight() - 1));
        return new PointF(x, y);
    }

    private static class PointF {
        final float x, y;
        PointF(float x, float y) { this.x = x; this.y = y; }
    }



    // ==================== Helper Methods ====================

    private int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private RectF getDisplayedImageRect(ImageView iv) {
        RectF out = new RectF();
        if (iv.getDrawable() == null) return out;

        android.graphics.Matrix m = iv.getImageMatrix();
        android.graphics.RectF dRect = new android.graphics.RectF(0, 0,
                iv.getDrawable().getIntrinsicWidth(),
                iv.getDrawable().getIntrinsicHeight());
        m.mapRect(out, dRect);

        out.offset(iv.getPaddingLeft(), iv.getPaddingTop());

        float vw = iv.getWidth();
        float vh = iv.getHeight();
        float dx = (vw - out.width()) / 2f;
        float dy = (vh - out.height()) / 2f;
        out.offset(dx, dy);

        return out;
    }

    private Bitmap copyBitmap(Bitmap source) {
        if (source == null) return null;
        Bitmap.Config config = source.getConfig() != null ?
                source.getConfig() : Bitmap.Config.ARGB_8888;
        return source.copy(config, false);
    }

    private void recycleBitmaps() {
        // Note: Be careful with recycling - only recycle if you own the bitmap
        // FLIR SDK manages its own bitmaps, so we just null our references
        lastMsxBitmap = null;
        lastDcBitmap = null;

        if (capturedMsxBitmap != null && !capturedMsxBitmap.isRecycled()) {
            capturedMsxBitmap.recycle();
            capturedMsxBitmap = null;
        }
    }

    private void saveBitmapToGallery(@NonNull Bitmap bitmap, @NonNull String displayName) {
        try {
            android.content.ContentResolver resolver = getContentResolver();
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
            values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FLIR");

            android.net.Uri uri = resolver.insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

            if (uri == null) throw new IOException("Failed to create media store entry");

            try (java.io.OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) throw new IOException("Failed to open output stream");
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) {
                    throw new IOException("Bitmap compression failed");
                }
            }

            Log.d(TAG, "Image saved: " + displayName);

        } catch (Exception e) {
            Log.e(TAG, "Save to gallery failed", e);
            runOnUiThread(() -> showMessage.show("Save failed: " + e.getMessage()));
        }
    }

    // ==================== Listeners & Callbacks ====================

    private final UsbPermissionHandler.UsbPermissionListener permissionListener =
            new UsbPermissionHandler.UsbPermissionListener() {
                @Override
                public void permissionGranted(@NonNull Identity identity) {
                    doConnect(identity);
                }

                @Override
                public void permissionDenied(@NonNull Identity identity) {
                    showMessage.show("USB permission denied");
                }

                @Override
                public void error(ErrorType errorType, Identity identity) {
                    if (errorType == ErrorType.DEVICE_UNAVAILABLE_WHEN_ASKED_PERMISSION) {
                        usbPermissionHandler.requestFlirOnePermisson(
                                connectedIdentity, MainActivity.this, permissionListener);
                    } else {
                        showMessage.show("USB permission error: " + errorType);
                    }
                }
            };

    private final ConnectionStatusListener connectionStatusListener = errorCode -> {
        Log.d(TAG, "Camera disconnected: " + errorCode);
        runOnUiThread(() -> {
            updateConnectionText(connectedIdentity, "DISCONNECTED");
            connectedIdentity = null;
        });
    };

    private final CameraHandler.DiscoveryStatus discoveryStatusListener =
            new CameraHandler.DiscoveryStatus() {
                @Override
                public void started() {
                    discoveryStatus.setText(getString(R.string.connection_status_text, "discovering"));
                }

                @Override
                public void stopped() {
                    discoveryStatus.setText(getString(R.string.connection_status_text, "not discovering"));
                }
            };

    private final DiscoveryEventListener cameraDiscoveryListener = new DiscoveryEventListener() {
        @Override
        public void onCameraFound(DiscoveredCamera discoveredCamera) {
            Log.d(TAG, "Camera found: " + discoveredCamera.getIdentity());
            runOnUiThread(() -> cameraHandler.add(discoveredCamera.getIdentity()));
        }

        @Override
        public void onDiscoveryError(CommunicationInterface commInterface, ErrorCode errorCode) {
            Log.e(TAG, "Discovery error: " + commInterface + " - " + errorCode);
            runOnUiThread(() -> {
                stopDiscovery();
                showMessage.show("Discovery error: " + errorCode);
            });
        }
    };

    private final ShowMessage showMessage = message ->
            Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();

    private void updateConnectionText(Identity identity, String status) {
        String deviceId = identity != null ? identity.deviceId : "";
        connectionStatus.setText(getString(R.string.connection_status_text,
                deviceId + " " + status));
    }

    private void showSDKversion(String version) {
        TextView sdkVersionTextView = findViewById(R.id.sdk_version);
        String sdkVersionText = getString(R.string.sdk_version_text, version);
        sdkVersionTextView.setText(sdkVersionText);
    }

    private void showSDKCommitHash(String commitHash) {
        TextView sdkVersionTextView = findViewById(R.id.sdk_commit_hash);
        String truncated = commitHash.length() > 10 ?
                commitHash.substring(0, 10) : commitHash;
        String sdkVersionText = getString(R.string.sdk_commit_hash_text, truncated);
        sdkVersionTextView.setText(sdkVersionText);
    }

    private void setupViews() {
        connectionStatus = findViewById(R.id.connection_status_text);
        discoveryStatus = findViewById(R.id.discovery_status);
        deviceInfo = findViewById(R.id.device_info_text);
        msxImage = findViewById(R.id.msx_image);
        photoImage = findViewById(R.id.photo_image);
    }

    public interface ShowMessage {
        void show(String message);
    }
}