/*******************************************************************
 * @title FLIR Atlas Android SDK - OPTIMIZED
 * @file CameraHandler.java
 * @Author Teledyne FLIR
 *
 * @brief Optimized helper class for real-time FLIR ONE camera streaming
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

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.LinkedList;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OPTIMIZED CameraHandler with:
 * - Frame skipping to prevent UI blocking
 * - Background thread processing
 * - Lightweight sampling for temperature readings
 * - Reduced memory allocations
 */
class CameraHandler {

    private static final String TAG = "CameraHandler";

    // Performance tuning constants
    private static final long MIN_FRAME_INTERVAL_MS = 100; // Max 10 FPS to UI
    private static final int TEMP_SAMPLE_STRIDE = 8; // Sample every 8th pixel
    private static final int TEMP_UPDATE_EVERY_N_FRAMES = 3; // Update temp every 3rd frame

    private StreamDataListener streamDataListener;

    // Temperature tracking with atomic operations for thread safety
    private volatile double lastMinTempC = Double.NaN;
    private volatile double lastMaxTempC = Double.NaN;
    private volatile float[] lastTempCBuffer = null;
    private volatile int lastW = 0, lastH = 0;

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
    private volatile boolean rawIsKelvin = true;

    public double getLastMinTempC() { return lastMinTempC; }
    public double getLastMaxTempC() { return lastMaxTempC; }

    private double toCelsius(double v) {
        return rawIsKelvin ? (v - 273.15) : v;
    }


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
        if (lastTempCBuffer == null || lastW <= 0 || lastH <= 0) return null;
        return new TempFrameSnapshot(lastTempCBuffer.clone(), lastW, lastH, lastMinTempC, lastMaxTempC);
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
                    // FRAME SKIPPING: Only process if enough time has passed
                    long currentTime = System.currentTimeMillis();
                    long lastTime = lastFrameTime.get();

                    if (currentTime - lastTime < MIN_FRAME_INTERVAL_MS) {
                        return; // Skip this frame
                    }

                    // Check if previous frame is still processing
                    if (isProcessing.get()) {
                        return; // Skip if still busy
                    }

                    isProcessing.set(true);
                    lastFrameTime.set(currentTime);
                    frameCounter++;

                    streamer.update();

                    // Process frame on background thread
                    processingHandler.post(() -> processFrame());
                },
                error -> {
                    Log.e(TAG, "Stream error: " + error);
                    isProcessing.set(false);
                }
        );
    }

    /**
     * OPTIMIZED frame processing - runs on background thread
     */
    private void processFrame() {
        try {
            final Bitmap[] bitmaps = new Bitmap[2]; // [0]=thermal, [1]=visible

            streamer.withThermalImage(thermalImage -> {
                try {
                    // Extract thermal bitmap (fast operation)
                    bitmaps[0] = BitmapAndroid.createBitmap(streamer.getImage()).getBitMap();

                    // Extract visible photo for MSX
                    try {
                        bitmaps[1] = BitmapAndroid.createBitmap(
                                Objects.requireNonNull(
                                        Objects.requireNonNull(thermalImage.getFusion()).getPhoto()
                                )
                        ).getBitMap();
                    } catch (Exception e) {
                        Log.w(TAG, "No visible photo available");
                        bitmaps[1] = null;
                    }

                    // LIGHTWEIGHT temperature update (every Nth frame only)
                    if (frameCounter % TEMP_UPDATE_EVERY_N_FRAMES == 0) {
                        updateTemperatureData(thermalImage);
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
     * LIGHTWEIGHT temperature sampling - uses stride to reduce CPU load
     */
    private void updateTemperatureData(ThermalImage thermalImage) {
        try {
            int w = thermalImage.getWidth();
            int h = thermalImage.getHeight();
            if (w <= 0 || h <= 0) return;

            // Detect unit on this frame from a quick sample
            int samples = 0, kelvinLike = 0;
            for (int y = 0; y < h && samples < 100; y += Math.max(1, h / 20)) {
                for (int x = 0; x < w && samples < 100; x += Math.max(1, w / 20)) {
                    ThermalValue tv = thermalImage.getValueAt(new Point(x, y));
                    if (tv == null) continue;
                    double v = tv.value;
                    if (Double.isNaN(v)) continue;
                    // Heuristic: typical ambient temps ~250–340 K vs -50..150 °C
                    if (v > 150 && v < 400) kelvinLike++;
                    samples++;
                }
            }
            rawIsKelvin = (kelvinLike > samples / 2);

            double minC = Double.POSITIVE_INFINITY;
            double maxC = Double.NEGATIVE_INFINITY;

            for (int y = 0; y < h; y += TEMP_SAMPLE_STRIDE) {
                for (int x = 0; x < w; x += TEMP_SAMPLE_STRIDE) {
                    ThermalValue tv = thermalImage.getValueAt(new Point(x, y));
                    if (tv == null) continue;
                    double v = tv.value;
                    if (Double.isNaN(v)) continue;
                    double c = toCelsius(v);
                    if (c < minC) minC = c;
                    if (c > maxC) maxC = c;
                }
            }

            lastMinTempC = minC;
            lastMaxTempC = maxC;

        } catch (Exception e) {
            Log.w(TAG, "Temperature update failed", e);
        }
    }


    /**
     * Build full temperature snapshot (for capture/save operations)
     * This is the HEAVY operation - only call when actually needed!
     */
    public TempFrameSnapshot buildTempSnapshotSync() throws Exception {
        if (streamer == null) throw new IllegalStateException("Streamer not initialized");

        final TempFrameSnapshot[] result = new TempFrameSnapshot[1];
        final Exception[] error = new Exception[1];

        streamer.withThermalImage(thermalImage -> {
            try {
                int w = thermalImage.getWidth();
                int h = thermalImage.getHeight();
                float[] buf = new float[w * h];

                // Detect unit for THIS snapshot
                int samples = 0, kelvinLike = 0;
                for (int y = 0; y < h && samples < 100; y += Math.max(1, h / 20)) {
                    for (int x = 0; x < w && samples < 100; x += Math.max(1, w / 20)) {
                        ThermalValue tv = thermalImage.getValueAt(new Point(x, y));
                        if (tv == null) continue;
                        double v = tv.value;
                        if (Double.isNaN(v)) continue;
                        if (v > 150 && v < 400) kelvinLike++;
                        samples++;
                    }
                }
                boolean snapshotKelvin = (kelvinLike > samples / 2);

                double minC = Double.POSITIVE_INFINITY;
                double maxC = Double.NEGATIVE_INFINITY;

                for (int y = 0; y < h; y++) {
                    int off = y * w;
                    for (int x = 0; x < w; x++) {
                        ThermalValue tv = thermalImage.getValueAt(new Point(x, y));
                        double v = (tv != null) ? tv.value : Double.NaN;
                        if (Double.isNaN(v)) continue;
                        double c = snapshotKelvin ? (v - 273.15) : v; // consistent for this snapshot
                        buf[off + x] = (float) c;
                        if (c < minC) minC = c;
                        if (c > maxC) maxC = c;
                    }
                }

                result[0] = new TempFrameSnapshot(buf, w, h, minC, maxC);

            } catch (Exception e) {
                error[0] = e;
            }
        });

        if (error[0] != null) throw error[0];
        return result[0];
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