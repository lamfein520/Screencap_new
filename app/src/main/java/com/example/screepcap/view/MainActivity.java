package com.example.screepcap.view;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.screepcap.R;
import com.example.screepcap.service.RecordService;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION,
            Manifest.permission.FOREGROUND_SERVICE_MICROPHONE,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.RECORD_AUDIO
    };

    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private RecordService recordService;
    private boolean isServiceBound = false;
    private Button recordButton;
    private boolean isRecording = false;
    private Intent screenCaptureResultData;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    permissions -> {
                        Log.d(TAG, "Permission results: " + permissions);
                        boolean allGranted = true;
                        for (Boolean granted : permissions.values()) {
                            allGranted &= granted;
                        }
                        if (allGranted) {
                            Log.i(TAG, "All permissions granted, requesting screen capture");
                            requestScreenCapture();
                        } else {
                            Log.w(TAG, "Some permissions were denied");
                            Toast.makeText(this, "Required permissions not granted",
                                    Toast.LENGTH_SHORT).show();
                        }
                    });

    private final ActivityResultLauncher<Intent> screenCaptureCallback = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                Log.d(TAG, "Screen capture result: " + result.getResultCode());
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Log.i(TAG, "Screen capture permission granted");
                    screenCaptureResultData = result.getData();
                    startAndBindService();
                } else {
                    Log.w(TAG, "Screen capture permission denied");
                    Toast.makeText(this, "Screen recording permission denied", Toast.LENGTH_SHORT).show();
                }
            }
    );

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(TAG, "Service connected");
            RecordService.RecordBinder binder = (RecordService.RecordBinder) service;
            recordService = binder.getService();
            isServiceBound = true;
            
            // 服务绑定后，设置MediaProjection并开始录制
            if (screenCaptureResultData != null) {
                Log.d(TAG, "Setting up MediaProjection after service binding");
                mediaProjection = projectionManager.getMediaProjection(RESULT_OK, screenCaptureResultData);
                if (mediaProjection != null) {
                    DisplayMetrics metrics = new DisplayMetrics();
                    getWindowManager().getDefaultDisplay().getMetrics(metrics);
                    Log.d(TAG, "Screen metrics - width: " + metrics.widthPixels + 
                               ", height: " + metrics.heightPixels + 
                               ", density: " + metrics.densityDpi);
                    recordService.setConfig(
                            mediaProjection,
                            metrics.widthPixels,
                            metrics.heightPixels,
                            metrics.densityDpi
                    );
                    startRecording();
                } else {
                    Log.e(TAG, "Failed to create MediaProjection");
                }
            } else {
                Log.w(TAG, "No screen capture data available");
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.i(TAG, "Service disconnected");
            recordService = null;
            isServiceBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");
        setContentView(R.layout.activity_main);

        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        recordButton = findViewById(R.id.button);
        recordButton.setOnClickListener(this::toggleRecording);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause");
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.d(TAG, "onStop");
    }

    private void toggleRecording(View view) {
        Log.d(TAG, "toggleRecording: isRecording=" + isRecording);
        if (!isRecording) {
            checkAndRequestPermissions();
        } else {
            stopRecording();
        }
    }

    private void checkAndRequestPermissions() {
        Log.d(TAG, "Checking permissions");
        boolean allPermissionsGranted = true;
        for (String permission : REQUIRED_PERMISSIONS) {
            boolean granted = ContextCompat.checkSelfPermission(this, permission)
                    == PackageManager.PERMISSION_GRANTED;
            Log.d(TAG, "Permission " + permission + ": " + (granted ? "GRANTED" : "DENIED"));
            if (!granted) {
                allPermissionsGranted = false;
                break;
            }
        }

        if (allPermissionsGranted) {
            Log.i(TAG, "All permissions already granted");
            requestScreenCapture();
        } else {
            Log.i(TAG, "Requesting permissions");
            permissionLauncher.launch(REQUIRED_PERMISSIONS);
        }
    }

    private void requestScreenCapture() {
        Log.d(TAG, "Requesting screen capture permission");
        Intent captureIntent = projectionManager.createScreenCaptureIntent();
        screenCaptureCallback.launch(captureIntent);
    }

    private void startAndBindService() {
        Log.d(TAG, "Starting and binding service");
        Intent serviceIntent = new Intent(this, RecordService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.d(TAG, "Using startForegroundService");
            startForegroundService(serviceIntent);
        } else {
            Log.d(TAG, "Using startService");
            startService(serviceIntent);
        }
        bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE);
    }

    private void startRecording() {
        Log.d(TAG, "Starting recording");
        if (recordService != null && recordService.startRecording()) {
            Log.i(TAG, "Recording started successfully");
            isRecording = true;
            recordButton.setText("Stop Recording");
        } else {
            Log.e(TAG, "Failed to start recording");
        }
    }

    private void stopRecording() {
        Log.d(TAG, "Stopping recording");
        if (recordService != null) {
            recordService.stopRecording();
            isRecording = false;
            recordButton.setText("Start Recording");
            
            // 停止并解绑服务
            Log.d(TAG, "Cleaning up service");
            unbindService(serviceConnection);
            isServiceBound = false;
            stopService(new Intent(this, RecordService.class));
            
            // 清理 MediaProjection
            Log.d(TAG, "Cleaning up MediaProjection");
            if (mediaProjection != null) {
                mediaProjection.stop();
                mediaProjection = null;
            }
            screenCaptureResultData = null;
            Log.i(TAG, "Recording stopped and cleaned up");
        }
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy");
        super.onDestroy();
        if (isServiceBound) {
            Log.d(TAG, "Unbinding service in onDestroy");
            unbindService(serviceConnection);
            isServiceBound = false;
        }
        if (mediaProjection != null) {
            Log.d(TAG, "Stopping MediaProjection in onDestroy");
            mediaProjection.stop();
            mediaProjection = null;
        }
    }
}