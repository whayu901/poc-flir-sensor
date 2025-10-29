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

import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
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

    private void showCapturePopupWithRoi(@NonNull Bitmap bitmap, double minC, double maxC,
                                         @NonNull CameraHandler.TempFrameSnapshot snap) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_captured_preview, null);
        ImageView imageView = dialogView.findViewById(R.id.capturedImageView);
        TextView statsText = dialogView.findViewById(R.id.tempStatsText);
        Button closeBtn = dialogView.findViewById(R.id.closeDialogBtn);
        FrameLayout container = dialogView.findViewById(R.id.previewContainer);

        imageView.setImageBitmap(bitmap);
        statsText.setText(String.format(java.util.Locale.US,
                "High: %.1f°C   Low: %.1f°C", maxC, minC));

        // Add selection overlay
        SelectionOverlay overlay = new SelectionOverlay(this);
        container.addView(overlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // Initialize ROI to center 40% of displayed image
        imageView.post(() -> {
            RectF imgRect = getDisplayedImageRect(imageView);
            float w = imgRect.width() * 0.4f;
            float h = imgRect.height() * 0.4f;
            float left = imgRect.centerX() - w / 2f;
            float top = imgRect.centerY() - h / 2f;
            overlay.setBounds(new RectF(left, top, left + w, top + h));

            // Compute initial ROI stats
            updateRoiStats(statsText, overlay.getBounds(), imageView, bitmap, snap);
        });

        // Listen to ROI changes
        overlay.setOnBoundsChangedListener(bounds ->
                updateRoiStats(statsText, bounds, imageView, bitmap, snap));

        // Create and show dialog
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
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