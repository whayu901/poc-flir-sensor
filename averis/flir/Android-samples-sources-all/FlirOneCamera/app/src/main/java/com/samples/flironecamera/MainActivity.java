/*
 * ******************************************************************
 * @title FLIR Atlas Android SDK
 * @file MainActivity.java
 * @Author Teledyne FLIR
 *
 * @brief  Main UI of test application
 *
 * Copyright 2023:    Teledyne FLIR
 * ******************************************************************/
package com.samples.flironecamera;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
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
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Sample application for scanning a FLIR ONE or a built in emulator
 * <p>
 * See the {@link CameraHandler} for how to preform discovery of a FLIR ONE camera, connecting to it and start streaming images
 * <p>
 * The MainActivity is primarily focused to "glue" different helper classes together and updating the UI components
 * <p/>
 * Please note, this is <b>NOT</b> production quality code, error handling has been kept to a minimum to keep the code as clear and concise as possible
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    //Handles network camera operations
    private CameraHandler cameraHandler;

    private Identity connectedIdentity = null;
    private TextView connectionStatus;
    private TextView discoveryStatus;
    private TextView deviceInfo;

    private ImageView msxImage;
    private ImageView photoImage;
    private Bitmap lastCapturedMsx;
    private volatile FrameDataHolder latestFrame = null;

    private final LinkedBlockingQueue<FrameDataHolder> framesBuffer = new LinkedBlockingQueue<>(21);
    private final UsbPermissionHandler usbPermissionHandler = new UsbPermissionHandler();
    private CameraHandler.TempFrameSnapshot lastTempSnapshot; // frozen at capture time


    /**
     * Show message on the screen
     */
    public interface ShowMessage {
        void show(String message);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //ThermalSdkAndroid has to be initiated from a Activity with the Application Context to prevent leaking Context,
        // and before ANY using any ThermalSdkAndroid functions
        //ThermalLog will show log from the Atlas Android SDK in standards android log framework
        ThermalSdkAndroid.init(getApplicationContext(), ThermalLog.LogLevel.DEBUG);


        cameraHandler = new CameraHandler();

        setupViews();

        showSDKversion(ThermalSdkAndroid.getVersion());
        showSDKCommitHash(ThermalSdkAndroid.getCommitHash());
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.d(TAG, "onStop()");
        //Always close the connection with a connected FLIR ONE when going into background
        disconnect();
    }

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
     * Connect to a Camera
     */
    private void connect(Identity identity) {
        //We don't have to stop a discovery but it's nice to do if we have found the camera that we are looking for
        cameraHandler.stopDiscovery(discoveryStatusListener);

        if (connectedIdentity != null) {
            Log.d(TAG, "connect(), in *this* code sample we only support one camera connection at the time");
            showMessage.show("connect(), in *this* code sample we only support one camera connection at the time");
            return;
        }

        if (identity == null) {
            Log.d(TAG, "connect(), can't connect, no camera available");
            showMessage.show("connect(), can't connect, no camera available");
            return;
        }

        connectedIdentity = identity;

        Log.d(TAG, "connectedIdentity: " + connectedIdentity);

        updateConnectionText(identity, "CONNECTING");
        //IF your using "USB_DEVICE_ATTACHED" and "usb-device vendor-id" in the Android Manifest
        // you don't need to request permission, see documentation for more information
        if (UsbPermissionHandler.isFlirOne(identity)) {
            usbPermissionHandler.requestFlirOnePermisson(identity, this, permissionListener);
        } else {
            doConnect(identity);
        }

    }

    public void captureFrame(View view) {
        FrameDataHolder last = latestFrame; // <-- read the cached latest
        if (last == null || last.msxBitmap == null) {
            showMessage.show("No frame available yet");
            return;
        }

        // Safe copy for preview (fallback to ARGB_8888 if config is null)
        Bitmap.Config cfg = last.msxBitmap.getConfig() != null ? last.msxBitmap.getConfig() : Bitmap.Config.RGB_565;
        lastCapturedMsx = last.msxBitmap.copy(cfg, false);

        // Read min/max °C computed in CameraHandler
        double minC = cameraHandler.getLastMinTempC();
        double maxC = cameraHandler.getLastMaxTempC();

        saveBitmapToGallery(lastCapturedMsx, "FLIR_MSX_" + System.currentTimeMillis() + ".jpg");
        showCapturePopup(lastCapturedMsx, minC, maxC);
    }

    private void showCapturePopup(@NonNull Bitmap bitmap, double minC, double maxC) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_captured_preview, null);
        ImageView imageView = dialogView.findViewById(R.id.capturedImageView);
        TextView statsText = dialogView.findViewById(R.id.tempStatsText);
        Button closeBtn = dialogView.findViewById(R.id.closeDialogBtn);

        imageView.setImageBitmap(bitmap);

        // Display High first, then Low (°C), 1 decimal
        String text = (Double.isNaN(maxC) || Double.isNaN(minC))
                ? "High: --°C   Low: --°C"
                : String.format(java.util.Locale.US, "High: %.1f°C   Low: %.1f°C", maxC, minC);
        statsText.setText(text);

        final android.app.AlertDialog dialog =
                new android.app.AlertDialog.Builder(this)
                        .setView(dialogView)
                        .setCancelable(true)
                        .create();

        closeBtn.setOnClickListener(v -> dialog.dismiss());
        runOnUiThread(dialog::show);
    }

    // (same save helper you already have in your project)
    private void saveBitmapToGallery(@NonNull Bitmap bitmap, @NonNull String displayName) {
        try {
            android.content.ContentResolver resolver = getContentResolver();
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
            values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FLIR");
            android.net.Uri uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("Resolver returned null Uri");
            try (java.io.OutputStream out = resolver.openOutputStream(uri)) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) {
                    throw new IOException("Bitmap compress failed");
                }
            }
            showMessage.show("Saved: " + displayName);
        } catch (Exception e) {
            Log.e(TAG, "saveBitmapToGallery error", e);
            showMessage.show("Save failed: " + e.getMessage());
        }
    }


    private final UsbPermissionHandler.UsbPermissionListener permissionListener = new UsbPermissionHandler.UsbPermissionListener() {
        @Override
        public void permissionGranted(@NonNull Identity identity) {
            doConnect(identity);
        }

        @Override
        public void permissionDenied(@NonNull Identity identity) {
            MainActivity.this.showMessage.show("Permission was denied for identity ");
        }

        @Override
        public void error(UsbPermissionHandler.UsbPermissionListener.ErrorType errorType, final Identity identity) {
            if (errorType == UsbPermissionHandler.UsbPermissionListener.ErrorType.DEVICE_UNAVAILABLE_WHEN_ASKED_PERMISSION) {
                // automatically retry asking for permission - basically this automatic retry attempt should cause UsbPermissionListener.permissionGranted() callback to be invoked
                usbPermissionHandler.requestFlirOnePermisson(connectedIdentity, MainActivity.this, permissionListener);
            } else {
                MainActivity.this.showMessage.show("Error when asking for permission for FLIR ONE, error:" + errorType + " identity:" + identity);
            }
        }
    };

    private void doConnect(Identity identity) {
        new Thread(() -> {
            try {
                cameraHandler.connect(identity, connectionStatusListener);
                runOnUiThread(() -> {
                    updateConnectionText(identity, "CONNECTED");
                    deviceInfo.setText(cameraHandler.getDeviceInfo());
                });
                cameraHandler.startStream(streamDataListener);
            } catch (IOException e) {
                runOnUiThread(() -> {
                    Log.d(TAG, "Could not connect: " + e);
                    updateConnectionText(identity, "DISCONNECTED");
                });
            }
        }).start();
    }

    /**
     * Disconnect to a camera
     */
    private void disconnect() {
        updateConnectionText(connectedIdentity, "DISCONNECTING");
        Log.d(TAG, "disconnect() called with: connectedIdentity = [" + connectedIdentity + "]");
        connectedIdentity = null;
        new Thread(() -> {
            cameraHandler.disconnect();
            runOnUiThread(() -> updateConnectionText(null, "DISCONNECTED"));
        }).start();
    }

    /**
     * Update the UI text for connection status
     */
    private void updateConnectionText(Identity identity, String status) {
        String deviceId = identity != null ? identity.deviceId : "";
        connectionStatus.setText(getString(R.string.connection_status_text, deviceId + " " + status));
    }

    /**
     * Start camera discovery
     */
    private void startDiscovery() {
        cameraHandler.startDiscovery(cameraDiscoveryListener, discoveryStatusListener);
    }

    /**
     * Stop camera discovery
     */
    private void stopDiscovery() {
        cameraHandler.stopDiscovery(discoveryStatusListener);
    }

    /**
     * Callback for discovery status, using it to update UI
     */
    private final CameraHandler.DiscoveryStatus discoveryStatusListener = new CameraHandler.DiscoveryStatus() {
        @Override
        public void started() {
            discoveryStatus.setText(getString(R.string.connection_status_text, "discovering"));
        }

        @Override
        public void stopped() {
            discoveryStatus.setText(getString(R.string.connection_status_text, "not discovering"));
        }
    };

    /**
     * Camera connecting state thermalImageStreamListener, keeps track of if the camera is connected or not
     * <p>
     * Note that callbacks are received on a non-ui thread so have to eg use {@link #runOnUiThread(Runnable)} to interact view UI components
     */
    private final ConnectionStatusListener connectionStatusListener = errorCode -> {
        Log.d(TAG, "onDisconnected errorCode:" + errorCode);

        runOnUiThread(() -> updateConnectionText(connectedIdentity, "DISCONNECTED"));
    };

    private final CameraHandler.StreamDataListener streamDataListener = new CameraHandler.StreamDataListener() {

        @Override
        public void images(FrameDataHolder dataHolder) {

            runOnUiThread(() -> {
                msxImage.setImageBitmap(dataHolder.msxBitmap);
                photoImage.setImageBitmap(dataHolder.dcBitmap);
            });
        }

        @Override
        public void images(Bitmap msxBitmap, Bitmap dcBitmap) {
            try {
                framesBuffer.put(new FrameDataHolder(msxBitmap, dcBitmap));
            } catch (InterruptedException e) {
                Log.e(TAG, "images(), unable to add incoming images to frames buffer, exception:" + e);
            }

            runOnUiThread(() -> {
                Log.d(TAG, "framebuffer size:" + framesBuffer.size());
                FrameDataHolder frame = framesBuffer.poll(); // keep poll for smooth playback
                if (frame != null) {
                    latestFrame = frame; // <-- cache the one being shown
                    msxImage.setImageBitmap(frame.msxBitmap);
                    photoImage.setImageBitmap(frame.dcBitmap);
                }
            });
        }
    };

    /**
     * Camera Discovery thermalImageStreamListener, is notified if a new camera was found during a active discovery phase
     * <p>
     * Note that callbacks are received on a non-ui thread so have to eg use {@link #runOnUiThread(Runnable)} to interact view UI components
     */
    private final DiscoveryEventListener cameraDiscoveryListener = new DiscoveryEventListener() {
        @Override
        public void onCameraFound(DiscoveredCamera discoveredCamera) {
            Log.d(TAG, "onCameraFound identity:" + discoveredCamera.getIdentity());
            runOnUiThread(() -> cameraHandler.add(discoveredCamera.getIdentity()));
        }

        @Override
        public void onDiscoveryError(CommunicationInterface communicationInterface, ErrorCode errorCode) {
            Log.d(TAG, "onDiscoveryError communicationInterface:" + communicationInterface + " errorCode:" + errorCode);

            runOnUiThread(() -> {
                stopDiscovery();
                MainActivity.this.showMessage.show("onDiscoveryError communicationInterface:" + communicationInterface + " errorCode:" + errorCode);
            });
        }
    };

    private final ShowMessage showMessage = message -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();

    private void showSDKversion(String version) {
        TextView sdkVersionTextView = findViewById(R.id.sdk_version);
        String sdkVersionText = getString(R.string.sdk_version_text, version);
        sdkVersionTextView.setText(sdkVersionText);
    }

    private void showSDKCommitHash(String version) {
        TextView sdkVersionTextView = findViewById(R.id.sdk_commit_hash);
        String sdkVersionText = getString(R.string.sdk_commit_hash_text, version).substring(0, 10); // 10 chars for SHA is enough
        sdkVersionTextView.setText(sdkVersionText);
    }

    private void setupViews() {
        connectionStatus = findViewById(R.id.connection_status_text);
        discoveryStatus = findViewById(R.id.discovery_status);
        deviceInfo = findViewById(R.id.device_info_text);

        msxImage = findViewById(R.id.msx_image);
        photoImage = findViewById(R.id.photo_image);
    }

}
