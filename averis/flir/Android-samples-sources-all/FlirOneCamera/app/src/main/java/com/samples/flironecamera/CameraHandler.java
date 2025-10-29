/*******************************************************************
 * @title FLIR Atlas Android SDK
 * @file CameraHandler.java
 * @Author Teledyne FLIR
 *
 * @brief Helper class that encapsulates *most* interactions with a FLIR ONE camera
 *
 * Copyright 2023:    Teledyne FLIR
 ********************************************************************/
package com.samples.flironecamera;

import android.graphics.Bitmap;
import android.util.Log;

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

/**
 * Encapsulates the handling of a FLIR ONE camera or built in emulator, discovery, connecting and start receiving images.
 * All listeners are called from Atlas Android SDK on a non-ui thread
 * <p/>
 * Usage:
 * <pre>
 * Start discovery of FLIR FLIR ONE cameras or built in FLIR ONE cameras emulators
 * {@linkplain #startDiscovery(DiscoveryEventListener, DiscoveryStatus)}
 * Use a discovered Camera {@linkplain Identity} and connect to the Camera
 * (note that calling connect is blocking and it is mandatory to call this function from a background thread):
 * {@linkplain #connect(Identity, ConnectionStatusListener)}
 * Once connected to a camera
 * {@linkplain #startStream(StreamDataListener)}
 * </pre>
 * <p/>
 * You don't *have* to specify your application to listen or USB intents but it might be beneficial for you application,
 * we are enumerating the USB devices during the discovery process which eliminates the need to listen for USB intents.
 * See the Android documentation about USB Host mode for more information
 * <p/>
 * Please note, this is <b>NOT</b> production quality code, error handling has been kept to a minimum to keep the code as clear and concise as possible
 */
class CameraHandler {

    private static final String TAG = "CameraHandler";

    private StreamDataListener streamDataListener;
    // --- Last frame min/max temperatures (Celsius) ---
    private volatile double lastMinTempC = Double.NaN;
    private volatile double lastMaxTempC = Double.NaN;

    private volatile float[] lastTempCBuffer = null;
    private volatile int lastW = 0, lastH = 0;

    public double getLastMinTempC() { return lastMinTempC; }
    public double getLastMaxTempC() { return lastMaxTempC; }

    // Immutable snapshot you can use after capture
    static class TempFrameSnapshot {
        final float[] tempC; // length = w*h, row-major
        final int w, h;
        TempFrameSnapshot(float[] tempC, int w, int h) { this.tempC = tempC; this.w = w; this.h = h; }
    }

    // Thread-safe snapshot getter (returns null if no frame yet)
    public synchronized TempFrameSnapshot getLastTempFrameSnapshot() {
        if (lastTempCBuffer == null || lastW <= 0 || lastH <= 0) return null;
        return new TempFrameSnapshot(lastTempCBuffer.clone(), lastW, lastH);
    }


    public interface StreamDataListener {
        void images(FrameDataHolder dataHolder);

        void images(Bitmap msxBitmap, Bitmap dcBitmap);
    }

    //Discovered FLIR cameras
    LinkedList<Identity> foundCameraIdentities = new LinkedList<>();

    //A FLIR Camera
    private Camera camera;

    private Stream connectedStream;
    private ThermalStreamer streamer;


    public interface DiscoveryStatus {
        void started();

        void stopped();
    }

    public CameraHandler() {
        Log.d(TAG, "CameraHandler constr");
    }

    /**
     * Start discovery of USB and Emulators
     */
    public void startDiscovery(DiscoveryEventListener cameraDiscoveryListener, DiscoveryStatus discoveryStatus) {
        DiscoveryFactory.getInstance().scan(cameraDiscoveryListener, CommunicationInterface.EMULATOR, CommunicationInterface.USB);
        discoveryStatus.started();
    }

    /**
     * Stop discovery of USB and Emulators
     */
    public void stopDiscovery(DiscoveryStatus discoveryStatus) {
        DiscoveryFactory.getInstance().stop(CommunicationInterface.EMULATOR, CommunicationInterface.USB);
        discoveryStatus.stopped();
    }

    public synchronized void connect(Identity identity, ConnectionStatusListener connectionStatusListener) throws IOException {
        Log.d(TAG, "connect identity: " + identity);
        camera = new Camera();
        camera.connect(identity, connectionStatusListener, new ConnectParameters());
    }

    public synchronized void disconnect() {
        Log.d(TAG, "disconnect");
        if (camera == null) {
            return;
        }
        if (connectedStream == null) {
            return;
        }

        if (connectedStream.isStreaming()) {
            connectedStream.stop();
        }
        camera.disconnect();
        camera = null;
    }

    public synchronized void performNuc() {
        Log.d(TAG, "performNuc");
        if (camera == null) {
            return;
        }
        RemoteControl rc = camera.getRemoteControl();
        if (rc == null) {
            return;
        }
        Calibration calib = rc.getCalibration();
        if (calib == null) {
            return;
        }
        calib.nuc().executeSync();
    }



    /**
     * Start a stream of {@link ThermalImage}s images from a FLIR ONE or emulator
     */
    public synchronized void startStream(StreamDataListener listener) {
        this.streamDataListener = listener;
        if (camera == null || !camera.isConnected()) {
            Log.e(TAG, "startStream, failed, camera was null or not connected");
            return;
        }
        connectedStream = camera.getStreams().get(0);
        if (connectedStream.isThermal()) {
            streamer = new ThermalStreamer(connectedStream);
        } else {
            Log.e(TAG, "startStream, failed, no thermal stream available for the camera");
            return;
        }

        connectedStream.start(
                unused -> {
                    streamer.update();

                    final Bitmap[] dcBitmap = new Bitmap[1];

                    // Touch the radiometric image to (a) grab a visible photo for MSX and
                    // (b) compute min/max temperature from the radiometric pixels.
                    streamer.withThermalImage((ThermalImage thermalImage) -> {
                        // --- (A) Optional: set parameters if you need better accuracy ---
                        // If your app has these values, set them here:
                        // thermalImage.getParameters().setEmissivity(0.95f);
                        // thermalImage.getParameters().setReflectedTemperature(20.0);
                        // thermalImage.getParameters().setAtmosphericTemperature(20.0);
                        // thermalImage.getParameters().setObjectDistance(1.0f);

                        // --- (B) Compute min/max temperature by sampling pixels ---
                        try {
                            int w = thermalImage.getWidth();
                            int h = thermalImage.getHeight();

                            // Sample stride keeps CPU low while staying accurate
                            int stepX = Math.max(1, w / 160);
                            int stepY = Math.max(1, h / 120);

                            double minRaw = Double.POSITIVE_INFINITY;
                            double maxRaw = Double.NEGATIVE_INFINITY;

                            // Read radiometric values using Point
                            for (int y = 0; y < h; y += stepY) {
                                for (int x = 0; x < w; x += stepX) {
                                    ThermalValue tv = thermalImage.getValueAt(new Point(x, y));
                                    double v = (tv != null) ? tv.value : Double.NaN; // raw numeric value
                                    if (Double.isNaN(v)) continue;
                                    if (v < minRaw) minRaw = v;
                                    if (v > maxRaw) maxRaw = v;
                                }
                            }

                            // Convert to Celsius using a safe heuristic:
                            // Many builds return Kelvin by default (values > ~200). If so, K -> °C.
                            double minC = (minRaw > 200.0) ? (minRaw - 273.15) : minRaw;
                            double maxC = (maxRaw > 200.0) ? (maxRaw - 273.15) : maxRaw;

                            lastMinTempC = minC;
                            lastMaxTempC = maxC;
                        } catch (Throwable t) {
                            Log.w(TAG, "Unable to compute min/max temperature", t);
                            lastMinTempC = Double.NaN;
                            lastMaxTempC = Double.NaN;
                        }


                        // --- (D) Visible photo for MSX/preview pairing (same as your code) ---
                        try {
                            dcBitmap[0] = BitmapAndroid.createBitmap(
                                    Objects.requireNonNull(
                                            Objects.requireNonNull(thermalImage.getFusion()).getPhoto()
                                    )
                            ).getBitMap();
                        } catch (Throwable t) {
                            Log.w(TAG, "Unable to extract DC photo", t);
                            dcBitmap[0] = null;
                        }
                    });

                    // Colorized thermal/MSX bitmap (same as your code)
                    final Bitmap thermalPixels = BitmapAndroid.createBitmap(streamer.getImage()).getBitMap();

                    streamDataListener.images(thermalPixels, dcBitmap[0]);
                },
                error -> Log.e(TAG, "Streaming error: " + error));
    }


    /**
     * Add a found camera to the list of known cameras
     */
    public void add(Identity identity) {
        foundCameraIdentities.add(identity);
    }

    @Nullable
    public Identity getCppEmulator() {
        for (Identity foundCameraIdentity : foundCameraIdentities) {
            if (foundCameraIdentity.deviceId.contains("C++ Emulator")) {
                return foundCameraIdentity;
            }
        }
        return null;
    }

    @Nullable
    public Identity getFlirOneEmulator() {
        for (Identity foundCameraIdentity : foundCameraIdentities) {
            if (foundCameraIdentity.deviceId.contains("EMULATED FLIR ONE")) {
                return foundCameraIdentity;
            }
        }
        return null;
    }

    @Nullable
    public Identity getFlirOne() {
        return foundCameraIdentities.stream().filter(identity -> identity.communicationInterface == CommunicationInterface.USB).findFirst().orElse(null);
    }

    public String getDeviceInfo() {
        if (camera == null) {
            return "N/A";
        }
        RemoteControl rc = camera.getRemoteControl();
        if (rc == null) {
            return "N/A";
        }
        CameraInformation ci = rc.cameraInformation().getSync();
        if (ci == null) {
            return "N/A";
        }
        return ci.displayName + ", SN: " + ci.serialNumber;
    }

}
