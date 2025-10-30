package com.spoon.simplecamerapreview;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.exifinterface.media.ExifInterface;
import androidx.fragment.app.Fragment;

import com.google.common.util.concurrent.ListenableFuture;

import org.apache.cordova.LOG;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

interface CameraCallback {
    void onCompleted(Exception err, String nativePath);
}

interface CameraStartedCallback {
    void onCameraStarted(Exception err);
}

interface TorchCallback {
    void onEnabled(Exception err);
}

interface HasFlashCallback {
    void onResult(boolean result);
}

interface SwitchCameraCallback {
    void onResult(boolean result);
}

public class CameraPreviewFragment extends Fragment {

    private PreviewView viewFinder;
    private Preview preview;
    private ImageCapture imageCapture;
    private Camera camera;
    private CameraStartedCallback startCameraCallback;
    private Location location;
    private int direction;
    private int targetSize;
    private boolean torchActivated = false;

    private static float ratio = (4 / (float) 3);
    private static final String TAG = "SimpleCameraPreview";

    private static final int containerViewId = 20;

    private SimpleCameraPreview pluginObject;

    private int nMaxWidth = 0;
    private int nMaxHeight = 0;

    private Size oBackCameraResolution = null;
    private Size oFrontCameraResolution = null;

    ProcessCameraProvider cameraProvider = null;

    public CameraPreviewFragment() {}

    @SuppressLint("ValidFragment")
    public CameraPreviewFragment(int cameraDirection, CameraStartedCallback cameraStartedCallback, JSONObject options, SimpleCameraPreview pluginObject) {
        this.direction = cameraDirection;
        this.pluginObject = pluginObject;

        try {
            this.targetSize = options.getInt("targetSize");
        } catch (JSONException e) {
            e.printStackTrace();
        }

        startCameraCallback = cameraStartedCallback;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        RelativeLayout containerView = new RelativeLayout(getActivity());
        RelativeLayout.LayoutParams containerLayoutParams = new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT);

        containerLayoutParams.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        containerLayoutParams.addRule(RelativeLayout.ALIGN_PARENT_START);
        containerView.setLayoutParams(containerLayoutParams);

        viewFinder = new PreviewView(getActivity());
        viewFinder.setLayoutParams(new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT));

        containerView.addView(viewFinder);

        startCamera();

        return containerView;
    }

    public void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(getActivity());
        
        try {
            cameraProvider = cameraProviderFuture.get();
        } catch (ExecutionException | InterruptedException e) {
            try {
                cameraProvider = cameraProviderFuture.get();
            } catch (ExecutionException | InterruptedException e1) {
                Log.e(TAG, "startCamera: " + e1.getMessage());
                e.printStackTrace();
                startCameraCallback.onCameraStarted(new Exception("Unable to start camera"));
                return;
            }
        }

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(direction)
                .build();

        preview = new Preview.Builder().build();

        Size oSize = calculateResolution(cameraProvider, cameraSelector, targetSize, direction);

        nMaxWidth = oSize.getWidth();
        nMaxHeight = oSize.getHeight();

        int nWidth = nMaxWidth;
        int nHeight = nMaxHeight;

        Size oSize1 = new Size(nWidth, nHeight);

        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {
            if (nWidth > nHeight) {
                oSize1 = new Size(nHeight, nWidth);
            }
        } else {
            if (nWidth < nHeight) {
                oSize1 = new Size(nHeight, nWidth);
            }
        }

        imageCapture = new ImageCapture.Builder()
                .setTargetResolution(oSize1)
                .build();

        cameraProvider.unbindAll();

        try {
            camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageCapture
            );
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
            imageCapture = new ImageCapture.Builder()
                    .build();
            camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageCapture
            );
        }

        preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

        if (startCameraCallback != null) {
            startCameraCallback.onCameraStarted(null);
        }
    }

    
    @SuppressLint("RestrictedApi")
    public Size calculateResolution(ProcessCameraProvider cameraProvider, CameraSelector cameraSelector, int targetSize, int direction) {
        if (direction == 1) {
            if (oBackCameraResolution != null) {
                return oBackCameraResolution;
            }
        } else {
            if (oFrontCameraResolution != null) {
                return oFrontCameraResolution;
            }
        }

        cameraProvider.unbindAll();

        Preview tempPreview = new Preview.Builder().build();
        ImageCapture tempImageCapture = new ImageCapture.Builder().build();
        Camera tempCamera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                tempPreview,
                tempImageCapture
        );

        @SuppressLint("UnsafeOptInUsageError") CameraCharacteristics cameraCharacteristics = Camera2CameraInfo
                .extractCameraCharacteristics(tempCamera.getCameraInfo());
        StreamConfigurationMap streamConfigurationMap = cameraCharacteristics
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        List<Size> supportedSizes = Arrays.asList(streamConfigurationMap.getOutputSizes(ImageFormat.JPEG));
        Collections.sort(supportedSizes, new Comparator<Size>(){
            @Override
            public int compare(Size size, Size t1) {
                return Integer.compare(t1.getWidth(), size.getWidth());
            }
        });

        if (direction == 1) {
            oBackCameraResolution = supportedSizes.get(0);
        } else {
            oFrontCameraResolution = supportedSizes.get(0);
        }

        return supportedSizes.get(0);
    }

    public static Size calculateResolution(Context context, int targetSize) {
        Size calculatedSize;
        if (getScreenOrientation(context) == Configuration.ORIENTATION_PORTRAIT) {
            calculatedSize = new Size((int) ((float) targetSize / ratio), targetSize);
        } else {
            calculatedSize = new Size(targetSize, (int) ((float) targetSize / ratio));
        }
        return calculatedSize;
    }

    private static int getScreenOrientation(Context context) {
        Display display = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        Point pointSize = new Point();
        display.getSize(pointSize);
        int orientation;
        if (pointSize.x < pointSize.y) {
            orientation = Configuration.ORIENTATION_PORTRAIT;
        } else {
            orientation = Configuration.ORIENTATION_LANDSCAPE;
        }
        return orientation;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int currentOrientation = getResources().getConfiguration().orientation;

        this.pluginObject.updateLayout();

        startCamera();
    }

    public void torchSwitch(boolean torchOn, TorchCallback torchCallback) {
        if (!camera.getCameraInfo().hasFlashUnit()) {
            torchCallback.onEnabled(new Exception("No flash unit present"));
            return;
        } else {
            try {
                camera.getCameraControl().enableTorch(torchOn);
                torchCallback.onEnabled(null);
            } catch (Exception e) {
                torchCallback.onEnabled(new Exception("Failed to switch " + (torchOn ? "on" : "off") + " torch", e));
                return;
            }
            torchActivated = torchOn;
        }
    }

    public void hasFlash(HasFlashCallback hasFlashCallback) {
        hasFlashCallback.onResult(camera.getCameraInfo().hasFlashUnit());
    }

    public void switchCamera(String oDirection, SwitchCameraCallback switchCameraCallback) {
        if (oDirection.equals("front")) {
            direction = 0;
        } else {
            direction = 1;
        }

        startCamera();
        switchCameraCallback.onResult(true);
    }

    public void takePicture(String useFlash, CameraCallback takePictureCallback) {
        UUID uuid = UUID.randomUUID();

        File imgFile = new File(
                getActivity().getBaseContext().getFilesDir(),
                uuid.toString() + ".jpg"
        );

        if (imageCapture == null) {
            imageCapture = new ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setTargetRotation(getActivity().getWindowManager().getDefaultDisplay().getRotation())
                    .build();
        }

        if (useFlash.equals("ON")) {
            imageCapture.setFlashMode(ImageCapture.FLASH_MODE_ON);
            camera.getCameraControl().enableTorch(true);
        } else if (useFlash.equals("OFF")) {
            imageCapture.setFlashMode(ImageCapture.FLASH_MODE_OFF);
            camera.getCameraControl().enableTorch(false);
        } else {
            imageCapture.setFlashMode(ImageCapture.FLASH_MODE_AUTO);
            camera.getCameraControl().enableTorch(true);
        }

        ImageCapture.Metadata oMetaData = new ImageCapture.Metadata();

        oMetaData.setReversedHorizontal( direction == 0);

        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(imgFile).setMetadata(oMetaData).build();
        imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(getActivity().getApplicationContext()),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                        if (camera.getCameraInfo().hasFlashUnit() && !torchActivated) {
                            camera.getCameraControl().enableTorch(false);
                        }

                        ExifInterface exif = null;
                        File oFinalFile = null;

                        oFinalFile = imgFile;

                        try {
                            exif = new ExifInterface(imgFile.getAbsolutePath());
                        } catch (IOException e) {
                            Log.e(TAG, "new ExifInterface err: " + e.getMessage());
                            e.printStackTrace();
                            takePictureCallback.onCompleted(new Exception("Unable to create exif object"), null);
                            return;
                        }

                        if (location != null) {
                            exif.setGpsInfo(location);
                            try {
                                exif.saveAttributes();
                            } catch (IOException e) {
                                Log.e(TAG, "save exif err: " + e.getMessage());
                                e.printStackTrace();
                                takePictureCallback.onCompleted(new Exception("Unable to save gps exif"), null);
                                return;
                            }
                        }

                        takePictureCallback.onCompleted(null, Uri.fromFile(oFinalFile).toString());
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        Log.e(TAG, "takePicture: " + exception.getMessage());
                        takePictureCallback.onCompleted(new Exception("Unable to take picture"), null);
                    }
                }
        );
    }

    public void setLocation(Location loc) {
        if (loc != null) {
            this.location = loc;
        }
    }
}
