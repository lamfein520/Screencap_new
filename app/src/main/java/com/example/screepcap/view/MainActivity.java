package com.example.screepcap.view;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.example.screepcap.R;
import com.example.screepcap.service.RecordService;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_CODE = 1;

    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private RecordService recordService;
    private boolean isServiceBound = false;
    private Button recordButton;
    private boolean isRecording = false;

    private final ActivityResultLauncher<Intent> screenCaptureCallback = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    mediaProjection = projectionManager.getMediaProjection(result.getResultCode(), result.getData());
                    if (mediaProjection != null && recordService != null) {
                        DisplayMetrics metrics = new DisplayMetrics();
                        getWindowManager().getDefaultDisplay().getMetrics(metrics);
                        recordService.setConfig(
                                mediaProjection,
                                metrics.widthPixels,
                                metrics.heightPixels,
                                metrics.densityDpi
                        );
                        startRecording();
                    }
                } else {
                    Toast.makeText(this, "Screen recording permission denied", Toast.LENGTH_SHORT).show();
                }
            }
    );

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            RecordService.RecordBinder binder = (RecordService.RecordBinder) service;
            recordService = binder.getService();
            isServiceBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            recordService = null;
            isServiceBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        recordButton = findViewById(R.id.button);
        recordButton.setOnClickListener(this::toggleRecording);

        // 绑定Service
        Intent intent = new Intent(this, RecordService.class);
        bindService(intent, serviceConnection, BIND_AUTO_CREATE);
    }

    private void toggleRecording(View view) {
        if (!isRecording) {
            Intent captureIntent = projectionManager.createScreenCaptureIntent();
            screenCaptureCallback.launch(captureIntent);
        } else {
            stopRecording();
        }
    }

    private void startRecording() {
        if (recordService != null && recordService.startRecording()) {
            isRecording = true;
            recordButton.setText("Stop Recording");
        }
    }

    private void stopRecording() {
        if (recordService != null) {
            recordService.stopRecording();
            isRecording = false;
            recordButton.setText("Start Recording");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isServiceBound) {
            unbindService(serviceConnection);
            isServiceBound = false;
        }
    }
}