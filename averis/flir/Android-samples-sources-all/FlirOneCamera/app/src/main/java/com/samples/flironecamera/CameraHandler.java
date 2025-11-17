/*******************************************************************
 * @title FLIR Atlas Android SDK - OPTIMIZED & FIXED
 * @file CameraHandler.java
 * @Author Teledyne FLIR
 *
 * @brief Fixed helper class with accurate temperature measurements
 *
 * Copyright 2023:    Teledyne FLIR
 ********************************************************************/
package com.samples.flironecamera;

import android.graphics.Bitmap;
import android.util.Log;
import android.os.Handler;
import android.os.HandlerThread;

import com.flir.thermalsdk.androidsdk.image.BitmapAndroid;
import com.flir.thermalsdk.image.ThermalImage;
import com.flir.thermalsdk.live.Camera;
import com.flir.thermalsdk.live.CameraInformation;
import com.flir.thermalsdk.live.CommunicationInterface;
import com.flir.thermalsdk.live.ConnectParameters;
import com.flir.thermalsdk.live.Identity;
import com.flir.thermalsdk.live.connectivity.ConnectionStatusListener;
import com.flir.thermalsdk.live.discovery.DiscoveryEventListener;
import com.flir.thermalsdk.live.discovery.DiscoveryFactory;
import com.flir.thermalsdk.live.remote.Calibration;
import com.flir.thermalsdk.live.remote.RemoteControl;
import com.flir.thermalsdk.live.streaming.Stream;
import com.flir.thermalsdk.live.streaming.ThermalStreamer;
import com.flir.thermalsdk.image.Point;
import com.flir.thermalsdk.image.ThermalValue;
import com.flir.thermalsdk.image.TemperatureUnit;
import com.flir.thermalsdk.image.PaletteManager;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.LinkedList;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * FIXED CameraHandler with accurate temperature measurements using SDK APIs
 */
class CameraHandler {

    private static final String TAG = "CameraHandler";

    // Performance tuning constants
    private static final long MIN_FRAME_INTERVAL_MS = 66; // Max 10 FPS to UI

    private StreamDataListener streamDataListener;

    // Temperature tracking with atomic operations for thread safety
    private volatile double lastMinTempC = Double.NaN;
    private volatile double lastMaxTempC = Double.NaN;

    // CRITICAL: Store snapshot data instead of ThermalImage reference
    private volatile TempFrameSnapshot lastSnapshot = null;

    // Frame management
    private final AtomicLong lastFrameTime = new AtomicLong(0);
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private int frameCounter = 0;

    // Background processing thread
    private HandlerThread processingThread;
    private Handler processingHandler;

    // Camera components
    private LinkedList<Identity> foundCameraIdentities = new LinkedList<>();
    private Camera camera;
    private Stream connectedStream;
    private ThermalStreamer streamer;
    private Bitmap reusableThermalBitmap = null;

    public double getLastMinTempC() { return lastMinTempC; }
    public double getLastMaxTempC() { return lastMaxTempC; }

    // Immutable snapshot class
    public static class TempFrameSnapshot {
        public final float[] tempC;
        public final int w, h;
        public final double minC, maxC;

        public TempFrameSnapshot(float[] tempC, int w, int h, double minC, double maxC) {
            this.tempC = tempC;
            this.w = w;
            this.h = h;
            this.minC = minC;
            this.maxC = maxC;
        }
    }

    public synchronized TempFrameSnapshot getLastTempFrameSnapshot() {
        return lastSnapshot;
    }

    /**
     * CRITICAL: Get temperature at specific coordinates in BITMAP space
     * Automatically scales from bitmap coordinates to thermal sensor coordinates
     */
    public Double getTemperatureAtPoint(int bitmapX, int bitmapY, int bitmapWidth, int bitmapHeight) {
        if (streamer == null || lastW <= 0 || lastH <= 0) return null;

        final Double[] result = new Double[1];

        streamer.withThermalImage(thermalImage -> {
            try {
                int thermalW = thermalImage.getWidth();
                int thermalH = thermalImage.getHeight();

                if (thermalW <= 0 || thermalH <= 0) return;

                // Scale bitmap to thermal coords
                float scaleX = (float) thermalW / bitmapWidth;
                float scaleY = (float) thermalH / bitmapHeight;

                int thermalX = Math.round(bitmapX * scaleX);
                int thermalY = Math.round(bitmapY * scaleY);

                thermalX = Math.max(0, Math.min(thermalX, thermalW - 1));
                thermalY = Math.max(0, Math.min(thermalY, thermalH - 1));

                ThermalValue tv = thermalImage.getValueAt(new Point(thermalX, thermalY));
                if (tv != null && Double.isFinite(tv.value)) {
                    result[0] = tv.value;
                }
            } catch (Exception e) {
                // Ignore
            }
        });

        return result[0];
    }

    /**
     * Get min/max temperature within a rectangle in BITMAP space
     */
    public double[] getTemperatureInRect(int bitmapLeft, int bitmapTop, int bitmapRight, int bitmapBottom,
                                         int bitmapWidth, int bitmapHeight) {
        TempFrameSnapshot snap = lastSnapshot;
        if (snap == null) return null;

        try {
            // Get thermal dimensions
            int thermalW = snap.w;
            int thermalH = snap.h;

            if (thermalW <= 0 || thermalH <= 0 || bitmapWidth <= 0 || bitmapHeight <= 0) {
                return null;
            }

            // Scale bitmap coordinates to thermal coordinates
            float scaleX = (float) thermalW / bitmapWidth;
            float scaleY = (float) thermalH / bitmapHeight;

            int thermalLeft = Math.round(bitmapLeft * scaleX);
            int thermalTop = Math.round(bitmapTop * scaleY);
            int thermalRight = Math.round(bitmapRight * scaleX);
            int thermalBottom = Math.round(bitmapBottom * scaleY);

            // Clamp to valid thermal range
            thermalLeft = Math.max(0, Math.min(thermalLeft, thermalW - 1));
            thermalTop = Math.max(0, Math.min(thermalTop, thermalH - 1));
            thermalRight = Math.max(thermalLeft + 1, Math.min(thermalRight, thermalW));
            thermalBottom = Math.max(thermalTop + 1, Math.min(thermalBottom, thermalH));

            double minTemp = Double.POSITIVE_INFINITY;
            double maxTemp = Double.NEGATIVE_INFINITY;

            // Scan thermal array
            for (int y = thermalTop; y < thermalBottom; y++) {
                int rowOffset = y * thermalW;
                for (int x = thermalLeft; x < thermalRight; x++) {
                    float temp = snap.tempC[rowOffset + x];
                    if (Float.isFinite(temp)) {
                        if (temp < minTemp) minTemp = temp;
                        if (temp > maxTemp) maxTemp = temp;
                    }
                }
            }

            if (Double.isFinite(minTemp) && Double.isFinite(maxTemp)) {
                return new double[]{minTemp, maxTemp};
            }

        } catch (Exception e) {
            Log.e(TAG, "Error getting temperature in rectangle", e);
        }

        return null;
    }

    public interface StreamDataListener {
        void images(Bitmap msxBitmap, Bitmap dcBitmap);
    }

    public interface DiscoveryStatus {
        void started();
        void stopped();
    }

    public CameraHandler() {
        Log.d(TAG, "CameraHandler initialized");
        initProcessingThread();
    }

    private void initProcessingThread() {
        processingThread = new HandlerThread("ThermalProcessing");
        processingThread.start();
        processingHandler = new Handler(processingThread.getLooper());
    }

    public void startDiscovery(DiscoveryEventListener cameraDiscoveryListener, DiscoveryStatus discoveryStatus) {
        DiscoveryFactory.getInstance().scan(cameraDiscoveryListener,
                CommunicationInterface.EMULATOR, CommunicationInterface.USB);
        discoveryStatus.started();
    }

    public void stopDiscovery(DiscoveryStatus discoveryStatus) {
        DiscoveryFactory.getInstance().stop(CommunicationInterface.EMULATOR, CommunicationInterface.USB);
        discoveryStatus.stopped();
    }

    public synchronized void connect(Identity identity, ConnectionStatusListener connectionStatusListener) throws IOException {
        Log.d(TAG, "Connecting to: " + identity);
        camera = new Camera();
        camera.connect(identity, connectionStatusListener, new ConnectParameters());
    }

    public synchronized void disconnect() {
        Log.d(TAG, "Disconnecting");

        lastSnapshot = null;

        if (connectedStream != null && connectedStream.isStreaming()) {
            connectedStream.stop();
        }

        if (camera != null) {
            camera.disconnect();
            camera = null;
        }

        if (processingThread != null) {
            processingThread.quitSafely();
            processingThread = null;
            processingHandler = null;
        }
    }

    public synchronized void performNuc() {
        if (camera == null) return;

        RemoteControl rc = camera.getRemoteControl();
        if (rc == null) return;

        Calibration calib = rc.getCalibration();
        if (calib != null) {
            calib.nuc().executeSync();
        }
    }

    /**
     * OPTIMIZED stream start with frame skipping and background processing
     */
    public synchronized void startStream(StreamDataListener listener) {
        this.streamDataListener = listener;

        if (camera == null || !camera.isConnected()) {
            Log.e(TAG, "Camera not connected");
            return;
        }

        connectedStream = camera.getStreams().get(0);

        if (!connectedStream.isThermal()) {
            Log.e(TAG, "No thermal stream available");
            return;
        }

        streamer = new ThermalStreamer(connectedStream);

        connectedStream.start(
                unused -> {
                    long currentTime = System.currentTimeMillis();

                    // Skip frames based on time interval only
                    if (currentTime - lastFrameTime.get() < MIN_FRAME_INTERVAL_MS) {
                        return;
                    }

                    lastFrameTime.set(currentTime);
                    frameCounter++;

                    // UPDATE: This is needed to refresh the streamer's image buffer
                    streamer.update();

                    // Process directly in callback thread (faster, no queue buildup)
                    try {
                        // Apply palette once
                        if (frameCounter == 1) {
                            streamer.withThermalImage(img -> applyColorPalette(img));
                        }

                        // Get bitmap directly
                        Bitmap bmp = BitmapAndroid.createBitmap(streamer.getImage()).getBitMap();

                        // Send to UI immediately
                        if (streamDataListener != null && bmp != null) {
                            streamDataListener.images(bmp, null);
                        }

                    } catch (Exception e) {
                        Log.e(TAG, "Frame error", e);
                    }
                },
                error -> Log.e(TAG, "Stream error: " + error)
        );
    }

    /**
     * OPTIMIZED frame processing - runs on background thread
     */
    /**
     * OPTIMIZED frame processing - runs on background thread
     */

    // Store minimal data for on-demand snapshot
    private volatile int lastW = 0, lastH = 0;

    private void storeImageReference(ThermalImage thermalImage) {
        try {
            int w = thermalImage.getWidth();
            int h = thermalImage.getHeight();

            if (w <= 0 || h <= 0 || w == -1 || h == -1) return;

            lastW = w;
            lastH = h;

            // Only sample 4 corner points for min/max (super fast)
            double minC = Double.POSITIVE_INFINITY;
            double maxC = Double.NEGATIVE_INFINITY;

            Point[] samples = {
                    new Point(0, 0),
                    new Point(w-1, 0),
                    new Point(0, h-1),
                    new Point(w-1, h-1),
                    new Point(w/2, h/2)
            };

            for (Point p : samples) {
                ThermalValue tv = thermalImage.getValueAt(p);
                if (tv != null && Double.isFinite(tv.value)) {
                    if (tv.value < minC) minC = tv.value;
                    if (tv.value > maxC) maxC = tv.value;
                }
            }

            lastMinTempC = minC;
            lastMaxTempC = maxC;

        } catch (Exception e) {
            // Ignore
        }
    }
    private void processFrame() {
        try {
            final Bitmap[] bitmaps = new Bitmap[2]; // [0]=thermal, [1]=visible
            final Bitmap[] oldBitmaps = new Bitmap[2]; // Track old bitmaps for recycling

            streamer.withThermalImage(thermalImage -> {
                try {
                    // Apply palette ONCE at startup only
                    if (frameCounter == 1) {
                        applyColorPalette(thermalImage);
                    }

                    // Extract thermal bitmap only (skip visible photo for speed)
                    bitmaps[0] = BitmapAndroid.createBitmap(streamer.getImage()).getBitMap();

                    // Build snapshot only every 10 frames (not 3)
                    if (frameCounter % 10 == 0) {
                        buildAndStoreSnapshot(thermalImage);
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Error processing thermal image", e);
                }
            });

            // Deliver to UI (listener should be UI-thread safe)
            if (streamDataListener != null && bitmaps[0] != null) {
                streamDataListener.images(bitmaps[0], bitmaps[1]);
            }

        } catch (Exception e) {
            Log.e(TAG, "Frame processing error", e);
        } finally {
            isProcessing.set(false);
        }
    }

    /**
     * Build and store thermal snapshot - MUST be called inside withThermalImage callback
     */
    private void buildAndStoreSnapshot(ThermalImage thermalImage) {
        try {
            int w = thermalImage.getWidth();
            int h = thermalImage.getHeight();

            // Skip if dimensions are invalid (camera still initializing)
            if (w <= 0 || h <= 0 || w == -1 || h == -1) {
                Log.d(TAG, "Skipping snapshot - invalid dimensions: " + w + "x" + h);
                return;
            }

            float[] tempC = new float[w * h];
            double minC = Double.POSITIVE_INFINITY;
            double maxC = Double.NEGATIVE_INFINITY;

            // Extract all temperature data
            for (int y = 0; y < h; y++) {
                int offset = y * w;
                for (int x = 0; x < w; x++) {
                    ThermalValue tv = thermalImage.getValueAt(new Point(x, y));
                    float val = (tv != null) ? (float) tv.value : Float.NaN;
                    tempC[offset + x] = val;

                    if (Float.isFinite(val)) {
                        if (val < minC) minC = val;
                        if (val > maxC) maxC = val;
                    }
                }
            }

            // Store snapshot (atomic update)
            lastSnapshot = new TempFrameSnapshot(tempC, w, h, minC, maxC);
            lastMinTempC = minC;
            lastMaxTempC = maxC;

        } catch (Exception e) {
            Log.w(TAG, "Snapshot build failed", e);
        }
    }

    /**
     * Apply color palette to thermal image for visualization
     * Atlas SDK 2.12.0 - Directly applies Iron palette (index 0)
     */
    private void applyColorPalette(ThermalImage thermalImage) {
        try {
            // CRITICAL: Set temperature unit to Celsius
            thermalImage.setTemperatureUnit(TemperatureUnit.CELSIUS);

            // Get default palettes and apply Iron (first palette)
            java.util.List<com.flir.thermalsdk.image.Palette> palettes =
                    PaletteManager.getDefaultPalettes();

            if (palettes != null && !palettes.isEmpty()) {
                thermalImage.setPalette(palettes.get(0));
                Log.d(TAG, "Applied palette: " + palettes.get(0).name);
            }

        } catch (Exception e) {
            Log.w(TAG, "Failed to apply Iron palette", e);
        }
    }

    /**
     * Build full temperature snapshot (for capture/save operations)
     */
    public TempFrameSnapshot buildTempSnapshotSync() throws Exception {
        // Build fresh snapshot synchronously when capture is pressed
        final TempFrameSnapshot[] result = new TempFrameSnapshot[1];
        final Exception[] error = new Exception[1];

        // Use streamer to get current frame with FULL thermal data
        if (streamer != null) {
            streamer.withThermalImage(thermalImage -> {
                try {
                    int w = thermalImage.getWidth();
                    int h = thermalImage.getHeight();

                    if (w <= 0 || h <= 0 || w == -1 || h == -1) {
                        error[0] = new IllegalStateException("Invalid dimensions: " + w + "x" + h);
                        return;
                    }

                    float[] tempC = new float[w * h];
                    double minC = Double.POSITIVE_INFINITY;
                    double maxC = Double.NEGATIVE_INFINITY;

                    // Extract ALL temperature data pixel by pixel
                    for (int y = 0; y < h; y++) {
                        int offset = y * w;
                        for (int x = 0; x < w; x++) {
                            ThermalValue tv = thermalImage.getValueAt(new Point(x, y));
                            float val = (tv != null) ? (float) tv.value : Float.NaN;
                            tempC[offset + x] = val;

                            if (Float.isFinite(val)) {
                                if (val < minC) minC = val;
                                if (val > maxC) maxC = val;
                            }
                        }
                    }

                    result[0] = new TempFrameSnapshot(tempC, w, h, minC, maxC);
                    Log.d(TAG, "Built snapshot: " + w + "x" + h + ", Min: " + minC + "°C, Max: " + maxC + "°C");

                } catch (Exception e) {
                    error[0] = e;
                }
            });
        }

        if (error[0] != null) throw error[0];
        if (result[0] == null) {
            throw new IllegalStateException("Failed to build snapshot - camera not streaming");
        }

        return result[0];
    }

    private TempFrameSnapshot buildSnapshotFromImage(ThermalImage thermalImage) {
        int w = thermalImage.getWidth();
        int h = thermalImage.getHeight();

        if (w <= 0 || h <= 0 || w == -1 || h == -1) {
            return null;
        }

        float[] tempC = new float[w * h];
        double minC = Double.POSITIVE_INFINITY;
        double maxC = Double.NEGATIVE_INFINITY;

        for (int y = 0; y < h; y++) {
            int offset = y * w;
            for (int x = 0; x < w; x++) {
                ThermalValue tv = thermalImage.getValueAt(new Point(x, y));
                float val = (tv != null) ? (float) tv.value : Float.NaN;
                tempC[offset + x] = val;

                if (Float.isFinite(val)) {
                    if (val < minC) minC = val;
                    if (val > maxC) maxC = val;
                }
            }
        }

        return new TempFrameSnapshot(tempC, w, h, minC, maxC);
    }

    public void add(Identity identity) {
        foundCameraIdentities.add(identity);
    }

    @Nullable
    public Identity getCppEmulator() {
        for (Identity id : foundCameraIdentities) {
            if (id.deviceId.contains("C++ Emulator")) return id;
        }
        return null;
    }

    @Nullable
    public Identity getFlirOneEmulator() {
        for (Identity id : foundCameraIdentities) {
            if (id.deviceId.contains("EMULATED FLIR ONE")) return id;
        }
        return null;
    }

    @Nullable
    public Identity getFlirOne() {
        return foundCameraIdentities.stream()
                .filter(identity -> identity.communicationInterface == CommunicationInterface.USB)
                .findFirst()
                .orElse(null);
    }

    public String getDeviceInfo() {
        if (camera == null) return "N/A";

        RemoteControl rc = camera.getRemoteControl();
        if (rc == null) return "N/A";

        CameraInformation ci = rc.cameraInformation().getSync();
        if (ci == null) return "N/A";

        return ci.displayName + ", SN: " + ci.serialNumber;
    }
}