package com.example.screepcap.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class RecordService extends Service {
    private static final String TAG = "RecordService";
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "screen_record_channel";
    private static final int MSG_START_RECORDING = 1;
    private static final int MSG_STOP_RECORDING = 2;
    
    private Boolean isRecording = false;
    private MediaProjection mediaProjection;
    private MediaRecorder mediaRecorder;
    private VirtualDisplay virtualDisplay;
    private int screenWidth;
    private int screenHeight;
    private int screenDensity;
    
    private HandlerThread handlerThread;
    private Handler recordHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    private final IBinder binder = new RecordBinder();
    
    private final MediaProjection.Callback mediaProjectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            mainHandler.post(() -> {
                if (isRecording) {
                    stopRecording();
                }
            });
        }
    };
    
    public class RecordBinder extends Binder {
        public RecordService getService() {
            return RecordService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
        
        // 初始化录制线程
        handlerThread = new HandlerThread("RecordThread");
        handlerThread.start();
        recordHandler = new Handler(handlerThread.getLooper()) {
            @Override
            public void handleMessage(@NonNull Message msg) {
                switch (msg.what) {
                    case MSG_START_RECORDING:
                        handleStartRecording();
                        break;
                    case MSG_STOP_RECORDING:
                        handleStopRecording();
                        break;
                }
            }
        };
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Screen Record Service",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Screen Recording")
                .setContentText("Recording in progress...")
                .setSmallIcon(android.R.drawable.ic_dialog_info);
        return builder.build();
    }

    public void setConfig(MediaProjection projection, int width, int height, int density) {
        this.mediaProjection = projection;
        this.screenWidth = width;
        this.screenHeight = height;
        this.screenDensity = density;
        if (this.mediaProjection != null) {
            this.mediaProjection.registerCallback(mediaProjectionCallback, mainHandler);
        }
    }

    public boolean startRecording() {
        if (isRecording || mediaProjection == null) {
            return false;
        }
        
        // 重新启动前台服务
        startForeground(NOTIFICATION_ID, createNotification());
        recordHandler.sendEmptyMessage(MSG_START_RECORDING);
        return true;
    }

    private void handleStartRecording() {
        try {
            initRecorder();
            createVirtualDisplay();
            mediaRecorder.start();
            mainHandler.post(() -> isRecording = true);
        } catch (Exception e) {
            Log.e(TAG, "handleStartRecording error: " + e.getMessage());
            handleStopRecording();
        }
    }

    public void stopRecording() {
        if (!isRecording) {
            return;
        }
        recordHandler.sendEmptyMessage(MSG_STOP_RECORDING);
    }

    private void handleStopRecording() {
        try {
            if (mediaRecorder != null) {
                mediaRecorder.stop();
                mediaRecorder.reset();
                mediaRecorder.release();
                mediaRecorder = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "handleStopRecording error: " + e.getMessage());
        }

        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        
        mainHandler.post(() -> {
            isRecording = false;
            stopForeground(true); // 只停止前台服务，不停止服务本身
        });
    }

    private void initRecorder() {
        mediaRecorder = new MediaRecorder();
        
        String fileName = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
                .format(new Date()) + ".mp4";
        File file = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), fileName);

        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mediaRecorder.setVideoSize(screenWidth, screenHeight);
        mediaRecorder.setVideoFrameRate(30);
        mediaRecorder.setVideoEncodingBitRate(5 * 1024 * 1024);
        mediaRecorder.setOutputFile(file.getAbsolutePath());

        try {
            mediaRecorder.prepare();
        } catch (IOException e) {
            Log.e(TAG, "initRecorder error: " + e.getMessage());
            throw new RuntimeException("Failed to prepare MediaRecorder", e);
        }
    }

    private void createVirtualDisplay() {
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenRecording",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder.getSurface(), null, null
        );
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopRecording();
        if (mediaProjection != null) {
            mediaProjection.unregisterCallback(mediaProjectionCallback);
            mediaProjection.stop();
            mediaProjection = null;
        }
        if (handlerThread != null) {
            handlerThread.quitSafely();
            handlerThread = null;
        }
    }
}
